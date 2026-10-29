package com.budgetingtool.app.receipt

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reads receipt text with Tesseract, entirely on the phone. No network, no Google services,
 * no telemetry. Images are only ever held in memory.
 */
object ReceiptOcr {
    private const val MAX_SIDE = 2000
    private val mutex = Mutex()

    suspend fun read(context: Context, image: Bitmap): String = mutex.withLock {
        withContext(Dispatchers.Default) {
            val dataDir = ensureLanguageData(context)
            val prepared = prepare(image)
            val api = TessBaseAPI()
            try {
                check(api.init(dataDir.absolutePath, "eng")) { "Text recognition couldn't start" }
                api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
                api.setVariable("user_defined_dpi", "300")
                api.setImage(prepared)
                api.getUTF8Text().orEmpty()
            } finally {
                api.recycle()
                if (prepared !== image) prepared.recycle()
            }
        }
    }

    /** Tesseract needs its language file on disk; copy it out of the APK once. */
    private fun ensureLanguageData(context: Context): File {
        val root = File(context.noBackupFilesDir, "ocr")
        val target = File(root, "tessdata/eng.traineddata")
        if (!target.exists() || target.length() == 0L) {
            target.parentFile?.mkdirs()
            context.assets.open("tessdata/eng.traineddata").use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }
        return root
    }

    /** Downscale huge photos and convert to high-contrast grayscale, which OCR reads much better. */
    private fun prepare(src: Bitmap): Bitmap {
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(src.width, src.height))
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val gray = ColorMatrix().apply { setSaturation(0f) }
        val contrast = 1.4f
        val shift = (-0.5f * contrast + 0.5f) * 255f
        gray.postConcat(ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, shift,
            0f, contrast, 0f, 0f, shift,
            0f, 0f, contrast, 0f, shift,
            0f, 0f, 0f, 1f, 0f,
        )))
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(gray) }
        Canvas(out).apply {
            scale(scale, scale)
            drawBitmap(src, 0f, 0f, paint)
        }
        return out
    }

    /** Loads a photo picked from the gallery, upright and reasonably sized. */
    fun decode(context: Context, uri: Uri): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val side = maxOf(info.size.width, info.size.height)
                if (side > MAX_SIDE * 2) decoder.setTargetSampleSize(side / (MAX_SIDE * 2) + 1)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }
    }.getOrNull()

    fun rotate(src: Bitmap, degrees: Int): Bitmap =
        if (degrees % 360 == 0) src
        else Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
}
