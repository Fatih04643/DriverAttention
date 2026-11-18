package com.example.driverattention.camera

import android.util.Log
import android.util.Size
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executor

private const val TAG = "CameraPreview"

/**
 * Ön kamera önizlemesi + kare analizi.
 *
 * İki CameraX use-case'i aynı anda ön kameraya bağlanır:
 *  - Preview: görüntüyü ekrandaki PreviewView'a çizer
 *  - ImageAnalysis: kareleri [analyzer]'a verir (yüz landmark tespiti için)
 *
 * İkisi de 4:3 en-boy oranında istenir; böylece analiz edilen kare ile ekranda
 * görünen kare aynı görüş alanına sahip olur ve landmark overlay doğru hizalanır.
 * Kamera ekranın yaşam döngüsüne bağlıdır: uygulama arka plana geçince kapanır.
 */
@Composable
fun CameraPreview(
    analysisExecutor: Executor,
    analyzer: ImageAnalysis.Analyzer,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember {
        PreviewView(context).apply {
            // Görüntü ekranı dolduracak şekilde ortalanıp kırpılır (overlay de aynı kuralı kullanır)
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(lifecycleOwner, analyzer) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var cameraProvider: ProcessCameraProvider? = null

        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider

            val aspect43 = AspectRatioStrategy(
                AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO
            )

            val preview = Preview.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder().setAspectRatioStrategy(aspect43).build()
                )
                .build()
                .also { it.surfaceProvider = previewView.surfaceProvider }

            // Analiz için ~640x480 yeterli: daha büyük çözünürlük FPS'i düşürür, doğruluğu pek artırmaz
            val analysisResolution = ResolutionSelector.Builder()
                .setAspectRatioStrategy(aspect43)
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(640, 480),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()

            val imageAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(analysisResolution)
                // Analiz yetişemezse eski kareleri at, sadece en yenisini işle (gecikme birikmesin)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(analysisExecutor, analyzer) }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    imageAnalysis,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Kamera başlatılamadı", e)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            cameraProvider?.unbindAll()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}
