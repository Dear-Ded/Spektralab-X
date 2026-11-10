#pragma once
#include <jni.h>
#include <cstdint>
struct AndroidBitmapInfo { uint32_t width, height, stride; int32_t format; uint32_t flags; };
constexpr int ANDROID_BITMAP_RESULT_SUCCESS = 0;
constexpr int ANDROID_BITMAP_FORMAT_RGBA_8888 = 1;
constexpr int ANDROID_BITMAP_FORMAT_A_8 = 8;
constexpr int ANDROID_BITMAP_FORMAT_RGBA_F16 = 9;
int AndroidBitmap_getInfo(JNIEnv*, jobject, AndroidBitmapInfo*);
int AndroidBitmap_lockPixels(JNIEnv*, jobject, void**);
int AndroidBitmap_unlockPixels(JNIEnv*, jobject);
