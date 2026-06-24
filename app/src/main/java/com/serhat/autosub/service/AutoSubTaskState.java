package com.serhat.autosub.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AutoSubTaskState {
    public enum TaskType {
        NONE,
        MODEL_DOWNLOAD,
        MODEL_LOAD,
        SUBTITLE_GENERATION,
        SUBTITLE_SAVE,
        VIDEO_EXPORT,
        BATCH_SUBTITLE_SAVE,
        BATCH_VIDEO_EXPORT,
        GEMMA_MODEL_DOWNLOAD,
        GEMMA_MODEL_LOAD,
        SHORTS_ANALYSIS,
        SHORTS_EXPORT
    }

    private final TaskType taskType;
    private final String title;
    private final String message;
    private final int progress;
    private final long activeQueueItemId;
    private final String activeDownloadModelId;
    private final String downloadSpeedText;
    private final String downloadEtaText;
    private final boolean downloadPaused;
    private final boolean queueRunning;
    private final List<String> queuedDownloadModelIds;

    public AutoSubTaskState(TaskType taskType, String title, String message, int progress,
                            long activeQueueItemId, String activeDownloadModelId,
                            String downloadSpeedText, String downloadEtaText,
                            boolean downloadPaused, boolean queueRunning,
                            List<String> queuedDownloadModelIds) {
        this.taskType = taskType == null ? TaskType.NONE : taskType;
        this.title = title == null ? "" : title;
        this.message = message == null ? "" : message;
        this.progress = progress;
        this.activeQueueItemId = activeQueueItemId;
        this.activeDownloadModelId = activeDownloadModelId;
        this.downloadSpeedText = downloadSpeedText == null ? "" : downloadSpeedText;
        this.downloadEtaText = downloadEtaText == null ? "" : downloadEtaText;
        this.downloadPaused = downloadPaused;
        this.queueRunning = queueRunning;
        this.queuedDownloadModelIds = queuedDownloadModelIds == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(queuedDownloadModelIds));
    }

    public static AutoSubTaskState idle(boolean queueRunning, List<String> queuedDownloadModelIds) {
        return new AutoSubTaskState(TaskType.NONE, "", "", 0, -1, null,
                "", "", false, queueRunning, queuedDownloadModelIds);
    }

    public TaskType getTaskType() { return taskType; }
    public String getTitle() { return title; }
    public String getMessage() { return message; }
    public int getProgress() { return progress; }
    public long getActiveQueueItemId() { return activeQueueItemId; }
    public String getActiveDownloadModelId() { return activeDownloadModelId; }
    public String getDownloadSpeedText() { return downloadSpeedText; }
    public String getDownloadEtaText() { return downloadEtaText; }
    public boolean isDownloadPaused() { return downloadPaused; }
    public boolean isQueueRunning() { return queueRunning; }
    public List<String> getQueuedDownloadModelIds() { return queuedDownloadModelIds; }
}
