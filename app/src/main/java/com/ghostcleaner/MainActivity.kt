package com.ghostcleaner

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.drawerlayout.widget.DrawerLayout
import java.io.File
import java.text.DateFormat
import java.util.Date
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
    private lateinit var filterBar: View
    private lateinit var search: EditText
    private lateinit var sort: Spinner
    private lateinit var drawer: DrawerLayout
    private lateinit var navFreed: TextView
    private lateinit var navUseBin: Switch

    private lateinit var prefs: Prefs
    private lateinit var bin: SafetyBin
    private lateinit var history: CleanHistory

    /** Scans and cleans run here, one at a time. */
    private val io = Executors.newSingleThreadExecutor()
    /** Side tools (breakdown, unused apps) run here so they don't wait on a scan. */
    private val tools = Executors.newCachedThreadPool()
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
        filterBar = findViewById(R.id.filterBar)
        search = findViewById(R.id.search)
        sort = findViewById(R.id.sort)
        listView.adapter = adapter

        prefs = Prefs(this)
        bin = SafetyBin(this)
        history = CleanHistory(this)

        scanBtn.setOnClickListener {
            val s = scanner
            if (s != null) s.cancelled = true
            else if (hasStorageAccess()) startScan() else requestStorageAccess()
        }
        cleanBtn.setOnClickListener { confirmClean() }
        undoBtn.setOnClickListener { undoLastClean() }

        setupFilterBar()
        setupSidebar()
        ScanJobService.ensureScheduled(this)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** The Quick Settings tile and the weekly-scan notification ask for a scan on open. */
    private fun handleIntent(intent: Intent?) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_AUTO_SCAN, false)) return
        intent.removeExtra(EXTRA_AUTO_SCAN)
        if (busy) return
        if (hasStorageAccess()) startScan() else requestStorageAccess()
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
        navAction(R.id.navBreakdown) { showStorageBreakdown() }
        navAction(R.id.navUnusedApps) { showUnusedApps() }
        navAction(R.id.navHistory) { showHistory() }
        navAction(R.id.navBin) { showBinDialog() }
        navAction(R.id.navIgnored) { showIgnoredDialog() }
        navAction(R.id.navSettings) { showSettings() }
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
                if (on) "Cleaned files will be kept ${prefs.binDays} days before deletion"
                else "Cleaned files will be deleted immediately",
                Toast.LENGTH_LONG).show()
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

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::drawer.isInitialized && drawer.isDrawerOpen(Gravity.START)) drawer.closeDrawer(Gravity.START)
        else super.onBackPressed()
    }

    private fun showAbout(version: String?) {
        AlertDialog.Builder(this)
            .setTitle("GhostCleaner ${version ?: ""}".trim())
            .setMessage("Finds and removes junk and ghost files: leftover app folders, old installers, duplicates, temp files, empty files and folders.\n\n" +
                    "Nothing is removed until you review and confirm. With the safety bin on, cleans can be undone for ${prefs.binDays} days.\n\n" +
                    "Other apps' caches can only be cleared from System storage settings.")
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        updateStorage()
        if (hasStorageAccess() && !busy) {
            // Quietly empty bin batches older than the configured number of days.
            val days = prefs.binDays
            io.execute {
                val freed = bin.purgeOlderThan(days)
                val hasUndo = bin.lastBatch() != null
                main.post {
                    if (freed > 0) updateStorage()
                    undoBtn.visibility = if (hasUndo) View.VISIBLE else View.GONE
                }
            }
        }
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
        val s = Scanner(this, prefs.ignored, prefs.scanConfig())
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
        val hidden = chosen.size - adapter.visibleSelectedCount()
        val hiddenNote = if (hidden > 0) "\n\n$hidden selected items are hidden by your filter and will be cleaned too." else ""
        AlertDialog.Builder(this)
            .setTitle("Clean ${chosen.size} items?")
            .setMessage(
                (if (useBin) "About ${Scanner.formatSize(bytes)}. Files go to the safety bin for ${prefs.binDays} days, so you can undo this. Space is fully freed when the bin is emptied."
                 else "This frees about ${Scanner.formatSize(bytes)} and can't be undone.") + hiddenNote)
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
            history.add(result.count, result.bytes, useBin)
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
                    .setMessage("$count items · ${Scanner.formatSize(size)}\n\nCleaned files wait here for ${prefs.binDays} days, then are deleted automatically.")
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

    // ---------- Preview & item options ----------

    private fun openFile(f: File) {
        if (f.isDirectory) {
            Toast.makeText(this, "That's a folder — nothing to preview", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
            val view = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(view, f.name))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app can open this file", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Can't open this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showItemOptions(item: JunkItem) {
        val canOpen = !item.file.isDirectory
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        if (canOpen) { labels += "Open / preview"; actions += { openFile(item.file) } }
        labels += "Ignore this folder"; actions += { askToIgnore(item) }
        AlertDialog.Builder(this)
            .setTitle(item.file.name)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
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

    // ---------- Settings ----------

    private fun showSettings() {
        val pad = dp(20)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(8), pad, 0)
        }
        fun numberField(label: String, value: Int): EditText {
            form.addView(TextView(this).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                alpha = 0.8f
                setPadding(0, dp(12), 0, 0)
            })
            val et = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(value.toString())
                setSelectAllOnFocus(true)
            }
            form.addView(et)
            return et
        }
        val large = numberField("Large files: at least this many MB", prefs.largeFileMb)
        val dupMin = numberField("Duplicates: ignore files smaller than this many KB", prefs.dupMinKb)
        val downloads = numberField("Old downloads: older than this many days", prefs.oldDownloadDays)
        val media = numberField("Old screenshots & messaging media: older than this many days", prefs.oldMediaDays)
        val unused = numberField("Unused apps: not opened for this many days", prefs.unusedAppDays)
        val binDays = numberField("Safety bin: keep cleaned files this many days", prefs.binDays)
        val weekly = Switch(this).apply {
            text = "Weekly background scan (notifies you, never deletes)"
            isChecked = prefs.weeklyScan
            setPadding(0, dp(16), 0, dp(8))
        }
        form.addView(weekly)

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Save") { _, _ ->
                fun EditText.intOr(fallback: Int) = text.toString().trim().toIntOrNull() ?: fallback
                prefs.largeFileMb = large.intOr(prefs.largeFileMb)
                prefs.dupMinKb = dupMin.intOr(prefs.dupMinKb)
                prefs.oldDownloadDays = downloads.intOr(prefs.oldDownloadDays)
                prefs.oldMediaDays = media.intOr(prefs.oldMediaDays)
                prefs.unusedAppDays = unused.intOr(prefs.unusedAppDays)
                prefs.binDays = binDays.intOr(prefs.binDays)
                val weeklyOn = weekly.isChecked
                if (weeklyOn != prefs.weeklyScan) {
                    prefs.weeklyScan = weeklyOn
                    ScanJobService.schedule(this, weeklyOn)
                    if (weeklyOn && Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
                    }
                }
                Toast.makeText(this, "Saved. New limits apply to the next scan.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- Storage breakdown ----------

    private fun loadingDialog(message: String): AlertDialog {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(ProgressBar(this@MainActivity))
            addView(TextView(this@MainActivity).apply { text = message; setPadding(dp(16), 0, 0, 0) })
        }
        return AlertDialog.Builder(this).setView(row).setCancelable(false).show()
    }

    private fun showStorageBreakdown() {
        if (!hasStorageAccess()) { requestStorageAccess(); return }
        val loading = loadingDialog("Measuring storage…")
        tools.execute {
            val result = try { StorageBreakdown.compute() } catch (e: Exception) { null }
            main.post {
                loading.dismiss()
                if (result == null || result.total <= 0) {
                    Toast.makeText(this, "Couldn't measure storage", Toast.LENGTH_SHORT).show()
                    return@post
                }
                showBreakdownDialog(result)
            }
        }
    }

    private fun showBreakdownDialog(result: StorageBreakdown.Result) {
        val pad = dp(20)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(12), pad, 0)
        }
        // Stacked bar
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(22))
            clipToOutline = true
            background = GradientDrawable().apply { cornerRadius = dp(11).toFloat(); setColor(0x22808080) }
        }
        result.sizes.forEach { (kind, size) ->
            if (size <= 0) return@forEach
            bar.addView(View(this).apply {
                setBackgroundColor(kind.color)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, size.toFloat())
            })
        }
        content.addView(bar)
        // Legend
        result.sizes.entries.sortedByDescending { if (it.key == StorageBreakdown.Kind.FREE) -1L else it.value }
            .forEach { (kind, size) ->
                val pct = size * 100.0 / result.total
                content.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(10), 0, 0)
                    addView(View(this@MainActivity).apply {
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(kind.color) }
                        layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(12) }
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = kind.label
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = "${Scanner.formatSize(size)}  (${String.format("%.0f", pct)}%)"
                        alpha = 0.8f
                    })
                })
            }
        content.addView(TextView(this).apply {
            text = "Total ${Scanner.formatSize(result.total)}. \"Apps, app data & system\" can't be broken down further without root."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            alpha = 0.6f
            setPadding(0, dp(16), 0, dp(8))
        })

        AlertDialog.Builder(this)
            .setTitle("Storage breakdown")
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Close", null)
            .show()
    }

    // ---------- Unused apps ----------

    private fun showUnusedApps() {
        if (!UnusedApps.hasUsageAccess(this)) {
            AlertDialog.Builder(this)
                .setTitle("Usage access needed")
                .setMessage("To see which apps you haven't opened lately, GhostCleaner needs Usage access. Find GhostCleaner in the list and turn it on, then come back.")
                .setPositiveButton("Open settings") { _, _ ->
                    try { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                    catch (e: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        val days = prefs.unusedAppDays
        val loading = loadingDialog("Checking your apps…")
        tools.execute {
            val apps = try { UnusedApps.find(this, days) } catch (e: Exception) { emptyList() }
            main.post {
                loading.dismiss()
                if (apps.isEmpty()) {
                    AlertDialog.Builder(this)
                        .setTitle("Unused apps")
                        .setMessage("Every app you installed has been used in the last $days days.")
                        .setPositiveButton("OK", null)
                        .show()
                } else showUnusedAppsDialog(apps, days)
            }
        }
    }

    private fun showUnusedAppsDialog(apps: List<UnusedApps.App>, days: Int) {
        val listAdapter = object : BaseAdapter() {
            override fun getCount() = apps.size
            override fun getItem(p: Int): Any = apps[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
                val app = apps[p]
                val row = (convert as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(20), dp(8), dp(20), dp(8))
                    addView(ImageView(this@MainActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) }
                    })
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        addView(TextView(this@MainActivity).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                        addView(TextView(this@MainActivity).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); alpha = 0.7f })
                    })
                }
                (row.getChildAt(0) as ImageView).setImageDrawable(app.icon)
                val texts = row.getChildAt(1) as LinearLayout
                (texts.getChildAt(0) as TextView).text = app.label
                val when_ = app.daysUnused?.let { "not opened in $it days" } ?: "not opened in the past year"
                (texts.getChildAt(1) as TextView).text =
                    (if (app.sizeBytes > 0) "${Scanner.formatSize(app.sizeBytes)} · " else "") + when_
                return row
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Unused for $days+ days · tap to uninstall")
            .setAdapter(listAdapter) { _, which ->
                val pkg = apps[which].packageName
                try { startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))) }
                catch (e: Exception) { Toast.makeText(this, "Couldn't open uninstaller", Toast.LENGTH_SHORT).show() }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ---------- History ----------

    private fun showHistory() {
        val entries = history.list()
        if (entries.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Clean history")
                .setMessage("Nothing cleaned yet.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val lines = entries.map {
            "${fmt.format(Date(it.time))}\n${Scanner.formatSize(it.bytes)} · ${it.count} items · " +
                    (if (it.toBin) "to safety bin" else "deleted")
        }.toTypedArray()
        val sum = entries.sumOf { it.bytes }
        AlertDialog.Builder(this)
            .setTitle("Clean history · ${Scanner.formatSize(sum)} total")
            .setItems(lines, null)
            .setPositiveButton("Close", null)
            .setNeutralButton("Clear history") { _, _ -> history.clear() }
            .show()
    }

    // ---------- Filter & sort ----------

    private fun setupFilterBar() {
        sort.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Biggest", "Name", "Newest", "Oldest"))
        sort.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = adapter.rebuild()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = adapter.rebuild()
        })
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
        filterBar.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    /** Rows are either a category header or a junk item. */
    private sealed class Row {
        data class Header(val category: Category, val items: List<JunkItem>) : Row()
        data class Item(val item: JunkItem) : Row()
    }

    private inner class JunkAdapter : BaseAdapter() {
        private var rows: List<Row> = emptyList()
        private val collapsed = HashSet<Category>()

        fun visibleSelectedCount(): Int =
            rows.filterIsInstance<Row.Header>().sumOf { h -> h.items.count { it.selected } }

        fun rebuild() {
            val query = if (::search.isInitialized) search.text.toString().trim().lowercase() else ""
            val sortPos = if (::sort.isInitialized) sort.selectedItemPosition else 0
            val cmp: Comparator<JunkItem> = when (sortPos) {
                1 -> compareBy<JunkItem> { it.file.name.lowercase() }
                2 -> compareByDescending<JunkItem> { it.modified }
                3 -> compareBy<JunkItem> { it.modified }
                else -> compareByDescending<JunkItem> { it.size }
            }
            val visible = if (query.isEmpty()) items else items.filter {
                it.file.name.lowercase().contains(query) || it.note.lowercase().contains(query)
            }
            val out = mutableListOf<Row>()
            visible.groupBy { it.category }.toSortedMap().forEach { (cat, list) ->
                val sorted = list.sortedWith(cmp)
                out += Row.Header(cat, sorted)
                if (cat !in collapsed) sorted.forEach { out += Row.Item(it) }
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
            val thumb = v.getChildAt(1) as ImageView
            val texts = v.getChildAt(2) as LinearLayout
            val title = texts.getChildAt(0) as TextView
            val sub = texts.getChildAt(1) as TextView
            box.setOnCheckedChangeListener(null)

            when (row) {
                is Row.Header -> {
                    val list = row.items
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
                    thumb.visibility = View.GONE
                    thumb.tag = null
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
                    if (Thumbnails.isPreviewable(f)) {
                        thumb.visibility = View.VISIBLE
                        Thumbnails.load(f, thumb, dp(THUMB_DP))
                        thumb.setOnClickListener { openFile(f) }
                    } else {
                        thumb.visibility = View.GONE
                        thumb.tag = null
                        thumb.setOnClickListener(null)
                    }
                    v.setPadding(dp(16), dp(4), 0, dp(4))
                    v.setOnClickListener { box.toggle() }
                    v.setOnLongClickListener { showItemOptions(item); true }
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
                addView(ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = LinearLayout.LayoutParams(dp(THUMB_DP), dp(THUMB_DP)).apply { marginEnd = dp(10) }
                    background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(0x22808080) }
                    clipToOutline = true
                    contentDescription = "Preview"
                    visibility = View.GONE
                })
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
        tools.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_AUTO_SCAN = "com.ghostcleaner.AUTO_SCAN"
        private const val REQ_STORAGE = 1
        private const val REQ_NOTIFY = 2
        private const val THUMB_DP = 44
    }
}
