package vn.micrecorder.app

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10

data class Recording(
    val file: File,
    val durationMs: Long,
    val sizeBytes: Long,
    val lastModified: Long,
)

enum class Quality(val bitRate: Int, val sampleRate: Int, val labelRes: Int) {
    HIGH(128_000, 44_100, R.string.quality_high),
    STANDARD(64_000, 44_100, R.string.quality_standard),
    LOW(32_000, 22_050, R.string.quality_low),
}

object AppPrefs {
    private const val FILE = "settings"
    private const val KEY_QUALITY = "quality"

    fun quality(context: Context): Quality {
        val name = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_QUALITY, Quality.HIGH.name)
        return Quality.entries.firstOrNull { it.name == name } ?: Quality.HIGH
    }

    fun setQuality(context: Context, q: Quality) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_QUALITY, q.name).apply()
    }
}

object Storage {
    const val EXT = "m4a"

    /** Thư mục riêng của app – không cần quyền bộ nhớ. */
    fun dir(context: Context): File {
        val d = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: File(context.filesDir, "recordings")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun newFile(context: Context): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        var f = File(dir(context), "REC_$stamp.$EXT")
        var i = 2
        while (f.exists()) f = File(dir(context), "REC_${stamp}_${i++}.$EXT")
        return f
    }

    fun list(context: Context, exclude: File?): List<Recording> {
        val files = dir(context).listFiles { f ->
            f.isFile && f.extension.equals(EXT, ignoreCase = true) && f != exclude
        } ?: return emptyList()
        return files
            .map { Recording(it, durationOf(it), it.length(), it.lastModified()) }
            .sortedByDescending { it.lastModified }
    }

    private fun durationOf(f: File): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }
}

fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.US, "%02d:%02d", m, sec)
}

/** Biên độ 0..32767 -> 0..100 theo thang dB (−60 dB .. 0 dB). */
fun amplitudeToPercent(amp: Int): Int {
    if (amp <= 0) return 0
    val db = 20 * log10(amp / 32767.0)
    return (((db + 60) / 60) * 100).toInt().coerceIn(0, 100)
}
