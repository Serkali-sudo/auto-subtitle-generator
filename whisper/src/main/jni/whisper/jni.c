#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <stdio.h>
#include <sys/sysinfo.h>
#include <string.h>
#include "whisper.h"
#include "ggml.h"

#include <stdbool.h>
#include <pthread.h>

static bool g_should_abort = false;
static pthread_mutex_t g_abort_mutex = PTHREAD_MUTEX_INITIALIZER;

#define UNUSED(x) (void)(x)
#define TAG "JNI"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,     TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,     TAG, __VA_ARGS__)

static JavaVM* g_jvm = NULL;

struct callback_state {
    jobject callback;
    jmethodID on_new_segment_method;
    jmethodID on_progress_method;
    jmethodID on_complete_method;
};


static inline int min(int a, int b) {
    return (a < b) ? a : b;
}

static inline int max(int a, int b) {
    return (a > b) ? a : b;
}

static bool contains_text(const char *text, const char *needle) {
    return text != NULL && strstr(text, needle) != NULL;
}

static enum whisper_alignment_heads_preset alignment_heads_preset_for_model(const char *model_path) {
    if (contains_text(model_path, "large-v3-turbo")) return WHISPER_AHEADS_LARGE_V3_TURBO;
    if (contains_text(model_path, "large-v3")) return WHISPER_AHEADS_LARGE_V3;
    if (contains_text(model_path, "large-v2")) return WHISPER_AHEADS_LARGE_V2;
    if (contains_text(model_path, "large-v1")) return WHISPER_AHEADS_LARGE_V1;
    if (contains_text(model_path, "medium-en") || contains_text(model_path, "medium.en")) return WHISPER_AHEADS_MEDIUM_EN;
    if (contains_text(model_path, "medium")) return WHISPER_AHEADS_MEDIUM;
    if (contains_text(model_path, "small-en") || contains_text(model_path, "small.en")) return WHISPER_AHEADS_SMALL_EN;
    if (contains_text(model_path, "small")) return WHISPER_AHEADS_SMALL;
    if (contains_text(model_path, "base-en") || contains_text(model_path, "base.en")) return WHISPER_AHEADS_BASE_EN;
    if (contains_text(model_path, "base")) return WHISPER_AHEADS_BASE;
    if (contains_text(model_path, "tiny-en") || contains_text(model_path, "tiny.en")) return WHISPER_AHEADS_TINY_EN;
    if (contains_text(model_path, "tiny")) return WHISPER_AHEADS_TINY;
    return WHISPER_AHEADS_NONE;
}

static struct whisper_context_params context_params_for_model(const char *model_path) {
    struct whisper_context_params params = whisper_context_default_params();
    enum whisper_alignment_heads_preset preset = alignment_heads_preset_for_model(model_path);
    if (preset != WHISPER_AHEADS_NONE) {
        params.dtw_token_timestamps = true;
        params.dtw_aheads_preset = preset;
        LOGI("Enabled DTW token timestamps with alignment preset %d", preset);
    } else {
        LOGW("Could not infer DTW alignment preset for model path '%s'", model_path == NULL ? "" : model_path);
    }
    return params;
}

struct json_builder {
    char *data;
    size_t len;
    size_t cap;
};

static bool json_builder_reserve(struct json_builder *builder, size_t extra) {
    if (builder->len + extra + 1 <= builder->cap) {
        return true;
    }

    size_t next_cap = builder->cap == 0 ? 256 : builder->cap;
    while (builder->len + extra + 1 > next_cap) {
        next_cap *= 2;
    }

    char *next_data = (char *) realloc(builder->data, next_cap);
    if (next_data == NULL) {
        return false;
    }

    builder->data = next_data;
    builder->cap = next_cap;
    return true;
}

static bool json_builder_append_len(struct json_builder *builder, const char *text, size_t len) {
    if (!json_builder_reserve(builder, len)) {
        return false;
    }

    memcpy(builder->data + builder->len, text, len);
    builder->len += len;
    builder->data[builder->len] = '\0';
    return true;
}

static bool json_builder_append(struct json_builder *builder, const char *text) {
    return json_builder_append_len(builder, text, strlen(text));
}

