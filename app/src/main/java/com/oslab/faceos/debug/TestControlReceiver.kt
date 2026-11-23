package com.oslab.faceos.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 调试用广播接收器：让 adb 可以脚本化切换实验配置、重置统计。
 *
 * 用法（真机实验自动化）：
 *   adb shell am broadcast -a com.oslab.faceos.debug.CONFIG --ei workers 4 --ei permits 2
 *   adb shell am broadcast -a com.oslab.faceos.debug.CONFIG --es mode single
 *   adb shell am broadcast -a com.oslab.faceos.debug.RESET
 *
 * 仅用于实验调试，release 包可通过 manifest 移除。
 */
class TestControlReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_CONFIG = "com.oslab.faceos.debug.CONFIG"
        const val ACTION_RESET = "com.oslab.faceos.debug.RESET"
        private const val TAG = "TestControl"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_CONFIG -> {
                val workers = intent.getIntExtra("workers", 0)
                if (workers > 0) {
                    DebugHub.actions?.setWorkers?.invoke(workers)
                    Log.i(TAG, "setWorkers=$workers")
                }
                val permits = intent.getIntExtra("permits", 0)
                if (permits > 0) {
                    DebugHub.actions?.setPermits?.invoke(permits)
                    Log.i(TAG, "setPermits=$permits")
                }
                val variant = intent.getStringExtra("variant")
                if (!variant.isNullOrEmpty()) {
                    DebugHub.actions?.setVariant?.invoke(variant)
                    Log.i(TAG, "setVariant=$variant")
                }
                val detSize = intent.getIntExtra("detsize", 0)
                if (detSize > 0) {
                    DebugHub.actions?.setDetSize?.invoke(detSize)
                    Log.i(TAG, "setDetSize=$detSize")
                }
                val mode = intent.getStringExtra("mode")
                if (!mode.isNullOrEmpty()) {
                    context.getSharedPreferences("faceos", Context.MODE_PRIVATE)
                        .edit().putString("infer_mode", mode).apply()
                    Log.i(TAG, "switch mode=$mode, restarting activity")
                    context.startActivity(
                        Intent(context, Class.forName("com.oslab.faceos.MainActivity"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    )
                }
            }
            ACTION_RESET -> {
                DebugHub.actions?.resetStats?.invoke()
                Log.i(TAG, "reset stats")
            }
        }
    }
}
