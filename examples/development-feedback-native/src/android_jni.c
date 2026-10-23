/* Resident JNI adapter; real selected jni.h, no hand-written VM table. */
#include <jni.h>
#include <stdint.h>
#ifdef _WIN32
#pragma comment(linker, "/export:JNI_OnLoad")
#endif

_Static_assert(sizeof(jlong) == 8 && sizeof(jint) == 4, "fixed JNI scalars");
_Static_assert(sizeof(uintptr_t) <= sizeof(jlong), "private pointer token fits");

extern void *rm_bridge_create(void);
extern int32_t rm_bridge_activate(void *, const uint8_t *, uint32_t, const uint8_t *, int64_t, int64_t);
extern int32_t rm_bridge_compute(void *, int64_t);
extern int32_t rm_bridge_snapshot(void *, int64_t *, uint32_t, uint8_t *, uint32_t);

static jlong JNICALL create(JNIEnv *env, jclass type) {
    (void)type;
    if ((*env)->ExceptionCheck(env)) return 0;
    return (jlong)(uintptr_t)rm_bridge_create();
}
static jint JNICALL activate(JNIEnv *env, jclass type, jlong token, jbyteArray path,
                             jbyteArray sha, jlong generation, jlong expected) {
    (void)type;
    if ((*env)->ExceptionCheck(env) || !token || !path || !sha) return 1;
    jsize length = (*env)->GetArrayLength(env, path);
    if ((*env)->ExceptionCheck(env) || length < 1 || length > 4096) return 1;
    jsize sha_length = (*env)->GetArrayLength(env, sha);
    if ((*env)->ExceptionCheck(env) || sha_length != 32) return 1;
    jbyte path_copy[4096], sha_copy[32];
    (*env)->GetByteArrayRegion(env, path, 0, length, path_copy);
    if ((*env)->ExceptionCheck(env)) return 1;
    (*env)->GetByteArrayRegion(env, sha, 0, 32, sha_copy);
    if ((*env)->ExceptionCheck(env)) return 1;
    return rm_bridge_activate((void *)(uintptr_t)token, (const uint8_t *)path_copy,
                              (uint32_t)length, (const uint8_t *)sha_copy, generation, expected);
}
static jint JNICALL compute(JNIEnv *env, jclass type, jlong token, jlong input) {
    (void)type;
    if ((*env)->ExceptionCheck(env) || !token) return 1;
    return rm_bridge_compute((void *)(uintptr_t)token, input);
}
static jint JNICALL snapshot(JNIEnv *env, jclass type, jlong token, jlongArray scalars, jbyteArray payload) {
    (void)type;
    if ((*env)->ExceptionCheck(env) || !token || !scalars || !payload) return 1;
    jsize scalar_length = (*env)->GetArrayLength(env, scalars);
    if ((*env)->ExceptionCheck(env) || scalar_length != 5) return 1;
    jsize payload_length = (*env)->GetArrayLength(env, payload);
    if ((*env)->ExceptionCheck(env) || payload_length != 8) return 1;
    int64_t values[5];
    uint8_t bytes[8];
    jint code = rm_bridge_snapshot((void *)(uintptr_t)token, values, 5, bytes, 8);
    if (code) return code;
    jlong copy[5];
    for (int i = 0; i < 5; ++i) copy[i] = (jlong)values[i];
    (*env)->SetLongArrayRegion(env, scalars, 0, 5, copy);
    if ((*env)->ExceptionCheck(env)) return 1;
    (*env)->SetByteArrayRegion(env, payload, 0, 8, (const jbyte *)bytes);
    return (*env)->ExceptionCheck(env) ? 1 : 0;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    JNIEnv *env = 0;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK || !env) return JNI_ERR;
    jclass type = (*env)->FindClass(env, "org/rusty/quest/feedback/NativeFeedbackBridge");
    if (!type || (*env)->ExceptionCheck(env)) return JNI_ERR;
    const JNINativeMethod methods[] = {
        {"nativeCreate", "()J", (void *)create},
        {"nativeActivate", "(J[B[BJJ)I", (void *)activate},
        {"nativeCompute", "(JJ)I", (void *)compute},
        {"nativeSnapshot", "(J[J[B)I", (void *)snapshot}
    };
    jint result = (*env)->RegisterNatives(env, type, methods, 4);
    (*env)->DeleteLocalRef(env, type);
    if (result != JNI_OK || (*env)->ExceptionCheck(env)) return JNI_ERR;
    return JNI_VERSION_1_6;
}
