package com.serhat.autosub.shorts;

import com.serhat.autosub.subtitles.SubtitleGenerator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ShortsAnalysisRequest {
    private final long queueItemId;
    private final List<SubtitleGenerator.SubtitleEntry> subtitles;
    private final int desiredCount;
    private final int minDurationSeconds;
    private final int maxDurationSeconds;
    private final String focusPrompt;

    public ShortsAnalysisRequest(long queueItemId, List<SubtitleGenerator.SubtitleEntry> subtitles,
                                 int desiredCount, int minDurationSeconds, int maxDurationSeconds,
                                 String focusPrompt) {
        this.queueItemId = queueItemId;
        this.subtitles = Collections.unmodifiableList(new ArrayList<>(subtitles));
        this.desiredCount = Math.max(1, Math.min(10, desiredCount));
        this.minDurationSeconds = Math.max(5, minDurationSeconds);
        this.maxDurationSeconds = Math.max(this.minDurationSeconds, Math.min(180, maxDurationSeconds));
        this.focusPrompt = focusPrompt == null ? "" : focusPrompt.trim();
    }

    public long getQueueItemId() { return queueItemId; }
    public List<SubtitleGenerator.SubtitleEntry> getSubtitles() { return subtitles; }
    public int getDesiredCount() { return desiredCount; }
    public int getMinDurationSeconds() { return minDurationSeconds; }
    public int getMaxDurationSeconds() { return maxDurationSeconds; }
    public String getFocusPrompt() { return focusPrompt; }
}
