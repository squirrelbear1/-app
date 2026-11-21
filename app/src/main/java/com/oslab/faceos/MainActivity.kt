package com.oslab.faceos

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.oslab.faceos.camera.FaceAnalyzer
import com.oslab.faceos.databinding.ActivityMainBinding
import com.oslab.faceos.ml.ScrfdDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 阶段 2：前置摄像头实时人脸检测（CameraX + SCRFD-500M-KPS + ONNX Runtime）。
 *
 * 数据流：
 *   CameraX 前摄 -> ImageAnalysis(RGBA_8888) -> 旋转为正向 -> SCRFD 推理
 *   -> 解码/NMS -> FaceOverlayView 叠加绘制 + 状态栏显示延迟/FPS
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "FaceOS"
        private const val UI_UPDATE_INTERVAL_MS = 200L
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var detector: ScrfdDetector? = null
    private var lastUiUpdate = 0L

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                binding.tvStatus.text = "未授予相机权限，无法进行人脸检测。\n请在系统设置中开启相机权限后重试。"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cameraExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "camera-analysis") }
        binding.previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        binding.overlayView.mirrored = true // 前摄预览是镜像的

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        binding.tvStatus.text = "正在加载 SCRFD-500M-KPS 模型…"
        lifecycleScope.launch {
            val det = withContext(Dispatchers.IO) { ScrfdDetector(applicationContext) }
            detector = det
            bindCamera(det)
        }
    }

    private fun bindCamera(det: ScrfdDetector) {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()

                val preview = Preview.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .build()
                preview.setSurfaceProvider(binding.previewView.surfaceProvider)

                val analysis = ImageAnalysis.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()

                val analyzer = FaceAnalyzer(det) { result -> onFrame(result) }
                analysis.setAnalyzer(cameraExecutor, analyzer)

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    analysis
                )
                Log.i(TAG, "camera bound, modelLoad=${det.modelLoadTimeMs}ms")
                binding.tvStatus.text =
                    "SCRFD 模型加载完成（${det.modelLoadTimeMs} ms）\n等待相机帧…"
            } catch (t: Throwable) {
                Log.e(TAG, "camera bind failed", t)
                binding.tvStatus.text = "相机启动失败：${t.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onFrame(result: FaceAnalyzer.FrameResult) {
        val now = System.currentTimeMillis()
        if (now - lastUiUpdate < UI_UPDATE_INTERVAL_MS) return
        lastUiUpdate = now

        runOnUiThread {
            binding.overlayView.update(result.detections, result.imageWidth, result.imageHeight)
            val top = result.detections.maxByOrNull { it.score }
            binding.tvStatus.text = buildString {
                appendLine("SCRFD-500M-KPS · ONNX Runtime · CPU")
                appendLine(
                    "推理 ${result.inferenceMs} ms   FPS ${"%.1f".format(result.fps)}   " +
                        "人脸 ${result.detections.size}"
                )
                if (top != null) {
                    appendLine(
                        "最高置信度 ${"%.3f".format(top.score)}   关键点 ${top.keypoints.size} 个"
                    )
                }
                append("线程 ${result.threadName}    PID ${android.os.Process.myPid()}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        detector?.close()
        detector = null
    }
}
