package com.serhat.autosub.shorts;

import android.content.Context;
import android.os.Build;

public final class ShortsLlmEngineFactory {
    private ShortsLlmEngineFactory() {}

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    public static ShortsLlmEngine create(Context context, boolean preferGpu) {
        if (!isSupported()) {
            throw new UnsupportedOperationException("AI Shorts requires Android 12 or newer");
        }
        return new LiteRtShortsLlmEngine(context.getApplicationContext(), preferGpu);
    }
}
