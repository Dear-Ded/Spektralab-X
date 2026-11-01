# R8 shrinks but does not rename: stack traces in bug reports stay readable,
# and every name the C++ side looks up stays put.
-dontobfuscate

# JNI: spektralab_jni.cpp binds Java_com_dearded_spektralab_NativeEngine_* by name.
-keep class com.dearded.spektralab.NativeEngine { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }


# Keep line numbers so stack traces in the diagnostics report point at real lines.
-keepattributes SourceFile,LineNumberTable
