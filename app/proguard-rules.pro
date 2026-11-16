# sherpa-onnx : le code natif accède aux champs des classes de config par réflexion JNI.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**
# Lambdas Kotlin passées comme callback au code natif
-keep class kotlin.jvm.functions.** { *; }
-keep class kotlin.Unit { *; }
