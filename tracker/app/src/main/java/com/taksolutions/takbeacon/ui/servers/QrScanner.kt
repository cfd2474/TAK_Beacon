package com.taksolutions.takbeacon.ui.servers

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.ChecksumException
import com.google.zxing.DecodeHintType
import com.google.zxing.FormatException
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Full-screen QR scanner. Calls [onResult] once with the first QR code's text. */
@Composable
fun QrScannerDialog(onResult: (String) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            CameraPreview(onResult)
            Box(
                Modifier.align(Alignment.Center).size(260.dp)
                    .border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(24.dp))
            )
            Column(Modifier.align(Alignment.BottomCenter).systemBarsPadding().padding(32.dp)) {
                Text(
                    "Point at the enrollment QR code from your TAK server",
                    color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge,
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.systemBarsPadding().padding(8.dp)) {
                Icon(Icons.Filled.Close, "Close", tint = Color.White)
            }
        }
    }
}

@Composable
private fun CameraPreview(onResult: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val done = remember { AtomicBoolean(false) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val reader = remember { QRCodeReader() }
    val previewView = remember { PreviewView(context) }
    DisposableEffect(Unit) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { proxy ->
                try {
                    if (!done.get()) decode(reader, proxy)?.let { text ->
                        if (done.compareAndSet(false, true)) ContextCompat.getMainExecutor(context).execute { onResult(text) }
                    }
                } finally {
                    proxy.close()
                }
            }
            provider.unbindAll()
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { providerFuture.get().unbindAll() }
            executor.shutdown()
        }
    }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

/** Decodes a QR code from the luminance (Y) plane of a camera frame. */
private fun decode(reader: QRCodeReader, proxy: ImageProxy): String? {
    val plane = proxy.planes[0]
    val w = proxy.width
    val h = proxy.height
    val rowStride = plane.rowStride
    val buf = plane.buffer.duplicate().apply { rewind() }
    val y = ByteArray(w * h)
    if (rowStride == w) {
        buf.get(y, 0, minOf(y.size, buf.remaining()))
    } else {
        for (row in 0 until h) {
            buf.position(row * rowStride)
            buf.get(y, row * w, w)
        }
    }
    val source = PlanarYUVLuminanceSource(y, w, h, 0, 0, w, h, false)
    val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
    for (bitmap in listOf(BinaryBitmap(HybridBinarizer(source)), BinaryBitmap(HybridBinarizer(source.invert())))) {
        try {
            return reader.decode(bitmap, hints).text
        } catch (_: NotFoundException) {
        } catch (_: ChecksumException) {
        } catch (_: FormatException) {
        } finally {
            reader.reset()
        }
    }
    return null
}
