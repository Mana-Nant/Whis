// -----------------------------------------------------------------------------
// whisper_jni.cpp
//
// Kotlin ↔ whisper.cpp のJNIブリッジ。
// メモリ節約のため use_mmap=true, language="ja" 固定、timestamps off。
// 進捗は JNI 経由で Kotlin 側の BridgeListener.onProgress に通知する。
// -----------------------------------------------------------------------------

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <fstream>
#include <cstring>
#include <cstdint>
#include <memory>

#include "whisper.h"

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// 16-bit PCM WAV を float32 モノラルに変換して読み込む（ffmpegで事前に 16kHz/mono/pcm_s16le に変換済み想定）
static bool read_wav_pcm16_mono_16k(const std::string& path, std::vector<float>& out_pcm) {
    std::ifstream f(path, std::ios::binary);
    if (!f.is_open()) {
        LOGE("failed to open wav: %s", path.c_str());
        return false;
    }

    // 最初の44バイトが標準 WAV ヘッダ。厳密にRIFFをパースするのが理想だがここでは簡略化。
    char header[44];
    f.read(header, 44);
    if (f.gcount() != 44 || std::memcmp(header, "RIFF", 4) != 0) {
        LOGE("not a RIFF wav");
        return false;
    }

    // data チャンクまでスキップ（extra chunkがある場合の保険）
    uint32_t data_size = 0;
    f.seekg(12, std::ios::beg);
    char chunk_id[4];
    while (f.read(chunk_id, 4)) {
        uint32_t chunk_sz = 0;
        f.read(reinterpret_cast<char*>(&chunk_sz), 4);
        if (std::memcmp(chunk_id, "data", 4) == 0) {
            data_size = chunk_sz;
            break;
        }
        f.seekg(chunk_sz, std::ios::cur);
    }
    if (data_size == 0) {
        LOGE("no data chunk");
        return false;
    }

    const size_t n_samples = data_size / sizeof(int16_t);
    std::vector<int16_t> pcm16(n_samples);
    f.read(reinterpret_cast<char*>(pcm16.data()), data_size);

    out_pcm.resize(n_samples);
    for (size_t i = 0; i < n_samples; ++i) {
        out_pcm[i] = pcm16[i] / 32768.0f;
    }
    return true;
}

// 進捗コールバック用ユーザーデータ
struct ProgressCtx {
    JavaVM* jvm;
    jobject listener;   // global ref to BridgeListener
    jmethodID on_progress;
};

static void progress_callback(struct whisper_context* /*ctx*/, struct whisper_state* /*state*/,
                              int progress, void* user_data) {
    auto* pc = static_cast<ProgressCtx*>(user_data);
    if (!pc || !pc->listener) return;

    JNIEnv* env = nullptr;
    bool attached = false;
    if (pc->jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (pc->jvm->AttachCurrentThread(&env, nullptr) == 0) attached = true;
    }
    if (env) {
        env->CallVoidMethod(pc->listener, pc->on_progress, progress);
    }
    if (attached) pc->jvm->DetachCurrentThread();
}

// -----------------------------------------------------------------------------
// JNI
// -----------------------------------------------------------------------------

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_whisperandroid_whisper_WhisperBridge_nativeInitContext(
        JNIEnv* env, jobject /*thiz*/, jstring jModelPath, jboolean useMmap) {

    const char* c_path = env->GetStringUTFChars(jModelPath, nullptr);
    std::string path(c_path);
    env->ReleaseStringUTFChars(jModelPath, c_path);

    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    // whisper.cpp 最新の use_mmap は cparams には直接無い場合がある。
    // whisper_init_from_file_with_params が mmap 対応。
    // ここではファイルロードAPIを使用。
    auto* ctx = whisper_init_from_file_with_params(path.c_str(), cparams);
    if (!ctx) {
        LOGE("whisper_init_from_file failed: %s", path.c_str());
        return 0;
    }
    LOGI("whisper context initialized (useMmap=%d)", useMmap);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_whisperandroid_whisper_WhisperBridge_nativeFreeContext(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong ctxPtr) {
    if (ctxPtr != 0) {
        whisper_free(reinterpret_cast<whisper_context*>(ctxPtr));
        LOGI("whisper context freed");
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_whisperandroid_whisper_WhisperBridge_nativeTranscribe(
        JNIEnv* env, jobject /*thiz*/,
        jlong ctxPtr, jstring jWavPath, jint nThreads,
        jobject listener) {

    auto* ctx = reinterpret_cast<whisper_context*>(ctxPtr);
    if (!ctx) {
        return env->NewStringUTF("");
    }

    const char* c_wav = env->GetStringUTFChars(jWavPath, nullptr);
    std::string wav_path(c_wav);
    env->ReleaseStringUTFChars(jWavPath, c_wav);

    std::vector<float> pcm;
    if (!read_wav_pcm16_mono_16k(wav_path, pcm)) {
        return env->NewStringUTF("");
    }
    LOGI("loaded %zu samples (%.2f sec)", pcm.size(), pcm.size() / 16000.0f);

    // 進捗コールバック設定
    ProgressCtx pc{};
    env->GetJavaVM(&pc.jvm);
    if (listener) {
        pc.listener = env->NewGlobalRef(listener);
        jclass cls = env->GetObjectClass(listener);
        pc.on_progress = env->GetMethodID(cls, "onProgress", "(I)V");
    }

    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.n_threads         = nThreads > 0 ? nThreads : 4;
    wparams.language          = "ja";
    wparams.translate         = false;
    wparams.print_progress    = false;
    wparams.print_special     = false;
    wparams.print_realtime    = false;
    wparams.print_timestamps  = false;
    wparams.no_context        = true;
    wparams.suppress_blank    = true;
    wparams.single_segment    = false;

    if (listener) {
        wparams.progress_callback           = progress_callback;
        wparams.progress_callback_user_data = &pc;
    }

    int ret = whisper_full(ctx, wparams, pcm.data(), static_cast<int>(pcm.size()));

    // 結果回収
    std::string result;
    if (ret == 0) {
        const int n = whisper_full_n_segments(ctx);
        for (int i = 0; i < n; ++i) {
            const char* seg = whisper_full_get_segment_text(ctx, i);
            if (seg) {
                result += seg;
                // 日本語は改行を段落区切り扱いとして抑制する。ここでは空白のみ追加。
                // 必要に応じて句点検知などで改行挿入を拡張可。
            }
        }
    } else {
        LOGE("whisper_full failed: %d", ret);
    }

    if (pc.listener) env->DeleteGlobalRef(pc.listener);

    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_whisperandroid_whisper_WhisperBridge_nativeSystemInfo(
        JNIEnv* env, jobject /*thiz*/) {
    const char* info = whisper_print_system_info();
    return env->NewStringUTF(info ? info : "");
}
