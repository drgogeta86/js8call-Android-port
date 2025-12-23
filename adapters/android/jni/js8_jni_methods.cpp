#include <jni.h>
#include <android/log.h>
#include "js8_engine_jni.h"

// JNI method implementations for com.js8call.core.JS8Engine

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_js8call_core_JS8Engine_00024Companion_nativeCreate(
    JNIEnv* env,
    jobject /* thiz */,
    jobject callback_handler,
    jint sample_rate_hz,
    jint submodes) {
  JS8Engine_Native* engine = js8_engine_create(
      env, callback_handler, sample_rate_hz, submodes);
  return reinterpret_cast<jlong>(engine);
}

JNIEXPORT jboolean JNICALL
Java_com_js8call_core_JS8Engine_nativeStart(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle) {
  JS8Engine_Native* engine = reinterpret_cast<JS8Engine_Native*>(handle);
  return js8_engine_start(engine) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_js8call_core_JS8Engine_nativeStop(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle) {
  JS8Engine_Native* engine = reinterpret_cast<JS8Engine_Native*>(handle);
  js8_engine_stop(engine);
}

JNIEXPORT void JNICALL
Java_com_js8call_core_JS8Engine_nativeDestroy(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle) {
  JS8Engine_Native* engine = reinterpret_cast<JS8Engine_Native*>(handle);
  js8_engine_destroy(engine);
}

JNIEXPORT jboolean JNICALL
Java_com_js8call_core_JS8Engine_nativeSubmitAudio(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jshortArray samples,
    jint num_samples,
    jlong timestamp_ns) {
  JS8Engine_Native* engine = reinterpret_cast<JS8Engine_Native*>(handle);

  // Get array elements
  jshort* sample_data = env->GetShortArrayElements(samples, nullptr);
  if (!sample_data) {
    __android_log_print(ANDROID_LOG_ERROR, "JS8Engine_JNI",
                       "Failed to get audio samples array");
    return JNI_FALSE;
  }

  // Log first submission with details
  static int submit_count = 0;
  if (submit_count++ % 100 == 0) {  // Every 100th submission
    __android_log_print(ANDROID_LOG_DEBUG, "JS8Engine_JNI",
                       "Audio submit: %d samples, first3=[%d, %d, %d]",
                       num_samples, sample_data[0], sample_data[1], sample_data[2]);
  }

  int result = js8_engine_submit_audio(
      engine,
      reinterpret_cast<const int16_t*>(sample_data),
      static_cast<size_t>(num_samples),
      timestamp_ns);

  // Release array (no need to copy back, read-only)
  env->ReleaseShortArrayElements(samples, sample_data, JNI_ABORT);

  return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_js8call_core_JS8Engine_nativeSubmitAudioRaw(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jshortArray samples,
    jint num_samples,
    jint input_sample_rate_hz,
    jlong timestamp_ns) {
  JS8Engine_Native* engine = reinterpret_cast<JS8Engine_Native*>(handle);

  jshort* sample_data = env->GetShortArrayElements(samples, nullptr);
  if (!sample_data) {
    __android_log_print(ANDROID_LOG_ERROR, "JS8Engine_JNI",
                       "Failed to get raw audio samples array");
    return JNI_FALSE;
  }

  int result = js8_engine_submit_audio_raw(
      engine,
      reinterpret_cast<const int16_t*>(sample_data),
      static_cast<size_t>(num_samples),
      static_cast<int>(input_sample_rate_hz),
      timestamp_ns);

  env->ReleaseShortArrayElements(samples, sample_data, JNI_ABORT);

  return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_js8call_core_JS8Engine_nativeSetFrequency(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle,
    jlong frequency_hz) {
  JS8Engine_Native* engine = reinterpret_cast<JS8Engine_Native*>(handle);
  js8_engine_set_frequency(engine, static_cast<uint64_t>(frequency_hz));
}

JNIEXPORT void JNICALL
Java_com_js8call_core_JS8Engine_nativeSetSubmodes(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle,
    jint submodes) {
  JS8Engine_Native* engine = reinterpret_cast<JS8Engine_Native*>(handle);
  js8_engine_set_submodes(engine, submodes);
}

JNIEXPORT jboolean JNICALL
Java_com_js8call_core_JS8Engine_nativeIsRunning(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle) {
  JS8Engine_Native* engine = reinterpret_cast<JS8Engine_Native*>(handle);
  return js8_engine_is_running(engine) ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
