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

- [x] 工程骨架 + 环境自检页（显示机型、ABI、PID、线程名）
- [x] CameraX 前摄预览
- [x] SCRFD-500M-KPS 人脸检测（ONNX Runtime，含 5 关键点、NMS、延迟/FPS 显示）
- [ ] EdgeFace 识别
- [ ] MiniFASNetV2 活体
- [ ] 双进程 IPC / 线程池 / Semaphore
- [ ] 系统状态页
- [ ] 优化对照实验

## 模型

| 文件 | 用途 | 大小 |
|---|---|---|
| `assets/models/det_500m.onnx` | SCRFD-500M-KPS 人脸检测（5 关键点） | 2.41 MB |
| `assets/models/w600k_mbf.onnx` | MobileFaceNet 人脸特征提取（备用/对照） | 12.99 MB |

