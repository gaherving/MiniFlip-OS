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
static llama_context * g_ctx = nullptr;
static llama_sampler * g_sampler = nullptr;
static bool g_inited = false;
static std::string g_model_info = "sin modelo";
static std::vector<llama_token> g_cached_tokens;
static int32_t g_pos = 0;
static int32_t g_remaining = 0;
static const int32_t G_CTX = 8192;
static const int32_t G_BATCH = 512;

static std::string jstr(JNIEnv * env, jstring s) {
    if (!s) return {};
    const char * p = env->GetStringUTFChars(s, nullptr);
    std::string out = p ? p : "";
    if (p) env->ReleaseStringUTFChars(s, p);
    return out;
}

static std::string token_piece(const llama_vocab * vocab, llama_token tok) {
    char small[256];
    int n = llama_token_to_piece(vocab, tok, small, sizeof(small), 0, true);
    if (n >= 0) return std::string(small, n);
    std::vector<char> big((size_t)(-n) + 16);
    n = llama_token_to_piece(vocab, tok, big.data(), (int32_t)big.size(), 0, true);
    return n > 0 ? std::string(big.data(), n) : std::string();
}

static std::vector<llama_token> tokenize(const std::string & text) {
    std::vector<llama_token> out;
    if (!g_model || text.empty()) return out;
    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    int n = -llama_tokenize(vocab, text.c_str(), (int32_t)text.size(), nullptr, 0, true, true);
    if (n <= 0) return out;
    out.resize((size_t)n);
    if (llama_tokenize(vocab, text.c_str(), (int32_t)text.size(), out.data(), n, true, true) < 0) out.clear();
    return out;
}

static bool decode_range(const std::vector<llama_token> & toks, int start_index, int start_pos) {
    if (!g_ctx) return false;
    int total = (int)toks.size();
    for (int i = start_index; i < total; i += G_BATCH) {
        int n = std::min(G_BATCH, total - i);
        llama_batch batch = llama_batch_init(n, 0, 1);
        batch.n_tokens = n;
        for (int j = 0; j < n; ++j) {
            batch.token[j] = toks[(size_t)i + j];
            batch.pos[j] = start_pos + (i - start_index) + j;
            batch.n_seq_id[j] = 1;
            batch.seq_id[j][0] = 0;
            batch.logits[j] = (i + j == total - 1) ? 1 : 0;
        }
        int rc = llama_decode(g_ctx, batch);
        llama_batch_free(batch);
        if (rc != 0) {
            LOGE("decode failed: %d", rc);
            return false;
        }
    }
    return true;
}

static void rebuild_sampler(float temp) {
    if (g_sampler) {
        llama_sampler_free(g_sampler);
        g_sampler = nullptr;
    }
    g_sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(g_sampler, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(std::max(0.1f, std::min(1.2f, temp))));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
}

static void clear_session() {
    if (g_ctx) llama_memory_clear(llama_get_memory(g_ctx), true);
    g_cached_tokens.clear();
    g_pos = 0;
    g_remaining = 0;
    if (g_sampler) llama_sampler_reset(g_sampler);
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
    LOGI("Neural backend initialized");
}

