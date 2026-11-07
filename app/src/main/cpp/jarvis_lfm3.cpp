// Optional Personal J.A.R.V.I.S. JNI bridge to pinned upstream ggml-org/llama.cpp.
// Android ARM64 only. No remote I/O, shell, device tools, or permissions are exposed.
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <mutex>
#include <memory>
#include <string>
#include <vector>
#include <algorithm>
#include <chrono>
#include "llama.h"
#include "ggml-backend.h"
#include "common.h"
#include "chat.h"
#include "sampling.h"

namespace {
std::mutex g_lock;
std::atomic<bool> g_cancel{false};
bool g_started = false;
llama_model *g_model = nullptr;
llama_context *g_context = nullptr;
common_sampler *g_sampler = nullptr;
std::unique_ptr<common_batch> g_batch;
common_chat_templates_ptr g_templates;
std::string g_loaded_path;
constexpr int kContext = 3072;
constexpr int kBatch = 256;

std::string utf(JNIEnv *env, jstring source) {
    if (!source) return "";
    const char *chars = env->GetStringUTFChars(source, nullptr);
    if (!chars) return "";
    std::string value(chars);
    env->ReleaseStringUTFChars(source, chars);
    return value;
}

// llama.cpp emits ordinary UTF-8, whereas JNI NewStringUTF expects modified UTF-8.
// Decode through java.lang.String(byte[], UTF-8) to preserve emoji and other non-BMP text.
jstring java_utf8(JNIEnv *env, const std::string &value) {
    if (value.size() > 65536) return nullptr;
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(value.size()));
    if (!bytes) return nullptr;
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(value.size()),
                            reinterpret_cast<const jbyte *>(value.data()));
    jclass cls = env->FindClass("java/lang/String");
    if (!cls) { env->DeleteLocalRef(bytes); return nullptr; }
    jmethodID constructor = env->GetMethodID(cls, "<init>", "([BLjava/lang/String;)V");
    if (!constructor) { env->DeleteLocalRef(cls); env->DeleteLocalRef(bytes); return nullptr; }
    jstring encoding = env->NewStringUTF("UTF-8");
    jobject result = env->NewObject(cls, constructor, bytes, encoding);
    env->DeleteLocalRef(encoding);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(cls);
    return static_cast<jstring>(result);
}

void clear_loaded() {
    g_batch.reset();
    g_templates.reset();
    if (g_sampler) { common_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_context) { llama_free(g_context); g_context = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    g_loaded_path.clear();
}

std::string formatted_prompt(const std::string &system, const std::string &user) {
    if (g_templates) {
        try {
            std::vector<common_chat_msg> messages;
            common_chat_msg smsg;
            smsg.role = "system";
            smsg.content = system;
            auto prefix = common_chat_format_single(g_templates.get(), messages, smsg, false, true);
            messages.push_back(smsg);
            common_chat_msg umsg;
            umsg.role = "user";
            umsg.content = user;
            return prefix + common_chat_format_single(g_templates.get(), messages, umsg, true, true);
        } catch (...) {
            // Some models do not implement a system role or Jinja template.
        }
    }
    return "System: " + system + "\nUser: " + user + "\nAssistant: ";
}
} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_assistant_core_services_EmbeddedLfm3Native_nativeInit(JNIEnv *env, jobject, jstring libs) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (g_started) return JNI_TRUE;
    auto path = utf(env, libs);
    if (path.empty()) return JNI_FALSE;
    ggml_backend_load_all_from_path(path.c_str());
    llama_backend_init();
    g_started = true;
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_assistant_core_services_EmbeddedLfm3Native_nativeLoad(JNIEnv *env, jobject, jstring path) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (!g_started) return JNI_FALSE;
    const auto modelPath = utf(env, path);
    if (modelPath.empty()) return JNI_FALSE;
    if (g_model && g_context && g_loaded_path == modelPath) return JNI_TRUE;
    g_cancel.store(true);
    clear_loaded();
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0; // predictably bounded CPU baseline; benchmark GPU later
    g_model = llama_model_load_from_file(modelPath.c_str(), mp);
    if (!g_model) return JNI_FALSE;
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = kContext;
    cp.n_batch = kBatch;
    cp.n_ubatch = kBatch;
    cp.n_threads = 4;
    cp.n_threads_batch = 4;
    g_context = llama_init_from_model(g_model, cp);
    if (!g_context) { clear_loaded(); return JNI_FALSE; }
    common_params_sampling sampling;
    sampling.temp = 0.45f;
    g_sampler = common_sampler_init(g_model, sampling);
    if (!g_sampler) { clear_loaded(); return JNI_FALSE; }
    try {
        g_templates = common_chat_templates_init(g_model, "");
        g_batch = std::make_unique<common_batch>(g_context);
    } catch (...) {
        clear_loaded();
        return JNI_FALSE;
    }
    g_loaded_path = modelPath;
    g_cancel.store(false);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_assistant_core_services_EmbeddedLfm3Native_nativeGenerate(
    JNIEnv *env, jobject, jstring systemText, jstring userText, jint requestedTokens) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (!g_model || !g_context || !g_batch || !g_sampler) return nullptr;
    g_cancel.store(false);
    // Limit CPU-only inference so a stalled/slow device cannot spin forever.
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(55);
    const int maxTokens = std::clamp(static_cast<int>(requestedTokens), 1, 256);
    auto system = utf(env, systemText);
    auto user = utf(env, userText);
    if (system.size() > 2500 || user.size() > 14000) return nullptr;
    std::string prompt;
    try { prompt = formatted_prompt(system, user); }
    catch (...) { return nullptr; }
    const auto tokens = common_tokenize(g_context, prompt, true, true);
    if (tokens.empty() || tokens.size() + maxTokens + 8 > kContext) return nullptr;
    llama_memory_clear(llama_get_memory(g_context), false);
    common_sampler_reset(g_sampler);
    int32_t position = 0;
    for (size_t i = 0; i < tokens.size(); i += kBatch) {
        if (g_cancel.load() || std::chrono::steady_clock::now() >= deadline) return nullptr;
        g_batch->clear();
        const auto end = std::min(tokens.size(), i + kBatch);
        for (size_t j = i; j < end; ++j)
            g_batch->add(tokens[j], position++, 0, j == tokens.size() - 1);
        if (llama_process(g_context, LLAMA_PROCESS_TYPE_DECODE, g_batch->get()) != 0)
            return nullptr;
    }

    std::string output;
    auto *vocab = llama_model_get_vocab(g_model);
    for (int i = 0; i < maxTokens; ++i) {
        if (g_cancel.load() || std::chrono::steady_clock::now() >= deadline) return nullptr;
        auto token = common_sampler_sample(g_sampler, g_context, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        common_sampler_accept(g_sampler, token, true);
        output += common_token_to_piece(g_context, token);
        g_batch->clear();
        g_batch->add(token, position++, 0, true);
        if (llama_process(g_context, LLAMA_PROCESS_TYPE_DECODE, g_batch->get()) != 0) return nullptr;
    }
    if (g_cancel.load() || std::chrono::steady_clock::now() >= deadline || output.empty()) return nullptr;
    return java_utf8(env, output);
}

extern "C" JNIEXPORT void JNICALL
Java_com_assistant_core_services_EmbeddedLfm3Native_nativeCancel(JNIEnv *, jobject) {
    g_cancel.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_assistant_core_services_EmbeddedLfm3Native_nativeUnload(JNIEnv *, jobject) {
    g_cancel.store(true);
    std::lock_guard<std::mutex> guard(g_lock);
    clear_loaded();
}