static bool json_builder_append_escaped_string(struct json_builder *builder, const char *text) {
    if (!json_builder_append(builder, "\"")) {
        return false;
    }

    for (const unsigned char *c = (const unsigned char *) text; c != NULL && *c != '\0'; c++) {
        char escaped[7];
        switch (*c) {
            case '"':
                if (!json_builder_append(builder, "\\\"")) return false;
                break;
            case '\\':
                if (!json_builder_append(builder, "\\\\")) return false;
                break;
            case '\b':
                if (!json_builder_append(builder, "\\b")) return false;
                break;
            case '\f':
                if (!json_builder_append(builder, "\\f")) return false;
                break;
            case '\n':
                if (!json_builder_append(builder, "\\n")) return false;
                break;
            case '\r':
                if (!json_builder_append(builder, "\\r")) return false;
                break;
            case '\t':
                if (!json_builder_append(builder, "\\t")) return false;
                break;
            default:
                if (*c < 0x20) {
                    snprintf(escaped, sizeof(escaped), "\\u%04x", *c);
                    if (!json_builder_append(builder, escaped)) return false;
                } else if (!json_builder_append_len(builder, (const char *) c, 1)) {
                    return false;
                }
                break;
        }
    }

    return json_builder_append(builder, "\"");
}

static char *empty_json_array(void) {
    char *result = (char *) malloc(3);
    if (result == NULL) {
        return NULL;
    }
    memcpy(result, "[]", 3);
    return result;
}

static char *build_token_timings_json(struct whisper_context *ctx, int segment_id) {
    struct json_builder builder = {};
    if (!json_builder_append(&builder, "[")) {
        free(builder.data);
        return empty_json_array();
    }

    const int token_count = whisper_full_n_tokens(ctx, segment_id);
    const whisper_token eot = whisper_token_eot(ctx);
    bool first = true;
    bool has_dtw_timings = false;
    int64_t previous_dtw = -1;

    for (int token_index = 0; token_index < token_count; token_index++) {
        const struct whisper_token_data token = whisper_full_get_token_data(ctx, segment_id, token_index);
        if (token.id < eot && token.t_dtw > previous_dtw) {
            if (previous_dtw >= 0) {
                has_dtw_timings = true;
                break;
            }
            previous_dtw = token.t_dtw;
        }
    }

    for (int token_index = 0; token_index < token_count; token_index++) {
        const struct whisper_token_data token = whisper_full_get_token_data(ctx, segment_id, token_index);
        const char *token_text = whisper_full_get_token_text(ctx, segment_id, token_index);
        if (token.id >= eot || token_text == NULL || token_text[0] == '\0') {
            continue;
        }

        const bool use_dtw = has_dtw_timings && token.t_dtw >= 0;
        int64_t token_t0 = use_dtw ? token.t_dtw : token.t0;
        int64_t token_t1 = use_dtw ? -1 : token.t1;
        if (use_dtw) {
            for (int next_index = token_index + 1; next_index < token_count; next_index++) {
                const struct whisper_token_data next = whisper_full_get_token_data(ctx, segment_id, next_index);
                if (next.id < eot && next.t_dtw > token_t0) {
                    token_t1 = next.t_dtw;
                    break;
                }
            }
            if (token_t1 <= token_t0) {
                const int64_t segment_t1 = whisper_full_get_segment_t1(ctx, segment_id);
                token_t1 = token_t0 + 20;
                if (segment_t1 > token_t0) {
                    token_t1 = segment_t1 < token_t0 + 50 ? segment_t1 : token_t0 + 50;
                }
            }
        }
        if (token_t0 < 0 || token_t1 <= token_t0) {
            continue;
        }

        char numbers[128];
        snprintf(
                numbers,
                sizeof(numbers),
                "%s{\"text\":",
                first ? "" : ","
        );
        if (!json_builder_append(&builder, numbers)
                || !json_builder_append_escaped_string(&builder, token_text)) {
            free(builder.data);
            return empty_json_array();
        }

        snprintf(
                numbers,
                sizeof(numbers),
                ",\"startMs\":%lld,\"endMs\":%lld,\"confidence\":%.6f}",
                (long long) token_t0 * 10,
                (long long) token_t1 * 10,
                token.p
        );
        if (!json_builder_append(&builder, numbers)) {
            free(builder.data);
            return empty_json_array();
        }

        first = false;
    }

    if (!json_builder_append(&builder, "]")) {
        free(builder.data);
        return empty_json_array();
    }

    return builder.data;
}

