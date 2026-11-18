// JNI bridge between app.meetingrecorder.whisper.WhisperContext and the speech engine (research R-3).
//
// Deliberately thin: it loads a model, detects the language, decodes one window of samples and can be
// aborted. Everything else (windows, overlap, checkpoints, language choice) lives in :core and :app.
//
//  * Samples are read from a direct FloatBuffer without copying.
//  * Strings cross the boundary as UTF-8 byte arrays. The engine may split a multi-byte character
//    between two segments, and NewStringUTF() must never see such a fragment.
//  * No transcript text is ever logged. The engine's own log goes to logcat at warning level and above.

#include <android/log.h>
#include <jni.h>

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#include "ggml-backend.h"
#include "ggml.h"
#include "whisper.h"

#ifndef WHISPER_ENGINE_VERSION
#error "WHISPER_ENGINE_VERSION must be defined by CMakeLists.txt"
#endif
#ifndef WHISPER_ENGINE_DYNAMIC_BACKENDS
#error "WHISPER_ENGINE_DYNAMIC_BACKENDS must be defined by CMakeLists.txt"
#endif

namespace {

constexpr const char *kTag = "WhisperJni";
constexpr const char *kExceptionClass = "app/meetingrecorder/whisper/WhisperException";

// Error codes, mirrored by WhisperException.Companion in Kotlin.
constexpr jint kErrorLoad = 1;
constexpr jint kErrorArgument = 2;
constexpr jint kErrorMel = 3;
constexpr jint kErrorDetect = 4;
constexpr jint kErrorDecode = 5;
constexpr jint kErrorBackend = 6;

struct Handle {
    whisper_context *ctx = nullptr;
    std::atomic<bool> aborted{false};
};

void throwError(JNIEnv *env, jint code, const char *message) {
    if (env->ExceptionCheck()) return;  // keep the exception that is already pending
    jclass cls = env->FindClass(kExceptionClass);
    if (cls == nullptr) return;  // NoClassDefFoundError is pending
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(ILjava/lang/String;)V");
    if (ctor == nullptr) return;
    jstring text = env->NewStringUTF(message);
    jobject ex = env->NewObject(cls, ctor, code, text);
    if (ex != nullptr) env->Throw(static_cast<jthrowable>(ex));
    env->DeleteLocalRef(cls);
}

std::string toStdString(JNIEnv *env, jbyteArray bytes) {
    if (bytes == nullptr) return {};
    const jsize length = env->GetArrayLength(bytes);
    std::string out(static_cast<size_t>(length), '\0');
    if (length > 0) {
        env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte *>(&out[0]));
    }
    return out;
}

void engineLog(ggml_log_level level, const char *text, void * /*user*/) {
    int priority;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: priority = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN: priority = ANDROID_LOG_WARN; break;
        default: return;  // info and debug are dropped; the engine prints decoded text at info level
    }
    __android_log_write(priority, "whisper.cpp", text);
}

std::once_flag gInitOnce;

void initProcess(const std::string &nativeLibraryDir) {
    whisper_log_set(engineLog, nullptr);
#if WHISPER_ENGINE_DYNAMIC_BACKENDS
    // arm64-v8a: the CPU backends are separate libraries; load the best one this CPU supports.
    ggml_backend_load_all_from_path(nativeLibraryDir.c_str());
#else
    (void)nativeLibraryDir;
#endif
}

float *directSamples(JNIEnv *env, jobject buffer, jint count) {
    if (buffer == nullptr || count <= 0) {
        throwError(env, kErrorArgument, "no samples");
        return nullptr;
    }
    void *address = env->GetDirectBufferAddress(buffer);
    // For a FloatBuffer the JVM reports the capacity in floats, not bytes (a direct ByteBuffer view keeps its element
    // count). The Kotlin side has already checked count against the buffer's own capacity.
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (address == nullptr || capacity < static_cast<jlong>(count)) {
        throwError(env, kErrorArgument, "samples must be a direct buffer holding count floats");
        return nullptr;
    }
    return static_cast<float *>(address);
}

