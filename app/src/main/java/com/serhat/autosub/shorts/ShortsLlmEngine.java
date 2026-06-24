package com.serhat.autosub.shorts;

import java.io.File;

/** Small runtime-neutral boundary around the Android 12+ LiteRT-LM implementation. */
public interface ShortsLlmEngine extends AutoCloseable {
    void initialize(File modelFile, int maxContextTokens) throws Exception;
    default void setThinkingEnabled(boolean enabled) {}
    String generate(String systemInstruction, String prompt) throws Exception;
    void cancel();
    int getMaxContextTokens();
    @Override void close();
}
