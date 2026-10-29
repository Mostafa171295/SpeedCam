package com.example.speedcam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import java.io.BufferedInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

/**
 * مصدر صورة من كاميرا على الشبكة بصيغة MJPEG عبر HTTP (زي كاميرات IP كتير، وتطبيقات تحوّل موبايل قديم لكاميرا).
 * خيط بيقرأ البث وبيحتفظ بآخر فريم بس، وخيط تاني بيحلله ويبعته للمحرك (فريمات بتتجاهل لو المحرك مشغول،
 * عشان التأخير ما يتراكمش). ملحوظة: توقيت كل فريم هو وقت وصوله للموبايل مش وقت التقاطه،
 * فدقة السرعة أقل من كاميرا الموبايل نفسها.
 */
class StreamSource(
    private val url: String,
    private val engine: SpeedEngine,
    private val onStatus: (String) -> Unit
) {
    @Volatile private var stopped = false
    @Volatile private var latest: ByteArray? = null
    @Volatile private var latestTs = 0L
    @Volatile private var conn: HttpURLConnection? = null
    private var reader: Thread? = null
    private var worker: Thread? = null

    fun start() {
        stopped = false
        reader = Thread({ readLoop() }, "stream-read").also { it.isDaemon = true; it.start() }
        worker = Thread({ processLoop() }, "stream-proc").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        stopped = true
        try { conn?.disconnect() } catch (_: Throwable) { }
        reader?.interrupt()
        worker?.interrupt()
    }

    private fun readLoop() {
        while (!stopped) {
            var gotFrames = false
            try {
                onStatus("جاري الاتصال...")
                val u = URL(url)
                val c = u.openConnection() as HttpURLConnection
                conn = c
                c.connectTimeout = 5000
                c.readTimeout = 8000
                u.userInfo?.let { c.setRequestProperty("Authorization", "Basic " + Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP)) }
                c.connect()
                if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
                onStatus("متصل")
                val input = BufferedInputStream(c.inputStream, 1 shl 16)
                val buf = ByteArray(MAX_BUF)
                var len = 0
                while (!stopped) {
                    val n = input.read(buf, len, buf.size - len)
                    if (n < 0) break
                    len += n
                    var consumed = 0
                    while (true) {
                        val soi = find(buf, consumed, len, 0xD8)
                        if (soi < 0) { consumed = max(consumed, len - 1); break }
                        val eoi = find(buf, soi + 2, len, 0xD9)
                        if (eoi < 0) { consumed = soi; break }
                        val end = eoi + 2
                        latest = buf.copyOfRange(soi, end)
                        latestTs = SystemClock.elapsedRealtimeNanos()
                        gotFrames = true
                        consumed = end
                    }
                    if (consumed > 0) {
                        System.arraycopy(buf, consumed, buf, 0, len - consumed)
                        len -= consumed
                    }
                    if (len >= buf.size) len = 0   // فريم أكبر من المتوقع: نتجاهله
                }
                try { c.disconnect() } catch (_: Throwable) { }
            } catch (e: InterruptedException) {
                return
            } catch (e: Throwable) {
                if (stopped) return
                Log.w(TAG, "stream error", e)
                onStatus("انقطع الاتصال (${e.message ?: e.javaClass.simpleName})، إعادة المحاولة...")
            }
            if (stopped) return
            try { Thread.sleep(if (gotFrames) 50L else 2000L) } catch (_: InterruptedException) { return }
        }
    }

    /** مكان أول 0xFF متبوع بالبايت marker في [from, to). */
    private fun find(b: ByteArray, from: Int, to: Int, marker: Int): Int {
        var i = from
        while (i < to - 1) {
            if (b[i] == 0xFF.toByte() && (b[i + 1].toInt() and 0xFF) == marker) return i
            i++
        }
        return -1
    }

    private fun processLoop() {
        var lastTs = 0L
        while (!stopped) {
            val bytes = latest
            val ts = latestTs
            if (bytes == null || ts == lastTs) {
                try { Thread.sleep(5) } catch (_: InterruptedException) { return }
                continue
            }
            lastTs = ts
            val bmp = decode(bytes) ?: continue
            engine.analyzeFrame(bmp, ts)
        }
    }

    private fun decode(b: ByteArray): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        var sample = 1
        while (o.outWidth / sample / 2 >= MAX_W) sample *= 2
        BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    } catch (_: Throwable) {
        null
    }

    private companion object {
        const val TAG = "StreamSource"
        const val MAX_BUF = 2 * 1024 * 1024
        const val MAX_W = 960
    }
}
