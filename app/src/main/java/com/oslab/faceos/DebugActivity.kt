package com.oslab.faceos

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.oslab.faceos.databinding.ActivityDebugBinding
import com.oslab.faceos.debug.DebugHub
import com.oslab.faceos.debug.DebugRenderer

/**
 * 系统运行状态 / 调试页（实验要求 2.5）：
 *   展示进程、线程、IPC 请求与时延、信号量许可、在途任务、队列、丢帧、各阶段耗时。
 *   并提供线程数 / 许可数切换按钮，用于 CPU 调度与背压对照实验。
 */
class DebugActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDebugBinding
    private val handler = Handler(Looper.getMainLooper())

    private val refresh = object : Runnable {
        override fun run() {
            render(DebugHub.provider?.invoke())
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDebugBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnWorkers1.setOnClickListener { DebugHub.actions?.setWorkers?.invoke(1) }
        binding.btnWorkers2.setOnClickListener { DebugHub.actions?.setWorkers?.invoke(2) }
        binding.btnWorkers4.setOnClickListener { DebugHub.actions?.setWorkers?.invoke(4) }
        binding.btnPermits1.setOnClickListener { DebugHub.actions?.setPermits?.invoke(1) }
        binding.btnPermits2.setOnClickListener { DebugHub.actions?.setPermits?.invoke(2) }
        binding.btnPermits4.setOnClickListener { DebugHub.actions?.setPermits?.invoke(4) }
        binding.btnDet640.setOnClickListener { DebugHub.actions?.setDetSize?.invoke(640) }
        binding.btnDet480.setOnClickListener { DebugHub.actions?.setDetSize?.invoke(480) }
        binding.btnDet320.setOnClickListener { DebugHub.actions?.setDetSize?.invoke(320) }
        binding.btnReset.setOnClickListener { DebugHub.actions?.resetStats?.invoke() }
        binding.btnKill.setOnClickListener { DebugHub.actions?.killService?.invoke() }
        binding.btnBack.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresh)
    }

    private fun render(s: DebugHub.Snapshot?) {
        binding.tvDebug.text = DebugRenderer.render(s)
    }
}
