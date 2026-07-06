package com.rain.sdk.utils

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import timber.log.Timber
import java.util.EnumMap

/**
 * Utility object for generating QR code Bitmaps.
 * Encapsulates the ZXing library usage within the SDK.
 */
object QRGenerator {

  /**
   * Generates an Android Bitmap representing a QR code for the given text.
   *
   * @param text The string to encode into the QR code.
   * @param width The width of the resulting Bitmap in pixels.
   * @param height The height of the resulting Bitmap in pixels.
   * @return A Bitmap containing the QR code.
   * @throws Exception if encoding fails (caller should wrap in appropriate error type).
   */
  fun generateQRCode(
    text: String,
    width: Int = 500,
    height: Int = 500
  ): Bitmap {
    val codeWriter = MultiFormatWriter()
    try {
      val hintMap: MutableMap<EncodeHintType, Any> = EnumMap(EncodeHintType::class.java)
      hintMap[EncodeHintType.MARGIN] = 1
      val bitMatrix = codeWriter.encode(text, BarcodeFormat.QR_CODE, width, height, hintMap)

      val pixels = IntArray(width * height)

      for (y in 0 until height) {
        val offset = y * width
        for (x in 0 until width) {
          pixels[offset + x] = if (bitMatrix[x, y]) Color.BLACK else Color.WHITE
        }
      }
      val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

      bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
      return bitmap
    } catch (e: Exception) {
      Timber.e(e, "QRGenerator generate failed!!")
      throw e
    }
  }
}
