package com.assistant.core.services;

import kotlin.jvm.functions.Function1;

/**
 * JNI in sherpa-onnx 1.13.8 looks up invoke(float[]): java.lang.Integer
 * on the callback's concrete class. An optimized Kotlin lambda can lose that
 * exact typed bridge after R8 even though Function1.invoke(Object) remains.
 *
 * Keep a named, Java-compiled bridge with the exact JNI method descriptor.
 */
public final class NativeTtsCallback implements Function1<float[], Integer> {
    public interface Consumer {
        int onSamples(float[] samples);
    }

    private final Consumer consumer;

    public NativeTtsCallback(Consumer consumer) {
        this.consumer = consumer;
    }

    @Override
    public Integer invoke(float[] samples) {
        return consumer.onSamples(samples);
    }
}
