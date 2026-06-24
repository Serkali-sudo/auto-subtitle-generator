package com.serhat.autosub;

import java.util.ArrayList;
import java.util.List;

public class ShortsProject {
    public enum Mode { AI_HIGHLIGHTS, PHRASE_MONTAGE }

    private long id;
    private final long queueItemId;
    private String focusPrompt;
    private int desiredCount;
    private int minDurationSeconds;
    private int maxDurationSeconds;
    private long updatedAt;
    private List<ShortsCandidate> candidates = new ArrayList<>();
    private Mode mode = Mode.AI_HIGHLIGHTS;
    private String phrase = "";
    private boolean keepWholeSubtitle;
    private boolean removeSilence;

    public ShortsProject(long queueItemId, String focusPrompt, int desiredCount,
                         int minDurationSeconds, int maxDurationSeconds) {
        this.queueItemId = queueItemId;
        this.focusPrompt = focusPrompt == null ? "" : focusPrompt;
        this.desiredCount = desiredCount;
        this.minDurationSeconds = minDurationSeconds;
        this.maxDurationSeconds = maxDurationSeconds;
        this.updatedAt = System.currentTimeMillis();
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public long getQueueItemId() { return queueItemId; }
    public String getFocusPrompt() { return focusPrompt; }
    public int getDesiredCount() { return desiredCount; }
    public int getMinDurationSeconds() { return minDurationSeconds; }
    public int getMaxDurationSeconds() { return maxDurationSeconds; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
    public List<ShortsCandidate> getCandidates() { return candidates; }
    public void setCandidates(List<ShortsCandidate> candidates) {
        this.candidates = candidates == null ? new ArrayList<>() : new ArrayList<>(candidates);
    }
    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode == null ? Mode.AI_HIGHLIGHTS : mode; }
    public boolean isPhraseMontage() { return mode == Mode.PHRASE_MONTAGE; }
    public String getPhrase() { return phrase; }
    public void setPhrase(String phrase) { this.phrase = phrase == null ? "" : phrase.trim(); }
    public boolean isKeepWholeSubtitle() { return keepWholeSubtitle; }
    public void setKeepWholeSubtitle(boolean keepWholeSubtitle) { this.keepWholeSubtitle = keepWholeSubtitle; }
    public boolean isRemoveSilence() { return removeSilence; }
    public void setRemoveSilence(boolean removeSilence) { this.removeSilence = removeSilence; }
}
