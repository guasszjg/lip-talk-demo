package com.zuicun.liptalkdemo;

/** Public switch interface for testing and business integration. */
public interface InferenceBackendController {
    void setInferenceBackend(InferenceBackend backend);
    InferenceBackend getInferenceBackend();
}
