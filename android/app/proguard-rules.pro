# R8 混淆规则
#
# 本项目代码量很小，风险主要来自第三方库的反射调用。

# ML Kit：内部通过反射加载模型与实现类，混淆后会运行时报错
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_common.** { *; }
-dontwarn com.google.mlkit.**

# CameraX：部分 API 通过反射实例化
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# 所有 feature 子包的类名全部保留：
# 1) 功能注册表里的入口类名是写死在代码里的（混淆后仍可用，但保留便于排障）；
# 2) 扫码功能的枚举名参与跨端一致性约定，日志里要能直接看懂；
# 3) 这些类体量很小，keep 的代价可以忽略。
-keep class com.szpdc.assistant.feature.** { *; }
-keep class com.szpdc.assistant.HomeActivity { *; }
-keep class com.szpdc.assistant.UpdateInstallReceiver { *; }

# 保留行号，崩溃栈才有意义；同时保留源文件名
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
