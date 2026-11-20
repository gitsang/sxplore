// JNI bridge to whisper.cpp for on-device recognition.
// Built into libwhisper_jni.so (whisper + ggml statically linked).
#include <jni.h>
#include <string>
#include <vector>
#include <algorithm>
#include "whisper.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_sxplore_app_caption_WhisperLocal_init(JNIEnv *env, jobject /*thiz*/, jstring path) {
    const char *cpath = env->GetStringUTFChars(path, nullptr);
    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    struct whisper_context *ctx = whisper_init_from_file_with_params(cpath, cparams);
    env->ReleaseStringUTFChars(path, cpath);
    return reinterpret_cast<jlong>(ctx);
}

// Returns segments as lines: "startMs\tendMs\ttext\n" (empty string on failure).
JNIEXPORT jstring JNICALL
Java_com_sxplore_app_caption_WhisperLocal_transcribe(JNIEnv *env, jobject /*thiz*/, jlong handle,
                                                     jfloatArray pcm, jstring language, jint threads) {
    auto *ctx = reinterpret_cast<struct whisper_context *>(handle);
    if (ctx == nullptr) return env->NewStringUTF("");

    jsize n = env->GetArrayLength(pcm);
    if (n <= 0) return env->NewStringUTF("");
    std::vector<float> data(n);
    env->GetFloatArrayRegion(pcm, 0, n, data.data());

    const char *lang = env->GetStringUTFChars(language, nullptr);
    struct whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.n_threads = threads > 0 ? threads : 4;
    wparams.language = (lang && lang[0] != '\0') ? lang : "auto";
    wparams.translate = false;
    wparams.no_context = true;      // chunks are independent
    wparams.single_segment = false;
    wparams.print_progress = false;
    wparams.print_realtime = false;
    wparams.print_timestamps = false;
    wparams.suppress_blank = true;
    wparams.suppress_nst = true;

    int rc = whisper_full(ctx, wparams, data.data(), n);
    env->ReleaseStringUTFChars(language, lang);

    std::string out;
    if (rc == 0) {
        int ns = whisper_full_n_segments(ctx);
        for (int i = 0; i < ns; i++) {
            int64_t t0 = whisper_full_get_segment_t0(ctx, i) * 10; // centiseconds -> ms
            int64_t t1 = whisper_full_get_segment_t1(ctx, i) * 10;
            std::string text = whisper_full_get_segment_text(ctx, i);
            std::replace(text.begin(), text.end(), '\n', ' ');
            std::replace(text.begin(), text.end(), '\t', ' ');
            // trim
            size_t b = text.find_first_not_of(' ');
            size_t e = text.find_last_not_of(' ');
            if (b == std::string::npos) continue;
            text = text.substr(b, e - b + 1);
            if (text.empty()) continue;
            out += std::to_string(t0);
            out += '\t';
            out += std::to_string(t1);
            out += '\t';
            out += text;
            out += '\n';
        }
    }
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT void JNICALL
Java_com_sxplore_app_caption_WhisperLocal_free(JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *ctx = reinterpret_cast<struct whisper_context *>(handle);
    if (ctx != nullptr) whisper_free(ctx);
}

} // extern "C"
