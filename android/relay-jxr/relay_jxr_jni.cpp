#include "relay_jxr_decoder.h"
#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <cstring>
#include <new>
#include <vector>

extern "C" JNIEXPORT jobject JNICALL
Java_com_metallic_chiaki_jb_JxrDecoder_decodeNative(JNIEnv* env, jobject, jbyteArray bytes, jint maxEdge) {
    if (!bytes) return nullptr;
    const jsize length = env->GetArrayLength(bytes);
    if (length < 16 || length > 32 * 1024 * 1024 || maxEdge < 1) return nullptr;
    relay::JxrPreview preview;
    try {
        // Copy rather than pin a managed array through a potentially lengthy native decode.
        std::vector<uint8_t> encoded(static_cast<size_t>(length));
        env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte*>(encoded.data()));
        if (env->ExceptionCheck()) return nullptr;
        if (!relay::decodeJxr(encoded.data(), encoded.size(), maxEdge, preview)) {
            __android_log_print(ANDROID_LOG_WARN, "RelayJxr", "%s", preview.error ? preview.error : "JPEG XR decode failed");
            return nullptr;
        }
    } catch (const std::bad_alloc&) {
        return nullptr;
    }
    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    if (!bitmapClass || !configClass) return nullptr;
    jfieldID argbField = env->GetStaticFieldID(configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jmethodID create = env->GetStaticMethodID(bitmapClass, "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    if (!argbField || !create) return nullptr;
    jobject config = env->GetStaticObjectField(configClass, argbField);
    jobject bitmap = env->CallStaticObjectMethod(bitmapClass, create, preview.width, preview.height, config);
    if (!bitmap || env->ExceptionCheck()) return nullptr;
    AndroidBitmapInfo info{};
    void* pixels = nullptr;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS
            || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888
            || AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return nullptr;
    for (int y = 0; y < preview.height; ++y) {
        std::memcpy(static_cast<uint8_t*>(pixels) + y * info.stride,
                    preview.rgba.data() + size_t(y) * preview.width * 4, size_t(preview.width) * 4);
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    __android_log_print(ANDROID_LOG_INFO, "RelayJxr", "Decoded %dx%d JPEG XR to %dx%d%s",
        preview.sourceWidth, preview.sourceHeight, preview.width, preview.height,
        preview.toneMapped ? " (HDR to SDR preview)" : "");
    return bitmap;
}