struct input_stream_context {
    size_t offset;
    JNIEnv * env;
    jobject thiz;
    jobject input_stream;

    jmethodID mid_available;
    jmethodID mid_read;
};

size_t inputStreamRead(void * ctx, void * output, size_t read_size) {
    struct input_stream_context* is = (struct input_stream_context*)ctx;

    jint avail_size = (*is->env)->CallIntMethod(is->env, is->input_stream, is->mid_available);
    jint size_to_copy = read_size < avail_size ? (jint)read_size : avail_size;

    jbyteArray byte_array = (*is->env)->NewByteArray(is->env, size_to_copy);

    jint n_read = (*is->env)->CallIntMethod(is->env, is->input_stream, is->mid_read, byte_array, 0, size_to_copy);

    if (size_to_copy != read_size || size_to_copy != n_read) {
        LOGI("Insufficient Read: Req=%zu, ToCopy=%d, Available=%d", read_size, size_to_copy, n_read);
    }

    jbyte* byte_array_elements = (*is->env)->GetByteArrayElements(is->env, byte_array, NULL);
    memcpy(output, byte_array_elements, size_to_copy);
    (*is->env)->ReleaseByteArrayElements(is->env, byte_array, byte_array_elements, JNI_ABORT);

    (*is->env)->DeleteLocalRef(is->env, byte_array);

    is->offset += size_to_copy;

    return size_to_copy;
}
bool inputStreamEof(void * ctx) {
    struct input_stream_context* is = (struct input_stream_context*)ctx;

    jint result = (*is->env)->CallIntMethod(is->env, is->input_stream, is->mid_available);
    return result <= 0;
}
void inputStreamClose(void * ctx) {

}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_initContextFromInputStream(
        JNIEnv *env, jobject thiz, jobject input_stream) {
    UNUSED(thiz);

    struct whisper_context *context = NULL;
    struct whisper_model_loader loader = {};
    struct input_stream_context inp_ctx = {};

    inp_ctx.offset = 0;
    inp_ctx.env = env;
    inp_ctx.thiz = thiz;
    inp_ctx.input_stream = input_stream;

    jclass cls = (*env)->GetObjectClass(env, input_stream);
    inp_ctx.mid_available = (*env)->GetMethodID(env, cls, "available", "()I");
    inp_ctx.mid_read = (*env)->GetMethodID(env, cls, "read", "([BII)I");

    loader.context = &inp_ctx;
    loader.read = inputStreamRead;
    loader.eof = inputStreamEof;
    loader.close = inputStreamClose;

    loader.eof(loader.context);

    context = whisper_init(&loader);
    return (jlong) context;
}

static size_t asset_read(void *ctx, void *output, size_t read_size) {
    return AAsset_read((AAsset *) ctx, output, read_size);
}

static bool asset_is_eof(void *ctx) {
    return AAsset_getRemainingLength64((AAsset *) ctx) <= 0;
}

static void asset_close(void *ctx) {
    AAsset_close((AAsset *) ctx);
}

