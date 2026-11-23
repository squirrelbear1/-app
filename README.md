# FaceOS —— 基于 Android 的轻量化 AI 人脸分析 App

《操作系统》综合实验：前置摄像头人脸检测、人脸识别与活体检测，并体现进程/IPC、线程调度、
信号量同步、I/O 事件链等操作系统机制。

## 构建环境（本机已装好）

| 项 | 版本 / 路径 |
|---|---|
| Android Studio | Narwhal 2025.1.1（免安装版 `D:\Android\android-studio`） |
| JDK | JBR 21（Studio 自带） |
| Android SDK | `D:\Android\Sdk`（android-35 / build-tools 35.0.0 / platform-tools） |
| Gradle | 8.9（wrapper 从腾讯镜像下载） |
| AGP / Kotlin | 8.7.3 / 2.0.21 |
| compileSdk / minSdk / targetSdk | 35 / 26 / 35 |

## 命令行构建

```bat
cd FaceOSApp
gradlew.bat assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

## 安装到真机

```bat
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.oslab.faceos.debug/com.oslab.faceos.MainActivity
```

## 目录说明

```
FaceOSApp/
├── app/src/main/java/com/oslab/faceos/   Kotlin 源码
├── app/src/main/res/                     布局与资源
├── app/build.gradle.kts                  模块构建配置（依赖、SDK 版本）
├── settings.gradle.kts                   仓库配置（阿里云 Maven 镜像）
└── gradle/wrapper/                       Gradle 版本锁定
```

## 当前进度

- [x] 工程骨架 + 环境自检
- [x] CameraX 前摄预览（锁定 4:3，避免叠加层错位）
- [x] SCRFD-500M-KPS 人脸检测（ONNX Runtime，含 5 关键点、NMS、延迟/FPS 显示）
- [x] EdgeFace-XXS 人脸识别（对齐 + 特征 + 余弦匹配 + 注册/删除）
- [x] MiniFASNetV2 活体检测（PAD，5 帧多数投票平滑）
- [x] 双进程 Binder IPC（Messenger，:infer 进程）+ 线程池 + Semaphore 限流 + 自动重连
- [x] 系统运行状态覆盖层（进程/线程/IPC/信号量/队列/P50/P95/CPU/内存）
- [x] 优化对照实验（模型侧：INT8/FP16 量化评估、检测输入 640→480/320；系统侧：线程/许可/队列/单双进程）
- [x] 逐帧时间戳与结构化日志（FaceOS-FRAME / FaceOS-STATS），支撑离线计算 P50/P95

## 进程模型

- 主进程：相机采集 + UI（camera-analysis 线程）
- \:infer\ 进程：三模型推理（infer-dispatch 调度 + infer-worker-N 线程池）
- 通过 Messenger（Binder）传递帧与结果；可在界面「模式」按钮切换单/双进程对照

## 调试与实验入口

| 入口 | 用法 |
|---|---|
| 状态面板 | 主界面「状态」按钮 → 实时显示进程/线程/信号量/队列/P50/P95/CPU/内存；按钮可切线程数、许可数、检测输入、终止推理服务 |
| 逐帧日志 | `adb logcat -s FaceOS-FRAME`（seq/相机回调/投递/服务接收/推理起止/结果回调/各阶段耗时） |
| 统计日志 | `adb logcat -s FaceOS-STATS`（每 2 s 一行，含吞吐/丢帧/平均/P50/P95/CPU 时间） |
| 配置广播 | `adb shell am broadcast -n com.oslab.faceos.debug/com.oslab.faceos.debug.TestControlReceiver -a com.oslab.faceos.debug.CONFIG --ei workers 4 --ei permits 2 --ei detsize 480` |
| 重置统计 | 同上 `-a com.oslab.faceos.debug.RESET`（同时重置客户端与服务端计数） |
| 故障注入 | 同上 `--ez kill true`，或状态面板「终止服务」按钮，验证 binder died 与自动重连 |

## 模型

| 文件 | 用途 | 大小 | 来源 |
|---|---|---|---|
| `assets/models/det_500m.onnx` | SCRFD-500M-KPS 人脸检测（5 关键点） | 2.41 MB | InsightFace 官方模型库 `buffalo_sc`（github.com/deepinsight/insightface） |
| `assets/models/edgeface_xxs.onnx` | EdgeFace-XXS 人脸特征提取（512 维） | 4.90 MB | EdgeFace 官方工程（github.com/otroshi/edgeface）；ONNX 导出参考 yakhyo/edgeface-onnx |
| `assets/models/minifasnet_v2.onnx` | MiniFASNetV2 活体检测 | 1.66 MB | minivision-ai/Silent-Face-Anti-Spoofing；ONNX 导出参考 garciafido/minifasnet-v2-anti-spoofing-onnx |
| `assets/models/w600k_mbf.onnx` | MobileFaceNet（对照模型，未接入运行时） | 12.99 MB | InsightFace |

> 模型权重版权归各上游项目所有，仅用于本课程实验；对外分发 APK 前请核对上游许可证。
> `*_int8.onnx` / `*_fp16.onnx` 是量化评估产物；当前 ONNX Runtime Android 不支持动态量化
> 卷积产生的 `ConvInteger`，因此实际仍以 FP32 运行。

## 已知限制

1. 识别阈值（0.40）与活体阈值（0.50）尚未用完整测试集标定，报告需给出 FAR/FRR 与攻击样本统计；
2. 只对面积最大的一张脸做活体/识别（实验允许的单脸限制），多人场景其余脸显示“未测”；
3. 屏幕锁定竖屏，旋转场景通过 `rotationDegrees` 处理分析帧，未做横屏 UI；
4. 未接入 NNAPI/QNN 后端，全部为 CPU 推理；INT8 动态量化模型在 ORT Android 上不可用；
5. 人脸特征存于本地 SharedPreferences，未加密，仅适用于教学演示；
6. 真机性能数据需固定机型/光照/距离并重复测试，当前仓库中的旧数据仅作预实验参考。

## 构建产物

当前 debug APK 约 107 MB（ONNX Runtime 全 ABI + 4 个模型）。正式包可用
`abiFilters += "arm64-v8a"` 只保留真机 ABI 显著缩小体积。