Handle *fromLong(JNIEnv *env, jlong handle) {
    if (handle == 0) {
        throwError(env, kErrorArgument, "context is closed");
        return nullptr;
    }
    return reinterpret_cast<Handle *>(handle);
}

struct CallbackState {
    JNIEnv *env;
    jobject callback;
    jmethodID method;
    Handle *handle;
};

void onNewSegment(whisper_context * /*ctx*/, whisper_state *state, int newCount, void *userData) {
    auto *cb = static_cast<CallbackState *>(userData);
    JNIEnv *env = cb->env;
    const int total = whisper_full_n_segments_from_state(state);
    for (int i = total - newCount; i < total; ++i) {
        if (i < 0) continue;
        const char *text = whisper_full_get_segment_text_from_state(state, i);
        const size_t length = text == nullptr ? 0 : std::char_traits<char>::length(text);
        jbyteArray bytes = env->NewByteArray(static_cast<jsize>(length));
        if (bytes == nullptr) {  // OutOfMemoryError is pending
            cb->handle->aborted.store(true);
            return;
        }
        if (length > 0) {
            env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(length), reinterpret_cast<const jbyte *>(text));
        }
        env->CallVoidMethod(cb->callback, cb->method,
                            static_cast<jlong>(whisper_full_get_segment_t0_from_state(state, i)),
                            static_cast<jlong>(whisper_full_get_segment_t1_from_state(state, i)), bytes);
        env->DeleteLocalRef(bytes);
        if (env->ExceptionCheck()) {  // the Kotlin callback threw: stop decoding, rethrow afterwards
            cb->handle->aborted.store(true);
            return;
        }
    }
}

bool abortRequested(void *userData) {
    return static_cast<Handle *>(userData)->aborted.load(std::memory_order_relaxed);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_app_meetingrecorder_whisper_WhisperContext_nativeInit(
    JNIEnv *env, jclass, jbyteArray modelPath, jbyteArray nativeLibraryDir, jboolean flashAttn) {
    const std::string path = toStdString(env, modelPath);
    const std::string libraryDir = toStdString(env, nativeLibraryDir);
    std::call_once(gInitOnce, initProcess, libraryDir);
    if (ggml_backend_dev_count() == 0) {
        throwError(env, kErrorBackend, "no compute backend available");
        return 0;
    }
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    params.flash_attn = flashAttn == JNI_TRUE;
    whisper_context *ctx = whisper_init_from_file_with_params(path.c_str(), params);
    if (ctx == nullptr) {
        throwError(env, kErrorLoad, "the model could not be loaded");
        return 0;
    }
    auto *handle = new Handle();
    handle->ctx = ctx;
    return reinterpret_cast<jlong>(handle);
}

JNIEXPORT jobjectArray JNICALL Java_app_meetingrecorder_whisper_WhisperContext_nativeLanguageCodes(JNIEnv *env,
                                                                                                    jclass) {
    const int count = whisper_lang_max_id() + 1;
    jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass == nullptr) return nullptr;
    jobjectArray result = env->NewObjectArray(count, stringClass, nullptr);
    if (result == nullptr) return nullptr;
    for (int id = 0; id < count; ++id) {
        const char *code = whisper_lang_str(id);
        jstring text = env->NewStringUTF(code == nullptr ? "" : code);
        env->SetObjectArrayElement(result, id, text);
        env->DeleteLocalRef(text);
    }
    return result;
}

