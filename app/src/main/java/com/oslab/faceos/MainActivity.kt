package com.oslab.faceos

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.oslab.faceos.databinding.ActivityMainBinding

/**
 * 阶段 1：环境自检页。
 *
 * 目标是先证明「工程能编译、APK 能安装、真机能运行」，
 * 并把真机关键信息显示出来，方便后续性能实验对照：
 *   - 手机型号 / Android 版本 / ABI
 *   - 应用进程 PID、主线程名称
 * 后续阶段会替换成相机预览 + 三模型流水线 + 系统状态页。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvStatus.text = buildString {
            appendLine("FaceOS 环境自检")
            appendLine("------------------------------")
            appendLine("应用包名 : $packageName")
            appendLine("Android  : ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            appendLine("设备厂商 : ${android.os.Build.MANUFACTURER}")
            appendLine("设备型号 : ${android.os.Build.MODEL}")
            appendLine("CPU ABI  : ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("CPU 核数 : ${Runtime.getRuntime().availableProcessors()}")
            appendLine("应用 PID : ${android.os.Process.myPid()}")
            appendLine("主线程   : ${Thread.currentThread().name}")
            appendLine("------------------------------")
            appendLine("环境验证通过 ✅")
            appendLine()
            appendLine("下一步：接入 CameraX 前摄预览")
        }
    }
}
