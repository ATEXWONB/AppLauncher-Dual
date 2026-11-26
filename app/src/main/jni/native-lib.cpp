#include <jni.h>
#include <unistd.h>
#include <sys/types.h>
#include <android/log.h>
#include <stdio.h>
#include <string.h>

#define LOG_TAG "nativebridge"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

/**
 * 读取当前进程真实 UID。
 * Android 中 UID = userId * 100000 + appId，
 * 因此 uid / 100000 就是当前所在的 Android 用户空间编号：
 *   user 0  -> 主用户
 *   user 10 -> 工作资料（Managed Profile）等
 * 这是判断“当前 App 跑在哪个空间”的可靠 native 手段，
 * 也是学习 JNI 与 Android 多用户机制的经典例子。
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_applauncher_dual_NativeBridge_nativeGetUid(JNIEnv* /*env*/, jclass /*clazz*/) {
    uid_t uid = getuid();
    LOGD("nativeGetUid: %d", (int)uid);
    return (jint)uid;
}

/**
 * 由 UID 换算出 Android 用户编号（userId）。
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_applauncher_dual_NativeBridge_nativeGetUserId(JNIEnv* /*env*/, jclass /*clazz*/) {
    uid_t uid = getuid();
    jint userId = (jint)(uid / 100000);
    LOGD("nativeGetUserId: uid=%d userId=%d", (int)uid, userId);
    return userId;
}

/**
 * 读取 /proc/self/status，返回其中 Uid/Gid 行的原始文本，
 * 方便在界面上直观看到 native 层看到的多用户身份信息。
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_applauncher_dual_NativeBridge_nativeProcStatus(JNIEnv* env, jclass /*clazz*/) {
    char buf[2048] = {0};
    FILE* fp = fopen("/proc/self/status", "r");
    if (fp == nullptr) {
        return env->NewStringUTF("(无法读取 /proc/self/status)");
    }
    size_t n = fread(buf, 1, sizeof(buf) - 1, fp);
    buf[n] = '\0';
    fclose(fp);

    // 只保留 Uid/Gid 相关行，输出更聚焦
    char out[1024] = {0};
    char* line = strtok(buf, "\n");
    while (line != nullptr) {
        if (strncmp(line, "Uid:", 4) == 0 ||
            strncmp(line, "Gid:", 4) == 0 ||
            strncmp(line, "Name:", 5) == 0) {
            strncat(out, line, sizeof(out) - strlen(out) - 1);
            strncat(out, "\n", sizeof(out) - strlen(out) - 1);
        }
        line = strtok(nullptr, "\n");
    }
    return env->NewStringUTF(out);
}
