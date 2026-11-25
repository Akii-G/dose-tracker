package io.github.akiig.dose.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import io.github.akiig.dose.Aic
import io.github.akiig.dose.tr
import java.util.concurrent.Executors

/**
 * Points the camera at the box and reads its barcode: the AIC number in Code 39 / Code 32 or in
 * the DataMatrix. Everything happens on the phone; `onCode` gets the 9-digit AIC.
 */
@Composable
fun ScanScreen(onClose: () -> Unit, onCode: (String) -> Unit) {
    val context = LocalContext.current
    var allowed by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var refused by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        allowed = ok; refused = !ok
    }
    LaunchedEffect(Unit) { if (!allowed) ask.launch(Manifest.permission.CAMERA) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (allowed) CameraReader(onCode)
            // where to hold the barcode
            Box(Modifier.align(Alignment.Center).fillMaxWidth(0.82f).height(170.dp)
                .border(3.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(18.dp)))
            Column(Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (refused) tr("Dose needs the camera to read the box. You can allow it in Android's Settings → Apps → Dose.")
                     else tr("Point the camera at the barcode on the box (the one under \"A0…\", or the square code)."),
                    color = Color.White, textAlign = TextAlign.Center)
                TextButton(onClose) { Text(tr("Cancel"), color = Color.White) }
            }
        }
    }
}

@Composable
private fun CameraReader(onCode: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    var done by remember { mutableStateOf(false) }
    val reader = remember {
        MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.CODE_39, BarcodeFormat.DATA_MATRIX),
                DecodeHintType.TRY_HARDER to true))
        }
    }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }
    AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
        val view = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            var turn = false
            analysis.setAnalyzer(executor) { image ->
                val code = if (done) null else read(reader, image, turn)
                turn = !turn
                image.close()
                if (code != null) {
                    done = true
                    ContextCompat.getMainExecutor(context).execute { provider.unbindAll(); onCode(code) }
                }
            }
            provider.unbindAll()
            try { provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis) } catch (e: Exception) { }
        }, ContextCompat.getMainExecutor(ctx))
        view
    })
}

/** One frame: the luminance as it comes, or turned a quarter (a 1D barcode must lie across the rows). */
private fun read(reader: MultiFormatReader, image: ImageProxy, turned: Boolean): String? {
    val plane = image.planes[0]
    val w = image.width; val h = image.height
    val rowStride = plane.rowStride
    val buffer = plane.buffer.also { it.rewind() }
    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
    val lum = ByteArray(w * h)
    for (y in 0 until h) System.arraycopy(bytes, y * rowStride, lum, y * w, w)
    val (data, dw, dh) = if (!turned) Triple(lum, w, h) else {
        val r = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) r[x * h + (h - 1 - y)] = lum[y * w + x]
        Triple(r, h, w)
    }
    return try {
        val result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(PlanarYUVLuminanceSource(data, dw, dh, 0, 0, dw, dh, false))))
        Aic.fromBarcode(result.text)
    } catch (e: NotFoundException) {
        null
    } catch (e: Exception) {
        null
    } finally {
        reader.reset()
    }
}
