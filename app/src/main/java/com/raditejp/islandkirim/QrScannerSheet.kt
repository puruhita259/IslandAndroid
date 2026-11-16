package com.raditejp.islandkirim

import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

@Composable
fun QrScannerDialog(
    onScannedUrl: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var manualUrl by remember { mutableStateOf("") }
    var handled by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xD9061B24))
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xF00B2C38))
                .border(1.dp, Color(0x66F7FFFD), RoundedCornerShape(24.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Pindai QR Dynamic Island", color = Color(0xFFF7FFFD), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                TextButton(onClick = onDismiss) {
                    Text("Tutup", color = Color(0xFFA8DADC))
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .border(2.dp, Color(0xFF2A9D8F), RoundedCornerShape(18.dp))
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        val executor = Executors.newSingleThreadExecutor()
                        val scanner = BarcodeScanning.getClient()

                        cameraProviderFuture.addListener({
                            try {
                                val cameraProvider = cameraProviderFuture.get()
                                val preview = Preview.Builder().build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }
                                @Suppress("DEPRECATION")
                                val analysis = ImageAnalysis.Builder()
                                    .setTargetResolution(Size(1280, 720))
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()

                                analysis.setAnalyzer(executor) { imageProxy ->
                                    val mediaImg = imageProxy.image
                                    if (mediaImg != null && !handled) {
                                        val img = InputImage.fromMediaImage(mediaImg, imageProxy.imageInfo.rotationDegrees)
                                        scanner.process(img)
                                            .addOnSuccessListener { barcodes ->
                                                val match = barcodes.firstOrNull {
                                                    val raw = it.rawValue ?: ""
                                                    raw.startsWith("http://") && raw.contains("/k/")
                                                }
                                                val rawVal = match?.rawValue
                                                if (rawVal != null && !handled) {
                                                    handled = true
                                                    onScannedUrl(rawVal)
                                                }
                                            }
                                            .addOnCompleteListener { imageProxy.close() }
                                    } else {
                                        imageProxy.close()
                                    }
                                }
                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                            } catch (_: Throwable) {}
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    }
                )
            }

            OutlinedTextField(
                value = manualUrl,
                onValueChange = { manualUrl = it },
                placeholder = { Text("Atau tempel tautan http://...", color = Color(0x99A8DADC), fontSize = 13.sp) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF2A9D8F),
                    unfocusedBorderColor = Color(0x55A8DADC)
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = { if (manualUrl.isNotBlank()) onScannedUrl(manualUrl.trim()) },
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A9D8F), contentColor = Color(0xFF042228))
            ) {
                Text("Hubungkan", fontWeight = FontWeight.Bold)
            }
        }
    }
}
