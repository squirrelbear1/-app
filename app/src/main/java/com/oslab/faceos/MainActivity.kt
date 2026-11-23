package com.oslab.faceos

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.util.Size
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.oslab.faceos.camera.FrameAnalyzer
import com.oslab.faceos.camera.FrameSink
import com.oslab.faceos.camera.LocalFrameSink
import com.oslab.faceos.databinding.ActivityMainBinding
import com.oslab.faceos.debug.DebugHub
import com.oslab.faceos.infer.InferenceEngine
import com.oslab.faceos.ipc.InferenceClient
import com.oslab.faceos.ipc.InferenceResult
import com.oslab.faceos.ui.toAnalyzedFaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * FaceOS 主界面：相机采集 + UI + 双进程/单进程模式切换。
 *
 * 双进程模式（默认）：相机帧经 Binder 投递给 :infer 进程的推理服务，结果回调绘制；
 * 单进程模式：模型在主进程内直接推理（实验对照基线）。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "FaceOS"
        private const val UI_INTERVAL_MS = 200L
        private const val PREFS = "faceos"
        private const val KEY_MODE = "infer_mode"
        const val MODE_DUAL = "dual"
        const val MODE_SINGLE = "single"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService

    private var analyzer: FrameAnalyzer? = null
    private var client: InferenceClient? = null
    private var engine: InferenceEngine? = null
    private var localSink: LocalFrameSink? = null
    private var mode = MODE_DUAL
    private var cameraStarted = false
    private var lastUiUpdate = 0L
    private var lastResult: InferenceResult? = null

    private val statsHandler = Handler(Looper.getMainLooper())
    private val statsLogger = object : Runnable {
        override fun run() {
            logStats()
            statsHandler.postDelayed(this, 2000)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startPipeline() else binding.tvStatus.text = "未授予相机权限，无法运行。"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mode = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_MODE, MODE_DUAL) ?: MODE_DUAL
        binding.tvMode.text = if (mode == MODE_DUAL) "双进程" else "单进程"

        cameraExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "camera-analysis") }
        binding.previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        binding.overlayView.mirrored = true

        binding.btnRegister.setOnClickListener { onRegisterClicked() }
        binding.btnClear.setOnClickListener { onClearClicked() }
        binding.btnDebug.setOnClickListener { startActivity(Intent(this, DebugActivity::class.java)) }
        binding.btnMode.setOnClickListener { onSwitchModeClicked() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startPipeline()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // ---------------- 启动：双进程绑定 / 单进程加载 ----------------

    private fun startPipeline() {
        if (mode == MODE_DUAL) {
            binding.tvStatus.text = "正在绑定推理服务进程 :infer …"
            client = InferenceClient(
                context = this,
                onResult = { result -> onInferenceResult(result) },
                onConnectionChanged = { connected ->
                    runOnUiThread {
                        if (connected) {
                            binding.tvStatus.text = "已连接推理服务进程，启动相机…"
                            if (!cameraStarted) startCamera()
                        } else {
                            binding.tvStatus.text = "推理服务进程未连接（自动重连中…）"
                        }
                    }
                }
            ).also { it.bind() }
        } else {
            binding.tvStatus.text = "单进程模式：正在加载三个模型…"
            lifecycleScope.launch {
                val e = withContext(Dispatchers.IO) { InferenceEngine(applicationContext) }
                engine = e
                startCamera()
            }
        }
        installDebugHooks()
    }

    private fun startCamera() {
        if (cameraStarted) return
        cameraStarted = true

        val sink: FrameSink = if (mode == MODE_DUAL) {
            client!!
        } else {
            LocalFrameSink(engine!!) { result -> onInferenceResult(result) }.also { localSink = it }
        }
        val frameAnalyzer = FrameAnalyzer(sink)
        analyzer = frameAnalyzer

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()

                val preview = Preview.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .build()
                preview.setSurfaceProvider(binding.previewView.surfaceProvider)

                // 480x360 RGBA：691,200 字节 < Binder 单次事务 1MB 上限，可安全跨进程传输
                val resolution = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(480, 360),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolution)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(cameraExecutor, frameAnalyzer)

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    analysis
                )
                Log.i(TAG, "camera bound, mode=$mode")
            } catch (t: Throwable) {
                Log.e(TAG, "camera bind failed", t)
                binding.tvStatus.text = "相机启动失败：${t.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ---------------- 结果展示 ----------------

    private fun onInferenceResult(result: InferenceResult) {
        lastResult = result
        val now = System.currentTimeMillis()
        if (now - lastUiUpdate < UI_INTERVAL_MS) return
        lastUiUpdate = now

        runOnUiThread {
            binding.overlayView.update(result.toAnalyzedFaces(), result.width, result.height)
            val top = (0 until result.faceCount)
                .maxByOrNull { i -> (result.boxes[i * 4 + 2] - result.boxes[i * 4]) * (result.boxes[i * 4 + 3] - result.boxes[i * 4 + 1]) }
            val galleryCount = if (mode == MODE_DUAL) (client?.galleryCount() ?: 0) else (engine?.gallery?.size() ?: 0)
            binding.tvStatus.text = buildString {
                appendLine("${if (mode == MODE_DUAL) "双进程 :infer" else "单进程"} · 检测 ${result.detMs}ms · 活体 ${result.liveMs}ms · 识别 ${result.recMs}ms · 总 ${result.totalMs}ms")
                appendLine("FPS ${"%.1f".format(analyzer?.fps ?: 0f)} · 人脸 ${result.faceCount} · 库 ${galleryCount} 人 · 推理PID ${result.servicePid} · 线程 ${result.threadName}")
                if (top != null) {
                    val name = result.names.getOrNull(top)?.takeIf { it.isNotEmpty() } ?: "未注册"
                    val sim = result.similarities.getOrElse(top) { 0f }
                    val live = if (result.hasLiveness.getOrElse(top) { false }) {
                        "${result.liveLabels.getOrNull(top) ?: "?"} ${"%.2f".format(result.liveScores.getOrElse(top) { 0f })}"
                    } else "未测"
                    append("识别：$name (${"%.3f".format(sim)})   活体：$live   本进程PID ${Process.myPid()}")
                } else {
                    append("未检测到人脸")
                }
            }
        }
    }

    // ---------------- 注册 / 删除 / 模式 ----------------

    private fun onRegisterClicked() {
        val result = lastResult
        val topLive = result?.let { r ->
            val top = (0 until r.faceCount).maxByOrNull { i ->
                (r.boxes[i * 4 + 2] - r.boxes[i * 4]) * (r.boxes[i * 4 + 3] - r.boxes[i * 4 + 1])
            }
            if (top != null && r.hasLiveness.getOrElse(top) { false }) r.liveScores[top] >= 0.55f else null
        }
        if (result == null || result.faceCount == 0) {
            Toast.makeText(this, "当前没有检测到人脸", Toast.LENGTH_SHORT).show()
            return
        }
        if (topLive == false) {
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
                    return@setPositiveButton
                }
                val ok = if (mode == MODE_DUAL) {
                    client?.registerFace(name) { success ->
                        runOnUiThread {
                            Toast.makeText(
                                this,
                                if (success) "已注册：$name" else "注册失败：服务未就绪或活体未通过",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    true // 异步结果稍后提示
                } else {
                    engine?.register(name) ?: false
                }
                if (mode == MODE_SINGLE) {
                    Toast.makeText(
                        this,
                        if (ok) "已注册：$name" else "注册失败：请先让活体通过",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun onClearClicked() {
        val count = if (mode == MODE_DUAL) (client?.galleryCount() ?: 0) else (engine?.gallery?.size() ?: 0)
        if (count == 0) {
            Toast.makeText(this, "人脸库为空", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("删除模板")
            .setMessage("将删除全部 $count 个人脸模板，确定？")
            .setPositiveButton("全部删除") { _, _ ->
                if (mode == MODE_DUAL) {
                    client?.clearFaces { success ->
                        runOnUiThread {
                            Toast.makeText(
                                this, if (success) "已清空人脸库" else "清空失败", Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                } else {
                    engine?.clearGallery()
                    Toast.makeText(this, "已清空人脸库", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun onSwitchModeClicked() {
        val next = if (mode == MODE_DUAL) MODE_SINGLE else MODE_DUAL
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_MODE, next).apply()
        Toast.makeText(
            this,
            "切换到${if (next == MODE_DUAL) "双进程" else "单进程"}模式，正在重启界面…",
            Toast.LENGTH_SHORT
        ).show()
        recreate()
    }

    // ---------------- 调试页数据 ----------------

    private fun installDebugHooks() {
        DebugHub.provider = {
            val c = client
            DebugHub.Snapshot(
                mode = if (mode == MODE_DUAL) "双进程 (:infer)" else "单进程 (主进程内)",
                mainPid = Process.myPid(),
                cameraThread = "camera-analysis",
                connected = if (mode == MODE_DUAL) (c?.connected?.get() ?: false) else true,
                submitted = c?.submitted?.get() ?: 0,
                received = c?.received?.get() ?: 0,
                reconnects = c?.reconnects?.get() ?: 0,
                avgIpcMs = c?.avgIpcMs ?: 0,
                fps = analyzer?.fps ?: 0f,
                galleryCount = if (mode == MODE_DUAL) (c?.galleryCount() ?: 0) else (engine?.gallery?.size() ?: 0),
                service = c?.latestStats
            )
        }
        DebugHub.actions = DebugHub.Actions(
            setWorkers = { n -> client?.setWorkers(n) },
            setPermits = { n -> client?.setPermits(n) },
            resetStats = { client?.resetStats() },
            setVariant = { v ->
                if (mode == MODE_DUAL) client?.setVariant(v) else engine?.reload(v)
            },
            setDetSize = { n ->
                if (mode == MODE_DUAL) client?.setDetSize(n) else engine?.reloadDetSize(n)
            }
        )
    }

    override fun onResume() {
        super.onResume()
        statsHandler.removeCallbacks(statsLogger)
        statsHandler.post(statsLogger)
    }

    override fun onPause() {
        super.onPause()
        statsHandler.removeCallbacks(statsLogger)
    }

    /** 每 2s 输出一行结构化统计，供 adb logcat 抓取做实验数据分析 */
    private fun logStats() {
        val svc = client?.latestStats
        val r = lastResult
        Log.i(
            "FaceOS-STATS",
            "mode=" + (if (mode == MODE_DUAL) "dual" else "single") +
                " workers=" + (svc?.workers ?: -1) +
                " variant=" + (svc?.variant ?: (engine?.variant ?: "?")) +
                " permits=" + (svc?.permitsTotal ?: -1) +
                " detsize=" + (svc?.detInputSize ?: (engine?.detInputSize ?: -1)) +
                " queue=" + (svc?.queueLength ?: -1) + "/" + (svc?.queueCapacity ?: -1) +
                " proc=" + (svc?.processed ?: -1) +
                " drop=" + (svc?.dropped ?: -1) +
                " det=" + (svc?.avgDetMs ?: -1) +
                " live=" + (svc?.avgLiveMs ?: -1) +
                " rec=" + (svc?.avgRecMs ?: -1) +
                " total=" + (svc?.avgTotalMs ?: -1) +
                " fps=" + "%.1f".format(analyzer?.fps ?: 0f) +
                " sub=" + (client?.submitted?.get() ?: -1) +
                " recv=" + (client?.received?.get() ?: -1) +
                " ipc=" + (client?.avgIpcMs ?: -1) +
                " faces=" + (r?.faceCount ?: -1) +
                " ldet=" + (r?.detMs ?: -1) +
                " llive=" + (r?.liveMs ?: -1) +
                " lrec=" + (r?.recMs ?: -1) +
                " ltotal=" + (r?.totalMs ?: -1) +
                " lproc=" + (localSink?.processedCount ?: -1) +
                " ldrop=" + (localSink?.droppedCount ?: -1) +
                " gallery=" + (if (mode == MODE_DUAL) (client?.galleryCount() ?: 0) else (engine?.gallery?.size() ?: 0))
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        DebugHub.provider = null
        DebugHub.actions = null
        client?.unbind()
        localSink?.shutdown()
        engine?.close()
        cameraExecutor.shutdown()
    }
}
