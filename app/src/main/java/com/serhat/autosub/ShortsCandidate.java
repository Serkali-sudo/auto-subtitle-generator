package com.serhat.autosub;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class ShortsCandidate {
    public enum RenderState { PENDING, RENDERING, EXPORTED, FAILED }

    private long id;
    private long projectId;
    private int startSubtitleId;
    private int endSubtitleId;
    private long startMs;
    private long endMs;
    private String title;
    private String hook;
    private String reason;
    private int score;
    private boolean selected = true;
    private float cropPosition = 0.5f;
    private final List<ShortsCropKeyframe> cropKeyframes = new ArrayList<>();
    private boolean burnCaptions = true;
    private SubtitleGenerator.SubtitleLayerMode captionLayer = SubtitleGenerator.SubtitleLayerMode.ORIGINAL;
    private RenderState renderState = RenderState.PENDING;
    private String outputPath = "";
    private String errorMessage = "";

    public ShortsCandidate(int startSubtitleId, int endSubtitleId, long startMs, long endMs,
                           String title, String hook, String reason, int score) {
        this.startSubtitleId = startSubtitleId;
        this.endSubtitleId = endSubtitleId;
        this.startMs = startMs;
        this.endMs = endMs;
        this.title = safe(title);
        this.hook = safe(hook);
        this.reason = safe(reason);
        this.score = Math.max(0, Math.min(100, score));
    }

    private static String safe(String value) { return value == null ? "" : value.trim(); }
    public long getDurationMs() { return Math.max(0, endMs - startMs); }
    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public long getProjectId() { return projectId; }
    public void setProjectId(long projectId) { this.projectId = projectId; }
    public int getStartSubtitleId() { return startSubtitleId; }
    public void setStartSubtitleId(int value) { startSubtitleId = value; }
    public int getEndSubtitleId() { return endSubtitleId; }
    public void setEndSubtitleId(int value) { endSubtitleId = value; }
    public long getStartMs() { return startMs; }
    public void setStartMs(long startMs) { this.startMs = Math.max(0, startMs); }
    public long getEndMs() { return endMs; }
    public void setEndMs(long endMs) { this.endMs = Math.max(0, endMs); }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = safe(title); }
    public String getHook() { return hook; }
    public String getReason() { return reason; }
    public int getScore() { return score; }
    public boolean isSelected() { return selected; }
    public void setSelected(boolean selected) { this.selected = selected; }
    public float getCropPosition() { return cropPosition; }
    public void setCropPosition(float cropPosition) { this.cropPosition = Math.max(0f, Math.min(1f, cropPosition)); }
    public List<ShortsCropKeyframe> getCropKeyframes() { return new ArrayList<>(cropKeyframes); }
    public void setCropKeyframes(List<ShortsCropKeyframe> keyframes) {
        cropKeyframes.clear();
        if (keyframes != null) cropKeyframes.addAll(keyframes);
        cropKeyframes.sort(Comparator.comparingLong(ShortsCropKeyframe::getTimeMs));
    }
    public void clearCropKeyframes() { cropKeyframes.clear(); }
    public boolean hasAutoFraming() { return !cropKeyframes.isEmpty(); }
    public float getCropPositionAt(long clipLocalMs) {
        ShortsCropKeyframe active = getCropKeyframeAt(clipLocalMs);
        return active == null ? cropPosition : active.getPosition();
    }
    public ShortsCropKeyframe getCropKeyframeAt(long clipLocalMs) {
        if (cropKeyframes.isEmpty()) return null;
        long time = Math.max(0, clipLocalMs);
        ShortsCropKeyframe active = cropKeyframes.get(0);
        for (int i = 1; i < cropKeyframes.size(); i++) {
            ShortsCropKeyframe next = cropKeyframes.get(i);
            if (time < next.getTimeMs()) break;
            active = next;
        }
        return active;
    }
    public boolean isBurnCaptions() { return burnCaptions; }
    public void setBurnCaptions(boolean burnCaptions) { this.burnCaptions = burnCaptions; }
    public SubtitleGenerator.SubtitleLayerMode getCaptionLayer() { return captionLayer; }
    public void setCaptionLayer(SubtitleGenerator.SubtitleLayerMode layer) {
        captionLayer = layer == null ? SubtitleGenerator.SubtitleLayerMode.ORIGINAL : layer;
    }
    public RenderState getRenderState() { return renderState; }
    public void setRenderState(RenderState renderState) { this.renderState = renderState == null ? RenderState.PENDING : renderState; }
    public String getOutputPath() { return outputPath; }
    public void setOutputPath(String outputPath) { this.outputPath = safe(outputPath); }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = safe(errorMessage); }
}
