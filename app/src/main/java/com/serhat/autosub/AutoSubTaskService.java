package com.serhat.autosub;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.arthenica.ffmpegkit.FFmpegKit;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class AutoSubTaskService extends Service {
    public interface Listener {
        void onTaskStateChanged(AutoSubTaskState state);
        void onQueueItemsChanged(List<QueueItem> items);
        void onModelStateChanged(boolean ready, VoskModelInfo selectedModel, String statusText, String generalText);
        void onCatalogShouldRefresh();
    }

    public static final String ACTION_CANCEL_MEDIA = "com.serhat.autosub.CANCEL_MEDIA";
    public static final String ACTION_PAUSE_DOWNLOAD = "com.serhat.autosub.PAUSE_DOWNLOAD";
    public static final String ACTION_RESUME_DOWNLOAD = "com.serhat.autosub.RESUME_DOWNLOAD";
    public static final String ACTION_CANCEL_DOWNLOAD = "com.serhat.autosub.CANCEL_DOWNLOAD";

    private static final int NOTIFICATION_ID = NotificationHelper.FOREGROUND_SERVICE_NOTIFICATION_ID;
    private static final String MEDIA_WAKE_LOCK_TAG = "AutoSub:MediaProcessing";
    private static final String PREFS_SETTINGS = "autosub_settings";
    private static final String KEY_BATCH_FORMAT = "batch_format";
    private static final String KEY_SUBTITLE_MAX_LENGTH = "subtitle_max_length";
    private static final String KEY_KEEP_SENTENCES_TOGETHER = "keep_sentences_together";
    private static final String KEY_SUPPRESS_WHISPER_SDH = "suppress_whisper_sdh";
    private static final String KEY_WHISPER_VAD_ENABLED = "whisper_vad_enabled";
    private static final String KEY_WHISPER_VAD_MODEL = "whisper_vad_model";
    private static final String KEY_WHISPER_VAD_AGGRESSIVENESS = "whisper_vad_aggressiveness";
    private static final String KEY_WHISPER_LANGUAGE = "whisper_language";
    private static final String KEY_WHISPER_THREAD_COUNT = "whisper_thread_count";
    private static final String KEY_TRANSLATE_SUBTITLES = "translate_subtitles";
    private static final String KEY_TRANSLATION_SOURCE_LANGUAGE = "translation_source_language";
    private static final String KEY_TRANSLATION_TARGET_LANGUAGE = "translation_target_language";
    private static final String KEY_SHOW_COMPLETION_NOTIFICATIONS = "show_completion_notifications";

    private final IBinder binder = new LocalBinder();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final List<VoskModelInfo> downloadQueue = new ArrayList<>();
    private final Set<Long> removedActiveQueueItemIds = new HashSet<>();
    private final Set<Long> cancelledActiveQueueItemIds = new HashSet<>();

    private VoskModelManager modelManager;
    private SubtitleGenerator subtitleGenerator;
    private QueueStore queueStore;
    private ExportStore exportStore;
    private android.content.SharedPreferences settingsPrefs;

    private VoskModelManager.DownloadTask activeDownloadTask;
    private String activeDownloadModelId;
    private int activeDownloadProgress;
    private String activeDownloadSpeedText = "";
    private String activeDownloadEtaText = "";
    private boolean activeDownloadPaused;

    private boolean modelReady;
    private boolean modelLoading;
    private boolean startedForWork;
    private VoskModelInfo selectedModelInfo;
    private String modelStatusText = "";
    private String generalStatusText = "Loading speech model...";
    private boolean queueRunning;
    private boolean batchRunning;
    private boolean queueCancelRequested;
    private QueueItem activeQueueItem;
    private AutoSubTaskState currentState = AutoSubTaskState.idle(false, new ArrayList<>());
    private AutoSubTaskState latestDownloadState;
    private AutoSubTaskState latestMediaState;
    private PowerManager.WakeLock mediaWakeLock;

    public class LocalBinder extends Binder {
        AutoSubTaskService getService() {
            return AutoSubTaskService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        modelManager = new VoskModelManager(this);
        subtitleGenerator = new SubtitleGenerator(this);
        queueStore = new QueueStore(this);
        exportStore = new ExportStore(this);
        settingsPrefs = getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE);
        try {
            modelManager.loadCatalog();
        } catch (IOException ignored) {
        }
        resetStaleQueueItems();
        publishQueueItems();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_CANCEL_MEDIA.equals(action)) {
            cancelCurrentQueueItem();
            cancelMediaWork();
        } else if (ACTION_PAUSE_DOWNLOAD.equals(action)) {
            pauseActiveDownload();
        } else if (ACTION_RESUME_DOWNLOAD.equals(action)) {
            resumeActiveDownload();
        } else if (ACTION_CANCEL_DOWNLOAD.equals(action)) {
            cancelActiveDownload();
        }
        return START_STICKY;
    }

    @Override
    public void onTimeout(int startId) {
        cancelMediaWork();
        stopForegroundAndMaybeSelf();
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        cancelMediaWork();
        stopForegroundAndMaybeSelf();
    }

    @Override
    public void onDestroy() {
        if (activeDownloadTask != null) {
            activeDownloadTask.cancel();
        }
        releaseMediaWakeLock();
        subtitleGenerator.release();
        super.onDestroy();
    }

    public void addListener(Listener listener) {
        if (listener == null || listeners.contains(listener)) {
            return;
        }
        listeners.add(listener);
        listener.onTaskStateChanged(currentState);
        listener.onQueueItemsChanged(queueStore.getItems());
        listener.onModelStateChanged(modelReady, selectedModelInfo, modelStatusText, generalStatusText);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void initializeSelectedModel(boolean allowHeavyModelLoad) {
        VoskModelInfo info = modelManager.getSelectedModel();
        if (info == null) {
            modelReady = false;
            modelLoading = false;
            generalStatusText = "No speech models are available";
            publishModelState();
            publishIdleStateIfNoWork();
            return;
        }

        VoskModelInfo activeModelInfo = selectedModelInfo != null
                ? selectedModelInfo
                : subtitleGenerator.getCurrentModelInfo();
        if ((modelReady || modelLoading || isMediaWorkActive()) && isSameModel(activeModelInfo, info)) {
            selectedModelInfo = activeModelInfo;
            publishModelState();
            if (modelReady) {
                startQueue();
            }
            return;
        }

        selectedModelInfo = info;
        if (shouldDeferHeavyModelLoad(info, allowHeavyModelLoad)) {
            VoskModelInfo fallback = modelManager.findById(VoskModelManager.DEFAULT_MODEL_ID);
            if (fallback != null) {
                modelManager.selectModel(fallback.getId());
                info = fallback;
                selectedModelInfo = info;
            }
        }

        modelReady = false;
        modelLoading = true;
        updateSelectedModelViews(info);
        generalStatusText = "Loading speech model...";
        publishModelState();
        beginForeground(AutoSubTaskState.TaskType.MODEL_LOAD,
                "Loading speech model", info.getLanguage(), -1);

        VoskModelInfo modelToLoad = info;
        subtitleGenerator.initModel(modelToLoad, new SubtitleGenerator.ModelInitCallback() {
            @Override
            public void onModelInitialized() {
                handler.post(() -> {
                    modelReady = true;
                    modelLoading = false;
                    updateSelectedModelViews(modelManager.getSelectedModel());
                    generalStatusText = "Ready. Choose a video to generate subtitles.";
                    publishModelState();
                    publishIdleStateIfNoWork();
                    startQueue();
                });
            }

            @Override
            public void onError(String errorMessage) {
                handler.post(() -> {
                    modelReady = false;
                    modelLoading = false;
                    modelStatusText = "Model error";
                    generalStatusText = "Error initializing model: " + errorMessage;
                    publishModelState();
                    publishIdleStateIfNoWork();
                });
            }
        });
    }

    private boolean isSameModel(VoskModelInfo first, VoskModelInfo second) {
        return first != null && second != null && first.getId().equals(second.getId());
    }

    private boolean isMediaWorkActive() {
        return queueRunning || batchRunning || isMediaTask(currentState.getTaskType());
    }

    public void startModelDownload(VoskModelInfo modelInfo) {
        if (modelInfo == null) {
            publishIdleStateIfNoWork();
            return;
        }
        if (activeDownloadTask != null) {
            if (modelInfo.getId().equals(activeDownloadModelId)) {
                return;
            }
            for (VoskModelInfo queued : downloadQueue) {
                if (queued.getId().equals(modelInfo.getId())) {
                    return;
                }
            }
            downloadQueue.add(modelInfo);
            publishDownloadState("Download queued", modelInfo.getLanguage(), activeDownloadProgress);
            publishCatalogRefresh();
            return;
        }

        beginForeground(AutoSubTaskState.TaskType.MODEL_DOWNLOAD,
                "Downloading Model: " + modelInfo.getLanguage(), "Starting...", 0);
        activeDownloadModelId = modelInfo.getId();
        activeDownloadProgress = 0;
        activeDownloadSpeedText = "";
        activeDownloadEtaText = "";
        activeDownloadPaused = false;
        final long startTime = System.currentTimeMillis();

        activeDownloadTask = modelManager.downloadModel(modelInfo.getId(), new VoskModelManager.DownloadCallback() {
            @Override
            public void onProgress(int progress, long bytesDownloaded, long totalBytes) {
                long now = System.currentTimeMillis();
                double elapsedSec = (now - startTime) / 1000.0;
                String speedStr = "";
                String etaStr = "";
                if (elapsedSec > 0.1 && bytesDownloaded > 0 && totalBytes > 0) {
                    double speedBytesPerSec = bytesDownloaded / elapsedSec;
                    long remainingBytes = Math.max(0, totalBytes - bytesDownloaded);
                    long remainingSec = speedBytesPerSec <= 0 ? 0 : (long) (remainingBytes / speedBytesPerSec);
                    speedStr = speedBytesPerSec < 1024 * 1024
                            ? String.format(Locale.US, "%.1f KB/s", speedBytesPerSec / 1024.0)
                            : String.format(Locale.US, "%.1f MB/s", speedBytesPerSec / (1024.0 * 1024.0));
                    etaStr = remainingSec < 60
                            ? String.format(Locale.US, "%ds left", remainingSec)
                            : remainingSec < 3600
                            ? String.format(Locale.US, "%dm %ds left", remainingSec / 60, remainingSec % 60)
                            : String.format(Locale.US, "%dh %dm left", remainingSec / 3600, (remainingSec % 3600) / 60);
                }
                final String speed = speedStr;
                final String eta = etaStr;
                handler.post(() -> {
                    activeDownloadProgress = progress;
                    activeDownloadSpeedText = speed;
                    activeDownloadEtaText = eta;
                    activeDownloadPaused = false;
                    String content = progress + "%";
                    if (!speed.isEmpty() || !eta.isEmpty()) {
                        content += " (" + speed + " - " + eta + ")";
                    }
                    publishDownloadState("Downloading Model: " + modelInfo.getLanguage(), content, progress);
                });
            }

            @Override
            public void onComplete(VoskModelInfo downloadedModel) {
                handler.post(() -> {
                    clearActiveDownload();
                    showSuccessNotificationIfEnabled(1001, "Model Download Complete",
                            downloadedModel.getLanguage() + " model downloaded successfully.");
                    updateSelectedModelViews(modelManager.getSelectedModel());
                    publishModelState();
                    publishCatalogRefresh();
                    processNextDownloadQueue();
                    publishIdleStateIfNoWork();
                });
            }

            @Override
            public void onCancelled() {
                handler.post(() -> {
                    clearActiveDownload();
                    publishCatalogRefresh();
                    processNextDownloadQueue();
                    publishIdleStateIfNoWork();
                });
            }

            @Override
            public void onPaused() {
                handler.post(() -> {
                    activeDownloadTask = null;
                    activeDownloadModelId = modelInfo.getId();
                    activeDownloadSpeedText = "Paused";
                    activeDownloadEtaText = "";
                    activeDownloadPaused = true;
                    publishDownloadState("Download Paused: " + modelInfo.getLanguage(),
                            activeDownloadProgress + "%", activeDownloadProgress);
                    publishCatalogRefresh();
                });
            }

            @Override
            public void onError(String errorMessage) {
                handler.post(() -> {
                    clearActiveDownload();
                    publishCatalogRefresh();
                    processNextDownloadQueue();
                    publishIdleStateIfNoWork();
                });
            }
        });
    }

    public void pauseActiveDownload() {
        if (activeDownloadTask != null) {
            activeDownloadTask.pause();
            activeDownloadPaused = true;
            publishDownloadState(currentState.getTitle(), activeDownloadProgress + "%", activeDownloadProgress);
        }
    }

    public void resumeActiveDownload() {
        if (activeDownloadTask != null || activeDownloadModelId == null) {
            return;
        }
        VoskModelInfo info = modelManager.findById(activeDownloadModelId);
        if (info != null) {
            startModelDownload(info);
        }
    }

    public void cancelActiveDownload() {
        if (activeDownloadTask != null) {
            activeDownloadTask.cancel();
        } else {
            clearActiveDownload();
            processNextDownloadQueue();
            publishIdleStateIfNoWork();
        }
        activeDownloadPaused = false;
    }

    public void cancelQueuedDownload(String modelId) {
        downloadQueue.removeIf(model -> model.getId().equals(modelId));
        publishDownloadState(currentState.getTitle(), currentState.getMessage(), currentState.getProgress());
        publishCatalogRefresh();
    }

    public void startQueue() {
        if (!modelReady || queueRunning) {
            return;
        }
        List<QueueItem> items = queueStore.getItems();
        boolean hasPending = false;
        for (QueueItem item : items) {
            if (item.getStatus() == QueueItem.Status.PENDING) {
                hasPending = true;
                break;
            }
        }
        if (!hasPending) {
            return;
        }
        beginForeground(AutoSubTaskState.TaskType.SUBTITLE_GENERATION,
                "Generating Subtitles", "Starting queue...", -1);
        queueRunning = true;
        queueCancelRequested = false;
        processNextQueueItem();
    }

    public void cancelCurrentQueueItem() {
        if (activeQueueItem != null) {
            queueCancelRequested = true;
            subtitleGenerator.cancelGeneration();
            if (isRemovedActiveQueueItem(activeQueueItem)) {
                finishRemovedActiveQueueItem(activeQueueItem);
            } else {
                cancelledActiveQueueItemIds.add(activeQueueItem.getId());
                activeQueueItem.setStatus(QueueItem.Status.CANCELLED);
                activeQueueItem.setMessage("Cancelled");
                activeQueueItem.setProgress(0);
                queueStore.updateItem(activeQueueItem);
                activeQueueItem = null;
                queueRunning = false;
                queueCancelRequested = false;
                publishQueueItems();
                publishIdleStateIfNoWork();
            }
        }
    }

    public void cancelMediaWork() {
        subtitleGenerator.cancelGeneration();
        FFmpegKit.cancel();
    }

    public void savePreviewSubtitles(List<SubtitleGenerator.SubtitleEntry> entries, String format, Uri videoUri,
                                     File outputDir, VoskModelInfo modelInfo,
                                     SubtitleGenerator.SubtitleSaveCallback callback) {
        savePreviewSubtitles(entries, format, videoUri, outputDir, modelInfo,
                SubtitleGenerator.SubtitleLayerMode.ORIGINAL, callback);
    }

    public void savePreviewSubtitles(List<SubtitleGenerator.SubtitleEntry> entries, String format, Uri videoUri,
                                     File outputDir, VoskModelInfo modelInfo,
                                     SubtitleGenerator.SubtitleLayerMode layerMode,
                                     SubtitleGenerator.SubtitleSaveCallback callback) {
        if (videoUri == null || entries == null || entries.isEmpty()) {
            callback.onError("No video or subtitles available to save");
            return;
        }
        beginForeground(AutoSubTaskState.TaskType.SUBTITLE_SAVE,
                "Saving Subtitles", format.toUpperCase(Locale.getDefault()), -1);
        subtitleGenerator.saveSubtitlesToFile(entries, format, videoUri, outputDir, layerMode, new SubtitleGenerator.SubtitleSaveCallback() {
            @Override
            public void onSubtitlesSaved(String filePath) {
                handler.post(() -> {
                    registerExport(filePath, ExportRecord.TYPE_SUBTITLE, videoUri, getDisplayNameHelper(videoUri),
                            format.toLowerCase(Locale.getDefault()) + "-" + layerMode.name().toLowerCase(Locale.US) + "-subtitles", format, modelInfo);
                    publishIdleStateIfNoWork();
                    callback.onSubtitlesSaved(filePath);
                });
            }

            @Override
            public void onError(String errorMessage) {
                handler.post(() -> {
                    publishIdleStateIfNoWork();
                    callback.onError(errorMessage);
                });
            }
        });
    }

    public void exportPreviewVideo(Uri videoUri, List<SubtitleGenerator.SubtitleEntry> entries, boolean burnSubtitles,
                                   String fontName, SubtitleGenerator.ShortsSubtitleStyle shortsStyle,
                                   boolean forceMp4SoftSubtitles, File outputDir, VoskModelInfo modelInfo,
                                   SubtitleGenerator.VideoExportCallback callback) {
        exportPreviewVideo(videoUri, entries, burnSubtitles, fontName, shortsStyle, forceMp4SoftSubtitles,
                outputDir, modelInfo, SubtitleGenerator.SubtitleLayerMode.ORIGINAL, callback);
    }

    public void exportPreviewVideo(Uri videoUri, List<SubtitleGenerator.SubtitleEntry> entries, boolean burnSubtitles,
                                   String fontName, SubtitleGenerator.ShortsSubtitleStyle shortsStyle,
                                   boolean forceMp4SoftSubtitles, File outputDir, VoskModelInfo modelInfo,
                                   SubtitleGenerator.SubtitleLayerMode layerMode,
                                   SubtitleGenerator.VideoExportCallback callback) {
        if (videoUri == null || entries == null || entries.isEmpty()) {
            callback.onError("No video or subtitles available to export");
            return;
        }
        beginForeground(AutoSubTaskState.TaskType.VIDEO_EXPORT,
                "Exporting Video", burnSubtitles ? "Hard subtitles" : "Soft subtitles", -1);
        subtitleGenerator.exportVideoWithSubtitles(videoUri, entries, burnSubtitles, fontName, shortsStyle,
                forceMp4SoftSubtitles, outputDir, layerMode, new SubtitleGenerator.VideoExportCallback() {
                    @Override
                    public void onVideoExported(String filePath) {
                        handler.post(() -> {
                            registerExport(filePath, ExportRecord.TYPE_VIDEO, videoUri, getDisplayNameHelper(videoUri),
                                    (burnSubtitles ? "hard-" : "soft-") + layerMode.name().toLowerCase(Locale.US) + "-subtitles",
                                    filePath.toLowerCase(Locale.getDefault()).endsWith(".mkv") ? "mkv" : "mp4", modelInfo);
                            showSuccessNotificationIfEnabled(3001, "Video Export Complete", "Video exported successfully.");
                            publishIdleStateIfNoWork();
                            callback.onVideoExported(filePath);
                        });
                    }

                    @Override
                    public void onError(String errorMessage) {
                        handler.post(() -> {
                            publishIdleStateIfNoWork();
                            callback.onError(errorMessage);
                        });
                    }

                    @Override
                    public void onProgressUpdate(int progress) {
                        handler.post(() -> {
                            publishState(new AutoSubTaskState(AutoSubTaskState.TaskType.VIDEO_EXPORT,
                                    "Exporting Video", progress < 0 ? "Working..." : progress + "%",
                                    progress, -1, activeDownloadModelId, activeDownloadSpeedText,
                                    activeDownloadEtaText, activeDownloadPaused, queueRunning, queuedDownloadIds()));
                            callback.onProgressUpdate(progress);
                        });
                    }
                });
    }

    public void saveSubtitlesForQueueItem(QueueItem item, String format, File outputDir, VoskModelInfo modelInfo,
                                          SubtitleGenerator.SubtitleSaveCallback callback) {
        saveSubtitlesForQueueItem(item, format, outputDir, modelInfo,
                SubtitleGenerator.SubtitleLayerMode.ORIGINAL, callback);
    }

    public void saveSubtitlesForQueueItem(QueueItem item, String format, File outputDir, VoskModelInfo modelInfo,
                                          SubtitleGenerator.SubtitleLayerMode layerMode,
                                          SubtitleGenerator.SubtitleSaveCallback callback) {
        if (item == null || item.getVideoUri() == null || item.getSubtitles().isEmpty()) {
            callback.onError("No video or subtitles available to save");
            return;
        }
        beginForeground(AutoSubTaskState.TaskType.SUBTITLE_SAVE,
                "Saving Subtitles: " + item.getDisplayName(), format.toUpperCase(Locale.getDefault()), -1);
        saveSubtitlesForQueueItemInternal(item, format, outputDir, modelInfo, layerMode, callback);
    }

    public void exportVideoForQueueItem(QueueItem item, boolean burnSubtitles, String fontName,
                                        SubtitleGenerator.ShortsSubtitleStyle shortsStyle,
                                        boolean forceMp4SoftSubtitles, File outputDir, VoskModelInfo modelInfo,
                                        SubtitleGenerator.VideoExportCallback callback) {
        exportVideoForQueueItem(item, burnSubtitles, fontName, shortsStyle, forceMp4SoftSubtitles,
                outputDir, modelInfo, SubtitleGenerator.SubtitleLayerMode.ORIGINAL, callback);
    }

    public void exportVideoForQueueItem(QueueItem item, boolean burnSubtitles, String fontName,
                                        SubtitleGenerator.ShortsSubtitleStyle shortsStyle,
                                        boolean forceMp4SoftSubtitles, File outputDir, VoskModelInfo modelInfo,
                                        SubtitleGenerator.SubtitleLayerMode layerMode,
                                        SubtitleGenerator.VideoExportCallback callback) {
        if (item == null || item.getVideoUri() == null || item.getSubtitles().isEmpty()) {
            callback.onError("No video or subtitles available to export");
            return;
        }
        beginForeground(AutoSubTaskState.TaskType.VIDEO_EXPORT,
                "Exporting Video: " + item.getDisplayName(), "Starting...", -1);
        exportVideoForQueueItemInternal(item, burnSubtitles, fontName, shortsStyle,
                forceMp4SoftSubtitles, outputDir, modelInfo, layerMode, callback);
    }

    public void translateQueueItem(QueueItem item, String sourceLanguage, String targetLanguage,
                                   SubtitleGenerator.TranslationCallback callback) {
        if (item == null || item.getSubtitles().isEmpty()) {
            callback.onError("No subtitles available to translate");
            return;
        }
        item.setStatus(QueueItem.Status.TRANSLATING);
        item.setProgress(-1);
        item.setMessage("Translating subtitles...");
        item.setTranslationStatus("translating");
        queueStore.updateItem(item);
        publishQueueItems();
        beginForeground(AutoSubTaskState.TaskType.SUBTITLE_SAVE,
                "Translating Subtitles: " + item.getDisplayName(), "Starting...", -1);

        subtitleGenerator.setTranslationSettings(true, sourceLanguage, targetLanguage);
        subtitleGenerator.translateExistingSubtitles(item.getSubtitles(), new SubtitleGenerator.TranslationCallback() {
            @Override
            public void onTranslated(List<SubtitleGenerator.SubtitleEntry> subtitleEntries, String resolvedSourceLanguage, String resolvedTargetLanguage) {
                handler.post(() -> {
                    item.setSubtitles(subtitleEntries);
                    item.setTranslationSourceLanguage(resolvedSourceLanguage);
                    item.setTranslationTargetLanguage(resolvedTargetLanguage);
                    item.setTranslationStatus("translated");
                    item.setPreviewText(getPreviewTextHelper(subtitleEntries));
                    item.setStatus(QueueItem.Status.COMPLETED);
                    item.setProgress(100);
                    item.setMessage("Translated subtitles");
                    queueStore.updateItem(item);
                    publishQueueItems();
                    publishIdleStateIfNoWork();
                    callback.onTranslated(subtitleEntries, resolvedSourceLanguage, resolvedTargetLanguage);
                });
            }

            @Override
            public void onError(String errorMessage) {
                handler.post(() -> {
                    item.setStatus(QueueItem.Status.COMPLETED);
                    item.setProgress(100);
                    item.setTranslationStatus("failed");
                    item.setMessage("Translation failed: " + errorMessage);
                    queueStore.updateItem(item);
                    publishQueueItems();
                    publishIdleStateIfNoWork();
                    callback.onError(errorMessage);
                });
            }

            @Override
            public void onProgressUpdate(int progress) {
                handler.post(() -> {
                    item.setProgress(progress);
                    item.setMessage(progress < 0 ? "Translating subtitles..." : "Translating subtitles... " + progress + "%");
                    queueStore.updateItem(item);
                    publishQueueItems();
                    publishState(new AutoSubTaskState(AutoSubTaskState.TaskType.SUBTITLE_SAVE,
                            "Translating Subtitles: " + item.getDisplayName(), item.getMessage(),
                            progress, item.getId(), activeDownloadModelId, activeDownloadSpeedText,
                            activeDownloadEtaText, activeDownloadPaused, queueRunning, queuedDownloadIds()));
                    callback.onProgressUpdate(progress);
                });
            }
        });
    }

    public void batchSaveSubtitles(List<QueueItem> items, String format, File outputDir, VoskModelInfo modelInfo,
                                   SubtitleGenerator.SubtitleSaveCallback callback) {
        if (items == null || items.isEmpty()) {
            callback.onError("No completed items with subtitles to export");
            return;
        }
        beginForeground(AutoSubTaskState.TaskType.BATCH_SUBTITLE_SAVE,
                "Batch Subtitle Export", "Starting...", -1);
        batchRunning = true;
        batchSaveNext(items, 0, format, outputDir, modelInfo, callback);
    }

    public void batchExportVideos(List<QueueItem> items, boolean burnSubtitles, String fontName, File outputDir,
                                  VoskModelInfo modelInfo, BatchStyleResolver styleResolver,
                                  SubtitleGenerator.VideoExportCallback callback) {
        if (items == null || items.isEmpty()) {
            callback.onError("No completed items with subtitles to export");
            return;
        }
        beginForeground(AutoSubTaskState.TaskType.BATCH_VIDEO_EXPORT,
                "Batch Video Export", "Starting...", -1);
        batchRunning = true;
        batchExportNext(items, 0, burnSubtitles, fontName, outputDir, modelInfo, styleResolver, callback);
    }

    public interface BatchStyleResolver {
        SubtitleGenerator.ShortsSubtitleStyle styleFor(QueueItem item);
    }

    private void processNextQueueItem() {
        if (queueCancelRequested) {
            queueRunning = false;
            activeQueueItem = null;
            publishQueueItems();
            publishIdleStateIfNoWork();
            return;
        }

        QueueItem next = null;
        for (QueueItem item : queueStore.getItems()) {
            if (item.getStatus() == QueueItem.Status.PENDING) {
                next = item;
                break;
            }
        }
        if (next == null) {
            queueRunning = false;
            activeQueueItem = null;
            publishQueueItems();
            publishIdleStateIfNoWork();
            return;
        }

        QueueItem queueItem = next;
        activeQueueItem = queueItem;
        queueItem.setStatus(QueueItem.Status.PROCESSING);
        queueItem.setProgress(-1);
        queueItem.setMessage("Extracting audio...");
        String permanentAudioPath = new File(getFilesDir(), "audio_" + queueItem.getId() + ".wav").getAbsolutePath();
        queueItem.setAudioPath(permanentAudioPath);
        queueStore.updateItem(queueItem);
        publishQueueItems();

        // The item's shorts flag already captures the word-by-word choice made when it was queued.
        boolean useWordByWord = queueItem.isShortsVideo();
        subtitleGenerator.setWordByWordMode(useWordByWord);
        subtitleGenerator.setMaxSubtitleLength(settingsPrefs.getInt(
                KEY_SUBTITLE_MAX_LENGTH, SubtitleGenerator.DEFAULT_MAX_SUBTITLE_LENGTH));
        subtitleGenerator.setKeepSentencesTogether(settingsPrefs.getBoolean(
                KEY_KEEP_SENTENCES_TOGETHER, SubtitleGenerator.DEFAULT_KEEP_SENTENCES_TOGETHER));
        subtitleGenerator.setSuppressWhisperSdh(settingsPrefs.getBoolean(KEY_SUPPRESS_WHISPER_SDH, true));
        subtitleGenerator.setWhisperVadEnabled(
                settingsPrefs.getBoolean(KEY_WHISPER_VAD_ENABLED, false) || queueItem.isUseVad());
        subtitleGenerator.setWhisperVadModel(settingsPrefs.getString(
                KEY_WHISPER_VAD_MODEL, SubtitleGenerator.VAD_MODEL_WEBRTC));
        subtitleGenerator.setWhisperVadAggressiveness(settingsPrefs.getString(
                KEY_WHISPER_VAD_AGGRESSIVENESS, SubtitleGenerator.VAD_AGGRESSIVENESS_NORMAL));
        subtitleGenerator.setWhisperLanguage(settingsPrefs.getString(KEY_WHISPER_LANGUAGE, "auto"));
        subtitleGenerator.setWhisperThreadCount(settingsPrefs.getInt(KEY_WHISPER_THREAD_COUNT, 0));
        subtitleGenerator.setTranslationSettings(
                settingsPrefs.getBoolean(KEY_TRANSLATE_SUBTITLES, false),
                settingsPrefs.getString(KEY_TRANSLATION_SOURCE_LANGUAGE, "auto"),
                settingsPrefs.getString(KEY_TRANSLATION_TARGET_LANGUAGE,
                        SubtitleGenerator.getDefaultTranslationTargetLanguage()));
        publishState(new AutoSubTaskState(AutoSubTaskState.TaskType.SUBTITLE_GENERATION,
                "Generating Subtitles: " + queueItem.getDisplayName(), "Extracting audio...",
                -1, queueItem.getId(), activeDownloadModelId, activeDownloadSpeedText,
                activeDownloadEtaText, activeDownloadPaused, true, queuedDownloadIds()));

        subtitleGenerator.generateSubtitles(queueItem.getVideoUri(), permanentAudioPath, new SubtitleGenerator.SubtitleGenerationCallback() {
            @Override
            public void onPartialSubtitlesGenerated(List<SubtitleGenerator.SubtitleEntry> partialSubtitles) {
                handler.post(() -> {
                    if (isRemovedActiveQueueItem(queueItem) || isCancelledActiveQueueItem(queueItem)) return;
                    queueItem.setSubtitles(partialSubtitles);
                    queueItem.setPreviewText(getPreviewTextHelper(partialSubtitles));
                    publishQueueItems(queueItem);
                });
            }

            @Override
            public void onSubtitlesGenerated(List<SubtitleGenerator.SubtitleEntry> entries) {
                if (isRemovedActiveQueueItem(queueItem)) {
                    handler.post(() -> finishRemovedActiveQueueItem(queueItem));
                    return;
                }
                if (isCancelledActiveQueueItem(queueItem)) {
                    return;
                }
                queueItem.setSubtitles(entries);
                queueItem.setTranslationSourceLanguage(subtitleGenerator.getResolvedTranslationSourceLanguage());
                queueItem.setTranslationTargetLanguage(subtitleGenerator.getTranslationTargetLanguage());
                queueItem.setTranslationStatus(SubtitleGenerator.hasTranslatedSubtitles(entries) ? "translated" : "");
                queueItem.setPreviewText(getPreviewTextHelper(entries));
                saveSubtitlesForQueueItemInternal(queueItem, currentBatchFormat(), null, selectedModelInfo,
                        SubtitleGenerator.SubtitleLayerMode.ORIGINAL,
                        new SubtitleGenerator.SubtitleSaveCallback() {
                            @Override
                            public void onSubtitlesSaved(String filePath) {
                                handler.post(() -> {
                                    if (isRemovedActiveQueueItem(queueItem)) {
                                        finishRemovedActiveQueueItem(queueItem);
                                        return;
                                    }
                                    if (isCancelledActiveQueueItem(queueItem)) {
                                        return;
                                    }
                                    queueItem.setStatus(QueueItem.Status.COMPLETED);
                                    queueItem.setProgress(100);
                                    queueItem.setOutputPath(filePath);
                                    queueItem.setMessage("");
                                    String format = currentBatchFormat().toLowerCase(Locale.getDefault());
                                    if ("srt".equals(format)) queueItem.setSrtPath(filePath);
                                    if ("vtt".equals(format)) queueItem.setVttPath(filePath);
                                    queueStore.updateItem(queueItem);
                                    showSuccessNotificationIfEnabled(2001,
                                            "Subtitles Generated", "Subtitles saved for " + queueItem.getDisplayName());
                                    publishQueueItems();
                                    processNextQueueItem();
                                });
                            }

                            @Override
                            public void onError(String errorMessage) {
                                handler.post(() -> {
                                    if (isRemovedActiveQueueItem(queueItem)) {
                                        finishRemovedActiveQueueItem(queueItem);
                                        return;
                                    }
                                    if (isCancelledActiveQueueItem(queueItem)) {
                                        return;
                                    }
                                    queueItem.setStatus(QueueItem.Status.FAILED);
                                    queueItem.setMessage(errorMessage);
                                    queueStore.updateItem(queueItem);
                                    publishQueueItems();
                                    processNextQueueItem();
                                });
                            }
                        });
            }

            @Override
            public void onError(String errorMessage) {
                handler.post(() -> {
                    if (isRemovedActiveQueueItem(queueItem)) {
                        finishRemovedActiveQueueItem(queueItem);
                        return;
                    }
                    if (isCancelledActiveQueueItem(queueItem)) {
                        return;
                    }
                    queueItem.setStatus(QueueItem.Status.FAILED);
                    queueItem.setMessage(errorMessage);
                    queueStore.updateItem(queueItem);
                    publishQueueItems();
                    processNextQueueItem();
                });
            }

            @Override
            public void onProgressUpdate(int progress) {
                handler.post(() -> {
                    if (isRemovedActiveQueueItem(queueItem) || isCancelledActiveQueueItem(queueItem)) return;
                    String progressMessage = subtitleProgressMessage(progress);
                    queueItem.setMessage(progressMessage);
                    queueItem.setProgress(progress);
                    publishQueueItems(queueItem);
                    publishState(new AutoSubTaskState(AutoSubTaskState.TaskType.SUBTITLE_GENERATION,
                            "Generating Subtitles: " + queueItem.getDisplayName(),
                            progress < 0 ? progressMessage : progressMessage + " " + progress + "%",
                            progress, queueItem.getId(), activeDownloadModelId, activeDownloadSpeedText,
                            activeDownloadEtaText, activeDownloadPaused, true, queuedDownloadIds()));
                });
            }

            @Override
            public void onCancelled() {
                handler.post(() -> {
                    if (isRemovedActiveQueueItem(queueItem)) {
                        finishRemovedActiveQueueItem(queueItem);
                        return;
                    }
                    if (isCancelledActiveQueueItem(queueItem)) {
                        cancelledActiveQueueItemIds.remove(queueItem.getId());
                        return;
                    }
                    queueItem.setStatus(QueueItem.Status.CANCELLED);
                    queueItem.setMessage("Cancelled");
                    queueStore.updateItem(queueItem);
                    activeQueueItem = null;
                    queueRunning = false;
                    publishQueueItems();
                    publishIdleStateIfNoWork();
                });
            }
        });
    }

    private String subtitleProgressMessage(int progress) {
        if (progress == SubtitleGenerator.PROGRESS_TRANSLATING) {
            return "Translating subtitles...";
        }
        if (SubtitleGenerator.isScanningSpeechProgress(progress)) {
            return "Detecting speech...";
        }
        if (progress == SubtitleGenerator.PROGRESS_DETECTING_LANGUAGE) {
            return "Detecting language...";
        }
        if (progress == SubtitleGenerator.PROGRESS_PREPARING_AUDIO) {
            return "Preparing audio...";
        }
        if (progress < 0) {
            return "Extracting audio...";
        }
        return "Generating subtitles...";
    }

    private void saveSubtitlesForQueueItemInternal(QueueItem item, String format, File outputDir, VoskModelInfo modelInfo,
                                                   SubtitleGenerator.SubtitleLayerMode layerMode,
                                                   SubtitleGenerator.SubtitleSaveCallback callback) {
        item.setStatus(QueueItem.Status.EXPORTING);
        item.setProgress(0);
        item.setMessage("Saving subtitles...");
        queueStore.updateItem(item);
        publishQueueItems();

        subtitleGenerator.saveSubtitlesToFile(item.getSubtitles(), format, item.getVideoUri(), outputDir, layerMode, new SubtitleGenerator.SubtitleSaveCallback() {
            @Override
            public void onSubtitlesSaved(String filePath) {
                handler.post(() -> {
                    registerExport(filePath, ExportRecord.TYPE_SUBTITLE, item.getVideoUri(), item.getDisplayName(),
                            format.toLowerCase(Locale.getDefault()) + "-" + layerMode.name().toLowerCase(Locale.US) + "-subtitles", format, modelInfo);
                    String f = format.toLowerCase(Locale.getDefault());
                    if ("srt".equals(f) && layerMode == SubtitleGenerator.SubtitleLayerMode.ORIGINAL) item.setSrtPath(filePath);
                    if ("vtt".equals(f) && layerMode == SubtitleGenerator.SubtitleLayerMode.ORIGINAL) item.setVttPath(filePath);
                    item.setStatus(QueueItem.Status.COMPLETED);
                    item.setProgress(100);
                    item.setOutputPath(filePath);
                    item.setMessage("Subtitles saved: " + filePath);
                    queueStore.updateItem(item);
                    publishQueueItems();
                    publishIdleStateIfNoWork();
                    callback.onSubtitlesSaved(filePath);
                });
            }

            @Override
            public void onError(String errorMessage) {
                handler.post(() -> {
                    item.setStatus(QueueItem.Status.COMPLETED);
                    item.setMessage("Failed to save: " + errorMessage);
                    queueStore.updateItem(item);
                    publishQueueItems();
                    publishIdleStateIfNoWork();
                    callback.onError(errorMessage);
                });
            }
        });
    }

    private void exportVideoForQueueItemInternal(QueueItem item, boolean burnSubtitles, String fontName,
                                                 SubtitleGenerator.ShortsSubtitleStyle shortsStyle,
                                                 boolean forceMp4SoftSubtitles, File outputDir, VoskModelInfo modelInfo,
                                                 SubtitleGenerator.SubtitleLayerMode layerMode,
                                                 SubtitleGenerator.VideoExportCallback callback) {
        item.setStatus(QueueItem.Status.EXPORTING);
        item.setProgress(-1);
        item.setMessage("Exporting video...");
        queueStore.updateItem(item);
        publishQueueItems();

        subtitleGenerator.exportVideoWithSubtitles(item.getVideoUri(), item.getSubtitles(), burnSubtitles, fontName,
                shortsStyle, forceMp4SoftSubtitles, outputDir, layerMode, new SubtitleGenerator.VideoExportCallback() {
                    @Override
                    public void onVideoExported(String filePath) {
                        handler.post(() -> {
                            registerExport(filePath, ExportRecord.TYPE_VIDEO, item.getVideoUri(), item.getDisplayName(),
                                    (burnSubtitles ? "hard-" : "soft-") + layerMode.name().toLowerCase(Locale.US) + "-subtitles",
                                    filePath.toLowerCase(Locale.getDefault()).endsWith(".mkv") ? "mkv" : "mp4", modelInfo);
                            if (burnSubtitles) item.setHardVideoPath(filePath);
                            else item.setSoftVideoPath(filePath);
                            item.setStatus(QueueItem.Status.COMPLETED);
                            item.setProgress(100);
                            item.setMessage("Video exported: " + filePath);
                            queueStore.updateItem(item);
                            publishQueueItems();
                            showSuccessNotificationIfEnabled(3001,
                                    "Video Export Complete", "Video exported successfully: " + item.getDisplayName());
                            publishIdleStateIfNoWork();
                            callback.onVideoExported(filePath);
                        });
                    }

                    @Override
                    public void onError(String errorMessage) {
                        handler.post(() -> {
                            item.setStatus(QueueItem.Status.COMPLETED);
                            item.setMessage("Failed to export: " + errorMessage);
                            queueStore.updateItem(item);
                            publishQueueItems();
                            publishIdleStateIfNoWork();
                            callback.onError(errorMessage);
                        });
                    }

                    @Override
                    public void onProgressUpdate(int progress) {
                        handler.post(() -> {
                            item.setProgress(progress);
                            item.setMessage(progress < 0 ? "Exporting video..." : "Exporting video... " + progress + "%");
                            queueStore.updateItem(item);
                            publishQueueItems();
                            publishState(new AutoSubTaskState(AutoSubTaskState.TaskType.VIDEO_EXPORT,
                                    "Exporting Video: " + item.getDisplayName(), item.getMessage(),
                                    progress, item.getId(), activeDownloadModelId, activeDownloadSpeedText,
                                    activeDownloadEtaText, activeDownloadPaused, queueRunning, queuedDownloadIds()));
                            callback.onProgressUpdate(progress);
                        });
                    }
                });
    }

    private void batchSaveNext(List<QueueItem> items, int index, String format, File outputDir,
                               VoskModelInfo modelInfo, SubtitleGenerator.SubtitleSaveCallback callback) {
        if (index >= items.size()) {
            batchRunning = false;
            publishIdleStateIfNoWork();
            callback.onSubtitlesSaved("All subtitles exported successfully!");
            return;
        }
        QueueItem item = items.get(index);
        publishState(new AutoSubTaskState(AutoSubTaskState.TaskType.BATCH_SUBTITLE_SAVE,
                "Batch Subtitle Export", (index + 1) + " of " + items.size() + ": " + item.getDisplayName(),
                -1, item.getId(), activeDownloadModelId, activeDownloadSpeedText, activeDownloadEtaText,
                activeDownloadPaused, queueRunning, queuedDownloadIds()));
        saveSubtitlesForQueueItemInternal(item, format, outputDir, modelInfo, SubtitleGenerator.SubtitleLayerMode.ORIGINAL, new SubtitleGenerator.SubtitleSaveCallback() {
            @Override
            public void onSubtitlesSaved(String filePath) {
                handler.post(() -> batchSaveNext(items, index + 1, format, outputDir, modelInfo, callback));
            }

            @Override
            public void onError(String errorMessage) {
                handler.post(() -> batchSaveNext(items, index + 1, format, outputDir, modelInfo, callback));
            }
        });
    }

    private void batchExportNext(List<QueueItem> items, int index, boolean burnSubtitles, String fontName,
                                 File outputDir, VoskModelInfo modelInfo, BatchStyleResolver styleResolver,
                                 SubtitleGenerator.VideoExportCallback callback) {
        if (index >= items.size()) {
            batchRunning = false;
            publishIdleStateIfNoWork();
            callback.onVideoExported("All videos exported successfully!");
            return;
        }
        QueueItem item = items.get(index);
        publishState(new AutoSubTaskState(AutoSubTaskState.TaskType.BATCH_VIDEO_EXPORT,
                "Batch Video Export", (index + 1) + " of " + items.size() + ": " + item.getDisplayName(),
                -1, item.getId(), activeDownloadModelId, activeDownloadSpeedText, activeDownloadEtaText,
                activeDownloadPaused, queueRunning, queuedDownloadIds()));
        SubtitleGenerator.ShortsSubtitleStyle style = styleResolver == null ? null : styleResolver.styleFor(item);
        exportVideoForQueueItemInternal(item, burnSubtitles, fontName, style, false, outputDir, modelInfo,
                SubtitleGenerator.SubtitleLayerMode.ORIGINAL,
                new SubtitleGenerator.VideoExportCallback() {
                    @Override
                    public void onVideoExported(String filePath) {
                        handler.post(() -> batchExportNext(items, index + 1, burnSubtitles, fontName, outputDir, modelInfo, styleResolver, callback));
                    }

                    @Override
                    public void onError(String errorMessage) {
                        handler.post(() -> batchExportNext(items, index + 1, burnSubtitles, fontName, outputDir, modelInfo, styleResolver, callback));
                    }

                    @Override
                    public void onProgressUpdate(int progress) {
                        callback.onProgressUpdate(progress);
                    }
                });
    }

    private boolean shouldDeferHeavyModelLoad(VoskModelInfo modelInfo, boolean allowHeavyModelLoad) {
        return !allowHeavyModelLoad
                && modelInfo != null
                && !modelInfo.isBundled()
                && modelManager.getModelLoadMode(modelInfo) == VoskModelManager.ModelLoadMode.FULL_QUALITY
                && (modelInfo.isVeryLarge() || !modelInfo.isMobileRecommended());
    }

    private void updateSelectedModelViews(VoskModelInfo info) {
        selectedModelInfo = info;
        if (info == null) return;
        String status = info.getId() + " - " + info.getSize();
        if (info.isBundled()) status += " - Bundled";
        else if (modelManager.isInstalled(info.getId())) status += " - Downloaded";
        VoskModelManager.ModelLoadMode loadMode = modelManager.getModelLoadMode(info);
        if (loadMode != VoskModelManager.ModelLoadMode.FULL_QUALITY) {
            status += " - " + loadMode.getLabel();
        }
        modelStatusText = status;
    }

    private void beginForeground(AutoSubTaskState.TaskType taskType, String title, String message, int progress) {
        if (!startedForWork) {
            startedForWork = true;
            startService(new Intent(this, AutoSubTaskService.class));
        }
        publishState(new AutoSubTaskState(taskType, title, message, progress, -1, activeDownloadModelId,
                activeDownloadSpeedText, activeDownloadEtaText, activeDownloadPaused, queueRunning,
                queuedDownloadIds()));
    }

    private void publishState(AutoSubTaskState state) {
        currentState = state;
        rememberNotificationLane(state);
        AutoSubTaskState foregroundState = foregroundStateForNotifications(state);
        updateForegroundNotification(foregroundState);
        updateSecondaryNotifications(foregroundState);
        updateMediaWakeLock();
        for (Listener listener : new ArrayList<>(listeners)) {
            listener.onTaskStateChanged(state);
        }
    }

    private void publishDownloadState(String title, String message, int progress) {
        publishState(new AutoSubTaskState(AutoSubTaskState.TaskType.MODEL_DOWNLOAD, title, message, progress,
                -1, activeDownloadModelId, activeDownloadSpeedText, activeDownloadEtaText,
                activeDownloadPaused, queueRunning, queuedDownloadIds()));
    }

    private void publishQueueItems() {
        List<QueueItem> items = queueStore.getItems();
        for (Listener listener : new ArrayList<>(listeners)) {
            listener.onQueueItemsChanged(items);
        }
    }

    private void publishQueueItems(QueueItem activeItemSnapshot) {
        List<QueueItem> items = queueStore.getItems();
        if (activeItemSnapshot != null) {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).getId() == activeItemSnapshot.getId()) {
                    items.set(i, activeItemSnapshot);
                    break;
                }
            }
        }
        for (Listener listener : new ArrayList<>(listeners)) {
            listener.onQueueItemsChanged(items);
        }
    }

    private void publishModelState() {
        for (Listener listener : new ArrayList<>(listeners)) {
            listener.onModelStateChanged(modelReady, selectedModelInfo, modelStatusText, generalStatusText);
        }
    }

    private void publishCatalogRefresh() {
        for (Listener listener : new ArrayList<>(listeners)) {
            listener.onCatalogShouldRefresh();
        }
    }

    private void publishIdleStateIfNoWork() {
        if (!queueRunning && !batchRunning) {
            clearMediaNotificationLane();
        }
        if (activeDownloadTask == null && activeDownloadModelId == null) {
            clearDownloadNotificationLane();
        }
        if (queueRunning || batchRunning || activeDownloadTask != null || modelLoading) {
            return;
        }
        publishState(AutoSubTaskState.idle(false, queuedDownloadIds()));
        stopForegroundAndMaybeSelf();
    }

    private void updateForegroundNotification(AutoSubTaskState state) {
        if (state.getTaskType() == AutoSubTaskState.TaskType.NONE) {
            return;
        }
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag());
        NotificationCompat.Builder builder = NotificationHelper.createForegroundTaskNotificationBuilder(
                this, notificationIconFor(state.getTaskType()), state.getTitle(), state.getMessage(), state.getProgress(), contentIntent);

        if (state.getTaskType() == AutoSubTaskState.TaskType.MODEL_DOWNLOAD) {
            builder.addAction(state.isDownloadPaused() ? R.drawable.ri_play_line : R.drawable.ri_pause_line,
                    state.isDownloadPaused() ? "Resume" : "Pause",
                    serviceAction(state.isDownloadPaused() ? ACTION_RESUME_DOWNLOAD : ACTION_PAUSE_DOWNLOAD, 1));
            builder.addAction(R.drawable.ri_close_line, "Cancel", serviceAction(ACTION_CANCEL_DOWNLOAD, 2));
        } else if (isMediaTask(state.getTaskType())) {
            builder.addAction(R.drawable.ri_close_line, "Cancel", serviceAction(ACTION_CANCEL_MEDIA, 3));
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, builder.build(), foregroundTypeFor(state.getTaskType()));
        } else {
            startForeground(NOTIFICATION_ID, builder.build());
        }
    }

    private void updateSecondaryNotifications(AutoSubTaskState foregroundState) {
        boolean foregroundIsDownload = foregroundState.getTaskType() == AutoSubTaskState.TaskType.MODEL_DOWNLOAD;
        boolean foregroundIsMedia = isMediaTask(foregroundState.getTaskType());

        if (latestDownloadState != null && !foregroundIsDownload) {
            showSecondaryTaskNotification(latestDownloadState, 1001);
        } else {
            NotificationHelper.cancelNotification(this, 1001);
        }

        if (latestMediaState != null && !foregroundIsMedia) {
            int mediaNotificationId = mediaNotificationIdFor(latestMediaState.getTaskType());
            showSecondaryTaskNotification(latestMediaState, mediaNotificationId);
            if (mediaNotificationId != 2001) {
                NotificationHelper.cancelNotification(this, 2001);
            }
            if (mediaNotificationId != 3001) {
                NotificationHelper.cancelNotification(this, 3001);
            }
        } else {
            NotificationHelper.cancelNotification(this, 2001);
            NotificationHelper.cancelNotification(this, 3001);
        }
    }

    private AutoSubTaskState foregroundStateForNotifications(AutoSubTaskState latestState) {
        if (isDownloadLaneActive() && latestDownloadState != null) {
            return latestDownloadState;
        }
        if (isMediaLaneActive() && latestMediaState != null) {
            return latestMediaState;
        }
        return latestState;
    }

    private void rememberNotificationLane(AutoSubTaskState state) {
        if (state.getTaskType() == AutoSubTaskState.TaskType.MODEL_DOWNLOAD) {
            latestDownloadState = state;
        } else if (isMediaTask(state.getTaskType())) {
            latestMediaState = state;
        }
    }

    private void showSecondaryTaskNotification(AutoSubTaskState state, int notificationId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        PendingIntent contentIntent = PendingIntent.getActivity(this, notificationId,
                new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag());
        NotificationCompat.Builder builder = NotificationHelper.createForegroundTaskNotificationBuilder(
                this, notificationIconFor(state.getTaskType()), state.getTitle(), state.getMessage(),
                state.getProgress(), contentIntent);
        if (state.getTaskType() == AutoSubTaskState.TaskType.MODEL_DOWNLOAD) {
            builder.addAction(state.isDownloadPaused() ? R.drawable.ri_play_line : R.drawable.ri_pause_line,
                    state.isDownloadPaused() ? "Resume" : "Pause",
                    serviceAction(state.isDownloadPaused() ? ACTION_RESUME_DOWNLOAD : ACTION_PAUSE_DOWNLOAD, 11));
            builder.addAction(R.drawable.ri_close_line, "Cancel", serviceAction(ACTION_CANCEL_DOWNLOAD, 12));
        } else if (isMediaTask(state.getTaskType())) {
            builder.addAction(R.drawable.ri_close_line, "Cancel", serviceAction(ACTION_CANCEL_MEDIA, 13));
        }
        try {
            NotificationManagerCompat.from(this).notify(notificationId, builder.build());
        } catch (SecurityException ignored) {
        }
    }

    private void clearDownloadNotificationLane() {
        latestDownloadState = null;
        NotificationHelper.cancelNotification(this, 1001);
    }

    private void clearMediaNotificationLane() {
        latestMediaState = null;
        NotificationHelper.cancelNotification(this, 2001);
        NotificationHelper.cancelNotification(this, 3001);
    }

    private boolean isDownloadLaneActive() {
        return activeDownloadTask != null || activeDownloadModelId != null;
    }

    private boolean isMediaLaneActive() {
        return queueRunning || batchRunning || isMediaTask(currentState.getTaskType());
    }

    private void updateMediaWakeLock() {
        if (isMediaLaneActive()) {
            acquireMediaWakeLock();
        } else {
            releaseMediaWakeLock();
        }
    }

    private void acquireMediaWakeLock() {
        if (mediaWakeLock != null && mediaWakeLock.isHeld()) {
            return;
        }

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager == null) {
            return;
        }

        mediaWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, MEDIA_WAKE_LOCK_TAG);
        mediaWakeLock.setReferenceCounted(false);
        mediaWakeLock.acquire();
    }

    private void releaseMediaWakeLock() {
        if (mediaWakeLock != null && mediaWakeLock.isHeld()) {
            mediaWakeLock.release();
        }
        mediaWakeLock = null;
    }

    private PendingIntent serviceAction(String action, int requestCode) {
        return PendingIntent.getService(this, requestCode,
                new Intent(this, AutoSubTaskService.class).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag());
    }

    private int notificationIconFor(AutoSubTaskState.TaskType taskType) {
        if (taskType == AutoSubTaskState.TaskType.MODEL_DOWNLOAD) {
            return R.drawable.ri_download_line;
        }
        if (taskType == AutoSubTaskState.TaskType.VIDEO_EXPORT
                || taskType == AutoSubTaskState.TaskType.BATCH_VIDEO_EXPORT) {
            return R.drawable.ri_file_video_line;
        }
        if (taskType == AutoSubTaskState.TaskType.SUBTITLE_GENERATION
                || taskType == AutoSubTaskState.TaskType.SUBTITLE_SAVE
                || taskType == AutoSubTaskState.TaskType.BATCH_SUBTITLE_SAVE) {
            return R.drawable.ri_closed_captioning_line;
        }
        return R.drawable.ri_closed_captioning_line;
    }

    private int mediaNotificationIdFor(AutoSubTaskState.TaskType taskType) {
        if (taskType == AutoSubTaskState.TaskType.VIDEO_EXPORT
                || taskType == AutoSubTaskState.TaskType.BATCH_VIDEO_EXPORT) {
            return 3001;
        }
        return 2001;
    }

    private void showSuccessNotificationIfEnabled(int notificationId, String title, String content) {
        if (settingsPrefs.getBoolean(KEY_SHOW_COMPLETION_NOTIFICATIONS, true)) {
            NotificationHelper.showSuccessNotification(this, notificationId, title, content);
        }
    }

    private int immutableFlag() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
    }

    private int foregroundTypeFor(AutoSubTaskState.TaskType taskType) {
        if (taskType == AutoSubTaskState.TaskType.MODEL_DOWNLOAD) {
            return ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
        }
        if (Build.VERSION.SDK_INT >= 35 && isMediaTask(taskType)) {
            return ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING;
        }
        return ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
    }

    private boolean isMediaTask(AutoSubTaskState.TaskType taskType) {
        return taskType == AutoSubTaskState.TaskType.SUBTITLE_GENERATION
                || taskType == AutoSubTaskState.TaskType.SUBTITLE_SAVE
                || taskType == AutoSubTaskState.TaskType.VIDEO_EXPORT
                || taskType == AutoSubTaskState.TaskType.BATCH_SUBTITLE_SAVE
                || taskType == AutoSubTaskState.TaskType.BATCH_VIDEO_EXPORT;
    }

    private void stopForegroundAndMaybeSelf() {
        if (currentState.getTaskType() != AutoSubTaskState.TaskType.NONE || queueRunning || batchRunning
                || activeDownloadTask != null || modelLoading) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        startedForWork = false;
        stopSelf();
    }

    private void clearActiveDownload() {
        activeDownloadTask = null;
        activeDownloadModelId = null;
        activeDownloadProgress = 0;
        activeDownloadSpeedText = "";
        activeDownloadEtaText = "";
        activeDownloadPaused = false;
        clearDownloadNotificationLane();
    }

    private void processNextDownloadQueue() {
        if (downloadQueue.isEmpty()) return;
        VoskModelInfo next = downloadQueue.remove(0);
        startModelDownload(next);
    }

    private List<String> queuedDownloadIds() {
        List<String> ids = new ArrayList<>();
        for (VoskModelInfo modelInfo : downloadQueue) {
            ids.add(modelInfo.getId());
        }
        return ids;
    }

    private void resetStaleQueueItems() {
        List<QueueItem> items = queueStore.getItems();
        for (QueueItem item : items) {
            if (item.getStatus() == QueueItem.Status.PROCESSING
                    || item.getStatus() == QueueItem.Status.EXPORTING
                    || item.getStatus() == QueueItem.Status.TRANSLATING) {
                item.setStatus(QueueItem.Status.PENDING);
                item.setProgress(0);
                queueStore.updateItem(item);
            }
        }
    }

    public void addRemovedActiveQueueItemId(long id) {
        removedActiveQueueItemIds.add(id);
    }

    private boolean isRemovedActiveQueueItem(QueueItem item) {
        return item != null && removedActiveQueueItemIds.contains(item.getId());
    }

    private boolean isCancelledActiveQueueItem(QueueItem item) {
        return item != null && cancelledActiveQueueItemIds.contains(item.getId());
    }

    private void finishRemovedActiveQueueItem(QueueItem item) {
        removedActiveQueueItemIds.remove(item.getId());
        cancelledActiveQueueItemIds.remove(item.getId());
        if (activeQueueItem != null && activeQueueItem.getId() == item.getId()) {
            activeQueueItem = null;
        }
        queueCancelRequested = false;
        queueRunning = false;
        publishQueueItems();
        startQueue();
    }

    private String currentBatchFormat() {
        return settingsPrefs.getString(KEY_BATCH_FORMAT, "srt");
    }

    private void registerExport(String filePath, String type, Uri sourceVideoUri, String sourceVideoName,
                                String exportKind, String format, VoskModelInfo modelInfo) {
        if (filePath == null || filePath.trim().isEmpty()) {
            return;
        }
        exportStore.addFile(new File(filePath),
                new File(ApplicationPath.applicationPath(this)),
                type,
                modelInfo == null ? selectedModelInfo : modelInfo,
                sourceVideoUri == null ? "" : sourceVideoUri.toString(),
                sourceVideoName,
                exportKind,
                format);
    }

    private String getDisplayNameHelper(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) {
        }
        return "Video";
    }

    private String getPreviewTextHelper(List<SubtitleGenerator.SubtitleEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return "";
        }
        int start = Math.max(0, entries.size() - 2);
        StringBuilder builder = new StringBuilder();
        for (int i = start; i < entries.size(); i++) {
            if (builder.length() > 0) builder.append(" ");
            builder.append(entries.get(i).getText());
        }
        return builder.toString();
    }
}
