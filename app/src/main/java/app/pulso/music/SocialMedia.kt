package app.pulso.music

import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

internal object SocialMedia {
    fun invitation(address: String) = "pulso://connect/${SocialProtocol.address(address)}"
    fun qr(address: String): Bitmap {
        val matrix = QRCodeWriter().encode(invitation(address), BarcodeFormat.QR_CODE, 768, 768, mapOf(EncodeHintType.MARGIN to 4))
        val pixels = IntArray(matrix.width * matrix.height) { i -> if (matrix[i % matrix.width, i / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
        return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    }
    private fun image(context: Context, uri: Uri, maximum: Int): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        require(info.size.width > 0 && info.size.height > 0)
        val scale = (maximum.toFloat() / maxOf(info.size.width, info.size.height)).coerceAtMost(1f)
        decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    fun readQr(context: Context, uri: Uri): String {
        val bitmap = image(context, uri, 1600)
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
            val result = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
            return SocialProtocol.address(result.text)
        } finally { bitmap.recycle() }
    }
    fun photo(context: Context, uri: Uri): String {
        val input = image(context, uri, 512)
        val side = minOf(input.width, input.height)
        val square = Bitmap.createBitmap(input, (input.width-side)/2, (input.height-side)/2, side, side)
        val scaled = Bitmap.createScaledBitmap(square, 192, 192, true)
        try {
            for (quality in listOf(85, 65, 40, 20)) {
                val stream = ByteArrayOutputStream(); scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                val bytes = stream.toByteArray()
                if (bytes.size <= SocialAvatarTransfer.MAX_BYTES) return Base64.getEncoder().encodeToString(bytes)
            }
            error("No se pudo preparar esta foto. Elige otra imagen.")
        } finally { if (scaled !== square) scaled.recycle(); if (square !== input) square.recycle(); input.recycle() }
    }
    fun decodePhoto(photo: String): Bitmap? {
        val bytes = SocialAvatarTransfer.decode(photo) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..256 || bounds.outHeight !in 1..256) return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
    fun shareQr(context: Context, bitmap: Bitmap) {
        val directory = File(context.cacheDir, "connect-shares").apply { mkdirs() }
        val file = File(directory, "Pulso-Connect.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.playlistfiles", file)
        val intent = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, "Añádeme en PULSO Connect")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("PULSO Connect", uri) }
        context.startActivity(Intent.createChooser(intent, "Compartir mi QR"))
    }
}