extern "C" JNIEXPORT jint JNICALL
Java_com_anastasia_ai_NativeBrain_nativeLoad(JNIEnv * env, jclass, jstring path) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_inited) {
        ggml_backend_load_all();
        llama_backend_init();
        g_inited = true;
    }
    if (g_model && g_ctx) return 0;

    const std::string model_path = jstr(env, path);
    llama_model_params params = llama_model_default_params();
    params.n_gpu_layers = 0;
    g_model = llama_model_load_from_file(model_path.c_str(), params);
    if (!g_model) {
        g_model_info = "error al cargar modelo";
        return 1;
    }

    int cores = (int)sysconf(_SC_NPROCESSORS_ONLN);
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = G_CTX;
    cp.n_batch = G_BATCH;
    cp.n_ubatch = G_BATCH;
    cp.n_threads = std::max(2, std::min(4, cores > 2 ? cores - 2 : cores));
    cp.n_threads_batch = std::max(cp.n_threads, std::min(8, std::max(2, cores - 1)));
    cp.no_perf = true;
    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) {
        llama_model_free(g_model);
        g_model = nullptr;
        g_model_info = "error al crear contexto";
        return 2;
    }

    rebuild_sampler(0.65f);

    char desc[256] = {0};
    llama_model_desc(g_model, desc, sizeof(desc));
    std::ostringstream ss;
    ss << desc << " · " << (double)llama_model_n_params(g_model) / 1e9 << "B · KV persistente";
    g_model_info = ss.str();
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_anastasia_ai_NativeBrain_nativePrepare(JNIEnv * env, jclass, jstring jprompt, jint jmax, jfloat jtemp) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model || !g_ctx) return 1;

    std::string prompt = jstr(env, jprompt);
    std::vector<llama_token> prompt_tokens = tokenize(prompt);
    if (prompt_tokens.empty()) return 2;

    int max_tokens = std::max(24, std::min((int)jmax, 768));
    if ((int)prompt_tokens.size() + max_tokens + 32 > G_CTX) {
        int keep = std::max(512, G_CTX - max_tokens - 32);
        if ((int)prompt_tokens.size() > keep) {
            prompt_tokens.erase(prompt_tokens.begin(), prompt_tokens.end() - keep);
            clear_session();
        }
    }

    size_t common = 0;
    size_t max_common = std::min(prompt_tokens.size(), g_cached_tokens.size());
    while (common < max_common && prompt_tokens[common] == g_cached_tokens[common]) ++common;

    if (common == 0) {
        clear_session();
    } else if (common < g_cached_tokens.size()) {
        bool ok = llama_memory_seq_rm(llama_get_memory(g_ctx), 0, (llama_pos)common, -1);
        if (!ok) {
            clear_session();
            common = 0;
        } else {
            g_cached_tokens.resize(common);
            g_pos = (int32_t)common;
        }
    } else {
        g_pos = (int32_t)common;
    }

    if (common < prompt_tokens.size()) {
        if (!decode_range(prompt_tokens, (int)common, g_pos)) {
            clear_session();
            if (!decode_range(prompt_tokens, 0, 0)) return 3;
            common = 0;
        }
    }

    g_cached_tokens = prompt_tokens;
    g_pos = (int32_t)prompt_tokens.size();
    g_remaining = max_tokens;
    rebuild_sampler((float)jtemp);
    return 0;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_anastasia_ai_NativeBrain_nativeNext(JNIEnv * env, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model || !g_ctx || !g_sampler || g_remaining <= 0) return nullptr;

    llama_token tok = llama_sampler_sample(g_sampler, g_ctx, -1);
    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    if (llama_vocab_is_eog(vocab, tok)) {
        g_remaining = 0;
        return nullptr;
    }

    llama_sampler_accept(g_sampler, tok);
    llama_batch batch = llama_batch_init(1, 0, 1);
    batch.n_tokens = 1;
    batch.token[0] = tok;
    batch.pos[0] = g_pos;
    batch.n_seq_id[0] = 1;
    batch.seq_id[0][0] = 0;
    batch.logits[0] = 1;
    int rc = llama_decode(g_ctx, batch);
    llama_batch_free(batch);
    if (rc != 0) {
        g_remaining = 0;
        return nullptr;
    }

    g_cached_tokens.push_back(tok);
    ++g_pos;
    --g_remaining;
    std::string piece = token_piece(vocab, tok);
    jbyteArray arr = env->NewByteArray((jsize)piece.size());
    if (arr && !piece.empty()) env->SetByteArrayRegion(arr, 0, (jsize)piece.size(), (const jbyte *)piece.data());
    return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_anastasia_ai_NativeBrain_nativeResetConversation(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    clear_session();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_anastasia_ai_NativeBrain_nativeModelInfo(JNIEnv * env, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return env->NewStringUTF(g_model_info.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_anastasia_ai_NativeBrain_nativeUnload(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    g_cached_tokens.clear();
    g_pos = 0;
    g_remaining = 0;
    g_model_info = "sin modelo";
}
