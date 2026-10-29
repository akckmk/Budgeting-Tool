package com.budgetingtool.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.budgetingtool.app.receipt.ReceiptInfo
import com.budgetingtool.app.receipt.ReceiptOcr
import com.budgetingtool.app.receipt.ReceiptParser
import kotlinx.coroutines.launch

/**
 * Full-screen receipt capture. The photo is taken inside the app, read on the phone, and
 * discarded; it is never written to storage or the gallery.
 */
@Composable
fun ReceiptScanner(onResult: (ReceiptInfo) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var hasCamera by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCamera = granted
        asked = true
    }
    LaunchedEffect(Unit) { if (!hasCamera) permission.launch(Manifest.permission.CAMERA) }

    fun process(bitmap: Bitmap?) {
        if (bitmap == null) {
            problem = "Couldn't open that photo."
            busy = false
            return
        }
        busy = true
        problem = null
        scope.launch {
            val info = try {
                ReceiptParser.parse(ReceiptOcr.read(context, bitmap))
            } catch (e: Exception) {
                null
            } finally {
                bitmap.recycle()
            }
            busy = false
            if (info == null || (info.totalCents == null && info.merchant == null)) {
                problem = "Couldn't read that receipt. Try again with the whole receipt in the frame, flat and well lit."
            } else {
                onResult(info)
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) process(ReceiptOcr.decode(context, uri))
    }

    BackHandler(onBack = onDismiss)
    run {
        // Drawn over the whole app (not in a popup window) so it can use the full edge-to-edge screen.
        Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures { } }) {
            if (hasCamera) {
                val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
                AndroidView({ previewView }, Modifier.fillMaxSize())
                DisposableEffect(lifecycleOwner) {
                    val providerFuture = ProcessCameraProvider.getInstance(context)
                    providerFuture.addListener({
                        runCatching {
                            val provider = providerFuture.get()
                            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                            provider.unbindAll()
                            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                        }.onFailure { problem = "Camera unavailable: ${it.message}" }
                    }, ContextCompat.getMainExecutor(context))
                    onDispose { runCatching { providerFuture.get().unbindAll() } }
                }
                // Framing guide
                Box(
                    Modifier.align(Alignment.Center).fillMaxWidth(0.82f).fillMaxHeight(0.6f)
                        .border(3.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(24.dp)),
                )
            } else {
                Column(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("📷", style = MaterialTheme.typography.displayMedium)
                    Text(
                        if (asked) "Camera access is off. You can still pick a photo of a receipt." else "Allow camera access to scan receipts.",
                        color = Color.White, textAlign = TextAlign.Center,
                    )
                    if (asked) TextButton(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Ask again") }
                }
            }

            IconButton(onClick = onDismiss, modifier = Modifier.statusBarsPadding().padding(8.dp)) {
                Icon(Icons.Default.Close, "Close", tint = Color.White)
            }
            Text(
                "Fit the whole receipt in the frame",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 18.dp),
            )

            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                problem?.let {
                    Text(it, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.background(Color(0xCCF03E5E), RoundedCornerShape(14.dp)).padding(12.dp))
                }
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(28.dp))
                        Text("  Reading receipt on your phone…", color = Color.White)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                            Icon(Icons.Default.PhotoLibrary, null, tint = Color.White)
                            Text("  Photos", color = Color.White)
                        }
                        if (hasCamera) {
                            Box(
                                Modifier.size(78.dp).border(5.dp, Color.White, CircleShape).padding(8.dp)
                                    .background(Color.White, CircleShape)
                                    .clickable {
                                        busy = true
                                        imageCapture.takePicture(
                                            ContextCompat.getMainExecutor(context),
                                            object : ImageCapture.OnImageCapturedCallback() {
                                                override fun onCaptureSuccess(image: ImageProxy) {
                                                    val bmp = runCatching { ReceiptOcr.rotate(image.toBitmap(), image.imageInfo.rotationDegrees) }.getOrNull()
                                                    image.close()
                                                    process(bmp)
                                                }
                                                override fun onError(e: ImageCaptureException) {
                                                    busy = false
                                                    problem = "Couldn't take the photo: ${e.message}"
                                                }
                                            },
                                        )
                                    },
                            )
                        }
                    }
                }
                Text("🔒 Read on this phone. The photo is never saved or uploaded.", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Button shown at the top of the Add purchase sheet. */
@Composable
fun ScanReceiptButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Text("📷  Scan a receipt")
    }
}
