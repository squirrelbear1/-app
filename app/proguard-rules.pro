# 混淆规则（release 开启 minify 后生效）
# 保留 ONNX Runtime 的 JNI 相关类（后续引入模型推理时使用）
-keep class ai.onnxruntime.** { *; }
