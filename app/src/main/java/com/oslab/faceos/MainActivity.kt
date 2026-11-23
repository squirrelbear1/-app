package com.oslab.faceos

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.oslab.faceos.camera.FrameAnalyzer
import com.oslab.faceos.camera.FrameSink
import com.oslab.faceos.camera.LocalFrameSink
import com.oslab.faceos.databinding.ActivityDebugBinding
import com.oslab.faceos.databinding.ActivityMainBinding
import com.oslab.faceos.debug.DebugHub
import com.oslab.faceos.debug.DebugRenderer
import com.oslab.faceos.debug.ResourceSampler
import com.oslab.faceos.infer.InferenceEngine
import com.oslab.faceos.ipc.InferenceClient
import com.oslab.faceos.ipc.InferenceResult
import com.oslab.faceos.ml.Config
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
    private var pipelineStarted = false
    private var lastUiUpdate = 0L
    private var lastResult: InferenceResult? = null
    private val resources by lazy { ResourceSampler(applicationContext) }
    private var debugView: View? = null
    private var debugBinding: ActivityDebugBinding? = null
    private var debugBackCallback: OnBackPressedCallback? = null
    private val debugHandler = Handler(Looper.getMainLooper())
    private val debugRefresh = object : Runnable {
        override fun run() {
            debugBinding?.tvDebug?.text = DebugRenderer.render(DebugHub.provider?.invoke())
            debugHandler.postDelayed(this, 500)
        }
    }

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

        // 状态面板是覆盖层：按返回键先关闭面板，而不是退出 App
        val backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                hideDebugPanel()
            }
        }
        debugBackCallback = backCallback
        onBackPressedDispatcher.addCallback(this, backCallback)

        mode = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_MODE, MODE_DUAL) ?: MODE_DUAL
        binding.tvMode.text = if (mode == MODE_DUAL) "双进程" else "单进程"

        cameraExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "camera-analysis") }
        binding.previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        binding.overlayView.mirrored = true

        binding.btnRegister.setOnClickListener { onRegisterClicked() }
        binding.btnClear.setOnClickListener { onClearClicked() }
        binding.btnDebug.setOnClickListener { showDebugPanel() }
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
        if (pipelineStarted) return
        pipelineStarted = true
        if (mode == MODE_DUAL) {
            binding.tvStatus.text = "正在绑定推理服务进程 :infer …（相机照常预览）"
            client = InferenceClient(
                context = this,
                onResult = { result -> onInferenceResult(result) },
                onConnectionChanged = { connected ->
                    runOnUiThread {
                        if (connected) {
                            binding.tvStatus.text = "已连接推理服务进程，正在推理…"
                        } else {
                            binding.tvStatus.text = "推理服务进程未连接（自动重连中…）"
                        }
                    }
                },
                onTimeout = {
                    runOnUiThread {
                        binding.tvStatus.text = "推理超时：超过 3 秒没有结果，请检查 :infer 进程"
                    }
                }
            ).also { it.bind() }
            // 即使服务未连上，也先启动相机，避免一直黑屏、看不到失败原因
            startCamera()
        } else {
            binding.tvStatus.text = "单进程模式：正在加载三个模型…"
            lifecycleScope.launch {
                try {
                    val e = withContext(Dispatchers.IO) { InferenceEngine(applicationContext) }
                    engine = e
                    startCamera()
                } catch (t: Throwable) {
                    Log.e(TAG, "模型加载失败", t)
                    binding.tvStatus.text = "模型加载失败：${t.javaClass.simpleName}: ${t.message}"
                }
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
                // 同时锁定 4:3，与 Preview 的比例一致，避免换机后叠加层错位
                val resolution = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
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
        val recvTsNs = SystemClock.elapsedRealtimeNanos()
        // 逐帧原始数据：采集脚本用 `adb logcat -s FaceOS-FRAME` 保存，供离线计算 P50/P95
        Log.i(
            "FaceOS-FRAME",
            "mode=${if (mode == MODE_DUAL) "dual" else "single"}" +
                " seq=${result.seq}" +
                " cap=${result.captureTsNs}" +
                " sub=${result.submitTsNs}" +
                " srecv=${result.serviceRecvTsNs}" +
                " istart=${result.inferStartTsNs}" +
                " iend=${result.inferEndTsNs}" +
                " recv=$recvTsNs" +
                " det=${result.detMs}" +
                " live=${result.liveMs}" +
                " rec=${result.recMs}" +
                " total=${result.totalMs}" +
                " faces=${result.faceCount}" +
                " spid=${result.servicePid}"
        )

        val now = SystemClock.elapsedRealtime()
        if (now - lastUiUpdate < UI_INTERVAL_MS) return
        lastUiUpdate = now

        runOnUiThread {
            binding.overlayView.update(result.toAnalyzedFaces(), result.width, result.height)
            Log.i("FaceOS-DRAW", "seq=${result.seq} ts=${SystemClock.elapsedRealtimeNanos()}")
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

    private fun galleryNames(): List<String> =
        if (mode == MODE_DUAL) client?.galleryNames() ?: emptyList()
        else engine?.gallery?.names() ?: emptyList()

    private fun onRegisterClicked() {
        val result = lastResult
        if (result == null || result.faceCount == 0) {
            Toast.makeText(this, "当前没有检测到人脸", Toast.LENGTH_SHORT).show()
            return
        }
        val top = (0 until result.faceCount).maxByOrNull { i ->
            (result.boxes[i * 4 + 2] - result.boxes[i * 4]) * (result.boxes[i * 4 + 3] - result.boxes[i * 4 + 1])
        }
        if (top == null) {
            Toast.makeText(this, "当前没有检测到人脸", Toast.LENGTH_SHORT).show()
            return
        }
        val hasLive = result.hasLiveness.getOrElse(top) { false }
        val liveScore = result.liveScores.getOrElse(top) { -1f }
        if (!hasLive || liveScore < Config.LIVENESS_THRESHOLD) {
            Toast.makeText(this, "请正对镜头，等待活体检测通过后再注册", Toast.LENGTH_LONG).show()
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
                if (name in galleryNames()) {
                    AlertDialog.Builder(this)
                        .setTitle("同名模板已存在")
                        .setMessage("继续将覆盖「$name」的旧模板，是否继续？")
                        .setPositiveButton("覆盖") { _, _ -> doRegister(name) }
                        .setNegativeButton("取消", null)
                        .show()
                } else {
                    doRegister(name)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doRegister(name: String) {
        if (mode == MODE_DUAL) {
            val c = client
            if (c == null) {
                Toast.makeText(this, "推理服务未连接，请稍后再试", Toast.LENGTH_SHORT).show()
                return
            }
            c.registerFace(name) { success ->
                Log.i("FaceOS-UI", "register name=$name ok=$success")
                runOnUiThread {
                    Toast.makeText(
                        this,
                        if (success) "已注册：$name" else "注册失败：服务未就绪或活体未通过",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } else {
            val ok = engine?.register(name) ?: false
            Toast.makeText(
                this,
                if (ok) "已注册：$name" else "注册失败：请先让活体通过",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun onClearClicked() {
        val names = galleryNames()
        if (names.isEmpty()) {
            Toast.makeText(this, "人脸库为空", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("删除模板（共 ${names.size} 人）")
            .setItems(names.toTypedArray()) { _, which -> confirmRemove(names[which]) }
            .setNeutralButton("全部删除") { _, _ -> confirmClear(names.size) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmRemove(name: String) {
        AlertDialog.Builder(this)
            .setTitle("删除模板")
            .setMessage("确定删除「$name」的人脸模板？")
            .setPositiveButton("删除") { _, _ -> doRemove(name) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doRemove(name: String) {
        if (mode == MODE_DUAL) {
            val c = client
            if (c == null) {
                Toast.makeText(this, "推理服务未连接", Toast.LENGTH_SHORT).show()
                return
            }
            c.removeFace(name) { success ->
                runOnUiThread {
                    Toast.makeText(
                        this, if (success) "已删除：$name" else "删除失败", Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } else {
            val ok = engine?.remove(name) ?: false
            Toast.makeText(this, if (ok) "已删除：$name" else "删除失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmClear(count: Int) {
        AlertDialog.Builder(this)
            .setTitle("删除全部模板")
            .setMessage("将删除全部 $count 个人脸模板，确定？")
            .setPositiveButton("全部删除") { _, _ ->
                if (mode == MODE_DUAL) {
                    val c = client
                    if (c == null) {
                        Toast.makeText(this, "推理服务未连接", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    c.clearFaces { success ->
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

    // ---------------- 调试面板（覆盖层，不暂停相机） ----------------

    private fun showDebugPanel() {
        if (debugView != null) return
        val b = ActivityDebugBinding.inflate(layoutInflater)
        val root = b.root
        root.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        binding.root.addView(root)

        b.btnWorkers1.setOnClickListener { DebugHub.actions?.setWorkers?.invoke(1) }
        b.btnWorkers2.setOnClickListener { DebugHub.actions?.setWorkers?.invoke(2) }
        b.btnWorkers4.setOnClickListener { DebugHub.actions?.setWorkers?.invoke(4) }
        b.btnPermits1.setOnClickListener { DebugHub.actions?.setPermits?.invoke(1) }
        b.btnPermits2.setOnClickListener { DebugHub.actions?.setPermits?.invoke(2) }
        b.btnPermits4.setOnClickListener { DebugHub.actions?.setPermits?.invoke(4) }
        b.btnDet640.setOnClickListener { DebugHub.actions?.setDetSize?.invoke(640) }
        b.btnDet480.setOnClickListener { DebugHub.actions?.setDetSize?.invoke(480) }
        b.btnDet320.setOnClickListener { DebugHub.actions?.setDetSize?.invoke(320) }
        b.btnReset.setOnClickListener { DebugHub.actions?.resetStats?.invoke() }
        b.btnKill.setOnClickListener { DebugHub.actions?.killService?.invoke() }
        b.btnBack.setOnClickListener { hideDebugPanel() }

        debugBinding = b
        debugView = root
        debugBackCallback?.isEnabled = true
        debugHandler.removeCallbacks(debugRefresh)
        debugHandler.post(debugRefresh)
    }

    private fun hideDebugPanel() {
        debugHandler.removeCallbacks(debugRefresh)
        debugView?.let { v ->
            (v.parent as? ViewGroup)?.removeView(v)
        }
        debugView = null
        debugBinding = null
        debugBackCallback?.isEnabled = false
    }

    // ---------------- 调试页数据 ----------------

    private fun installDebugHooks() {
        DebugHub.provider = {
            val c = client
            val svc = c?.latestStats
            val svcPid = svc?.servicePid ?: 0
            val res = resources.sample(Process.myPid(), svcPid)

            // 服务端自己按 snapshot 间隔差分出 CPU%；/proc 采样失败时用它兜底
            val serviceCpuPct = if (svc != null && svc.cpuPct >= 0f) svc.cpuPct else res.serviceCpuPct

            DebugHub.Snapshot(
                mode = if (mode == MODE_DUAL) "双进程 (:infer)" else "单进程 (主进程内)",
                mainPid = Process.myPid(),
                cameraThread = "camera-analysis",
                connected = if (mode == MODE_DUAL) (c?.connected?.get() ?: false) else true,
                submitted = c?.submitted?.get() ?: 0,
                received = c?.received?.get() ?: 0,
                reconnects = c?.reconnects?.get() ?: 0,
                timeouts = c?.timeouts?.get() ?: 0,
                lastResultAgeMs = c?.lastResultAgeMs ?: -1,
                avgIpcMs = c?.avgIpcMs ?: 0,
                fps = analyzer?.fps ?: 0f,
                galleryCount = if (mode == MODE_DUAL) (c?.galleryCount() ?: 0) else (engine?.gallery?.size() ?: 0),
                mainPssKb = res.mainPssKb,
                servicePssKb = res.servicePssKb,
                mainCpuPct = res.mainCpuPct,
                serviceCpuPct = serviceCpuPct,
                service = svc
            )
        }
        DebugHub.actions = DebugHub.Actions(
            setWorkers = { n -> if (mode == MODE_DUAL) client?.setWorkers(n) },
            setPermits = { n -> if (mode == MODE_DUAL) client?.setPermits(n) },
            resetStats = { if (mode == MODE_DUAL) client?.resetStats() else localSink?.reset() },
            setVariant = { v ->
                if (mode == MODE_DUAL) client?.setVariant(v)
                else reloadAsync { engine?.reload(v) ?: false }
            },
            setDetSize = { n ->
                if (mode == MODE_DUAL) client?.setDetSize(n)
                else reloadAsync { engine?.reloadDetSize(n) ?: false }
            },
            killService = {
                if (mode == MODE_DUAL) {
                    client?.killService()
                } else {
                    Toast.makeText(this, "单进程模式没有独立推理服务可终止", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    /** 单进程模式下热切换模型会加载 ONNX，必须放到后台线程，避免卡 UI */
    private fun reloadAsync(block: () -> Boolean) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    block()
                } catch (t: Throwable) {
                    Log.w(TAG, "reload failed: ${t.message}")
                    false
                }
            }
            Toast.makeText(
                this@MainActivity,
                if (ok) "模型/分辨率已切换" else "切换失败（文件缺失或算子不兼容），保持原模型",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onResume() {
        super.onResume()
        statsHandler.removeCallbacks(statsLogger)
        statsHandler.post(statsLogger)
        // 用户可能在系统设置里补授了相机权限，回到前台时补启动
        if (!pipelineStarted &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        ) {
            startPipeline()
        }
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
                " pavail=" + (svc?.permitsAvailable ?: -1) +
                " inflight=" + (svc?.inFlight ?: -1) +
                " detsize=" + (svc?.detInputSize ?: (engine?.detInputSize ?: -1)) +
                " queue=" + (svc?.queueLength ?: -1) + "/" + (svc?.queueCapacity ?: -1) +
                " proc=" + (svc?.processed ?: -1) +
                " drop=" + (svc?.dropped ?: -1) +
                " err=" + (svc?.errors ?: -1) +
                " det=" + (svc?.avgDetMs ?: -1) +
                " live=" + (svc?.avgLiveMs ?: -1) +
                " rec=" + (svc?.avgRecMs ?: -1) +
                " total=" + (svc?.avgTotalMs ?: -1) +
                " p50=" + (svc?.p50TotalMs ?: -1) +
                " p95=" + (svc?.p95TotalMs ?: -1) +
                " p50det=" + (svc?.p50DetMs ?: -1) +
                " p95det=" + (svc?.p95DetMs ?: -1) +
                " nsample=" + (svc?.sampleCount ?: -1) +
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
                " svc_cpu=" + (svc?.cpuTimeMs ?: -1) +
                " svc_cpu_pct=" + (svc?.cpuPct ?: -1f) +
                " gallery=" + (if (mode == MODE_DUAL) (client?.galleryCount() ?: 0) else (engine?.gallery?.size() ?: 0))
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        hideDebugPanel()
        DebugHub.provider = null
        DebugHub.actions = null
        client?.unbind()
        localSink?.shutdown()
        engine?.close()
        cameraExecutor.shutdown()
    }
}
