package com.ghostcleaner

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.drawerlayout.widget.DrawerLayout
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var storageText: TextView
    private lateinit var storageBar: ProgressBar
    private lateinit var freedText: TextView
    private lateinit var status: TextView
    private lateinit var total: TextView
    private lateinit var progress: ProgressBar
    private lateinit var scanBtn: Button
    private lateinit var undoBtn: Button
    private lateinit var cleanBtn: Button
    private lateinit var listView: ListView
    private lateinit var drawer: DrawerLayout
    private lateinit var navFreed: TextView
    private lateinit var navUseBin: Switch

    private lateinit var prefs: Prefs
    private lateinit var bin: SafetyBin

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var scanner: Scanner? = null
    private var busy = false

    private var items: List<JunkItem> = emptyList()
    private val adapter = JunkAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        storageText = findViewById(R.id.storageText)
        storageBar = findViewById(R.id.storageBar)
        freedText = findViewById(R.id.freedText)
        status = findViewById(R.id.status)
        total = findViewById(R.id.total)
        progress = findViewById(R.id.progress)
        scanBtn = findViewById(R.id.scan)
        undoBtn = findViewById(R.id.undo)
        cleanBtn = findViewById(R.id.clean)
        listView = findViewById(R.id.list)
        listView.adapter = adapter

        prefs = Prefs(this)
        bin = SafetyBin(this)

        scanBtn.setOnClickListener {
            val s = scanner
            if (s != null) s.cancelled = true
            else if (hasStorageAccess()) startScan() else requestStorageAccess()
        }
        cleanBtn.setOnClickListener { confirmClean() }
        undoBtn.setOnClickListener { undoLastClean() }

        setupSidebar()
    }

    // ---------- Sidebar ----------

    private fun setupSidebar() {
        drawer = findViewById(R.id.drawer)
        navFreed = findViewById(R.id.navFreed)
        navUseBin = findViewById(R.id.navUseBin)

        actionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_menu)
            setHomeActionContentDescription("Open menu")
        }

        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { null }
        findViewById<TextView>(R.id.navVersion).text = "Version ${version ?: "?"}"

        fun navAction(id: Int, action: () -> Unit) {
            findViewById<View>(id).setOnClickListener {
                drawer.closeDrawer(Gravity.START)
                action()
            }
        }
        navAction(R.id.navScan) { scanBtn.performClick() }
        navAction(R.id.navBin) { showBinDialog() }
        navAction(R.id.navIgnored) { showIgnoredDialog() }
        navAction(R.id.navSystemStorage) {
            try { startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) }
            catch (e: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
        navAction(R.id.navAbout) { showAbout(version) }

        navUseBin.isChecked = prefs.useBin
        navUseBin.setOnCheckedChangeListener { _, on ->
            if (on == prefs.useBin) return@setOnCheckedChangeListener
            prefs.useBin = on
            Toast.makeText(this,
                if (on) "Cleaned files will be kept ${SafetyBin.KEEP_DAYS} days before deletion"
                else "Cleaned files will be deleted immediately",
                Toast.LENGTH_LONG).show()
        }
    }

    private fun showAbout(version: String?) {
        AlertDialog.Builder(this)
            .setTitle("GhostCleaner ${version ?: ""}".trim())
            .setMessage("Finds and removes junk and ghost files: leftover app folders, old installers, duplicates, temp files, empty files and folders.\n\n" +
                    "Nothing is removed until you review and confirm. With the safety bin on, cleans can be undone for ${SafetyBin.KEEP_DAYS} days.\n\n" +
                    "Other apps' caches can only be cleared from System storage settings.")
            .setPositiveButton("OK", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::drawer.isInitialized && drawer.isDrawerOpen(Gravity.START)) drawer.closeDrawer(Gravity.START)
        else super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        updateStorage()
        if (hasStorageAccess() && !busy) {
            // Quietly empty bin batches older than a few days.
            io.execute {
                val freed = bin.purgeOlderThan(SafetyBin.KEEP_DAYS)
                val hasUndo = bin.lastBatch() != null
                main.post {
                    if (freed > 0) updateStorage()
                    undoBtn.visibility = if (hasUndo) View.VISIBLE else View.GONE
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            if (drawer.isDrawerOpen(Gravity.START)) drawer.closeDrawer(Gravity.START)
            else drawer.openDrawer(Gravity.START)
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // ---------- Permissions ----------

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun requestStorageAccess() {
        AlertDialog.Builder(this)
            .setTitle("Storage access needed")
            .setMessage("GhostCleaner needs access to all files to find and remove junk and ghost files. Nothing is removed until you review the list and tap Clean.")
            .setPositiveButton("Grant") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName")))
                    } catch (e: Exception) {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                } else {
                    requestPermissions(arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQ_STORAGE && hasStorageAccess()) startScan()
    }

    // ---------- Scan ----------

    private fun startScan() {
        val s = Scanner(this, prefs.ignored)
        scanner = s
        items = emptyList()
        adapter.rebuild()
        updateTotal()
        setBusy(true, "Scanning…")
        io.execute {
            var last = 0L
            val found = s.scan { msg ->
                val now = System.currentTimeMillis()
                if (now - last > 100) { last = now; main.post { status.text = msg } }
            }
            val wasCancelled = s.cancelled
            main.post {
                scanner = null
                items = found
                adapter.rebuild()
                setBusy(false, if (wasCancelled) "Scan stopped — showing partial results"
                               else "Found ${found.size} items. Review, then tap Clean.")
                updateTotal()
            }
        }
    }

    // ---------- Clean & undo ----------

    private fun confirmClean() {
        val chosen = items.filter { it.selected }
        if (chosen.isEmpty()) return
        val bytes = chosen.sumOf { it.size }
        val useBin = prefs.useBin
        AlertDialog.Builder(this)
            .setTitle("Clean ${chosen.size} items?")
            .setMessage(
                if (useBin) "About ${Scanner.formatSize(bytes)}. Files go to the safety bin for ${SafetyBin.KEEP_DAYS} days, so you can undo this. Space is fully freed when the bin is emptied."
                else "This frees about ${Scanner.formatSize(bytes)} and can't be undone.")
            .setPositiveButton(if (useBin) "Clean" else "Delete") { _, _ -> clean(chosen, useBin) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clean(chosen: List<JunkItem>, useBin: Boolean) {
        setBusy(true, "Cleaning…")
        io.execute {
            val onProgress: (Int) -> Unit = { n -> main.post { status.text = "Cleaning $n / ${chosen.size}" } }
            val result = if (useBin) {
                bin.moveToBin(chosen, onProgress)
            } else {
                var count = 0
                var bytes = 0L
                chosen.forEachIndexed { i, item ->
                    if (SafetyBin.deleteNow(item.file)) { count++; bytes += item.size }
                    onProgress(i + 1)
                }
                SafetyBin.Result(count, bytes, chosen.size - count)
            }
            val hasUndo = useBin && bin.lastBatch() != null
            main.post {
                prefs.totalFreed = prefs.totalFreed + result.bytes
                val gone = chosen.filter { !it.file.exists() }.toSet()
                items = items.filterNot { it in gone }
                adapter.rebuild()
                updateTotal()
                updateStorage()
                undoBtn.visibility = if (hasUndo) View.VISIBLE else View.GONE
                val verb = if (useBin) "Moved to bin" else "Freed"
                setBusy(false, "$verb ${Scanner.formatSize(result.bytes)} — ${result.count} items" +
                        if (result.failed > 0) " (${result.failed} couldn't be removed)" else "")
            }
        }
    }

    private fun undoLastClean() {
        setBusy(true, "Restoring…")
        io.execute {
            val batch = bin.lastBatch()
            val restored = if (batch != null) bin.restore(batch) else 0
            val hasMore = bin.lastBatch() != null
            main.post {
                undoBtn.visibility = if (hasMore) View.VISIBLE else View.GONE
                updateStorage()
                setBusy(false, "Restored $restored items. Scan again to refresh the list.")
            }
        }
    }

    private fun showBinDialog() {
        io.execute {
            val size = bin.totalSize()
            val count = bin.itemCount()
            main.post {
                AlertDialog.Builder(this)
                    .setTitle("Safety bin")
                    .setMessage("$count items · ${Scanner.formatSize(size)}\n\nCleaned files wait here for ${SafetyBin.KEEP_DAYS} days, then are deleted automatically.")
                    .setPositiveButton("Empty now") { _, _ ->
                        io.execute {
                            val freed = bin.emptyAll()
                            main.post {
                                undoBtn.visibility = View.GONE
                                updateStorage()
                                status.text = "Bin emptied — freed ${Scanner.formatSize(freed)}"
                            }
                        }
                    }
                    .setNegativeButton("Close", null)
                    .show()
            }
        }
    }

    // ---------- Ignore list ----------

    private fun askToIgnore(item: JunkItem) {
        val folder = if (item.file.isDirectory) item.file else item.file.parentFile ?: return
        val rel = folder.absolutePath.removePrefix(Environment.getExternalStorageDirectory().absolutePath).ifEmpty { "/" }
        AlertDialog.Builder(this)
            .setTitle("Ignore this folder?")
            .setMessage("$rel\n\nGhostCleaner won't scan or clean anything inside it. You can undo this from the sidebar under Ignored folders.")
            .setPositiveButton("Ignore") { _, _ ->
                prefs.ignored = prefs.ignored + folder.absolutePath
                val p = folder.absolutePath
                items = items.filterNot { it.file.absolutePath == p || it.file.absolutePath.startsWith("$p/") }
                adapter.rebuild()
                updateTotal()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showIgnoredDialog() {
        val list = prefs.ignored.sorted()
        if (list.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Ignored folders")
                .setMessage("None yet. Long-press any item in the scan results to ignore its folder.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val root = Environment.getExternalStorageDirectory().absolutePath
        val labels = list.map { it.removePrefix(root).ifEmpty { "/" } }.toTypedArray()
        val checked = BooleanArray(list.size)
        AlertDialog.Builder(this)
            .setTitle("Tick folders to stop ignoring")
            .setMultiChoiceItems(labels, checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Remove ticked") { _, _ ->
                prefs.ignored = list.filterIndexed { i, _ -> !checked[i] }.toSet()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ---------- UI helpers ----------

    private fun updateStorage() {
        try {
            val stat = StatFs(Environment.getExternalStorageDirectory().path)
            val totalBytes = stat.totalBytes
            val used = totalBytes - stat.availableBytes
            storageBar.progress = if (totalBytes > 0) (used * 1000 / totalBytes).toInt() else 0
            storageText.text = "${Scanner.formatSize(used)} used of ${Scanner.formatSize(totalBytes)} · ${Scanner.formatSize(stat.availableBytes)} free"
        } catch (e: Exception) {
            storageText.text = ""
        }
        val freed = prefs.totalFreed
        freedText.text = if (freed > 0) "GhostCleaner has cleaned ${Scanner.formatSize(freed)} so far" else ""
        if (::navFreed.isInitialized) navFreed.text = "${Scanner.formatSize(freed)} cleaned in total"
    }

    private fun setBusy(isBusy: Boolean, msg: String) {
        busy = isBusy
        status.text = msg
        progress.visibility = if (isBusy) View.VISIBLE else View.GONE
        scanBtn.text = if (isBusy && scanner != null) "Stop" else "Scan"
        scanBtn.isEnabled = !isBusy || scanner != null
        undoBtn.isEnabled = !isBusy
        cleanBtn.isEnabled = !isBusy && items.any { it.selected }
    }

    private fun updateTotal() {
        val chosen = items.filter { it.selected }
        total.text = if (items.isEmpty()) "" else Scanner.formatSize(chosen.sumOf { it.size })
        cleanBtn.isEnabled = !busy && chosen.isNotEmpty()
        cleanBtn.text = if (chosen.isEmpty()) "Clean" else "Clean (${chosen.size})"
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    /** Rows are either a category header or a junk item. */
    private sealed class Row {
        data class Header(val category: Category) : Row()
        data class Item(val item: JunkItem) : Row()
    }

    private inner class JunkAdapter : BaseAdapter() {
        private var rows: List<Row> = emptyList()
        private val collapsed = HashSet<Category>()

        fun rebuild() {
            val out = mutableListOf<Row>()
            items.groupBy { it.category }.toSortedMap().forEach { (cat, list) ->
                out += Row.Header(cat)
                if (cat !in collapsed) list.forEach { out += Row.Item(it) }
            }
            rows = out
            notifyDataSetChanged()
        }

        override fun getCount() = rows.size
        override fun getItem(p: Int): Any = rows[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(p: Int) = if (rows[p] is Row.Header) 0 else 1

        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val row = rows[p]
            val v = (convert as? LinearLayout) ?: makeRow()
            val box = v.getChildAt(0) as CheckBox
            val texts = v.getChildAt(1) as LinearLayout
            val title = texts.getChildAt(0) as TextView
            val sub = texts.getChildAt(1) as TextView
            box.setOnCheckedChangeListener(null)

            when (row) {
                is Row.Header -> {
                    val list = items.filter { it.category == row.category }
                    val size = list.sumOf { it.size }
                    val arrow = if (row.category in collapsed) "▸" else "▾"
                    title.text = "$arrow ${row.category.title}"
                    title.setTypeface(null, Typeface.BOLD)
                    sub.text = "${list.size} items · ${Scanner.formatSize(size)} — ${row.category.description}"
                    sub.maxLines = 2
                    box.isChecked = list.all { it.selected }
                    box.setOnCheckedChangeListener { _, checked ->
                        list.forEach { it.selected = checked }
                        notifyDataSetChanged(); updateTotal()
                    }
                    v.setPadding(0, dp(12), 0, dp(4))
                    v.setOnClickListener {
                        if (!collapsed.add(row.category)) collapsed.remove(row.category)
                        rebuild()
                    }
                    v.setOnLongClickListener(null)
                    v.isLongClickable = false
                }
                is Row.Item -> {
                    val item = row.item
                    val f = item.file
                    title.text = f.name
                    title.setTypeface(null, Typeface.NORMAL)
                    val rel = (f.parent ?: "").removePrefix(Environment.getExternalStorageDirectory().absolutePath).ifEmpty { "/" }
                    val parts = listOfNotNull(
                        if (item.size > 0) Scanner.formatSize(item.size) else null,
                        item.note.ifEmpty { null },
                        rel,
                    )
                    sub.text = parts.joinToString(" · ")
                    sub.maxLines = 1
                    box.isChecked = item.selected
                    box.setOnCheckedChangeListener { _, checked ->
                        item.selected = checked
                        notifyDataSetChanged(); updateTotal()
                    }
                    v.setPadding(dp(16), dp(4), 0, dp(4))
                    v.setOnClickListener { box.toggle() }
                    v.setOnLongClickListener { askToIgnore(item); true }
                }
            }
            return v
        }

        private fun makeRow(): LinearLayout {
            val ctx = this@MainActivity
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(CheckBox(ctx).apply { isFocusable = false })
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(ctx).apply {
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                        maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
                    })
                    addView(TextView(ctx).apply {
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        alpha = 0.7f; ellipsize = TextUtils.TruncateAt.MIDDLE
                    })
                })
            }
        }
    }

    override fun onDestroy() {
        scanner?.cancelled = true
        io.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val REQ_STORAGE = 1
    }
}