JNIEXPORT jfloatArray JNICALL Java_app_meetingrecorder_whisper_WhisperContext_nativeDetectLanguage(
    JNIEnv *env, jclass, jlong handlePtr, jobject samples, jint count, jint threads) {
    Handle *handle = fromLong(env, handlePtr);
    if (handle == nullptr) return nullptr;
    const float *pcm = directSamples(env, samples, count);
    if (pcm == nullptr) return nullptr;
    if (whisper_pcm_to_mel(handle->ctx, pcm, count, threads) != 0) {
        throwError(env, kErrorMel, "the spectrogram could not be computed");
        return nullptr;
    }
    std::vector<float> probabilities(static_cast<size_t>(whisper_lang_max_id() + 1), 0.0f);
    if (whisper_lang_auto_detect(handle->ctx, 0, threads, probabilities.data()) < 0) {
        throwError(env, kErrorDetect, "the language could not be detected");
        return nullptr;
    }
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(probabilities.size()));
    if (result == nullptr) return nullptr;
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(probabilities.size()), probabilities.data());
    return result;
}

// Returns 0 when the window was decoded completely and 1 when it was aborted.
JNIEXPORT jint JNICALL Java_app_meetingrecorder_whisper_WhisperContext_nativeDecode(
    JNIEnv *env, jclass, jlong handlePtr, jobject samples, jint count, jbyteArray language, jint threads,
    jbyteArray initialPrompt, jboolean suppressNst, jbyteArray vadModelPath, jobject callback) {
    Handle *handle = fromLong(env, handlePtr);
    if (handle == nullptr) return 0;
    const float *pcm = directSamples(env, samples, count);
    if (pcm == nullptr) return 0;
    if (callback == nullptr) {
        throwError(env, kErrorArgument, "no segment callback");
        return 0;
    }
    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID method = env->GetMethodID(callbackClass, "onSegment", "(JJ[B)V");
    env->DeleteLocalRef(callbackClass);
    if (method == nullptr) return 0;  // NoSuchMethodError is pending

    const std::string languageCode = toStdString(env, language);
    const std::string prompt = toStdString(env, initialPrompt);
    const std::string vadPath = toStdString(env, vadModelPath);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.language = languageCode.c_str();
    params.detect_language = false;
    params.translate = false;
    params.no_context = true;
    params.no_timestamps = false;
    params.single_segment = false;
    params.n_threads = threads;
    params.suppress_nst = suppressNst == JNI_TRUE;
    params.initial_prompt = prompt.empty() ? nullptr : prompt.c_str();
    params.print_special = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    if (!vadPath.empty()) {
        params.vad = true;
        params.vad_model_path = vadPath.c_str();
    }

    CallbackState state{env, callback, method, handle};
    params.new_segment_callback = onNewSegment;
    params.new_segment_callback_user_data = &state;
    params.abort_callback = abortRequested;
    params.abort_callback_user_data = handle;

    const int status = whisper_full(handle->ctx, params, pcm, count);
    if (env->ExceptionCheck()) return 1;  // the Kotlin callback threw
    if (handle->aborted.load()) return 1;
    if (status != 0) {
        throwError(env, kErrorDecode, "decoding failed");
        return 0;
    }
    return 0;
}

JNIEXPORT void JNICALL Java_app_meetingrecorder_whisper_WhisperContext_nativeAbort(JNIEnv *, jclass,
                                                                                    jlong handlePtr) {
    if (handlePtr != 0) reinterpret_cast<Handle *>(handlePtr)->aborted.store(true);
}

JNIEXPORT void JNICALL Java_app_meetingrecorder_whisper_WhisperContext_nativeFree(JNIEnv *, jclass,
                                                                                   jlong handlePtr) {
    if (handlePtr == 0) return;
    auto *handle = reinterpret_cast<Handle *>(handlePtr);
    whisper_free(handle->ctx);
    delete handle;
}

JNIEXPORT jstring JNICALL Java_app_meetingrecorder_whisper_WhisperContext_nativeEngineVersion(JNIEnv *env,
                                                                                               jclass) {
    return env->NewStringUTF(WHISPER_ENGINE_VERSION);
}

}  // extern "C"
