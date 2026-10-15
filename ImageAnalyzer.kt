package com.photocull

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

object ImageAnalyzer {

    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setMinFaceSize(0.05f)
                .build()
        )
    }

    suspend fun analyze(ctx: Context, uri: Uri): Analysis? = withContext(Dispatchers.Default) {
        val bmp = decode(ctx, uri, 1024) ?: return@withContext null
        try {
            val w = bmp.width
            val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            val gray = IntArray(w * h) { i -> lum(px[i]) }

            val brightness = gray.average().toFloat()
            val sharp = sharpness(gray, w, h)
            val hash = dHash(bmp)

            val faces = runCatching { detector.process(InputImage.fromBitmap(bmp, 0)).await() }
                .getOrDefault(emptyList())
            val big = faces.filter { it.boundingBox.width() >= w * 0.06f }
            val closed = big.count { f ->
                val l = f.leftEyeOpenProbability
                val r = f.rightEyeOpenProbability
                l != null && r != null && l < 0.4f && r < 0.4f
            }
            Analysis(sharp, brightness, big.size, closed, hash)
        } finally {
            bmp.recycle()
        }
    }

    private fun lum(c: Int): Int =
        (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000

    // تباين اللابلاسيان على شبكة 4x4، ونأخد متوسط أحدّ مربعين:
    // كده الصورة اللي فيها سبجكت واضح وخلفية مموّهة (بوكيه) متتحسبش مهزوزة.
    private fun sharpness(g: IntArray, w: Int, h: Int): Float {
        val grid = 4
        val n = grid * grid
        val sum = DoubleArray(n)
        val sq = DoubleArray(n)
        val cnt = IntArray(n)
        for (y in 1 until h - 1) {
            val ty = min(y * grid / h, grid - 1)
            for (x in 1 until w - 1) {
                val i = y * w + x
                val lap = (4 * g[i] - g[i - 1] - g[i + 1] - g[i - w] - g[i + w]).toDouble()
                val t = ty * grid + min(x * grid / w, grid - 1)
                sum[t] += lap
                sq[t] += lap * lap
                cnt[t]++
            }
        }
        val vars = (0 until n).filter { cnt[it] > 0 }.map {
            val m = sum[it] / cnt[it]
            (sq[it] / cnt[it] - m * m)
        }.sortedDescending()
        return if (vars.isEmpty()) 0f else vars.take(2).average().toFloat()
    }

    private fun dHash(bmp: Bitmap): Long {
        val s = Bitmap.createScaledBitmap(bmp, 9, 8, true)
        var hash = 0L
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val a = lum(s.getPixel(x, y))
                val b = lum(s.getPixel(x + 1, y))
                hash = (hash shl 1) or (if (a > b) 1L else 0L)
            }
        }
        if (s !== bmp) s.recycle()
        return hash
    }

    private fun decode(ctx: Context, uri: Uri, maxSide: Int): Bitmap? {
        try {
            val cr = ctx.contentResolver
            val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, b) }
            if (b.outWidth <= 0 || b.outHeight <= 0) return null
            var sample = 1
            while (max(b.outWidth, b.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val o = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
                ?: return null
            val deg = runCatching {
                cr.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
            }.getOrDefault(0)
            val scale = maxSide.toFloat() / max(bmp.width, bmp.height)
            val m = Matrix()
            if (scale < 1f) m.postScale(scale, scale)
            if (deg != 0) m.postRotate(deg.toFloat())
            if (!m.isIdentity) {
                val nb = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (nb !== bmp) bmp.recycle()
                bmp = nb
            }
            return bmp
        } catch (t: Throwable) {
            return null
        }
    }

    /** تجميع اللقطات المتشابهة (Burst) واختيار أفضل لقطة في كل مجموعة. */
    fun group(photos: List<Photo>, res: Map<Uri, Analysis>, maxDist: Int = 10): Map<Uri, Analysis> {
        val ordered = photos
            .filter { res[it.uri] != null }
            .sortedWith(compareBy({ it.modified }, { it.name }))
        val groups = mutableListOf<MutableList<Photo>>()
        for (p in ordered) {
            val last = groups.lastOrNull()?.last()
            val same = last != null &&
                java.lang.Long.bitCount(res[last.uri]!!.hash xor res[p.uri]!!.hash) <= maxDist
            if (same) groups.last().add(p) else groups.add(mutableListOf(p))
        }
        val out = HashMap<Uri, Analysis>()
        groups.forEachIndexed { gi, g ->
            if (g.size < 2) return@forEachIndexed
            fun score(p: Photo): Float {
                val a = res[p.uri]!!
                return a.sharpness - (if (a.closedFaces > 0) 1_000_000f else 0f)
            }
            val best = g.maxByOrNull { score(it) }!!
            g.forEach { p -> out[p.uri] = res[p.uri]!!.copy(group = gi, bestInGroup = p === best) }
        }
        return out
    }
}
