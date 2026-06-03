package com.whispercpp.whisper;

public interface WhisperCallback {
    void onNewSegment(long startMs, long endMs, String text, String tokenTimingsJson);

    void onProgress(int progress);

    void onComplete();
}
