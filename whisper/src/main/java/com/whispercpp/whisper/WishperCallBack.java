package com.whispercpp.whisper;

import androidx.annotation.Keep;

import com.whispercpp.DebugLog;

@Keep
public class WishperCallBack implements WhisperCallback {
    @Override
    public void onNewSegment(long startMs, long endMs, String text, String tokenTimingsJson) {
        DebugLog.d("LibWhisper", text);
    }

    @Override
    public void onProgress(int progress) {
        DebugLog.d("LibWhisper", String.valueOf(progress));
    }

    @Override
    public void onComplete() {
        DebugLog.d("LibWhisper", "Completed");
    }
}
