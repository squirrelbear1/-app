package com.oslab.faceos

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.widget.Toast
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
import com.oslab.faceos.ml.EdgeFaceRecognizer
import com.oslab.faceos.ml.FaceGallery
import com.oslab.faceos.ml.MiniFasNetDetector
import com.oslab.faceos.ml.ScrfdDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * FaceOS 主界面：
 *   前置摄像头实时预览 + 人脸框/关键点 + 活体结果 + 身份识别 + 注册/删除模板
 *
 * 阶段 3/4 完成后，本 App 已具备三项 AI 功能的完整离线流程：
 *   采集 -> 检测 -> 对齐 -> 活体 -> 识别 -> 展示
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "FaceOS"
        private const val UI_UPDATE_INTERVAL_MS = 200L
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var gallery: FaceGallery

    private var detector: ScrfdDetector? = null
    private var recognizer: EdgeFaceRecognizer? = null
    private var livenessDetector: MiniFasNetDetector? = null

    private var lastUiUpdate = 0L
    private var lastEmbedding: FloatArray? = null
    private var lastLive: Boolean? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else binding.tvStatus.text = "未授予相机权限，无法运行。"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        gallery = FaceGallery(this)
        cameraExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "camera-analysis") }
        binding.previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        binding.overlayView.mirrored = true

        binding.btnRegister.setOnClickListener { onRegisterClicked() }
        binding.btnClear.setOnClickListener { onClearClicked() }
        updateGalleryInfo()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        binding.tvStatus.text = "正在加载三个模型…"
        lifecycleScope.launch {
            val ctx = applicationContext
            val t0 = System.currentTimeMillis()
            val det = withContext(Dispatchers.IO) { ScrfdDetector(ctx) }
            val rec = withContext(Dispatchers.IO) { EdgeFaceRecognizer(ctx) }
            val live = withContext(Dispatchers.IO) { MiniFasNetDetector(ctx) }
            val loadMs = System.currentTimeMillis() - t0

            detector = det
            recognizer = rec
            livenessDetector = live
            Log.i(TAG, "models loaded in ${loadMs}ms")
            bindCamera(det, rec, live, loadMs)
        }
    }

    private fun bindCamera(
        det: ScrfdDetector,
        rec: EdgeFaceRecognizer,
        live: MiniFasNetDetector,
        loadMs: Long
    ) {
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

                val analyzer = FaceAnalyzer(det, rec, live, gallery) { result -> onFrame(result) }
                analysis.setAnalyzer(cameraExecutor, analyzer)

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    analysis
                )
                binding.tvStatus.text = "三模型加载完成（${loadMs} ms），等待相机帧…"
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

        val largestLive = result.faces.maxByOrNull { it.detection.width * it.detection.height }?.liveness
        lastEmbedding = result.alignedEmbedding?.copyOf()
        lastLive = largestLive?.live

        runOnUiThread {
            binding.overlayView.update(result.faces, result.imageWidth, result.imageHeight)
            val top = result.faces.maxByOrNull { it.detection.width * it.detection.height }
            binding.tvStatus.text = buildString {
                appendLine("检测 ${result.detMs}ms · 识别 ${result.recMs}ms · 活体 ${result.liveMs}ms · FPS ${"%.1f".format(result.fps)}")
                appendLine("人脸 ${result.faces.size} 个 · 库中 ${gallery.size()} 人 · 线程 ${result.threadName} · PID ${android.os.Process.myPid()}")
                if (top != null) {
                    val id = if (top.identity != null) "${top.identity} (${"%.3f".format(top.similarity)})" else "未注册"
                    val lv = top.liveness?.let { "${it.label} ${"%.3f".format(it.score)}" } ?: "未测"
                    append("识别：$id   活体：$lv")
                } else {
                    append("未检测到人脸")
                }
            }
        }
    }

    private fun onRegisterClicked() {
        val emb = lastEmbedding
        if (emb == null) {
            Toast.makeText(this, "当前没有可用人脸，请正对前摄再试", Toast.LENGTH_SHORT).show()
            return
        }
        if (lastLive == false) {
            Toast.makeText(this, "活体未通过，禁止注册（防照片/屏幕攻击）", Toast.LENGTH_LONG).show()
            return
        }

        val input = EditText(this).apply {
            hint = "输入姓名"
            setPadding(56, 32, 56, 16)
        }
        AlertDialog.Builder(this)
            .setTitle("注册人脸模板")
            .setView(input)
            .setPositiveButton("注册") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "姓名不能为空", Toast.LENGTH_SHORT).show()
                } else {
                    gallery.register(name, emb)
                    updateGalleryInfo()
                    Toast.makeText(this, "已注册：$name", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun onClearClicked() {
        if (gallery.size() == 0) {
            Toast.makeText(this, "人脸库为空", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("删除模板")
            .setMessage("将删除全部 ${gallery.size()} 个人脸模板，确定？")
            .setPositiveButton("全部删除") { _, _ ->
                gallery.clear()
                updateGalleryInfo()
                Toast.makeText(this, "已清空人脸库", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateGalleryInfo() {
        binding.tvGallery.text = "人脸库：${gallery.size()} 人"
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        detector?.close()
        recognizer?.close()
        livenessDetector?.close()
        detector = null
        recognizer = null
        livenessDetector = null
    }
}
