package app.couchphone

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** QR codes as small SVG pictures, for the TV's "Connect a remote" screen (ZXing, Apache-2.0). */
object Qr {
    fun svg(text: String): String {
        val hints = mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val path = StringBuilder()
        for (y in 0 until m.height) {
            var x = 0
            while (x < m.width) {
                if (!m.get(x, y)) { x++; continue }
                val start = x
                while (x < m.width && m.get(x, y)) x++
                path.append("M").append(start).append(' ').append(y).append("h").append(x - start).append("v1h-").append(x - start).append("z")
            }
        }
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ${m.width} ${m.height}\" shape-rendering=\"crispEdges\">" +
            "<rect width=\"${m.width}\" height=\"${m.height}\" fill=\"#fff\"/><path d=\"$path\" fill=\"#000\"/></svg>"
    }
}
