package vn.micrecorder.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var timer: TextView
    private lateinit var statusText: TextView
    private lateinit var level: LinearProgressIndicator
    private lateinit var fabRecord: FloatingActionButton
    private lateinit var btnPause: MaterialButton
    private lateinit var header: TextView
    private lateinit var empty: TextView
    private lateinit var playerPanel: MaterialCardView
    private lateinit var playerName: TextView
    private lateinit var playerTime: TextView
    private lateinit var seek: SeekBar
    private lateinit var btnPlayPause: ImageButton
    private lateinit var adapter: RecordingsAdapter

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var lastStatus: RecorderService.Status? = null

    private var player: MediaPlayer? = null
    private var playing: Recording? = null
    private var userSeeking = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasMicPermission()) {
                startRecording()
            } else if (!ActivityCompat.shouldShowRequestPermissionRationale(
                    this, Manifest.permission.RECORD_AUDIO)
            ) {
                showOpenSettingsDialog()
            } else {
                toast(R.string.perm_needed)
            }
        }

    private val ticker = object : Runnable {
        override fun run() {
            updateRecorderUi()
            updatePlayerUi()
            handler.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))

        timer = findViewById(R.id.timer)
        statusText = findViewById(R.id.status)
        level = findViewById(R.id.level)
        fabRecord = findViewById(R.id.fabRecord)
        btnPause = findViewById(R.id.btnPause)
        header = findViewById(R.id.header)
        empty = findViewById(R.id.empty)
        playerPanel = findViewById(R.id.playerPanel)
        playerName = findViewById(R.id.playerName)
        playerTime = findViewById(R.id.playerTime)
        seek = findViewById(R.id.seek)
        btnPlayPause = findViewById(R.id.btnPlayPause)

        adapter = RecordingsAdapter(onClick = ::play, onMore = ::showItemMenu)
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        list.adapter = adapter

        fabRecord.setOnClickListener { onRecordClicked() }
        btnPause.setOnClickListener {
            when (RecorderService.status) {
                RecorderService.Status.RECORDING -> RecorderService.send(this, RecorderService.ACTION_PAUSE)
                RecorderService.Status.PAUSED -> RecorderService.send(this, RecorderService.ACTION_RESUME)
                RecorderService.Status.IDLE -> Unit
            }
        }

        btnPlayPause.setOnClickListener { togglePlayback() }
        findViewById<ImageButton>(R.id.btnClosePlayer).setOnClickListener { releasePlayer() }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) playerTime.text = timeLabel(progress, sb.max)
            }
            override fun onStartTrackingTouch(sb: SeekBar) { userSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                userSeeking = false
                player?.seekTo(sb.progress)
            }
        })
    }

    override fun onStart() {
        super.onStart()
        loadRecordings()
        handler.post(ticker)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(ticker)
        player?.let { if (it.isPlaying) it.pause() }
    }

    override fun onDestroy() {
        releasePlayer()
        executor.shutdown()
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_quality) {
            showQualityDialog()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // ---------------- Recording ----------------

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun onRecordClicked() {
        if (RecorderService.status != RecorderService.Status.IDLE) {
            RecorderService.send(this, RecorderService.ACTION_STOP)
            return
        }
        val needed = mutableListOf<String>()
        if (!hasMicPermission()) needed += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.POST_NOTIFICATIONS

        if (needed.isEmpty()) startRecording()
        else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun startRecording() {
        releasePlayer()
        RecorderService.send(this, RecorderService.ACTION_START)
    }

    private fun updateRecorderUi() {
        val st = RecorderService.status
        timer.text = formatDuration(RecorderService.elapsedMs())
        level.progress = amplitudeToPercent(RecorderService.amplitude())

        if (st != lastStatus) {
            when (st) {
                RecorderService.Status.IDLE -> {
                    statusText.setText(R.string.status_ready)
                    fabRecord.setImageResource(R.drawable.ic_mic)
                    fabRecord.contentDescription = getString(R.string.record)
                    btnPause.isEnabled = false
                    btnPause.setText(R.string.pause)
                    btnPause.setIconResource(R.drawable.ic_pause)
                }
                RecorderService.Status.RECORDING -> {
                    statusText.setText(R.string.status_recording)
                    fabRecord.setImageResource(R.drawable.ic_stop)
                    fabRecord.contentDescription = getString(R.string.stop)
                    btnPause.isEnabled = true
                    btnPause.setText(R.string.pause)
                    btnPause.setIconResource(R.drawable.ic_pause)
                }
                RecorderService.Status.PAUSED -> {
                    statusText.setText(R.string.status_paused)
                    fabRecord.setImageResource(R.drawable.ic_stop)
                    fabRecord.contentDescription = getString(R.string.stop)
                    btnPause.isEnabled = true
                    btnPause.setText(R.string.resume)
                    btnPause.setIconResource(R.drawable.ic_mic)
                }
            }
            // Vừa dừng ghi -> làm mới danh sách
            if (lastStatus != null && lastStatus != RecorderService.Status.IDLE &&
                st == RecorderService.Status.IDLE
            ) {
                loadRecordings()
            }
            lastStatus = st
        }
        RecorderService.lastSaved?.let {
            RecorderService.lastSaved = null
            Toast.makeText(this, getString(R.string.saved, it.nameWithoutExtension), Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------- List ----------------

    private fun loadRecordings() {
        if (executor.isShutdown) return
        val exclude = RecorderService.currentFile
        val ctx = applicationContext
        executor.execute {
            val items = Storage.list(ctx, exclude)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                adapter.items = items
                empty.isVisible = items.isEmpty()
                header.text = getString(R.string.recordings_header, items.size)
            }
        }
    }

    private fun showItemMenu(rec: Recording, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.item_menu, popup.menu)
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_rename -> renameDialog(rec)
                R.id.action_share -> share(rec)
                R.id.action_delete -> deleteDialog(rec)
            }
            true
        }
        popup.show()
    }

    private fun renameDialog(rec: Recording) {
        val input = EditText(this).apply {
            setText(rec.file.nameWithoutExtension)
            setSingleLine()
            selectAll()
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                val newName = input.text.toString().trim()
                    .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                if (newName.isEmpty() || newName == rec.file.nameWithoutExtension) return@setPositiveButton
                val target = File(rec.file.parentFile, "$newName.${Storage.EXT}")
                if (target.exists()) {
                    toast(R.string.name_exists)
                    return@setPositiveButton
                }
                if (playing?.file == rec.file) releasePlayer()
                if (rec.file.renameTo(target)) loadRecordings() else toast(R.string.rename_failed)
            }
            .show()
        input.requestFocus()
    }

    private fun share(rec: Recording) {
        try {
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", rec.file)
            val send = Intent(Intent.ACTION_SEND)
                .setType("audio/mp4")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, getString(R.string.share)))
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "Error", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteDialog(rec: Recording) {
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.delete_confirm, rec.file.nameWithoutExtension))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                if (playing?.file == rec.file) releasePlayer()
                rec.file.delete()
                loadRecordings()
            }
            .show()
    }

    private fun showQualityDialog() {
        val qs = Quality.entries
        val labels = qs.map { getString(it.labelRes) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.quality)
            .setSingleChoiceItems(labels, AppPrefs.quality(this).ordinal) { d, which ->
                AppPrefs.setQuality(this, qs[which])
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showOpenSettingsDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.perm_title)
            .setMessage(R.string.perm_settings_msg)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", packageName, null))
                )
            }
            .show()
    }

    // ---------------- Playback ----------------

    private fun play(rec: Recording) {
        if (RecorderService.status != RecorderService.Status.IDLE) {
            toast(R.string.no_play_while_recording)
            return
        }
        if (playing?.file == rec.file && player != null) {
            togglePlayback()
            return
        }
        releasePlayer()
        val mp = MediaPlayer()
        try {
            mp.setDataSource(rec.file.absolutePath)
            mp.prepare()
        } catch (e: Exception) {
            mp.release()
            toast(R.string.play_failed)
            return
        }
        mp.setOnCompletionListener { it.seekTo(0) }
        mp.start()
        player = mp
        playing = rec
        playerName.text = rec.file.nameWithoutExtension
        seek.max = mp.duration.coerceAtLeast(1)
        seek.progress = 0
        playerPanel.isVisible = true
        adapter.selectedPath = rec.file.absolutePath
    }

    private fun togglePlayback() {
        val mp = player ?: return
        if (mp.isPlaying) mp.pause() else mp.start()
    }

    private fun releasePlayer() {
        player?.release()
        player = null
        playing = null
        if (::playerPanel.isInitialized) {
            playerPanel.isVisible = false
            adapter.selectedPath = null
        }
    }

    private fun updatePlayerUi() {
        val mp = player ?: return
        val pos = mp.currentPosition
        if (!userSeeking) {
            seek.progress = pos
            playerTime.text = timeLabel(pos, seek.max)
        }
        val isPlaying = mp.isPlaying
        btnPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        btnPlayPause.contentDescription = getString(if (isPlaying) R.string.pause else R.string.play)
    }

    private fun timeLabel(pos: Int, dur: Int) =
        "${formatDuration(pos.toLong())} / ${formatDuration(dur.toLong())}"

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
}
