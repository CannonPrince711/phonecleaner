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
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var total: TextView
    private lateinit var progress: ProgressBar
    private lateinit var scanBtn: Button
    private lateinit var cleanBtn: Button
    private lateinit var listView: ListView

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var scanner: Scanner? = null

    private var items: List<JunkItem> = emptyList()
    private val adapter = JunkAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        total = findViewById(R.id.total)
        progress = findViewById(R.id.progress)
        scanBtn = findViewById(R.id.scan)
        cleanBtn = findViewById(R.id.clean)
        listView = findViewById(R.id.list)
        listView.adapter = adapter

        scanBtn.setOnClickListener {
            if (scanner != null) scanner?.cancelled = true
            else if (hasStorageAccess()) startScan() else requestStorageAccess()
        }
        cleanBtn.setOnClickListener { confirmClean() }
    }

    // ---------- Permissions ----------

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun requestStorageAccess() {
        AlertDialog.Builder(this)
            .setTitle("Storage access needed")
            .setMessage("GhostCleaner needs access to all files to find and remove junk and ghost files. Nothing is deleted until you review the list and tap Clean.")
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
        val s = Scanner(this)
        scanner = s
        items = emptyList()
        adapter.notifyDataSetChanged()
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

    // ---------- Clean ----------

    private fun confirmClean() {
        val chosen = items.filter { it.selected }
        if (chosen.isEmpty()) return
        val bytes = chosen.sumOf { it.size }
        AlertDialog.Builder(this)
            .setTitle("Delete ${chosen.size} items?")
            .setMessage("This frees about ${Scanner.formatSize(bytes)} and can't be undone.")
            .setPositiveButton("Delete") { _, _ -> clean(chosen) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clean(chosen: List<JunkItem>) {
        setBusy(true, "Cleaning…")
        io.execute {
            val (count, bytes) = Scanner.delete(chosen) { n ->
                main.post { status.text = "Deleting $n / ${chosen.size}" }
            }
            main.post {
                items = items.filterNot { it in chosen && !it.file.exists() }
                adapter.rebuild()
                updateTotal()
                val failed = chosen.size - count
                setBusy(false, "Freed ${Scanner.formatSize(bytes)} — removed $count items" +
                        if (failed > 0) " ($failed couldn't be removed)" else "")
                Toast.makeText(this, "Cleaned ${Scanner.formatSize(bytes)}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------- UI helpers ----------

    private fun setBusy(busy: Boolean, msg: String) {
        status.text = msg
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        scanBtn.text = if (busy && scanner != null) "Stop" else "Scan"
        scanBtn.isEnabled = !busy || scanner != null
        cleanBtn.isEnabled = !busy && items.any { it.selected }
    }

    private fun updateTotal() {
        val chosen = items.filter { it.selected }
        total.text = if (items.isEmpty()) "" else Scanner.formatSize(chosen.sumOf { it.size })
        cleanBtn.isEnabled = scanner == null && chosen.isNotEmpty()
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
        override fun getItem(p: Int) = rows[p]
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
                }
                is Row.Item -> {
                    val f = row.item.file
                    title.text = f.name
                    title.setTypeface(null, Typeface.NORMAL)
                    val rel = f.parent?.removePrefix(Environment.getExternalStorageDirectory().absolutePath) ?: ""
                    sub.text = if (row.item.size > 0) "${Scanner.formatSize(row.item.size)} · $rel" else rel.ifEmpty { "/" }
                    sub.maxLines = 1
                    box.isChecked = row.item.selected
                    box.setOnCheckedChangeListener { _, checked ->
                        row.item.selected = checked
                        notifyDataSetChanged(); updateTotal()
                    }
                    v.setPadding(dp(16), dp(4), 0, dp(4))
                    v.setOnClickListener { box.toggle() }
                }
            }
            return v
        }

        private fun makeRow(): LinearLayout {
            val ctx = this@MainActivity
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
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

    companion object { private const val REQ_STORAGE = 1 }
}
