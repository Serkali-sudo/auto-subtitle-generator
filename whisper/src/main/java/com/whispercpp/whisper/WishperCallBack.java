package com.whispercpp.whisper;

import androidx.annotation.Keep;

@Keep
public class WishperCallBack implements WhisperCallback {
    @Override
    public void onNewSegment(long startMs, long endMs, String text, String tokenTimingsJson) {
        System.out.println(text);
    }

    @Override
    public void onProgress(int progress) {
        System.out.println(progress);
    }

    @Override
    public void onComplete() {
        System.out.println("Completed");
    }
}