static struct whisper_context *whisper_init_from_asset(
        JNIEnv *env,
        jobject assetManager,
        const char *asset_path
) {
    LOGI("Loading model from asset '%s'\n", asset_path);
    AAssetManager *asset_manager = AAssetManager_fromJava(env, assetManager);
    AAsset *asset = AAssetManager_open(asset_manager, asset_path, AASSET_MODE_STREAMING);
    if (!asset) {
        LOGW("Failed to open '%s'\n", asset_path);
        return NULL;
    }

    whisper_model_loader loader = {
            .context = asset,
            .read = &asset_read,
            .eof = &asset_is_eof,
            .close = &asset_close
    };

    return whisper_init_with_params(&loader, context_params_for_model(asset_path));
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_initContextFromAsset(
        JNIEnv *env, jobject thiz, jobject assetManager, jstring asset_path_str) {
    UNUSED(thiz);
    struct whisper_context *context = NULL;
    const char *asset_path_chars = (*env)->GetStringUTFChars(env, asset_path_str, NULL);
    context = whisper_init_from_asset(env, assetManager, asset_path_chars);
    (*env)->ReleaseStringUTFChars(env, asset_path_str, asset_path_chars);
    return (jlong) context;
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_initContext(
        JNIEnv *env, jobject thiz, jstring model_path_str) {
    UNUSED(thiz);
    struct whisper_context *context = NULL;
    const char *model_path_chars = (*env)->GetStringUTFChars(env, model_path_str, NULL);
    context = whisper_init_from_file_with_params(model_path_chars, context_params_for_model(model_path_chars));
    (*env)->ReleaseStringUTFChars(env, model_path_str, model_path_chars);
    return (jlong) context;
}

JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_freeContext(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    whisper_free(context);
}
// Callback for new segments
void new_segment_callback(struct whisper_context * ctx, struct whisper_state * state, int n_new, void * user_data) {
    UNUSED(state);
    struct callback_state *callback_state = (struct callback_state *) user_data;
    if (callback_state == NULL || callback_state->callback == NULL) {
        return;
    }

    JNIEnv* env;
    if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) != JNI_OK) {
        return;
    }

    for (int i = 0; i < n_new; i++) {
        const int segment_id = whisper_full_n_segments(ctx) - n_new + i;
        const char* text = whisper_full_get_segment_text(ctx, segment_id);
        const int64_t t0 = whisper_full_get_segment_t0(ctx, segment_id);
        const int64_t t1 = whisper_full_get_segment_t1(ctx, segment_id);
        char *token_timings_json = build_token_timings_json(ctx, segment_id);
        const char *safe_token_timings_json = token_timings_json == NULL ? "[]" : token_timings_json;
        const char *safe_text = text == NULL ? "" : text;

        jstring jtext = (*env)->NewStringUTF(env, safe_text);
        jstring jtoken_timings_json = (*env)->NewStringUTF(env, safe_token_timings_json);
        (*env)->CallVoidMethod(
                env,
                callback_state->callback,
                callback_state->on_new_segment_method,
                (jlong)t0,
                (jlong)t1,
                jtext,
                jtoken_timings_json
        );
        (*env)->DeleteLocalRef(env, jtext);
        (*env)->DeleteLocalRef(env, jtoken_timings_json);
        if (token_timings_json != NULL) {
            free(token_timings_json);
        }
    }
}

// Progress callback
void progress_callback(struct whisper_context * ctx, struct whisper_state * state, int progress, void * user_data) {
    UNUSED(ctx);
    UNUSED(state);
    struct callback_state *callback_state = (struct callback_state *) user_data;
    if (callback_state == NULL || callback_state->callback == NULL) {
        return;
    }

    JNIEnv* env;
    if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) != JNI_OK) {
        return;
    }
    (*env)->CallVoidMethod(env, callback_state->callback, callback_state->on_progress_method, (jint)progress);
}

static bool abort_callback(void* user_data) {
    bool should_abort;
    pthread_mutex_lock(&g_abort_mutex);
    should_abort = g_should_abort;
    pthread_mutex_unlock(&g_abort_mutex);
    return should_abort;
}

JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_resetAbort(JNIEnv* env, jobject thiz) {
    pthread_mutex_lock(&g_abort_mutex);
    g_should_abort = false;
    pthread_mutex_unlock(&g_abort_mutex);
}

JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_stopTranscription(JNIEnv* env, jobject thiz) {
    pthread_mutex_lock(&g_abort_mutex);
    g_should_abort = true;
    pthread_mutex_unlock(&g_abort_mutex);
}


JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_fullTranscribe(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint num_threads,
        jfloatArray audio_data, jstring language, jint max_segment_length,
        jboolean suppress_non_speech_tokens, jobject callback) {
    UNUSED(thiz);
// Reset abort state
    Java_com_whispercpp_whisper_WhisperLib_00024Companion_resetAbort(env, thiz);
    // Store JavaVM for later callbacks
    if (g_jvm == NULL) {
        (*env)->GetJavaVM(env, &g_jvm);
    }

    struct callback_state callback_state = {};
    callback_state.callback = (*env)->NewGlobalRef(env, callback);

    // Get method IDs
    jclass callbackClass = (*env)->GetObjectClass(env, callback_state.callback);

    jmethodID toStringMethod = (*env)->GetMethodID(env, callbackClass, "toString", "()Ljava/lang/String;");
    jstring str = (*env)->CallObjectMethod(env, callback_state.callback, toStringMethod);
    const char *cStr = (*env)->GetStringUTFChars(env, str, NULL);
    __android_log_print(ANDROID_LOG_DEBUG, "JNI", "Callback class: %s", cStr);
    (*env)->ReleaseStringUTFChars(env, str, cStr);
    callback_state.on_new_segment_method = (*env)->GetMethodID(
            env,
            callbackClass,
            "onNewSegment",
            "(JJLjava/lang/String;Ljava/lang/String;)V"
    );
    callback_state.on_progress_method = (*env)->GetMethodID(
            env,
            callbackClass,
            "onProgress",
            "(I)V"
    );
    callback_state.on_complete_method = (*env)->GetMethodID(
            env,
            callbackClass,
            "onComplete",
            "()V"
    );

    struct whisper_context *context = (struct whisper_context *) context_ptr;
    jfloat *audio_data_arr = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    const jsize audio_data_length = (*env)->GetArrayLength(env, audio_data);

    // Get language parameter (default to "auto" if null)
    const char *language_str = "auto";
    if (language != NULL) {
        language_str = (*env)->GetStringUTFChars(env, language, NULL);
    }

    // Configure whisper parameters with callbacks
    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false; // We handle callbacks ourselves
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = language_str;
    params.n_threads = num_threads;
    params.offset_ms = 0;
    params.no_context = true;
    params.single_segment = false;
    params.token_timestamps = true;
    params.suppress_nst = suppress_non_speech_tokens == JNI_TRUE;
    params.max_len = max_segment_length <= 0 ? 0 : max(24, min(96, max_segment_length));
    params.split_on_word = true;


    // Set our callbacks
    params.new_segment_callback = new_segment_callback;
    params.new_segment_callback_user_data = &callback_state;
    params.progress_callback = progress_callback;
    params.progress_callback_user_data = &callback_state;
    params.abort_callback = abort_callback;
    params.abort_callback_user_data = NULL;


    whisper_reset_timings(context);

    LOGI("About to run whisper_full with callbacks (language: %s, suppress_nst: %d)",
            language_str, params.suppress_nst);
    int result = whisper_full(context, params, audio_data_arr, audio_data_length);

    // Cleanup language string if we allocated it
    if (language != NULL) {
        (*env)->ReleaseStringUTFChars(env, language, language_str);
    }

    // Notify completion
    if (result == 0) {
        (*env)->CallVoidMethod(env, callback_state.callback, callback_state.on_complete_method);
    } else {
        LOGI("Failed to run the model");
    }

    // Cleanup
    (*env)->ReleaseFloatArrayElements(env, audio_data, audio_data_arr, JNI_ABORT);
    (*env)->DeleteGlobalRef(env, callback_state.callback);
}

JNIEXPORT jint JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegmentCount(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_n_segments(context);
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegment(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    const char *text = whisper_full_get_segment_text(context, index);
    jstring string = (*env)->NewStringUTF(env, text);
    return string;
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegmentT0(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_get_segment_t0(context, index);
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegmentT1(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_get_segment_t1(context, index);
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getSystemInfo(
        JNIEnv *env, jobject thiz
) {
    UNUSED(thiz);
    const char *sysinfo = whisper_print_system_info();
    jstring string = (*env)->NewStringUTF(env, sysinfo);
    return string;
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_benchMemcpy(JNIEnv *env, jobject thiz,
                                                                      jint n_threads) {
    UNUSED(thiz);
    const char *bench_ggml_memcpy = whisper_bench_memcpy_str(n_threads);
    jstring string = (*env)->NewStringUTF(env, bench_ggml_memcpy);
    return string;
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_benchGgmlMulMat(JNIEnv *env, jobject thiz,
                                                                          jint n_threads) {
    UNUSED(thiz);
    const char *bench_ggml_mul_mat = whisper_bench_ggml_mul_mat_str(n_threads);
    jstring string = (*env)->NewStringUTF(env, bench_ggml_mul_mat);
    return string;
}
