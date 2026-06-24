package com.serhat.autosub.subtitles;

public class WordTiming {
    private final String word;
    private final long startMs;
    private final long endMs;
    private final double confidence;

    public WordTiming(String word, long startMs, long endMs, double confidence) {
        this.word = word;
        this.startMs = startMs;
        this.endMs = endMs;
        this.confidence = confidence;
    }

    public String getWord() {
        return word;
    }

    public long getStartMs() {
        return startMs;
    }

    public long getEndMs() {
        return endMs;
    }

    public double getConfidence() {
        return confidence;
    }
}
