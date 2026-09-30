package app.photoindex

import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.photoindex.core.ImageBytes
import app.photoindex.core.JpegCompressResult
import app.photoindex.core.jpegHasGpsExif
import app.photoindex.platform.AndroidJpegCompressor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class 压缩结果不含GPS定位 {
    @Test
    fun 带定位的图压完后字节里没有GPS() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = jpegWithGps(context.cacheDir)
        assertTrue(jpegHasGpsExif(input))
        val located = ExifInterface(input.inputStream())
        assertTrue(located.latLong != null)

        val result = AndroidJpegCompressor.compress(ImageBytes { input }, longEdge = 8, jpegQuality = 70)

        val ready = result as? JpegCompressResult.Ready
            ?: error("压缩失败：${(result as JpegCompressResult.Failed).reason}")
        assertEquals(8, ready.widthPx)
        assertEquals(4, ready.heightPx)
        assertFalse(jpegHasGpsExif(ready.bytes))
        val stripped = ExifInterface(ready.bytes.inputStream())
        assertNull(stripped.latLong)
    }

    @Test
    fun 长边和质量都由参数决定且上一张位图不会留下来() {
        val bitmapFields = AndroidJpegCompressor::class.java.declaredFields.filter { field ->
            Bitmap::class.java.isAssignableFrom(field.type)
        }
        assertEquals(emptyList<Any>(), bitmapFields)

        val wide = noisyJpeg(width = 40, height = 20)
        val first = AndroidJpegCompressor.compress(ImageBytes { wide }, longEdge = 10, jpegQuality = 80)
        val firstReady = first as JpegCompressResult.Ready
        assertEquals(10, firstReady.widthPx)
        assertEquals(5, firstReady.heightPx)

        val tall = noisyJpeg(width = 30, height = 40)
        val second = AndroidJpegCompressor.compress(ImageBytes { tall }, longEdge = 10, jpegQuality = 80)
        val secondReady = second as JpegCompressResult.Ready
        assertEquals(7, secondReady.widthPx)
        assertEquals(10, secondReady.heightPx)

        val noisy = noisyJpeg(width = 64, height = 48)
        val high = AndroidJpegCompressor.compress(ImageBytes { noisy }, longEdge = null, jpegQuality = 90)
        val low = AndroidJpegCompressor.compress(ImageBytes { noisy }, longEdge = null, jpegQuality = 60)
        val highReady = high as JpegCompressResult.Ready
        val lowReady = low as JpegCompressResult.Ready
        assertTrue(lowReady.bytes.size < highReady.bytes.size)
    }

    @Test
    fun 解不开的图返回原因且HEIC解不开时写明HEIC() {
        val garbage = AndroidJpegCompressor.compress(ImageBytes { byteArrayOf(1, 2, 3, 4) }, 1280, 80)
        assertTrue(garbage is JpegCompressResult.Failed)
        assertEquals("图片解不开", (garbage as JpegCompressResult.Failed).reason)

        val heic = ByteArray(16)
        heic[3] = 16
        heic[4] = 'f'.code.toByte()
        heic[5] = 't'.code.toByte()
        heic[6] = 'y'.code.toByte()
        heic[7] = 'p'.code.toByte()
        heic[8] = 'h'.code.toByte()
        heic[9] = 'e'.code.toByte()
        heic[10] = 'i'.code.toByte()
        heic[11] = 'c'.code.toByte()
        val failed = AndroidJpegCompressor.compress(ImageBytes { heic }, 1280, 80)
        assertTrue(failed is JpegCompressResult.Failed)
        assertEquals("HEIC 解不开", (failed as JpegCompressResult.Failed).reason)
    }
}

private fun jpegWithGps(cacheDir: File): ByteArray {
    val bitmap = Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(Color.rgb(40, 120, 200))
    val plain = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, plain)
    bitmap.recycle()
    val file = File(cacheDir, "t8-gps.jpg")
    file.writeBytes(plain.toByteArray())
    val exif = ExifInterface(file.absolutePath)
    exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, "31/1,14/1,29/1")
    exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
    exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "121/1,28/1,12/1")
    exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
    exif.saveAttributes()
    return file.readBytes()
}

private fun noisyJpeg(width: Int, height: Int): ByteArray {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(width * height) { index ->
        Color.rgb((index * 17) % 256, (index * 29) % 256, (index * 43) % 256)
    }
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    val output = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
    bitmap.recycle()
    return output.toByteArray()
}

private fun ByteArray.inputStream() = java.io.ByteArrayInputStream(this)
