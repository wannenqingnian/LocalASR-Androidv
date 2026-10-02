# 本地语音转文字

一个原生 Android/Kotlin 离线语音转文字应用。识别由 sherpa-onnx 在手机本地完成，音频和转写内容不会上传。

## 已实现

- 三种按需下载的识别模型：通用中英实时、普通话/粤语/英语实时、川渝方言分段识别
- 川渝方言模型使用 Silero VAD 分段，停顿后输出整段文字
- 后台与权限页提供电池优化、自启动、省电策略和通知设置入口
- 每段转写使用手机当前时钟时间标记
- 麦克风前台服务支持切到后台或锁屏后继续转写
- 通知栏显示运行状态，并可直接停止和保存
- 已完成的语句会增量写入历史，降低长时间转写丢失风险
- APK 不内置语音模型；未下载模型时仍可正常安装和打开
- 模型管理页可选择国内镜像或官方原站
- 分文件下载、暂停、断点续传、大小与 SHA-256 校验
- 停止转写后自动保存历史记录
- 历史记录支持查看、复制和删除
- 统一的卡片式界面、状态提示、下载进度和历史记录视觉层级

## 模型下载源

应用可按需下载以下 INT8 模型，一次只加载用户选择的一个模型：

- 通用普通话/英语实时模型：`csukuangfj/streaming-paraformer-zh`
- 普通话/粤语/英语实时模型：`csukuangfj/sherpa-onnx-streaming-paraformer-trilingual-zh-cantonese-en`
- 四川话/重庆话非流式模型：`csukuangfj/sherpa-onnx-paraformer-zh-int8-2025-10-07`
- 川渝模式使用的 Silero VAD INT8 文件来自 sherpa-onnx 官方发布页

Hugging Face 模型提供两条下载线路：

- 国内镜像：`https://hf-mirror.com/csukuangfj/streaming-paraformer-zh/resolve/main`
- 官方原站：`https://huggingface.co/csukuangfj/streaming-paraformer-zh/resolve/main`

每个语音模型约 237–239 MB，存放在应用专属目录，卸载应用时系统会一并清理。切换源不会破坏已下载的分片，因为两个源经过同一组校验值验证。

## 构建

1. 使用 Android Studio 打开本目录。
2. 使用 JDK 17 和 Android SDK 35。
3. 运行 `gradlew.bat assembleDebug`。

项目随附 sherpa-onnx 1.13.8 Android 运行库。该 AAR 只包含识别代码和本地动态库，不包含语音模型。

## 当前边界

当前版本专注于单设备的实时/分段转写、长时间后台运行、模型下载和历史记录。河南、天津等口音暂时没有单独的轻量 Android 模型；说话人区分、声纹绑定和自动总结尚未加入。
