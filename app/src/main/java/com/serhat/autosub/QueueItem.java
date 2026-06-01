package com.serhat.autosub;

import android.net.Uri;

public class QueueItem {
    public enum Status {
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED,
        CANCELLED,
        EXPORTING
    }

    private long id;
    private Uri videoUri;
    private final String displayName;
    private Status status = Status.PENDING;
    private int progress;
    private String outputPath = "";
    private String srtPath = "";
    private String vttPath = "";
    private String softVideoPath = "";
    private String hardVideoPath = "";
    private String message = "";
    private String previewText = "";
    private java.util.List<SubtitleGenerator.SubtitleEntry> subtitles = new java.util.ArrayList<>();
    private boolean shortsVideo;
    private float shortsCaptionX = 0.5f;
    private float shortsCaptionY = 0.5f;
    private float shortsCaptionScale = 1f;
    private float subtitleCaptionX = 0.5f;
    private float subtitleCaptionY = 0.88f;
    private float subtitleCaptionScale = 1f;
    private boolean subtitleCaptionPositionAdjusted;
    private String thumbnailPath = "";
    private String audioPath = "";
    private boolean selected = false;

    public QueueItem(Uri videoUri, String displayName) {
        this.videoUri = videoUri;
        this.displayName = displayName;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public Uri getVideoUri() {
        return videoUri;
    }

    public void setVideoUri(Uri videoUri) {
        this.videoUri = videoUri;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public int getProgress() {
        return progress;
    }

    public void setProgress(int progress) {
        this.progress = progress;
    }

    public String getOutputPath() {
        return outputPath;
    }

    public void setOutputPath(String outputPath) {
        this.outputPath = outputPath == null ? "" : outputPath;
    }

    public String getSrtPath() {
        return srtPath;
    }

    public void setSrtPath(String srtPath) {
        this.srtPath = srtPath == null ? "" : srtPath;
    }

    public String getVttPath() {
        return vttPath;
    }

    public void setVttPath(String vttPath) {
        this.vttPath = vttPath == null ? "" : vttPath;
    }

    public String getSoftVideoPath() {
        return softVideoPath;
    }

    public void setSoftVideoPath(String softVideoPath) {
        this.softVideoPath = softVideoPath == null ? "" : softVideoPath;
    }

    public String getHardVideoPath() {
        return hardVideoPath;
    }

    public void setHardVideoPath(String hardVideoPath) {
        this.hardVideoPath = hardVideoPath == null ? "" : hardVideoPath;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message == null ? "" : message;
    }

    public String getPreviewText() {
        return previewText;
    }

    public void setPreviewText(String previewText) {
        this.previewText = previewText == null ? "" : previewText;
    }

    public java.util.List<SubtitleGenerator.SubtitleEntry> getSubtitles() {
        return subtitles;
    }

    public void setSubtitles(java.util.List<SubtitleGenerator.SubtitleEntry> subtitles) {
        this.subtitles = subtitles == null ? new java.util.ArrayList<>() : subtitles;
    }

    public boolean isShortsVideo() {
        return shortsVideo;
    }

    public void setShortsVideo(boolean shortsVideo) {
        this.shortsVideo = shortsVideo;
    }

    public float getShortsCaptionX() {
        return shortsCaptionX;
    }

    public void setShortsCaptionX(float shortsCaptionX) {
        this.shortsCaptionX = clampCaptionPosition(shortsCaptionX);
    }

    public float getShortsCaptionY() {
        return shortsCaptionY;
    }

    public void setShortsCaptionY(float shortsCaptionY) {
        this.shortsCaptionY = clampCaptionPosition(shortsCaptionY);
    }

    public float getShortsCaptionScale() {
        return shortsCaptionScale;
    }

    public void setShortsCaptionScale(float shortsCaptionScale) {
        this.shortsCaptionScale = clampCaptionScale(shortsCaptionScale);
    }

    private float clampCaptionPosition(float value) {
        if (Float.isNaN(value)) return 0.5f;
        return Math.max(0f, Math.min(1f, value));
    }

    private float clampCaptionScale(float value) {
        if (Float.isNaN(value)) return 1f;
        return Math.max(0.5f, Math.min(3f, value));
    }

    public float getSubtitleCaptionX() {
        return subtitleCaptionX;
    }

    public void setSubtitleCaptionX(float subtitleCaptionX) {
        this.subtitleCaptionX = clampCaptionPosition(subtitleCaptionX);
    }

    public float getSubtitleCaptionY() {
        return subtitleCaptionY;
    }

    public void setSubtitleCaptionY(float subtitleCaptionY) {
        this.subtitleCaptionY = clampCaptionPosition(subtitleCaptionY);
    }

    public float getSubtitleCaptionScale() {
        return subtitleCaptionScale;
    }

    public void setSubtitleCaptionScale(float subtitleCaptionScale) {
        this.subtitleCaptionScale = clampCaptionScale(subtitleCaptionScale);
    }

    public boolean isSubtitleCaptionPositionAdjusted() {
        return subtitleCaptionPositionAdjusted;
    }

    public void setSubtitleCaptionPositionAdjusted(boolean subtitleCaptionPositionAdjusted) {
        this.subtitleCaptionPositionAdjusted = subtitleCaptionPositionAdjusted;
    }

    public String getThumbnailPath() {
        return thumbnailPath;
    }

    public void setThumbnailPath(String thumbnailPath) {
        this.thumbnailPath = thumbnailPath == null ? "" : thumbnailPath;
    }

    public String getAudioPath() {
        return audioPath;
    }

    public void setAudioPath(String audioPath) {
        this.audioPath = audioPath == null ? "" : audioPath;
    }
}
