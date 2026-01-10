#include <jni.h>

#include <chrono>
#include <condition_variable>
#include <cstdarg>
#include <cstdio>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

#if defined(__ANDROID__)
#include <android/log.h>
#endif

#include "hamlib/rig.h"

namespace {

struct HamlibRigHandle {
  RIG* rig = nullptr;
  std::mutex mutex;
};

struct RigOpenContext {
  RIG* rig = nullptr;
  std::mutex mutex;
  std::condition_variable cv;
  int result = RIG_OK;
  bool done = false;
  bool timed_out = false;
};

constexpr auto kRigOpenTimeout = std::chrono::milliseconds(5000);

int hamlib_debug_callback(enum rig_debug_level_e level,
                          rig_ptr_t /*arg*/,
                          const char* format,
                          va_list ap) {
#if defined(__ANDROID__)
  if (!format) {
    return 0;
  }
  int priority = ANDROID_LOG_INFO;
  switch (level) {
    case RIG_DEBUG_BUG:
    case RIG_DEBUG_ERR:
      priority = ANDROID_LOG_ERROR;
      break;
    case RIG_DEBUG_WARN:
      priority = ANDROID_LOG_WARN;
      break;
    case RIG_DEBUG_VERBOSE:
      priority = ANDROID_LOG_VERBOSE;
      break;
    case RIG_DEBUG_TRACE:
      priority = ANDROID_LOG_DEBUG;
      break;
    case RIG_DEBUG_CACHE:
    case RIG_DEBUG_NONE:
    default:
      priority = ANDROID_LOG_INFO;
      break;
  }
  __android_log_vprint(priority, "HamlibDebug", format, ap);
  return 0;
#else
  (void)level;
  (void)format;
  (void)ap;
  return 0;
#endif
}

std::once_flag g_hamlib_loaded;
std::mutex g_last_error_mutex;
std::string g_last_error;

void ensure_hamlib_loaded() {
  rig_set_debug_callback(hamlib_debug_callback, nullptr);
  rig_set_debug_level(RIG_DEBUG_TRACE);
  rig_load_all_backends();
}

void set_last_error(const std::string& message) {
  std::lock_guard<std::mutex> lock(g_last_error_mutex);
  g_last_error = message;
}

std::string get_last_error() {
  std::lock_guard<std::mutex> lock(g_last_error_mutex);
  return g_last_error;
}

void log_error(const char* prefix, const char* detail) {
  std::string message = prefix ? prefix : "";
  if (detail && *detail) {
    message += detail;
  }
  set_last_error(message);
#if defined(__ANDROID__)
  __android_log_print(ANDROID_LOG_WARN, "HamlibRigControl", "%s", message.c_str());
#else
  (void)message;
#endif
}

void log_info(const char* fmt, ...) {
#if defined(__ANDROID__)
  if (!fmt) {
    return;
  }
  va_list args;
  va_start(args, fmt);
  __android_log_vprint(ANDROID_LOG_INFO, "HamlibRigControl", fmt, args);
  va_end(args);
#else
  (void)fmt;
#endif
}

enum serial_parity_e parse_parity(const std::string& parity) {
  if (parity == "odd") return RIG_PARITY_ODD;
  if (parity == "even") return RIG_PARITY_EVEN;
  return RIG_PARITY_NONE;
}

rmode_t parse_mode(const std::string& mode_str) {
  if (mode_str == "USB") return RIG_MODE_USB;
  if (mode_str == "PKTUSB") return RIG_MODE_PKTUSB;
  if (mode_str == "LSB") return RIG_MODE_LSB;
  if (mode_str == "CW") return RIG_MODE_CW;
  if (mode_str == "AM") return RIG_MODE_AM;
  if (mode_str == "FM") return RIG_MODE_FM;
  return RIG_MODE_NONE;
}

jlong open_rig_with_path(int rig_model,
                         const std::string& path,
                         int baud_rate,
                         int data_bits,
                         int stop_bits,
                         const std::string& parity_value) {
  std::call_once(g_hamlib_loaded, ensure_hamlib_loaded);

  log_info("nativeOpen: model=%d path=%s baud=%d data=%d stop=%d",
           rig_model, path.c_str(), baud_rate, data_bits, stop_bits);

  if (rig_model <= 0) {
    log_error("Invalid rig model: ", "model <= 0");
    return 0;
  }

  RIG* rig = rig_init(static_cast<rig_model_t>(rig_model));
  if (!rig) {
    log_error("rig_init failed: ", "null rig");
    return 0;
  }
  log_info("rig_init ok: model=%d", rig_model);

  if (!rig->state.priv && rig->caps && rig->caps->rig_init) {
    int init_ret = rig->caps->rig_init(rig);
    if (init_ret != RIG_OK) {
      rig_cleanup(rig);
      log_error("rig_init (retry) failed: ", rigerror(init_ret));
      return 0;
    }
  }

  if (!rig->state.priv) {
    const char* model = rig->caps && rig->caps->model_name ? rig->caps->model_name : "unknown";
    rig_cleanup(rig);
    log_error("rig_init missing priv for model: ", model);
    return 0;
  }

  token_t path_token = rig_token_lookup(rig, "rig_pathname");
  if (path_token == 0) {
    rig_cleanup(rig);
    log_error("rig_pathname token lookup failed: ", path.c_str());
    return 0;
  }

  int ret = rig_set_conf(rig, path_token, path.c_str());
  if (ret != RIG_OK) {
    rig_cleanup(rig);
    log_error("rig_set_conf failed: ", rigerror(ret));
    return 0;
  }
  log_info("rig_pathname=%s", path.c_str());

  hamlib_port_t* port = &rig->state.rigport;
  if (port) {
    port->type.rig = RIG_PORT_SERIAL;
    port->parm.serial.rate = baud_rate > 0 ? baud_rate : 9600;
    port->parm.serial.data_bits = (data_bits == 7 || data_bits == 8) ? data_bits : 8;
    port->parm.serial.stop_bits = stop_bits == 2 ? 2 : 1;
    port->parm.serial.handshake = RIG_HANDSHAKE_NONE;
    port->parm.serial.parity = parse_parity(parity_value);
    log_info("serial params: baud=%d data=%d stop=%d parity=%s",
             port->parm.serial.rate,
             port->parm.serial.data_bits,
             port->parm.serial.stop_bits,
             parity_value.c_str());
  }

  log_info("rig_open starting");
  auto open_ctx = std::make_shared<RigOpenContext>();
  open_ctx->rig = rig;

  std::thread open_thread([open_ctx]() {
    int open_ret = rig_open(open_ctx->rig);
    {
      std::lock_guard<std::mutex> lock(open_ctx->mutex);
      open_ctx->result = open_ret;
      open_ctx->done = true;
    }
    open_ctx->cv.notify_one();

    if (open_ctx->timed_out) {
      if (open_ret == RIG_OK) {
        rig_close(open_ctx->rig);
      }
      rig_cleanup(open_ctx->rig);
    }
  });

  {
    std::unique_lock<std::mutex> lock(open_ctx->mutex);
    if (!open_ctx->cv.wait_for(lock, kRigOpenTimeout, [&open_ctx]() { return open_ctx->done; })) {
      open_ctx->timed_out = true;
      lock.unlock();
      log_error("rig_open timed out: ", "no response");
      open_thread.detach();
      return 0;
    }
    ret = open_ctx->result;
  }

  open_thread.join();

  if (ret != RIG_OK) {
    rig_cleanup(rig);
    log_error("rig_open failed: ", rigerror(ret));
    return 0;
  }
  log_info("rig_open ok");

  int transceive_ret = rig_set_func(rig, RIG_VFO_CURR, RIG_FUNC_TRANSCEIVE, 0);
  if (transceive_ret != RIG_OK && transceive_ret != -RIG_ENIMPL &&
      transceive_ret != -RIG_ENAVAIL) {
#if defined(__ANDROID__)
    __android_log_print(ANDROID_LOG_WARN, "HamlibRigControl",
                        "rig_set_func TRANSCEIVE failed: %s",
                        rigerror2(transceive_ret));
#endif
  }

  set_last_error("");

  auto* handle = new HamlibRigHandle();
  handle->rig = rig;
  return reinterpret_cast<jlong>(handle);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_js8call_core_HamlibRigControl_nativeOpen(JNIEnv* env,
                                                  jobject /*thiz*/,
                                                  jint rig_model,
                                                  jint device_id,
                                                  jint port_index,
                                                  jint baud_rate,
                                                  jint data_bits,
                                                  jint stop_bits,
                                                  jstring parity) {
  char path[64];
  snprintf(path, sizeof(path), "android-usb:%d:%d", device_id, port_index);

  const char* parity_str = env->GetStringUTFChars(parity, nullptr);
  std::string parity_value = parity_str ? parity_str : "";
  if (parity_str) {
    env->ReleaseStringUTFChars(parity, parity_str);
  }

  return open_rig_with_path(rig_model, path, baud_rate, data_bits, stop_bits, parity_value);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_js8call_core_HamlibRigControl_nativeOpenWithPath(JNIEnv* env,
                                                          jobject /*thiz*/,
                                                          jint rig_model,
                                                          jstring serial_path,
                                                          jint baud_rate,
                                                          jint data_bits,
                                                          jint stop_bits,
                                                          jstring parity) {
  std::string path;
  if (serial_path) {
    const char* path_str = env->GetStringUTFChars(serial_path, nullptr);
    if (path_str) {
      path = path_str;
      env->ReleaseStringUTFChars(serial_path, path_str);
    }
  }
  if (path.empty()) {
    log_error("Invalid serial path: ", "empty");
    return 0;
  }

  const char* parity_str = env->GetStringUTFChars(parity, nullptr);
  std::string parity_value = parity_str ? parity_str : "";
  if (parity_str) {
    env->ReleaseStringUTFChars(parity, parity_str);
  }

  return open_rig_with_path(rig_model, path, baud_rate, data_bits, stop_bits, parity_value);
}

extern "C" JNIEXPORT void JNICALL
Java_com_js8call_core_HamlibRigControl_nativeClose(JNIEnv* /*env*/,
                                                   jobject /*thiz*/,
                                                   jlong handle_value) {
  if (!handle_value) return;
  auto* handle = reinterpret_cast<HamlibRigHandle*>(handle_value);
  std::lock_guard<std::mutex> lock(handle->mutex);
  if (handle->rig) {
    rig_close(handle->rig);
    rig_cleanup(handle->rig);
    handle->rig = nullptr;
  }
  delete handle;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_js8call_core_HamlibRigControl_nativeSetFrequency(JNIEnv* /*env*/,
                                                          jobject /*thiz*/,
                                                          jlong handle_value,
                                                          jlong frequency_hz) {
  if (!handle_value || frequency_hz <= 0) return JNI_FALSE;
  auto* handle = reinterpret_cast<HamlibRigHandle*>(handle_value);
  std::lock_guard<std::mutex> lock(handle->mutex);
  if (!handle->rig) return JNI_FALSE;
  rig_flush(&handle->rig->state.rigport);
  int ret = rig_set_freq(handle->rig, RIG_VFO_CURR, static_cast<freq_t>(frequency_hz));
  if (ret != RIG_OK) {
    log_error("rig_set_freq failed: ", rigerror(ret));
  }
  return ret == RIG_OK ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_js8call_core_HamlibRigControl_nativeSetPtt(JNIEnv* /*env*/,
                                                    jobject /*thiz*/,
                                                    jlong handle_value,
                                                    jboolean enabled) {
  if (!handle_value) return JNI_FALSE;
  auto* handle = reinterpret_cast<HamlibRigHandle*>(handle_value);
  std::lock_guard<std::mutex> lock(handle->mutex);
  if (!handle->rig) return JNI_FALSE;
  int ret = rig_set_ptt(handle->rig, RIG_VFO_CURR,
                        enabled == JNI_TRUE ? RIG_PTT_ON : RIG_PTT_OFF);
  if (ret != RIG_OK) {
    log_error("rig_set_ptt failed: ", rigerror(ret));
  }
  return ret == RIG_OK ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_js8call_core_HamlibRigControl_nativeGetLastError(JNIEnv* env,
                                                          jobject /*thiz*/) {
  std::string message = get_last_error();
  return env->NewStringUTF(message.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_js8call_core_HamlibRigControl_nativeSetMode(JNIEnv* env,
                                                    jobject /*thiz*/,
                                                    jlong handle_value,
                                                    jstring mode,
                                                    jint passband) {
  if (!handle_value || !mode) return JNI_FALSE;
  auto* handle = reinterpret_cast<HamlibRigHandle*>(handle_value);
  std::lock_guard<std::mutex> lock(handle->mutex);
  if (!handle->rig) return JNI_FALSE;

  const char* mode_cstr = env->GetStringUTFChars(mode, nullptr);
  std::string mode_str = mode_cstr ? mode_cstr : "";
  if (mode_cstr) {
    env->ReleaseStringUTFChars(mode, mode_cstr);
  }

  rmode_t rmode = parse_mode(mode_str);
  if (rmode == RIG_MODE_NONE) {
      // Just log warning, don't fail, maybe? No, fail.
      log_error("Invalid mode or not supported in mapping: ", mode_str.c_str());
      return JNI_FALSE;
  }

  pbwidth_t width = static_cast<pbwidth_t>(passband);

  rig_flush(&handle->rig->state.rigport);
  int ret = rig_set_mode(handle->rig, RIG_VFO_CURR, rmode, width);
  if (ret != RIG_OK) {
    log_error("rig_set_mode failed: ", rigerror(ret));
  }
  return ret == RIG_OK ? JNI_TRUE : JNI_FALSE;
}
