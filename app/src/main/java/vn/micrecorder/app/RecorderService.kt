package vn.micrecorder.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Foreground service giữ việc ghi âm chạy cả khi tắt màn hình / thoát app.
 * Trạng thái được đặt trong companion object để Activity đọc định kỳ.
 */
class RecorderService : Service() {

    enum class Status { IDLE, RECORDING, PAUSED }

    companion object {
        const val ACTION_START = "vn.micrecorder.app.START"
        const val ACTION_PAUSE = "vn.micrecorder.app.PAUSE"
        const val ACTION_RESUME = "vn.micrecorder.app.RESUME"
        const val ACTION_STOP = "vn.micrecorder.app.STOP"

        private const val CHANNEL_ID = "recording"
        private const val NOTIF_ID = 1

        @Volatile var status: Status = Status.IDLE
            private set
        @Volatile var currentFile: File? = null
            private set
        /** File vừa lưu xong gần nhất (để Activity báo cho người dùng). */
        @Volatile var lastSaved: File? = null

        @Volatile private var recorder: MediaRecorder? = null
        @Volatile private var accumulatedMs = 0L
        @Volatile private var segmentStart = 0L

        fun elapsedMs(): Long = when (status) {
            Status.RECORDING -> accumulatedMs + (SystemClock.elapsedRealtime() - segmentStart)
            Status.PAUSED -> accumulatedMs
            Status.IDLE -> 0L
        }

        fun amplitude(): Int =
            if (status == Status.RECORDING) {
                try { recorder?.maxAmplitude ?: 0 } catch (e: Exception) { 0 }
            } else 0

        fun send(context: Context, action: String) {
            val i = Intent(context, RecorderService::class.java).setAction(action)
            if (action == ACTION_START) ContextCompat.startForegroundService(context, i)
            else context.startService(i)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_STOP -> stopRecording()
            else -> if (status == Status.IDLE) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(false, elapsedMs()), type)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.error_start, Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        if (status != Status.IDLE) { updateNotification(); return } // đang ghi rồi

        val file = Storage.newFile(this)
        val q = AppPrefs.quality(this)
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this)
        else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(q.sampleRate)
            r.setAudioEncodingBitRate(q.bitRate)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            file.delete()
            Toast.makeText(this, R.string.error_start, Toast.LENGTH_LONG).show()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        recorder = r
        currentFile = file
        accumulatedMs = 0L
        segmentStart = SystemClock.elapsedRealtime()
        status = Status.RECORDING
        updateNotification()
    }

    private fun pauseRecording() {
        if (status != Status.RECORDING) return
        try {
            recorder?.pause()
            accumulatedMs += SystemClock.elapsedRealtime() - segmentStart
            status = Status.PAUSED
        } catch (_: Exception) {}
        updateNotification()
    }

    private fun resumeRecording() {
        if (status != Status.PAUSED) return
        try {
            recorder?.resume()
            segmentStart = SystemClock.elapsedRealtime()
            status = Status.RECORDING
        } catch (_: Exception) {}
        updateNotification()
    }

    private fun stopRecording() {
        releaseRecorder(keepFile = true)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseRecorder(keepFile: Boolean) {
        val r = recorder
        val f = currentFile
        var ok = true
        if (r != null) {
            try { r.stop() } catch (e: RuntimeException) { ok = false } // quá ngắn -> lỗi
            r.release()
        }
        recorder = null
        currentFile = null
        accumulatedMs = 0L
        status = Status.IDLE
        if (f != null) {
            if (ok && keepFile) lastSaved = f else f.delete()
        }
    }

    override fun onDestroy() {
        if (status != Status.IDLE) releaseRecorder(keepFile = true)
        super.onDestroy()
    }

    // ---------- Notification ----------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW
            )
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
    }

    private fun updateNotification() {
        if (status == Status.IDLE) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(status == Status.PAUSED, elapsedMs()))
    }

    private fun buildNotification(paused: Boolean, elapsed: Long): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(getString(if (paused) R.string.notif_paused else R.string.notif_recording))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (paused) {
            b.setContentText(formatDuration(elapsed))
            b.setShowWhen(false)
            b.addAction(R.drawable.ic_play_notif, getString(R.string.resume), actionIntent(ACTION_RESUME))
        } else {
            b.setShowWhen(true)
            b.setUsesChronometer(true)
            b.setWhen(System.currentTimeMillis() - elapsed)
            b.addAction(R.drawable.ic_pause_notif, getString(R.string.pause), actionIntent(ACTION_PAUSE))
        }
        b.addAction(R.drawable.ic_stop_notif, getString(R.string.stop), actionIntent(ACTION_STOP))
        return b.build()
    }

    private fun actionIntent(action: String): PendingIntent =
        PendingIntent.getService(
            this, action.hashCode(),
            Intent(this, RecorderService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
}
