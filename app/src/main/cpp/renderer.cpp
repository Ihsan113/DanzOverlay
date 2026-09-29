// Tahap 1: stub renderer. Pass shader (upscale, AA, dll.) masuk di tahap 4+.
#include <jni.h>
#include <android/log.h>

#define TAG "DanzRenderer"

extern "C" JNIEXPORT jstring JNICALL
Java_com_danzku_overlay_NativeBridge_version(JNIEnv* env, jobject /*self*/) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "renderer stub loaded");
    return env->NewStringUTF("renderer-stub 0.1 (stage1)");
}
