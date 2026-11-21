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

    // 服务进程 -> 主进程
    const val RESULT = 20         // data: result (InferenceResult)
    const val REGISTER_RESULT = 21 // data: ok(boolean), name(String)
    const val CLEAR_RESULT = 22   // data: ok(boolean)
    const val STATS = 23          // data: stats (StatsSnapshot)

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
}
