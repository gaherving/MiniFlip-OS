#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <mutex>
#include <string>
#include <vector>
#include <sstream>
#include <unistd.h>
#include "llama.h"

#define TAG "AnastasiaBrain"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static std::mutex g_mutex;
static llama_model * g_model = nullptr;
static bool g_inited = false;
static std::string g_model_info = "sin modelo";

static std::string jstr(JNIEnv * env, jstring s) {
    if (!s) return {};
    const char * p = env->GetStringUTFChars(s, nullptr);
    std::string out = p ? p : "";
    if (p) env->ReleaseStringUTFChars(s, p);
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_com_anastasia_ai_NativeBrain_nativeInit(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_inited) return;
    llama_log_set([](enum ggml_log_level level, const char * text, void *) {
        if (level >= GGML_LOG_LEVEL_ERROR) LOGE("%s", text);
    }, nullptr);
    ggml_backend_load_all();
    llama_backend_init();
    g_inited = true;
    LOGI("Neural brain backend initialized");
}

extern "C" JNIEXPORT jint JNICALL
Java_com_anastasia_ai_NativeBrain_nativeLoad(JNIEnv * env, jclass, jstring path) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_inited) {
        ggml_backend_load_all();
        llama_backend_init();
        g_inited = true;
    }
    if (g_model) return 0;
    const std::string model_path = jstr(env, path);
    llama_model_params params = llama_model_default_params();
    params.n_gpu_layers = 0;
    g_model = llama_model_load_from_file(model_path.c_str(), params);
    if (!g_model) {
        g_model_info = "error al cargar modelo";
        LOGE("Could not load model: %s", model_path.c_str());
        return 1;
    }
    char desc[256] = {0};
    llama_model_desc(g_model, desc, sizeof(desc));
    std::ostringstream ss;
    ss << desc << " · " << (double)llama_model_n_params(g_model) / 1e9 << "B";
    g_model_info = ss.str();
    return 0;
}

static std::string token_piece(const llama_vocab * vocab, llama_token tok) {
    char small[256];
    int n = llama_token_to_piece(vocab, tok, small, sizeof(small), 0, true);
    if (n >= 0) return std::string(small, n);
    std::vector<char> big((size_t)(-n) + 16);
    n = llama_token_to_piece(vocab, tok, big.data(), (int32_t)big.size(), 0, true);
    return n > 0 ? std::string(big.data(), n) : std::string();
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_anastasia_ai_NativeBrain_nativeGenerate(JNIEnv * env, jclass, jstring jprompt, jint jmax, jfloat jtemp) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model) return nullptr;

    std::string prompt = jstr(env, jprompt);
    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    int n_prompt = -llama_tokenize(vocab, prompt.c_str(), (int32_t)prompt.size(), nullptr, 0, true, true);
    if (n_prompt <= 0) return nullptr;
    std::vector<llama_token> prompt_tokens((size_t)n_prompt);
    if (llama_tokenize(vocab, prompt.c_str(), (int32_t)prompt.size(), prompt_tokens.data(), n_prompt, true, true) < 0) return nullptr;

    int max_tokens = std::max(32, std::min((int)jmax, 768));
    int wanted_ctx = n_prompt + max_tokens + 64;
    int n_ctx = std::max(2048, std::min(8192, wanted_ctx));
    if (n_prompt >= n_ctx - 64) {
        int keep = n_ctx - max_tokens - 64;
        if (keep < 256) keep = 256;
        prompt_tokens.erase(prompt_tokens.begin(), prompt_tokens.end() - std::min(keep, (int)prompt_tokens.size()));
        n_prompt = (int)prompt_tokens.size();
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t)n_ctx;
    cp.n_batch = (uint32_t)std::min(n_ctx, std::max(512, n_prompt));
    cp.n_ubatch = (uint32_t)std::min(512, (int)cp.n_batch);
    int cores = (int)sysconf(_SC_NPROCESSORS_ONLN);
    int threads = std::max(4, std::min(8, cores > 2 ? cores - 2 : cores));
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    cp.no_perf = true;

    llama_context * ctx = llama_init_from_model(g_model, cp);
    if (!ctx) return nullptr;

    llama_sampler * sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(std::max(0.1f, std::min(1.2f, (float)jtemp))));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    llama_batch batch = llama_batch_get_one(prompt_tokens.data(), (int32_t)prompt_tokens.size());
    std::string response;
    response.reserve(4096);
    int generated = 0;

    while (generated < max_tokens) {
        if (llama_decode(ctx, batch) != 0) break;
        llama_token tok = llama_sampler_sample(sampler, ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;
        response += token_piece(vocab, tok);
        batch = llama_batch_get_one(&tok, 1);
        generated++;
        if (response.size() > 24000) break;
    }

    llama_sampler_free(sampler);
    llama_free(ctx);

    jbyteArray arr = env->NewByteArray((jsize)response.size());
    if (arr && !response.empty()) env->SetByteArrayRegion(arr, 0, (jsize)response.size(), (const jbyte *)response.data());
    return arr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_anastasia_ai_NativeBrain_nativeModelInfo(JNIEnv * env, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return env->NewStringUTF(g_model_info.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_anastasia_ai_NativeBrain_nativeUnload(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    g_model_info = "sin modelo";
}
