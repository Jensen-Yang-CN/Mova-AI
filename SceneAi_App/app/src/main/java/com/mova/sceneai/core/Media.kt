package com.mova.sceneai.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File

/** 用户选中的图片/文件，统一带一个用于展示与上传的文件名。 */
data class PickedMedia(val bytes: ByteArray, val filename: String) {
    // ByteArray 需要手动实现 equals/hashCode，否则在 Compose 里做状态比较会出错
    override fun equals(other: Any?): Boolean =
        this === other || (other is PickedMedia && filename == other.filename && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + filename.hashCode()
}

/**
 * 拍照 / 相册 的统一入口。
 *
 * 两个刻意的工程决定：
 *  ① **用系统相机的全分辨率输出**（FileProvider + EXTRA_OUTPUT），而不是
 *     `extras.get("data")` 返回的缩略图 —— 后者只有一百多像素，识别食材必然失败。
 *  ② **上传前在端上压缩**，控制到长边 1280、JPEG 85。这样既省流量和等待时间，
 *     也避免服务端因大图超时。
 */
class PhotoCapture internal constructor(
    val capturePhoto: () -> Unit,
    val pickFromGallery: () -> Unit,
    val pickPdf: () -> Unit,
)

fun createTempImageUri(context: Context): Uri {
    val dir = File(context.cacheDir, "images").apply { mkdirs() }
    val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

@Composable
fun rememberPhotoCapture(
    onImage: (PickedMedia) -> Unit,
    onPdf: (PickedMedia) -> Unit = {},
): PhotoCapture {
    val context = androidx.compose.ui.platform.LocalContext.current
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var captureAfterGrant by remember { mutableStateOf(false) }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingUri
        pendingUri = null
        if (ok && uri != null) {
            readBytes(context, uri)?.let { raw ->
                onImage(PickedMedia(ImageCompressor.compress(raw), "capture.jpg"))
            }
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        readBytes(context, uri)?.let { raw ->
            onImage(PickedMedia(ImageCompressor.compress(raw), "picked.jpg"))
        }
    }

    val pickPdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        readBytes(context, uri)?.let { onPdf(PickedMedia(it, "document.pdf")) }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        captureAfterGrant = granted
    }

    fun launchCamera() {
        val uri = createTempImageUri(context)
        pendingUri = uri
        takePicture.launch(uri)
    }

    LaunchedEffect(captureAfterGrant) {
        if (captureAfterGrant) {
            captureAfterGrant = false
            launchCamera()
        }
    }

    return remember {
        PhotoCapture(
            capturePhoto = {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
                if (granted) launchCamera() else permission.launch(Manifest.permission.CAMERA)
            },
            pickFromGallery = { pickImage.launch("image/*") },
            pickPdf = { pickPdfLauncher.launch("application/pdf") },
        )
    }
}

private fun readBytes(context: Context, uri: Uri): ByteArray? =
    runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()

/**
 * 端侧图片压缩。用 inSampleSize 做整数倍降采样（内存友好，不会先解码全图再缩），
 * 再按质量二次压缩，直到低于目标体积。
 */
object ImageCompressor {

    private const val MAX_DIMENSION = 1280
    private const val TARGET_MAX_BYTES = 1_500_000

    fun compress(raw: ByteArray, maxDimension: Int = MAX_DIMENSION): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return raw

        var sample = 1
        while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) {
            sample *= 2
        }

        val decoded = runCatching {
            BitmapFactory.decodeByteArray(
                raw, 0, raw.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        }.getOrNull() ?: return raw

        var quality = 85
        var out = encode(decoded, quality)
        while (out.size > TARGET_MAX_BYTES && quality > 45) {
            quality -= 15
            out = encode(decoded, quality)
        }
        decoded.recycle()
        return out
    }

    private fun encode(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            stream.toByteArray()
        }
}
