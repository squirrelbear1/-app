package com.oslab.faceos.ipc

/**
 * 主进程 <-> 推理服务进程 的 Messenger 消息协议。
 *
 * 为什么用 Messenger 而不是 AIDL：
 *  AIDL 编译器（aidl.exe）在本机「中文工程路径」下会因依赖文件编码问题直接失败；
 *  Messenger 基于 Binder + Handler，官方同样属于 Binder IPC，
 *  且天然支持 oneway 投递与异步回调，足够覆盖本实验的请求/结果/统计三类通信。
 */
object Msg {
    // 主进程 -> 服务进程
    const val SUBMIT = 1          // data: rgba, w, h, rot, seq
    const val SET_CALLBACK = 2    // replyTo: 客户端的 Messenger
    const val REGISTER = 3        // data: name
    const val CLEAR = 4
    const val GET_STATS = 5
    const val SET_WORKERS = 6     // arg1
    const val SET_PERMITS = 7     // arg1
    const val RESET_STATS = 8
    const val SET_VARIANT = 9      // data: variant(fp32/int8/fp16)
    const val SET_DET_SIZE = 10    // arg1: detection input size (640/480/320)
    const val REMOVE = 11         // 删除单个模板，arg1=请求号，data: name
    const val KILL = 12           // 调试：让服务进程自杀，用于故障隔离演示
    const val CAPTURE = 13        // 注册前冻结当前帧特征/活体，arg1=请求号，data: seq
    const val CANCEL_CAPTURE = 14 // 取消注册对话框时清掉冻结快照

    // 服务进程 -> 主进程
    const val RESULT = 20         // data: result (InferenceResult)
    const val REGISTER_RESULT = 21 // data: ok(boolean), name(String), arg1=请求号
    const val CLEAR_RESULT = 22   // data: ok(boolean), arg1=请求号
    const val STATS = 23          // data: stats (StatsSnapshot)
    const val REMOVE_RESULT = 24  // data: ok(boolean), arg1=请求号
    const val CAPTURE_RESULT = 25 // data: ok(boolean), arg1=请求号

    // Bundle keys
    const val K_RGBA = "rgba"
    const val K_W = "w"
    const val K_H = "h"
    const val K_ROT = "rot"
    const val K_SEQ = "seq"
    const val K_NAME = "name"
    const val K_OK = "ok"
    const val K_RESULT = "result"
    const val K_STATS = "stats"
    const val K_VARIANT = "variant"
    const val K_DET_SIZE = "detSize"
    /** 相机回调时刻（SystemClock.elapsedRealtimeNanos） */
    const val K_CAP_TS = "capTs"
    /** 主进程投递时刻（SystemClock.elapsedRealtimeNanos） */
    const val K_SUB_TS = "subTs"
}
