package com.example.epubreader

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.OpenableColumns
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private var controller: MediaControllerCompat? = null
    private var connection: ServiceConnection? = null
    private var binder: TtsService.LocalBinder? = null
    private lateinit var chapterList: ListView
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var statusText: TextView
    private lateinit var playPauseBtn: Button
    private lateinit var contentsBtn: Button
    private lateinit var progressSeek: SeekBar
    private lateinit var progressLabel: TextView
    private lateinit var nowReadingText: TextView
    private lateinit var bookProgressText: TextView
    private lateinit var sleepCountdown: TextView
    private lateinit var settingsBtn: Button
    private lateinit var sleepBtn: Button
    private val chapterTitles = ArrayList<String>()
    private val prefs by lazy { getSharedPreferences("book", Context.MODE_PRIVATE) }
    private var dragging = false
    private var currentTotal = 0
    private var sleepMinutes = 0
    private val uiHandler = Handler(Looper.getMainLooper())
    private val uiPoll = object : Runnable {
        override fun run() {
            updateProgress()
            updateBookInfo()
            updateCountdown()
            uiHandler.postDelayed(this, 1000)
        }
    }

    private val callback = object : MediaControllerCompat.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            updateUiFromState()
        }

        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            updateUiFromState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)
        buildUi()
        requestNotificationPermission()
        connectService()
        restoreLastBook()
    }

    private fun applyTheme() {
        when (prefs.getString("theme", "system")) {
            "light" -> setTheme(R.style.AppThemeLight)
            "dark" -> setTheme(R.style.AppThemeDark)
            else -> setTheme(R.style.AppTheme)
        }
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(uiPoll)
        connection?.let { unbindService(it) }
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 16)
        }

        // top row: open a book + recents + contents toggle
        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val openBtn = Button(this).apply {
            text = "Open EPUB"
            setOnClickListener { pickEpub() }
        }
        val recentsBtn = Button(this).apply {
            text = "Recents"
            setOnClickListener { showRecentsDialog() }
        }
        contentsBtn = Button(this).apply {
            text = "Contents"
            setOnClickListener { toggleContents() }
        }
        topRow.addView(openBtn)
        topRow.addView(recentsBtn)
        topRow.addView(contentsBtn)
        root.addView(topRow)

        statusText = TextView(this).apply {
            text = "Open an EPUB file to begin."
            textSize = 14f
            setPadding(0, 12, 0, 4)
        }
        root.addView(statusText)

        sleepCountdown = TextView(this).apply {
            textSize = 14f
            visibility = View.GONE
        }
        root.addView(sleepCountdown)

        nowReadingText = TextView(this).apply {
            textSize = 14f
            setPadding(0, 4, 0, 0)
        }
        root.addView(nowReadingText)

        bookProgressText = TextView(this).apply {
            textSize = 14f
            setPadding(0, 0, 0, 4)
        }
        root.addView(bookProgressText)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, chapterTitles)
        chapterList = ListView(this).apply {
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, position, _ -> jumpToChapter(position) }
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(chapterList)

        // transport controls
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val prev = Button(this).apply {
            text = "Prev"
            setOnClickListener { controller?.transportControls?.skipToPrevious() }
        }
        playPauseBtn = Button(this).apply {
            text = "Play"
            setOnClickListener { togglePlayPause() }
        }
        val next = Button(this).apply {
            text = "Next"
            setOnClickListener { controller?.transportControls?.skipToNext() }
        }
        controls.addView(prev)
        controls.addView(playPauseBtn)
        controls.addView(next)
        root.addView(controls)

        // chapter progress slider
        val progressRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val progressCap = TextView(this).apply {
            text = "Chapter "
            textSize = 14f
        }
        progressSeek = SeekBar(this).apply {
            isEnabled = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) progressLabel.text = "${progress + 1} / $currentTotal"
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {
                    dragging = true
                }

                override fun onStopTrackingTouch(sb: SeekBar?) {
                    dragging = false
                    binder?.seekWithinChapter(sb?.progress ?: 0)
                }
            })
        }
        progressLabel = TextView(this).apply {
            text = ""
            textSize = 14f
        }
        progressRow.addView(progressCap)
        progressRow.addView(progressSeek)
        progressRow.addView(progressLabel)
        root.addView(progressRow)

        settingsBtn = Button(this).apply {
            text = "Settings"
            setOnClickListener { showSettingsDialog() }
        }
        root.addView(settingsBtn)

        setContentView(root)
    }

    private fun toggleContents() {
        val show = chapterList.visibility != View.VISIBLE
        chapterList.visibility = if (show) View.VISIBLE else View.GONE
        contentsBtn.text = if (show) "Hide contents" else "Contents"
    }

    private fun showSettingsDialog() {
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 0)
        }

        view.addView(TextView(this).apply {
            text = "Speech speed"
            textSize = 14f
        })
        val speedLabel = TextView(this).apply {
            text = "1.00x"
            textSize = 14f
        }
        val speedSeek = SeekBar(this).apply {
            max = 30
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val r = 0.5f + progress * 0.05f
                    speedLabel.text = String.format("%.2fx", r)
                    if (fromUser) binder?.setRate(r)
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        val speedRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        speedRow.addView(speedSeek)
        speedRow.addView(speedLabel)
        view.addView(speedRow)
        // reflect the current speed
        binder?.getRate()?.let { rate ->
            speedSeek.progress = ((rate - 0.5f) / 0.05f).roundToInt()
        }

        view.addView(TextView(this).apply {
            text = "Sleep timer"
            textSize = 14f
            setPadding(0, 20, 0, 0)
        })
        sleepBtn = Button(this).apply {
            text = if (sleepMinutes > 0) "Sleep: $sleepMinutes min" else "Set sleep timer"
            setOnClickListener { showSleepDialog() }
        }
        view.addView(sleepBtn)

        view.addView(TextView(this).apply {
            text = "Theme"
            textSize = 14f
            setPadding(0, 20, 0, 0)
        })
        val currentTheme = prefs.getString("theme", "system")
        val themeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Light" to "light", "Dark" to "dark", "System" to "system").forEach { (label, value) ->
            themeRow.addView(Button(this).apply {
                text = label
                isEnabled = currentTheme != value
                setOnClickListener {
                    prefs.edit().putString("theme", value).apply()
                    this@MainActivity.recreate()
                }
            })
        }
        view.addView(themeRow)

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(view)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun updateProgress() {
        val p = binder?.getChapterProgress() ?: return
        val cur = p.first
        val total = p.second
        currentTotal = total
        if (total <= 0) {
            progressSeek.isEnabled = false
            progressLabel.text = ""
            return
        }
        progressSeek.isEnabled = true
        progressSeek.max = total - 1
        if (!dragging) {
            progressSeek.progress = cur.coerceIn(0, total - 1)
            progressLabel.text = "${cur.coerceIn(0, total - 1) + 1} / $total"
        }
    }

    private fun updateBookInfo() {
        val bp = binder?.getBookProgress() ?: return
        val cp = binder?.getChapterProgress() ?: return
        if (bp.totalChapters <= 0) {
            nowReadingText.text = ""
            bookProgressText.text = ""
            return
        }
        nowReadingText.text = if (bp.chapterTitle.isNotEmpty()) "Now reading: ${bp.chapterTitle}" else ""
        val sentenceFrac = if (cp.second > 0) cp.first.toFloat() / cp.second else 0f
        val bookPct = ((bp.chapterIndex + sentenceFrac) / bp.totalChapters * 100).roundToInt()
        bookProgressText.text = "Chapter ${bp.chapterIndex + 1} of ${bp.totalChapters} \u00b7 $bookPct%"
    }

    private fun updateCountdown() {
        val remaining = binder?.getSleepRemaining() ?: 0L
        if (remaining > 0) {
            val totalSec = (remaining / 1000).toInt()
            sleepCountdown.text = "Sleep timer: %d:%02d".format(totalSec / 60, totalSec % 60)
            sleepCountdown.visibility = View.VISIBLE
        } else {
            sleepCountdown.visibility = View.GONE
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1
                )
            }
        }
    }

    private fun connectService() {
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val b = service as? TtsService.LocalBinder ?: return
                binder = b
                val token = b.getToken()
                if (token != null) {
                    controller = MediaControllerCompat(this@MainActivity, token)
                    controller?.registerCallback(callback)
                    updateUiFromState()
                }
                // keep the chapter progress slider + sleep countdown in sync
                uiHandler.post(uiPoll)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                controller?.unregisterCallback(callback)
                controller = null
                binder = null
            }
        }
        connection = conn
        bindService(Intent(this, TtsService::class.java), conn, Context.BIND_AUTO_CREATE)
    }

    private fun updateUiFromState() {
        val state = controller?.playbackState?.state ?: PlaybackStateCompat.STATE_NONE
        playPauseBtn.text = if (state == PlaybackStateCompat.STATE_PLAYING) "Pause" else "Play"
    }

    private fun togglePlayPause() {
        val state = controller?.playbackState?.state ?: PlaybackStateCompat.STATE_NONE
        if (state == PlaybackStateCompat.STATE_PLAYING) {
            controller?.transportControls?.pause()
        } else {
            controller?.transportControls?.play()
        }
    }

    private fun jumpToChapter(position: Int) {
        TtsService.seekTo(this, position)
        statusText.text = "Chapter ${position + 1}"
    }

    private fun showSleepDialog() {
        val options = arrayOf("Off", "5 minutes", "10 minutes", "15 minutes", "30 minutes", "45 minutes", "60 minutes")
        val minutes = intArrayOf(0, 5, 10, 15, 30, 45, 60)
        AlertDialog.Builder(this)
            .setTitle("Sleep timer")
            .setItems(options) { _, which ->
                val m = minutes[which]
                sleepMinutes = m
                binder?.cancelSleepTimer()
                if (m > 0) binder?.setSleepTimer(m)
                sleepBtn.text = if (m > 0) "Sleep: ${m} min" else "Set sleep timer"
            }
            .show()
    }

    private fun pickEpub() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/epub+zip"
        }
        startActivityForResult(intent, 100)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK && data?.data != null) {
            loadEpub(data.data!!)
        }
    }

    private fun loadEpub(uri: Uri) {
        statusText.text = "Loading\u2026"
        Thread {
            try {
                val name = queryName(uri) ?: "Book"
                val existing = loadRecents().firstOrNull { it.first == name }
                val fileName = if (existing != null && File(filesDir, "books/${existing.second}").exists()) {
                    existing.second
                } else {
                    copyToBooks(uri)
                }
                addRecent(name, fileName)
                openBook(name, fileName, resume = false)
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Failed to open file."
                    Toast.makeText(this, "Could not read EPUB: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun restoreLastBook() {
        val recent = loadRecents().firstOrNull() ?: return
        val (name, fileName) = recent
        val file = File(filesDir, "books/$fileName")
        if (!file.exists()) return
        statusText.text = "Restoring $name\u2026"
        openBook(name, fileName, resume = true)
    }

    private fun showRecentsDialog() {
        val recents = loadRecents()
        if (recents.isEmpty()) {
            Toast.makeText(this, "No recent books yet.", Toast.LENGTH_SHORT).show()
            return
        }
        val list = ArrayList(recents)

        var dialog: AlertDialog? = null
        val listView = ListView(this)
        listView.adapter = object : BaseAdapter() {
            override fun getCount(): Int = list.size
            override fun getItem(position: Int): Any = list[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val (name, fileName) = list[position]
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(24, 8, 24, 8)
                }
                row.addView(TextView(this@MainActivity).apply {
                    text = name
                    textSize = 16f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(Button(this@MainActivity).apply {
                    text = "Delete"
                    setOnClickListener {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Delete recent?")
                            .setMessage("Remove \"$name\" from recents?")
                            .setPositiveButton("Delete") { _, _ ->
                                removeRecent(fileName)
                                list.removeAt(position)
                                notifyDataSetChanged()
                                if (list.isEmpty()) dialog?.dismiss()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                })
                return row
            }
        }

        dialog = AlertDialog.Builder(this)
            .setTitle("Recent books")
            .setView(listView)
            .setNegativeButton("Close", null)
            .create()
        listView.setOnItemClickListener { _, _, position, _ ->
            val (name, fileName) = list[position]
            addRecent(name, fileName)
            openBook(name, fileName, resume = false)
            dialog?.dismiss()
        }
        dialog?.show()
    }

    private fun openBook(name: String, fileName: String, resume: Boolean) {
        val file = File(filesDir, "books/$fileName")
        Thread {
            try {
                val parsed = EpubParser.parse(file)
                runOnUiThread {
                    BookHolder.title = name
                    BookHolder.chapters = parsed
                    chapterTitles.clear()
                    for (i in parsed.indices) {
                        chapterTitles.add("${parsed[i].title}  (${parsed[i].text.length} chars)")
                    }
                    adapter.notifyDataSetChanged()
                    statusText.text = if (resume) {
                        "Resuming $name \u2014 press Play to listen."
                    } else {
                        "${parsed.size} chapters loaded \u2014 press Play to listen."
                    }
                    TtsService.load(this, resume = resume)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Failed to open file."
                    Toast.makeText(this, "Could not read EPUB: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun copyToBooks(uri: Uri): String {
        val dir = File(filesDir, "books").apply { mkdirs() }
        val file = File(dir, "book_${System.currentTimeMillis()}.epub")
        contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return file.name
    }

    private fun loadRecents(): List<Pair<String, String>> {
        val count = prefs.getInt("recent_count", 0)
        val list = ArrayList<Pair<String, String>>(count)
        for (i in 0 until count) {
            val name = prefs.getString("recent_${i}_name", null)
            val file = prefs.getString("recent_${i}_file", null)
            if (name != null && file != null) list.add(name to file)
        }
        return list
    }

    private fun saveRecents(list: List<Pair<String, String>>) {
        val edit = prefs.edit()
        val oldCount = prefs.getInt("recent_count", 0)
        for (i in 0 until oldCount) {
            edit.remove("recent_${i}_name")
            edit.remove("recent_${i}_file")
        }
        list.forEachIndexed { i, (name, file) ->
            edit.putString("recent_${i}_name", name)
            edit.putString("recent_${i}_file", file)
        }
        edit.putInt("recent_count", list.size).apply()
    }

    private fun addRecent(name: String, fileName: String) {
        val updated = (listOf(name to fileName) + loadRecents().filter { it.first != name }).take(6)
        saveRecents(updated)
        // remove stored copies that are no longer in the recents list
        val keep = updated.map { it.second }.toSet()
        File(filesDir, "books").listFiles()?.forEach { f ->
            if (f.name !in keep) f.delete()
        }
    }

    private fun removeRecent(fileName: String) {
        saveRecents(loadRecents().filter { it.second != fileName })
        File(filesDir, "books/$fileName").delete()
    }

    private fun queryName(uri: Uri): String? {
        var name: String? = null
        try {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = it.getString(idx)
                }
            }
        } catch (_: Exception) {
        }
        return name
    }
}
