#include <jni.h>
#include <whisper.h>
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

namespace {
struct Session {
    whisper_context *context = nullptr;
    int threads = 1;
    std::atomic<bool> cancelled{false};
    std::mutex inference;
    ~Session() { if (context) whisper_free(context); }
};
std::mutex registry_mutex;
std::mutex creation_mutex;
std::unordered_map<jlong, std::shared_ptr<Session>> sessions;
std::atomic<jlong> next_id{1};

std::shared_ptr<Session> find_session(jlong id) {
    std::lock_guard<std::mutex> guard(registry_mutex);
    const auto entry = sessions.find(id);
    return entry == sessions.end() ? nullptr : entry->second;
}
void throw_java(JNIEnv *env, const char *type, const char *message) {
    if (env->ExceptionCheck()) return;
    jclass klass = env->FindClass(type);
    if (klass) { env->ThrowNew(klass, message); env->DeleteLocalRef(klass); }
}
class UtfChars {
    JNIEnv *env_;
    jstring value_;
    const char *chars_;
public:
    UtfChars(JNIEnv *env, jstring value) : env_(env), value_(value), chars_(value ? env->GetStringUTFChars(value, nullptr) : nullptr) {}
    ~UtfChars() { if (chars_) env_->ReleaseStringUTFChars(value_, chars_); }
    const char *get() const { return chars_; }
};
bool abort_inference(void *data) { return static_cast<Session *>(data)->cancelled.load(); }
bool begin_encoder(whisper_context *, whisper_state *, void *data) { return !abort_inference(data); }
// Do not put recognized speech or model internals in logcat.
void quiet_log(ggml_log_level, const char *, void *) {}

std::string json_string(const char *text) {
    std::string result = "\"";
    const char hex[] = "0123456789abcdef";
    for (const unsigned char *p = reinterpret_cast<const unsigned char *>(text); *p; ++p) {
        if (*p == '"' || *p == '\\') { result += '\\'; result += static_cast<char>(*p); }
        else if (*p < 0x20) { result += "\\u00"; result += hex[*p >> 4]; result += hex[*p & 15]; }
        else result += static_cast<char>(*p);
    }
    return result + '"';
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_defter_app_audio_WhisperNative_version(JNIEnv *env, jobject) {
    return env->NewStringUTF(whisper_version());
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_defter_app_audio_WhisperNative_create(JNIEnv *env, jobject, jstring model_path, jint threads) {
    try {
        UtfChars path(env, model_path);
        if (!path.get()) { throw_java(env, "java/io/IOException", "Model path is missing."); return 0; }
        auto session = std::make_shared<Session>();
        session->threads = std::clamp(static_cast<int>(threads), 1, 4);
        {
            std::lock_guard<std::mutex> guard(creation_mutex);
            whisper_log_set(quiet_log, nullptr);
            auto params = whisper_context_default_params();
            params.use_gpu = false;
            params.flash_attn = false;
            session->context = whisper_init_from_file_with_params(path.get(), params);
        }
        if (!session->context) { throw_java(env, "java/io/IOException", "Whisper model could not be loaded."); return 0; }
        const auto id = next_id.fetch_add(1);
        std::lock_guard<std::mutex> guard(registry_mutex);
        sessions.emplace(id, std::move(session));
        return id;
    } catch (const std::exception &) {
        throw_java(env, "java/io/IOException", "Whisper model could not be initialized.");
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_defter_app_audio_WhisperNative_resetCancellation(JNIEnv *, jobject, jlong id) {
    if (auto session = find_session(id)) session->cancelled.store(false);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_defter_app_audio_WhisperNative_cancel(JNIEnv *, jobject, jlong id) {
    if (auto session = find_session(id)) session->cancelled.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_defter_app_audio_WhisperNative_close(JNIEnv *, jobject, jlong id) {
    std::shared_ptr<Session> removed;
    {
        std::lock_guard<std::mutex> guard(registry_mutex);
        const auto entry = sessions.find(id);
        if (entry != sessions.end()) { removed = entry->second; sessions.erase(entry); }
    }
    if (removed) removed->cancelled.store(true);
    // In-flight inference holds a shared_ptr; freeing cannot race with its abort callback.
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_defter_app_audio_WhisperNative_transcribe(JNIEnv *env, jobject, jlong id, jfloatArray audio, jstring language) {
    try {
        auto session = find_session(id);
        if (!session || !audio) throw std::runtime_error("Session is closed.");
        std::lock_guard<std::mutex> guard(session->inference);
        const auto count = env->GetArrayLength(audio);
        if (count <= 0 || count > 30 * WHISPER_SAMPLE_RATE) throw std::runtime_error("Invalid audio chunk.");
        std::vector<float> samples(static_cast<size_t>(count));
        env->GetFloatArrayRegion(audio, 0, count, samples.data());
        if (env->ExceptionCheck()) return nullptr;
        for (float &sample : samples) sample = std::isfinite(sample) ? std::clamp(sample, -1.0f, 1.0f) : 0.0f;
        UtfChars lang(env, language);
        if (!lang.get()) throw std::runtime_error("Language is missing.");
        const std::string language_code(lang.get());
        if (language_code != "tr" && language_code != "en" && language_code != "auto") throw std::runtime_error("Invalid language.");
        auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        params.n_threads = session->threads;
        params.language = lang.get();
        params.translate = false;
        params.no_context = true;
        params.print_special = false;
        params.print_progress = false;
        params.print_realtime = false;
        params.print_timestamps = false;
        params.suppress_nst = true;
        params.abort_callback = abort_inference;
        params.abort_callback_user_data = session.get();
        params.encoder_begin_callback = begin_encoder;
        params.encoder_begin_callback_user_data = session.get();
        if (session->cancelled.load()) { throw_java(env, "java/util/concurrent/CancellationException", "Transcription cancelled."); return nullptr; }
        const int status = whisper_full(session->context, params, samples.data(), count);
        if (session->cancelled.load()) { throw_java(env, "java/util/concurrent/CancellationException", "Transcription cancelled."); return nullptr; }
        if (status != 0) throw std::runtime_error("Whisper inference failed.");
        std::string json = "[";
        const auto segments = whisper_full_n_segments(session->context);
        for (int index = 0; index < segments; ++index) {
            if (index) json += ',';
            json += "{\"startMs\":" + std::to_string(10 * whisper_full_get_segment_t0(session->context, index));
            json += ",\"endMs\":" + std::to_string(10 * whisper_full_get_segment_t1(session->context, index));
            json += ",\"text\":" + json_string(whisper_full_get_segment_text(session->context, index)) + '}';
        }
        json += ']';
        auto result = env->NewByteArray(static_cast<jsize>(json.size()));
        if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(json.size()), reinterpret_cast<const jbyte *>(json.data()));
        return result;
    } catch (const std::exception &) {
        throw_java(env, "java/io/IOException", "Local transcription failed.");
        return nullptr;
    }
}
