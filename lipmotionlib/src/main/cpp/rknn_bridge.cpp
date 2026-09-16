#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cstring>
#include <memory>
#include <mutex>
#include <vector>

#include "rknn_api.h"

namespace {
constexpr const char* TAG = "LipMotionRKNN";

struct Session {
    rknn_context context = 0;
    uint32_t input_elements = 0;
    std::vector<uint32_t> output_elements;
    std::mutex mutex;

    ~Session() {
        if (context != 0) rknn_destroy(context);
    }
};

void log_error(const char* operation, int code) {
    __android_log_print(ANDROID_LOG_ERROR, TAG, "%s failed: %d", operation, code);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_zuicun_lipmotion_RknnSession_nativeCreate(
        JNIEnv* env, jclass, jbyteArray model, jintArray expected_output_sizes) {
    if (model == nullptr || expected_output_sizes == nullptr) return 0;
    const jsize expected_count = env->GetArrayLength(expected_output_sizes);
    if (expected_count <= 0) return 0;
    std::vector<jint> expected(static_cast<size_t>(expected_count));
    env->GetIntArrayRegion(expected_output_sizes, 0, expected_count, expected.data());
    const jsize model_size = env->GetArrayLength(model);
    std::vector<uint8_t> model_data(static_cast<size_t>(model_size));
    env->GetByteArrayRegion(
            model, 0, model_size, reinterpret_cast<jbyte*>(model_data.data()));

    auto session = std::make_unique<Session>();
    int result = rknn_init(
            &session->context, model_data.data(), static_cast<uint32_t>(model_data.size()), 0, nullptr);
    if (result != RKNN_SUCC) {
        log_error("rknn_init", result);
        return 0;
    }

    rknn_input_output_num io_count{};
    result = rknn_query(
            session->context, RKNN_QUERY_IN_OUT_NUM, &io_count, sizeof(io_count));
    if (result != RKNN_SUCC || io_count.n_input != 1
            || io_count.n_output != static_cast<uint32_t>(expected_count)) {
        log_error("RKNN_QUERY_IN_OUT_NUM", result);
        return 0;
    }

    rknn_tensor_attr input_attr{};
    input_attr.index = 0;
    result = rknn_query(
            session->context, RKNN_QUERY_INPUT_ATTR, &input_attr, sizeof(input_attr));
    if (result != RKNN_SUCC) {
        log_error("RKNN_QUERY_INPUT_ATTR", result);
        return 0;
    }
    session->input_elements = input_attr.n_elems;

    for (uint32_t index = 0; index < io_count.n_output; index++) {
        rknn_tensor_attr output_attr{};
        output_attr.index = index;
        result = rknn_query(
                session->context, RKNN_QUERY_OUTPUT_ATTR, &output_attr, sizeof(output_attr));
        if (result != RKNN_SUCC) {
            log_error("RKNN_QUERY_OUTPUT_ATTR", result);
            return 0;
        }
        session->output_elements.push_back(output_attr.n_elems);
    }
    std::vector<uint32_t> sorted_actual = session->output_elements;
    std::vector<uint32_t> sorted_expected;
    sorted_expected.reserve(expected.size());
    for (jint value : expected) sorted_expected.push_back(static_cast<uint32_t>(value));
    std::sort(sorted_actual.begin(), sorted_actual.end());
    std::sort(sorted_expected.begin(), sorted_expected.end());
    if (sorted_actual != sorted_expected) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "RKNN output shape mismatch");
        return 0;
    }
    return reinterpret_cast<jlong>(session.release());
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_zuicun_lipmotion_RknnSession_nativeRun(
        JNIEnv* env, jclass, jlong handle, jbyteArray rgb) {
    auto* session = reinterpret_cast<Session*>(handle);
    if (session == nullptr || rgb == nullptr) return nullptr;
    std::lock_guard<std::mutex> lock(session->mutex);

    const jsize input_size = env->GetArrayLength(rgb);
    if (input_size != static_cast<jsize>(session->input_elements)) return nullptr;
    jbyte* input_data = env->GetByteArrayElements(rgb, nullptr);
    if (input_data == nullptr) return nullptr;
    rknn_input input{};
    input.index = 0;
    input.buf = input_data;
    input.size = static_cast<uint32_t>(input_size);
    input.pass_through = 0;
    input.type = RKNN_TENSOR_UINT8;
    input.fmt = RKNN_TENSOR_NHWC;

    int result = rknn_inputs_set(session->context, 1, &input);
    env->ReleaseByteArrayElements(rgb, input_data, JNI_ABORT);
    if (result != RKNN_SUCC) {
        log_error("rknn_inputs_set", result);
        return nullptr;
    }
    result = rknn_run(session->context, nullptr);
    if (result != RKNN_SUCC) {
        log_error("rknn_run", result);
        return nullptr;
    }

    std::vector<rknn_output> outputs(session->output_elements.size());
    for (uint32_t index = 0; index < outputs.size(); index++) {
        outputs[index].index = index;
        outputs[index].want_float = 1;
        outputs[index].is_prealloc = 0;
    }
    result = rknn_outputs_get(
            session->context, static_cast<uint32_t>(outputs.size()), outputs.data(), nullptr);
    if (result != RKNN_SUCC) {
        log_error("rknn_outputs_get", result);
        return nullptr;
    }

    jclass float_array_class = env->FindClass("[F");
    jobjectArray java_outputs = env->NewObjectArray(
            static_cast<jsize>(outputs.size()), float_array_class, nullptr);
    for (uint32_t index = 0; index < outputs.size(); index++) {
        jfloatArray java_output =
                env->NewFloatArray(static_cast<jsize>(session->output_elements[index]));
        env->SetFloatArrayRegion(
                java_output,
                0,
                static_cast<jsize>(session->output_elements[index]),
                static_cast<const jfloat*>(outputs[index].buf));
        env->SetObjectArrayElement(java_outputs, static_cast<jsize>(index), java_output);
        env->DeleteLocalRef(java_output);
    }
    rknn_outputs_release(
            session->context, static_cast<uint32_t>(outputs.size()), outputs.data());
    return java_outputs;
}

extern "C" JNIEXPORT void JNICALL
Java_com_zuicun_lipmotion_RknnSession_nativeDestroy(
        JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<Session*>(handle);
}
