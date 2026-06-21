package com.serhat.autosub;

import java.io.File;

/** Small runtime-neutral boundary around the Android 12+ LiteRT-LM implementation. */
public interface ShortsLlmEngine extends AutoCloseable {
    void initialize(File modelFile) throws Exception;
    String generate(String systemInstruction, String prompt) throws Exception;
    void cancel();
    @Override void close();
}
