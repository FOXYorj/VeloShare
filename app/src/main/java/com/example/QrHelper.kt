package com.example

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

class QrHelper {

    companion object {
        fun generateQrCode(content: String, size: Int = 512): Bitmap {
            return try {
                val bitMatrix = com.google.zxing.MultiFormatWriter().encode(
                    content,
                    BarcodeFormat.QR_CODE,
                    size,
                    size
                )
                val width = bitMatrix.width
                val height = bitMatrix.height
                val pixels = IntArray(width * height)
                for (y in 0 until height) {
                    val offset = y * width
                    for (x in 0 until width) {
                        pixels[offset + x] = if (bitMatrix[x, y]) Color.BLACK else Color.WHITE
                    }
                }
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                    setPixels(pixels, 0, width, 0, 0, width, height)
                }
            } catch (e: Exception) {
                Log.e("QrHelper", "Error generating QR code", e)
                Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(Color.RED)
                }
            }
        }
    }

    class QrImageAnalyzer(private val onQrDetected: (String) -> Unit) : ImageAnalysis.Analyzer {
        private val reader = MultiFormatReader().apply {
            val hints = mapOf(
                com.google.zxing.DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)
            )
            setHints(hints)
        }

        override fun analyze(image: ImageProxy) {
            // CameraX output is YUV_420_888
            val buffer = image.planes[0].buffer
            val data = ByteArray(buffer.remaining())
            buffer.get(data)
            
            val width = image.width
            val height = image.height

            // Construct planar source
            val source = PlanarYUVLuminanceSource(
                data,
                width,
                height,
                0,
                0,
                width,
                height,
                false
            )
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))

            try {
                val result = reader.decode(binaryBitmap)
                onQrDetected(result.text)
            } catch (e: Exception) {
                // Ignore, QR code not decoded in current frame
            } finally {
                image.close()
            }
        }
    }
}
