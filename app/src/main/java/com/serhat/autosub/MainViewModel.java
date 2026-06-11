package com.serhat.autosub;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ResultReceiver;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainViewModel extends AndroidViewModel {

    public interface DisplayNameResolver {
        String resolve(Uri uri);
    }

    public interface RotationResolver {
        boolean isVertical(Uri uri);
    }

    public interface ProbeCallback {
        void onProbeStarted();
        void onProbeFinished();
        void onProbeSuccess();
        void onProbeError(String error);
    }

    private final VoskModelManager modelManager;
    private final SubtitleGenerator subtitleGenerator;
    private final QueueStore queueStore;
    private final ExportStore exportStore;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private static final String PREFS_SETTINGS = "autosub_settings";
    private static final String KEY_SHORTS_DONT_SHOW_AGAIN = "shorts_dont_show_again";
    private static final String KEY_SHORTS_MODE_WORD_BY_WORD = "shorts_mode_word_by_word";
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
    private static final String KEY_SHOW_RAM_USAGE = "show_ram_usage";
    private final android.content.SharedPreferences settingsPrefs;

    // --- State LiveData ---
    private final MutableLiveData<Boolean> modelReady = new MutableLiveData<>(false);
    private final MutableLiveData<VoskModelInfo> selectedModelInfo = new MutableLiveData<>();
    private final MutableLiveData<String> modelStatusText = new MutableLiveData<>("");
    private final MutableLiveData<String> generalStatusText = new MutableLiveData<>("Loading speech model...");
    private final MutableLiveData<List<VoskModelInfo>> catalogModels = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<String> activeDownloadModelId = new MutableLiveData<>(null);
    private final MutableLiveData<Integer> activeDownloadProgress = new MutableLiveData<>(0);
    private final MutableLiveData<String> activeDownloadSpeedText = new MutableLiveData<>("");
    private final MutableLiveData<String> activeDownloadEtaText = new MutableLiveData<>("");
    private final MutableLiveData<Boolean> activeDownloadPaused = new MutableLiveData<>(false);
    private final MutableLiveData<List<String>> queuedDownloadModelIds = new MutableLiveData<>(new ArrayList<>());
    private final List<VoskModelInfo> downloadQueue = new ArrayList<>();

    private final MutableLiveData<List<QueueItem>> queueItems = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<Boolean> queueRunning = new MutableLiveData<>(false);

    private final MutableLiveData<QueueItem> selectedQueueItem = new MutableLiveData<>(null);
    private final MutableLiveData<Uri> currentVideoUri = new MutableLiveData<>(null);
    private final MutableLiveData<List<SubtitleGenerator.SubtitleEntry>> subtitleEntries = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<Boolean> shortsPreviewMode = new MutableLiveData<>(false);
    private final MutableLiveData<Float> shortsCaptionX = new MutableLiveData<>(0.5f);
    private final MutableLiveData<Float> shortsCaptionY = new MutableLiveData<>(0.5f);
    private final MutableLiveData<Float> shortsCaptionScale = new MutableLiveData<>(1f);
    private final MutableLiveData<Float> subtitleCaptionX = new MutableLiveData<>(0.5f);
    private final MutableLiveData<Float> subtitleCaptionY = new MutableLiveData<>(0.88f);
    private final MutableLiveData<Float> subtitleCaptionScale = new MutableLiveData<>(1f);

    // --- Settings States ---
    private final MutableLiveData<String> batchFormat = new MutableLiveData<>("srt");
    private final MutableLiveData<Integer> subtitleMaxLength = new MutableLiveData<>(SubtitleGenerator.DEFAULT_MAX_SUBTITLE_LENGTH);
    private final MutableLiveData<Boolean> keepSentencesTogether = new MutableLiveData<>(SubtitleGenerator.DEFAULT_KEEP_SENTENCES_TOGETHER);
    private final MutableLiveData<Boolean> suppressWhisperSdh = new MutableLiveData<>(true);
    private final MutableLiveData<Boolean> whisperVadEnabled = new MutableLiveData<>(false);
    private final MutableLiveData<String> whisperVadModel = new MutableLiveData<>(SubtitleGenerator.VAD_MODEL_WEBRTC);
    private final MutableLiveData<String> whisperVadAggressiveness =
            new MutableLiveData<>(SubtitleGenerator.VAD_AGGRESSIVENESS_NORMAL);
    private final MutableLiveData<String> whisperLanguage = new MutableLiveData<>("auto");
    // 0 = automatic thread selection, otherwise a user-forced Whisper thread count.
    private final MutableLiveData<Integer> whisperThreadCount = new MutableLiveData<>(0);
    private final MutableLiveData<Boolean> translateSubtitles = new MutableLiveData<>(false);
    private final MutableLiveData<String> translationSourceLanguage = new MutableLiveData<>("auto");
    private final MutableLiveData<String> translationTargetLanguage =
            new MutableLiveData<>(SubtitleGenerator.getDefaultTranslationTargetLanguage());
    private final MutableLiveData<Float> shortsCaptionSize = new MutableLiveData<>(30f);
    private final MutableLiveData<Boolean> shortsUppercase = new MutableLiveData<>(true);
    private final MutableLiveData<Boolean> shortsWordByWordDefault = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> skipShortsDialog = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> showCompletionNotifications = new MutableLiveData<>(true);
    private final MutableLiveData<Boolean> showRamUsage = new MutableLiveData<>(true);

    // Navigation and screen command trigger
    private final MutableLiveData<Integer> activeNavigationTab = new MutableLiveData<>(R.id.nav_generate);
    private final MutableLiveData<Boolean> navigateToPreviewTrigger = new MutableLiveData<>(false);
    
    private final MutableLiveData<String> ramUsage = new MutableLiveData<>("RAM: -- MB");

    private VoskModelManager.DownloadTask activeDownloadTask;
    private String currentQuery = "";
    private int currentCheckedChipId = R.id.filterAllChip;
    private boolean allowHeavyModelLoad = false;
    private boolean modelProbeInProgress = false;
    private Runnable modelProbeTimeoutRunnable;
    private boolean singleGenerationRunning = false;
    private boolean queueCancelRequested = false;
    private QueueItem activeQueueItem;
    private final Set<Long> removedActiveQueueItemIds = new HashSet<>();
    private AutoSubTaskService taskService;
    private boolean taskServiceBound = false;
    private boolean taskServiceBindingRequested = false;
    private final List<Runnable> pendingServiceActions = new ArrayList<>();

    private final AutoSubTaskService.Listener taskListener = new AutoSubTaskService.Listener() {
        @Override
        public void onTaskStateChanged(AutoSubTaskState state) {
            handler.post(() -> applyTaskState(state));
        }

        @Override
        public void onQueueItemsChanged(List<QueueItem> items) {
            handler.post(() -> {
                List<QueueItem> visibleItems = new ArrayList<>();
                if (items != null) {
                    for (QueueItem item : items) {
                        if (!isRemovedActiveQueueItem(item)) {
                            visibleItems.add(item);
                        }
                    }
                }
                queueItems.setValue(visibleItems);
                syncSelectedQueueItemFrom(visibleItems);
            });
        }

        @Override
        public void onModelStateChanged(boolean ready, VoskModelInfo selectedModel, String statusText, String generalText) {
            handler.post(() -> {
                modelReady.setValue(ready);
                selectedModelInfo.setValue(selectedModel);
                modelStatusText.setValue(statusText);
                generalStatusText.setValue(generalText);
                if (ready) {
                    startQueue();
                }
            });
        }

        @Override
        public void onCatalogShouldRefresh() {
            handler.post(() -> {
                loadCatalog();
                refreshModels(currentQuery, currentCheckedChipId);
            });
        }
    };

    private final ServiceConnection taskServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            AutoSubTaskService.LocalBinder binder = (AutoSubTaskService.LocalBinder) service;
            taskService = binder.getService();
            taskServiceBound = true;
            taskServiceBindingRequested = false;
            taskService.addListener(taskListener);
            List<Runnable> actions = new ArrayList<>(pendingServiceActions);
            pendingServiceActions.clear();
            for (Runnable action : actions) {
                action.run();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            taskServiceBound = false;
            taskServiceBindingRequested = false;
            taskService = null;
        }
    };

    public MainViewModel(@NonNull Application application) {
        super(application);
        modelManager = new VoskModelManager(application);
        subtitleGenerator = new SubtitleGenerator(application);
        queueStore = new QueueStore(application);
        exportStore = new ExportStore(application);
        settingsPrefs = application.getSharedPreferences(PREFS_SETTINGS, android.content.Context.MODE_PRIVATE);
        loadSettings();

        bindTaskService();
        loadCatalog();
        loadQueueItemsFromDb();
        startRamUsagePolling();
    }

    private void bindTaskService() {
        if (taskServiceBound || taskServiceBindingRequested) {
            return;
        }
        taskServiceBindingRequested = true;
        Intent intent = new Intent(getApplication(), AutoSubTaskService.class);
        getApplication().bindService(intent, taskServiceConnection, Context.BIND_AUTO_CREATE);
    }

    private void runWhenTaskServiceReady(boolean foregroundWork, Runnable action) {
        Runnable serviceAction = () -> {
            action.run();
        };
        if (taskServiceBound && taskService != null) {
            serviceAction.run();
        } else {
            pendingServiceActions.add(serviceAction);
            bindTaskService();
        }
    }

    private boolean useTaskService() {
        return true;
    }

    private void applyTaskState(AutoSubTaskState state) {
        if (state == null) return;
        queueRunning.setValue(state.isQueueRunning());
        activeDownloadModelId.setValue(state.getActiveDownloadModelId());
        if (state.getTaskType() == AutoSubTaskState.TaskType.MODEL_DOWNLOAD) {
            activeDownloadProgress.setValue(Math.max(0, state.getProgress()));
            activeDownloadSpeedText.setValue(state.getDownloadSpeedText());
            activeDownloadEtaText.setValue(state.getDownloadEtaText());
            activeDownloadPaused.setValue(state.isDownloadPaused());
        } else if (state.getActiveDownloadModelId() == null) {
            activeDownloadProgress.setValue(0);
            activeDownloadSpeedText.setValue("");
            activeDownloadEtaText.setValue("");
            activeDownloadPaused.setValue(false);
        }
        queuedDownloadModelIds.setValue(new ArrayList<>(state.getQueuedDownloadModelIds()));
    }

    private void syncSelectedQueueItemFrom(List<QueueItem> items) {
        QueueItem selected = selectedQueueItem.getValue();
        if (selected == null || items == null) return;
        for (QueueItem item : items) {
            if (item.getId() == selected.getId()) {
                selectedQueueItem.setValue(item);
                currentVideoUri.setValue(item.getVideoUri());
                subtitleEntries.setValue(item.getSubtitles());
                return;
            }
        }
    }

    public void loadQueueItemsFromDb() {
        List<QueueItem> items = queueStore.getItems();
        queueItems.setValue(items);
        startQueue();
    }

    // --- LiveData Getters ---
    public LiveData<Boolean> getModelReady() { return modelReady; }
    public LiveData<VoskModelInfo> getSelectedModelInfo() { return selectedModelInfo; }
    public LiveData<String> getModelStatusText() { return modelStatusText; }
    public LiveData<String> getGeneralStatusText() { return generalStatusText; }
    public LiveData<List<VoskModelInfo>> getCatalogModels() { return catalogModels; }
    public LiveData<String> getActiveDownloadModelId() { return activeDownloadModelId; }
    public LiveData<Integer> getActiveDownloadProgress() { return activeDownloadProgress; }
    public LiveData<String> getActiveDownloadSpeedText() { return activeDownloadSpeedText; }
    public LiveData<String> getActiveDownloadEtaText() { return activeDownloadEtaText; }
    public LiveData<Boolean> getActiveDownloadPaused() { return activeDownloadPaused; }
    public LiveData<List<String>> getQueuedDownloadModelIds() { return queuedDownloadModelIds; }
    
    public LiveData<List<QueueItem>> getQueueItems() { return queueItems; }
    public LiveData<Boolean> getQueueRunning() { return queueRunning; }
    
    public LiveData<QueueItem> getSelectedQueueItem() { return selectedQueueItem; }
    public LiveData<Uri> getCurrentVideoUri() { return currentVideoUri; }
    public LiveData<List<SubtitleGenerator.SubtitleEntry>> getSubtitleEntries() { return subtitleEntries; }
    public LiveData<Boolean> getShortsPreviewMode() { return shortsPreviewMode; }
    public LiveData<Float> getShortsCaptionX() { return shortsCaptionX; }
    public LiveData<Float> getShortsCaptionY() { return shortsCaptionY; }
    public LiveData<Float> getShortsCaptionScale() { return shortsCaptionScale; }
    public LiveData<Float> getSubtitleCaptionX() { return subtitleCaptionX; }
    public LiveData<Float> getSubtitleCaptionY() { return subtitleCaptionY; }
    public LiveData<Float> getSubtitleCaptionScale() { return subtitleCaptionScale; }
    
    public LiveData<String> getBatchFormat() { return batchFormat; }
    public LiveData<Integer> getSubtitleMaxLength() { return subtitleMaxLength; }
    public LiveData<Boolean> getKeepSentencesTogether() { return keepSentencesTogether; }
    public LiveData<Boolean> getSuppressWhisperSdh() { return suppressWhisperSdh; }
    public LiveData<Boolean> getWhisperVadEnabled() { return whisperVadEnabled; }
    public LiveData<String> getWhisperVadModel() { return whisperVadModel; }
    public LiveData<String> getWhisperVadAggressiveness() { return whisperVadAggressiveness; }
    public LiveData<String> getWhisperLanguage() { return whisperLanguage; }
    public LiveData<Integer> getWhisperThreadCount() { return whisperThreadCount; }
    public LiveData<Boolean> getTranslateSubtitles() { return translateSubtitles; }
    public LiveData<String> getTranslationSourceLanguage() { return translationSourceLanguage; }
    public LiveData<String> getTranslationTargetLanguage() { return translationTargetLanguage; }
    public LiveData<Float> getShortsCaptionSize() { return shortsCaptionSize; }
    public LiveData<Boolean> getShortsUppercase() { return shortsUppercase; }
    public LiveData<Boolean> getShortsWordByWordDefault() { return shortsWordByWordDefault; }
    public LiveData<Boolean> getSkipShortsDialog() { return skipShortsDialog; }
    public LiveData<Boolean> getShowCompletionNotifications() { return showCompletionNotifications; }
    public LiveData<Boolean> getShowRamUsage() { return showRamUsage; }

    public LiveData<Integer> getActiveNavigationTab() { return activeNavigationTab; }
    public LiveData<Boolean> getNavigateToPreviewTrigger() { return navigateToPreviewTrigger; }
    public LiveData<String> getRamUsage() { return ramUsage; }

    public VoskModelManager getModelManager() { return modelManager; }

    private void loadSettings() {
        batchFormat.setValue(settingsPrefs.getString(KEY_BATCH_FORMAT, "srt"));
        int maxLength = settingsPrefs.getInt(KEY_SUBTITLE_MAX_LENGTH, SubtitleGenerator.DEFAULT_MAX_SUBTITLE_LENGTH);
        subtitleMaxLength.setValue(maxLength);
        subtitleGenerator.setMaxSubtitleLength(maxLength);
        boolean savedKeepSentencesTogether = settingsPrefs.getBoolean(
                KEY_KEEP_SENTENCES_TOGETHER, SubtitleGenerator.DEFAULT_KEEP_SENTENCES_TOGETHER);
        keepSentencesTogether.setValue(savedKeepSentencesTogether);
        subtitleGenerator.setKeepSentencesTogether(savedKeepSentencesTogether);
        boolean suppressSdh = settingsPrefs.getBoolean(KEY_SUPPRESS_WHISPER_SDH, true);
        suppressWhisperSdh.setValue(suppressSdh);
        subtitleGenerator.setSuppressWhisperSdh(suppressSdh);
        boolean savedWhisperVadEnabled = settingsPrefs.getBoolean(KEY_WHISPER_VAD_ENABLED, false);
        whisperVadEnabled.setValue(savedWhisperVadEnabled);
        subtitleGenerator.setWhisperVadEnabled(savedWhisperVadEnabled);
        String savedWhisperVadModel = normalizeVadModel(settingsPrefs.getString(
                KEY_WHISPER_VAD_MODEL, SubtitleGenerator.VAD_MODEL_WEBRTC));
        whisperVadModel.setValue(savedWhisperVadModel);
        subtitleGenerator.setWhisperVadModel(savedWhisperVadModel);
        String savedWhisperVadAggressiveness = normalizeVadAggressiveness(settingsPrefs.getString(
                KEY_WHISPER_VAD_AGGRESSIVENESS, SubtitleGenerator.VAD_AGGRESSIVENESS_VERY_AGGRESSIVE));
        whisperVadAggressiveness.setValue(savedWhisperVadAggressiveness);
        subtitleGenerator.setWhisperVadAggressiveness(savedWhisperVadAggressiveness);
        String savedWhisperLanguage = settingsPrefs.getString(KEY_WHISPER_LANGUAGE, "auto");
        whisperLanguage.setValue(savedWhisperLanguage);
        subtitleGenerator.setWhisperLanguage(savedWhisperLanguage);
        int savedWhisperThreadCount = settingsPrefs.getInt(KEY_WHISPER_THREAD_COUNT, 0);
        whisperThreadCount.setValue(savedWhisperThreadCount);
        subtitleGenerator.setWhisperThreadCount(savedWhisperThreadCount);
        boolean savedTranslateSubtitles = settingsPrefs.getBoolean(KEY_TRANSLATE_SUBTITLES, false);
        String savedTranslationSource = settingsPrefs.getString(KEY_TRANSLATION_SOURCE_LANGUAGE, "auto");
        String savedTranslationTarget = settingsPrefs.getString(KEY_TRANSLATION_TARGET_LANGUAGE,
                SubtitleGenerator.getDefaultTranslationTargetLanguage());
        translateSubtitles.setValue(savedTranslateSubtitles);
        translationSourceLanguage.setValue(savedTranslationSource);
        translationTargetLanguage.setValue(savedTranslationTarget);
        subtitleGenerator.setTranslationSettings(savedTranslateSubtitles, savedTranslationSource, savedTranslationTarget);
        shortsWordByWordDefault.setValue(settingsPrefs.getBoolean(KEY_SHORTS_MODE_WORD_BY_WORD, false));
        skipShortsDialog.setValue(settingsPrefs.getBoolean(KEY_SHORTS_DONT_SHOW_AGAIN, false));
        showCompletionNotifications.setValue(settingsPrefs.getBoolean(KEY_SHOW_COMPLETION_NOTIFICATIONS, true));
        showRamUsage.setValue(settingsPrefs.getBoolean(KEY_SHOW_RAM_USAGE, true));
    }

    public void setActiveNavigationTab(int id) {
        activeNavigationTab.setValue(id);
    }

    public void consumeNavigateToPreviewTrigger() {
        navigateToPreviewTrigger.setValue(false);
    }

    // --- Settings Setter ---
    public void setBatchFormat(String format) {
        batchFormat.setValue(format);
        settingsPrefs.edit().putString(KEY_BATCH_FORMAT, format == null ? "srt" : format).apply();
        refreshQueue();
    }

    public void setSubtitleMaxLength(int maxLength) {
        int boundedLength = Math.max(SubtitleGenerator.MIN_SUBTITLE_LENGTH,
                Math.min(SubtitleGenerator.MAX_SUBTITLE_LENGTH_LIMIT, maxLength));
        subtitleMaxLength.setValue(boundedLength);
        subtitleGenerator.setMaxSubtitleLength(boundedLength);
        settingsPrefs.edit().putInt(KEY_SUBTITLE_MAX_LENGTH, boundedLength).apply();
    }

    public void setKeepSentencesTogether(boolean keepTogether) {
        keepSentencesTogether.setValue(keepTogether);
        subtitleGenerator.setKeepSentencesTogether(keepTogether);
        settingsPrefs.edit().putBoolean(KEY_KEEP_SENTENCES_TOGETHER, keepTogether).apply();
    }

    public void setSuppressWhisperSdh(boolean suppress) {
        suppressWhisperSdh.setValue(suppress);
        subtitleGenerator.setSuppressWhisperSdh(suppress);
        settingsPrefs.edit().putBoolean(KEY_SUPPRESS_WHISPER_SDH, suppress).apply();
    }

    public void setWhisperVadEnabled(boolean enabled) {
        whisperVadEnabled.setValue(enabled);
        subtitleGenerator.setWhisperVadEnabled(enabled);
        settingsPrefs.edit().putBoolean(KEY_WHISPER_VAD_ENABLED, enabled).apply();
    }

    public void setWhisperVadModel(String model) {
        String normalizedModel = normalizeVadModel(model);
        whisperVadModel.setValue(normalizedModel);
        subtitleGenerator.setWhisperVadModel(normalizedModel);
        settingsPrefs.edit().putString(KEY_WHISPER_VAD_MODEL, normalizedModel).apply();
    }

    public void setWhisperVadAggressiveness(String aggressiveness) {
        String normalizedAggressiveness = normalizeVadAggressiveness(aggressiveness);
        whisperVadAggressiveness.setValue(normalizedAggressiveness);
        subtitleGenerator.setWhisperVadAggressiveness(normalizedAggressiveness);
        settingsPrefs.edit().putString(KEY_WHISPER_VAD_AGGRESSIVENESS, normalizedAggressiveness).apply();
    }

    public void setWhisperLanguage(String language) {
        String normalizedLanguage = language == null || language.trim().isEmpty()
                ? "auto"
                : language.trim().toLowerCase(Locale.US);
        whisperLanguage.setValue(normalizedLanguage);
        subtitleGenerator.setWhisperLanguage(normalizedLanguage);
        settingsPrefs.edit().putString(KEY_WHISPER_LANGUAGE, normalizedLanguage).apply();
    }

    public void setWhisperThreadCount(int threadCount) {
        int normalizedThreadCount = Math.max(0, threadCount);
        whisperThreadCount.setValue(normalizedThreadCount);
        subtitleGenerator.setWhisperThreadCount(normalizedThreadCount);
        settingsPrefs.edit().putInt(KEY_WHISPER_THREAD_COUNT, normalizedThreadCount).apply();
    }

    public void setTranslateSubtitles(boolean enabled) {
        translateSubtitles.setValue(enabled);
        subtitleGenerator.setTranslationSettings(enabled,
                getLiveDataValue(translationSourceLanguage, "auto"),
                getLiveDataValue(translationTargetLanguage, "en"));
        settingsPrefs.edit().putBoolean(KEY_TRANSLATE_SUBTITLES, enabled).apply();
    }

    public void setTranslationSourceLanguage(String language) {
        String normalizedLanguage = normalizeTranslationLanguage(language, "auto");
        translationSourceLanguage.setValue(normalizedLanguage);
        subtitleGenerator.setTranslationSettings(Boolean.TRUE.equals(translateSubtitles.getValue()),
                normalizedLanguage,
                getLiveDataValue(translationTargetLanguage, "en"));
        settingsPrefs.edit().putString(KEY_TRANSLATION_SOURCE_LANGUAGE, normalizedLanguage).apply();
    }

    public void setTranslationTargetLanguage(String language) {
        String normalizedLanguage = normalizeTranslationLanguage(language, "en");
        translationTargetLanguage.setValue(normalizedLanguage);
        subtitleGenerator.setTranslationSettings(Boolean.TRUE.equals(translateSubtitles.getValue()),
                getLiveDataValue(translationSourceLanguage, "auto"),
                normalizedLanguage);
        settingsPrefs.edit().putString(KEY_TRANSLATION_TARGET_LANGUAGE, normalizedLanguage).apply();
    }

    private String normalizeTranslationLanguage(String language, String fallback) {
        if (language == null || language.trim().isEmpty()) {
            return fallback;
        }
        return language.trim().toLowerCase(Locale.US);
    }

    private String normalizeVadModel(String model) {
        if (SubtitleGenerator.VAD_MODEL_SILERO.equalsIgnoreCase(model)) {
            return SubtitleGenerator.VAD_MODEL_SILERO;
        }
        return SubtitleGenerator.VAD_MODEL_WEBRTC;
    }

    private String normalizeVadAggressiveness(String aggressiveness) {
        if (SubtitleGenerator.VAD_AGGRESSIVENESS_NORMAL.equalsIgnoreCase(aggressiveness)) {
            return SubtitleGenerator.VAD_AGGRESSIVENESS_NORMAL;
        }
        if (SubtitleGenerator.VAD_AGGRESSIVENESS_AGGRESSIVE.equalsIgnoreCase(aggressiveness)) {
            return SubtitleGenerator.VAD_AGGRESSIVENESS_AGGRESSIVE;
        }
        return SubtitleGenerator.VAD_AGGRESSIVENESS_VERY_AGGRESSIVE;
    }

    private <T> T getLiveDataValue(MutableLiveData<T> liveData, T fallback) {
        T value = liveData.getValue();
        return value == null ? fallback : value;
    }

    public void setShortsCaptionSize(float size) {
        shortsCaptionSize.setValue(size);
    }

    public void setShortsUppercase(boolean uppercase) {
        shortsUppercase.setValue(uppercase);
    }

    public void setShortsWordByWordDefault(boolean enabled) {
        shortsWordByWordDefault.setValue(enabled);
        settingsPrefs.edit().putBoolean(KEY_SHORTS_MODE_WORD_BY_WORD, enabled).apply();
    }

    public void setSkipShortsDialog(boolean skip) {
        skipShortsDialog.setValue(skip);
        settingsPrefs.edit().putBoolean(KEY_SHORTS_DONT_SHOW_AGAIN, skip).apply();
    }

    public void setShowCompletionNotifications(boolean enabled) {
        showCompletionNotifications.setValue(enabled);
        settingsPrefs.edit().putBoolean(KEY_SHOW_COMPLETION_NOTIFICATIONS, enabled).apply();
    }

    public void setShowRamUsage(boolean enabled) {
        showRamUsage.setValue(enabled);
        settingsPrefs.edit().putBoolean(KEY_SHOW_RAM_USAGE, enabled).apply();
    }

    public void setShortsPreviewMode(boolean isShorts) {
        shortsPreviewMode.setValue(isShorts);
    }

    public void setShortsCaptionPosition(float x, float y) {
        float clampedX = clampNormalizedPosition(x);
        float clampedY = clampNormalizedPosition(y);
        shortsCaptionX.setValue(clampedX);
        shortsCaptionY.setValue(clampedY);
        QueueItem qItem = selectedQueueItem.getValue();
        if (qItem != null) {
            qItem.setShortsCaptionX(clampedX);
            qItem.setShortsCaptionY(clampedY);
            refreshQueue();
        }
    }

    public void setShortsCaptionScale(float scale) {
        float clampedScale = clampCaptionScale(scale);
        shortsCaptionScale.setValue(clampedScale);
        QueueItem qItem = selectedQueueItem.getValue();
        if (qItem != null) {
            qItem.setShortsCaptionScale(clampedScale);
            refreshQueue();
        }
    }

    public void setSubtitleCaptionPosition(float x, float y) {
        float clampedX = clampNormalizedPosition(x);
        float clampedY = clampNormalizedPosition(y);
        subtitleCaptionX.setValue(clampedX);
        subtitleCaptionY.setValue(clampedY);
        QueueItem qItem = selectedQueueItem.getValue();
        if (qItem != null) {
            qItem.setSubtitleCaptionX(clampedX);
            qItem.setSubtitleCaptionY(clampedY);
            qItem.setSubtitleCaptionPositionAdjusted(true);
            refreshQueue();
        }
    }

    public void setSubtitleCaptionScale(float scale) {
        float clampedScale = clampCaptionScale(scale);
        subtitleCaptionScale.setValue(clampedScale);
        QueueItem qItem = selectedQueueItem.getValue();
        if (qItem != null) {
            qItem.setSubtitleCaptionScale(clampedScale);
            qItem.setSubtitleCaptionPositionAdjusted(true);
            refreshQueue();
        }
    }

    private float clampNormalizedPosition(float value) {
        if (Float.isNaN(value)) return 0.5f;
        return Math.max(0f, Math.min(1f, value));
    }

    private float clampCaptionScale(float value) {
        if (Float.isNaN(value)) return 1f;
        return Math.max(0.5f, Math.min(3f, value));
    }

    // --- Business Logic API ---

    public void loadCatalog() {
        try {
            modelManager.loadCatalog();
            refreshModels(currentQuery, currentCheckedChipId);
        } catch (IOException e) {
            generalStatusText.setValue("Error loading model catalog");
        }
    }

    public boolean isModelInstalled(String modelId) {
        return modelManager.isInstalled(modelId);
    }

    public boolean shouldUseCompatibilityMode(VoskModelInfo modelInfo) {
        return modelManager.shouldUseCompatibilityMode(modelInfo);
    }

    public void refreshModels(String query, int checkId) {
        this.currentQuery = query == null ? "" : query;
        this.currentCheckedChipId = checkId;

        List<VoskModelInfo> models = modelManager.search(currentQuery);
        if (checkId == R.id.filterInstalledChip) {
            List<VoskModelInfo> filtered = new ArrayList<>();
            for (VoskModelInfo model : models) {
                if (modelManager.isInstalled(model.getId())) filtered.add(model);
            }
            models = filtered;
        } else if (checkId == R.id.filterMobileChip) {
            List<VoskModelInfo> filtered = new ArrayList<>();
            for (VoskModelInfo model : models) {
                if (model.isMobileRecommended()) filtered.add(model);
            }
            models = filtered;
        } else if (checkId == R.id.filterDownloadingChip) {
            List<VoskModelInfo> filtered = new ArrayList<>();
            String activeId = activeDownloadModelId.getValue();
            for (VoskModelInfo model : models) {
                boolean isDownloading = activeId != null && activeId.equals(model.getId());
                boolean isPartial = modelManager.hasPartialDownload(model.getId());
                if (isDownloading || isPartial) {
                    filtered.add(model);
                }
            }
            models = filtered;
        }

        String downloadingId = activeDownloadModelId.getValue();
        if (downloadingId != null) {
            boolean alreadyInList = false;
            for (VoskModelInfo m : models) {
                if (m.getId().equals(downloadingId)) {
                    alreadyInList = true;
                    break;
                }
            }
            if (!alreadyInList) {
                VoskModelInfo downloadingModel = modelManager.findById(downloadingId);
                if (downloadingModel != null) {
                    models.add(downloadingModel);
                    Collections.sort(models, Comparator.comparing(VoskModelInfo::getLanguage)
                            .thenComparing(VoskModelInfo::getId));
                }
            }
        }

        catalogModels.setValue(models);
    }

    public VoskModelManager.ModelLoadMode getModelLoadMode(VoskModelInfo modelInfo) {
        return modelManager.getModelLoadMode(modelInfo);
    }

    public void initializeSelectedModel() {
        VoskModelInfo info = modelManager.getSelectedModel();
        selectedModelInfo.setValue(info);
        if (info == null) {
            generalStatusText.setValue("No speech models are available");
            return;
        }

        if (shouldDeferHeavyModelLoad(info)) {
            VoskModelInfo fallbackModelInfo = modelManager.findById(VoskModelManager.DEFAULT_MODEL_ID);
            if (fallbackModelInfo != null) {
                modelManager.selectModel(fallbackModelInfo.getId());
                info = fallbackModelInfo;
                selectedModelInfo.setValue(info);
            }
        }

        modelReady.setValue(false);
        updateSelectedModelViews(info);
        generalStatusText.setValue("Loading speech model...");
        boolean allowHeavy = allowHeavyModelLoad;
        allowHeavyModelLoad = false;
        runWhenTaskServiceReady(true, () -> taskService.initializeSelectedModel(allowHeavy));
    }

    private boolean shouldDeferHeavyModelLoad(VoskModelInfo modelInfo) {
        return !allowHeavyModelLoad
                && modelInfo != null
                && !modelInfo.isBundled()
                && modelManager.getModelLoadMode(modelInfo) == VoskModelManager.ModelLoadMode.FULL_QUALITY
                && (modelInfo.isVeryLarge() || !modelInfo.isMobileRecommended());
    }

    private void updateSelectedModelViews(VoskModelInfo info) {
        if (info == null) return;
        selectedModelInfo.setValue(info);
        String status = info.getId() + " - " + info.getSize();
        if (info.isBundled()) status += " - Bundled";
        else if (modelManager.isInstalled(info.getId())) status += " - Downloaded";
        VoskModelManager.ModelLoadMode loadMode = modelManager.getModelLoadMode(info);
        if (loadMode != VoskModelManager.ModelLoadMode.FULL_QUALITY) {
            status += " - " + loadMode.getLabel();
        }
        modelStatusText.setValue(status);
    }

    public void maybeSelectModel(VoskModelInfo modelInfo, ProbeCallback callback) {
        if (singleGenerationRunning || Boolean.TRUE.equals(queueRunning.getValue())) {
            callback.onProbeError("Wait for current generation to finish before switching models");
            return;
        }
        if (!modelInfo.isWhisper() && (modelInfo.isVeryLarge() || !modelInfo.isMobileRecommended())) {
            if (modelManager.shouldUseCompatibilityMode(modelInfo)) {
                callback.onProbeError("ASK_COMPATIBILITY"); // Signal back to Fragment to show dialog
                return;
            }
            callback.onProbeError("ASK_PROBE"); // Signal back to Fragment to show dialog
            return;
        }
        selectModel(modelInfo);
    }

    public void selectModelInCompatibilityMode(VoskModelInfo modelInfo) {
        selectModelInLoadMode(modelInfo, VoskModelManager.ModelLoadMode.COMPATIBILITY);
    }

    public void selectModelInLoadMode(VoskModelInfo modelInfo, VoskModelManager.ModelLoadMode loadMode) {
        modelManager.setModelLoadMode(modelInfo, loadMode);
        selectModel(modelInfo);
    }

    public void selectModel(VoskModelInfo modelInfo) {
        allowHeavyModelLoad = modelInfo.isVeryLarge() || !modelInfo.isMobileRecommended();
        modelManager.selectModel(modelInfo.getId());
        initializeSelectedModel();
    }

    public void probeAndSelectHeavyModel(VoskModelInfo modelInfo, ProbeCallback callback) {
        if (modelProbeInProgress) return;
        modelProbeInProgress = true;
        callback.onProbeStarted();

        ResultReceiver receiver = new ResultReceiver(handler) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                if (!modelProbeInProgress) return;
                finishModelProbe();
                callback.onProbeFinished();
                if (resultCode == ModelLoadProbeService.RESULT_OK) {
                    modelManager.setModelLoadMode(modelInfo, VoskModelManager.ModelLoadMode.FULL_QUALITY);
                    selectModel(modelInfo);
                    callback.onProbeSuccess();
                } else {
                    String error = resultData != null ? resultData.getString(ModelLoadProbeService.EXTRA_ERROR, "Model failed to load") : "Model failed to load";
                    callback.onProbeError(error);
                }
            }
        };

        modelProbeTimeoutRunnable = () -> {
            finishModelProbe();
            callback.onProbeFinished();
            callback.onProbeError("Full-quality model load did not finish. Android may have killed the probe process.");
        };
        handler.postDelayed(modelProbeTimeoutRunnable, 90000);

        Intent intent = new Intent(getApplication(), ModelLoadProbeService.class);
        intent.putExtra(ModelLoadProbeService.EXTRA_MODEL_ID, modelInfo.getId());
        intent.putExtra(ModelLoadProbeService.EXTRA_RECEIVER, receiver);
        getApplication().startService(intent);
    }

    private void finishModelProbe() {
        if (modelProbeTimeoutRunnable != null) {
            handler.removeCallbacks(modelProbeTimeoutRunnable);
            modelProbeTimeoutRunnable = null;
        }
        modelProbeInProgress = false;
    }

    public void confirmDeleteModel(VoskModelInfo modelInfo) {
        boolean wasSelected = selectedModelInfo.getValue() != null && selectedModelInfo.getValue().getId().equals(modelInfo.getId());
        boolean deleted = modelManager.deleteModel(modelInfo.getId());
        if (wasSelected) {
            initializeSelectedModel();
        } else {
            updateSelectedModelViews(modelManager.getSelectedModel());
        }
        loadCatalog();
    }

    public void startModelDownload(VoskModelInfo modelInfo) {
        if (useTaskService()) {
            runWhenTaskServiceReady(true, () -> taskService.startModelDownload(modelInfo));
            return;
        }
        // If a download is already active, queue this one
        if (activeDownloadTask != null) {
            for (VoskModelInfo queued : downloadQueue) {
                if (queued.getId().equals(modelInfo.getId())) return;
            }
            if (modelInfo.getId().equals(activeDownloadModelId.getValue())) return;
            downloadQueue.add(modelInfo);
            updateQueuedDownloadIds();
            loadCatalog();
            return;
        }
        activeDownloadModelId.setValue(modelInfo.getId());
        activeDownloadProgress.setValue(0);
        activeDownloadSpeedText.setValue("");
        activeDownloadEtaText.setValue("");
        activeDownloadPaused.setValue(false);
        final long startTime = System.currentTimeMillis();
        
        activeDownloadTask = modelManager.downloadModel(modelInfo.getId(), new VoskModelManager.DownloadCallback() {
            @Override
            public void onProgress(int progress, long bytesDownloaded, long totalBytes) {
                long now = System.currentTimeMillis();
                double elapsedSec = (now - startTime) / 1000.0;
                String speedStr = "";
                String etaStr = "";
                if (elapsedSec > 0.1 && bytesDownloaded > 0) {
                    double speedBytesPerSec = bytesDownloaded / elapsedSec;
                    long remainingBytes = totalBytes - bytesDownloaded;
                    long remainingSec = (long) (remainingBytes / speedBytesPerSec);
                    
                    if (speedBytesPerSec < 1024 * 1024) {
                        speedStr = String.format(Locale.US, "%.1f KB/s", speedBytesPerSec / 1024.0);
                    } else {
                        speedStr = String.format(Locale.US, "%.1f MB/s", speedBytesPerSec / (1024.0 * 1024.0));
                    }
                    
                    if (remainingSec < 60) {
                        etaStr = String.format(Locale.US, "%ds left", remainingSec);
                    } else if (remainingSec < 3600) {
                        etaStr = String.format(Locale.US, "%dm %ds left", remainingSec / 60, remainingSec % 60);
                    } else {
                        etaStr = String.format(Locale.US, "%dh %dm left", remainingSec / 3600, (remainingSec % 3600) / 60);
                    }
                }
                final String fSpeed = speedStr;
                final String fEta = etaStr;
                handler.post(() -> {
                    activeDownloadProgress.setValue(progress);
                    activeDownloadSpeedText.setValue(fSpeed);
                    activeDownloadEtaText.setValue(fEta);
                    
                    String progressContent = progress + "%";
                    if (!fSpeed.isEmpty() || !fEta.isEmpty()) {
                        progressContent += " (" + fSpeed + " • " + fEta + ")";
                    }
                    NotificationHelper.showProgressNotification(
                            getApplication(),
                            1001,
                            "Downloading Model: " + modelInfo.getLanguage(),
                            progressContent,
                            progress
                    );
                });
            }

            @Override
            public void onComplete(VoskModelInfo downloadedModel) {
                handler.post(() -> {
                    activeDownloadTask = null;
                    activeDownloadModelId.setValue(null);
                    activeDownloadProgress.setValue(0);
                    activeDownloadSpeedText.setValue("");
                    activeDownloadEtaText.setValue("");
                    activeDownloadPaused.setValue(false);
                    
                    NotificationHelper.cancelNotification(getApplication(), 1001);
                    NotificationHelper.showSuccessNotification(
                            getApplication(),
                            1001,
                            "Model Download Complete",
                            downloadedModel.getLanguage() + " model downloaded successfully."
                    );
                    
                    updateSelectedModelViews(modelManager.getSelectedModel());
                    loadCatalog();
                    processNextDownloadQueue();
                });
            }

            @Override
            public void onCancelled() {
                handler.post(() -> {
                    activeDownloadTask = null;
                    activeDownloadModelId.setValue(null);
                    activeDownloadProgress.setValue(0);
                    activeDownloadSpeedText.setValue("");
                    activeDownloadEtaText.setValue("");
                    activeDownloadPaused.setValue(false);
                    NotificationHelper.cancelNotification(getApplication(), 1001);
                    loadCatalog();
                    processNextDownloadQueue();
                });
            }

            @Override
            public void onPaused() {
                handler.post(() -> {
                    activeDownloadSpeedText.setValue("Paused");
                    activeDownloadEtaText.setValue("");
                    activeDownloadPaused.setValue(true);
                    
                    int progress = activeDownloadProgress.getValue() != null ? activeDownloadProgress.getValue() : 0;
                    NotificationHelper.showProgressNotification(
                            getApplication(),
                            1001,
                            "Download Paused: " + modelInfo.getLanguage(),
                            progress + "%",
                            progress
                    );
                    
                    loadCatalog();
                });
            }

            @Override
            public void onError(String errorMessage) {
                handler.post(() -> {
                    activeDownloadTask = null;
                    activeDownloadModelId.setValue(null);
                    activeDownloadProgress.setValue(0);
                    activeDownloadSpeedText.setValue("");
                    activeDownloadEtaText.setValue("");
                    activeDownloadPaused.setValue(false);
                    NotificationHelper.cancelNotification(getApplication(), 1001);
                    loadCatalog();
                    processNextDownloadQueue();
                });
            }
        });
    }

    public void pauseActiveDownload() {
        if (useTaskService()) {
            runWhenTaskServiceReady(false, () -> taskService.pauseActiveDownload());
            return;
        }
        if (activeDownloadTask != null) {
            activeDownloadTask.pause();
            activeDownloadPaused.setValue(true);
        }
    }

    public void cancelActiveDownload() {
        if (useTaskService()) {
            runWhenTaskServiceReady(false, () -> taskService.cancelActiveDownload());
            return;
        }
        if (activeDownloadTask != null) activeDownloadTask.cancel();
        else processNextDownloadQueue();
        activeDownloadPaused.setValue(false);
    }

    public void cancelQueuedDownload(String modelId) {
        if (useTaskService()) {
            runWhenTaskServiceReady(false, () -> taskService.cancelQueuedDownload(modelId));
            return;
        }
        downloadQueue.removeIf(m -> m.getId().equals(modelId));
        updateQueuedDownloadIds();
        loadCatalog();
    }

    private void processNextDownloadQueue() {
        if (downloadQueue.isEmpty()) return;
        VoskModelInfo next = downloadQueue.remove(0);
        updateQueuedDownloadIds();
        startModelDownload(next);
    }

    private void updateQueuedDownloadIds() {
        List<String> ids = new ArrayList<>();
        for (VoskModelInfo m : downloadQueue) {
            ids.add(m.getId());
        }
        queuedDownloadModelIds.setValue(ids);
    }

    public boolean shouldShowShortsDialog(List<Uri> uris, RotationResolver rotationResolver) {
        if (settingsPrefs.getBoolean(KEY_SHORTS_DONT_SHOW_AGAIN, false)) {
            return false;
        }
        for (Uri uri : uris) {
            if (rotationResolver.isVertical(uri)) {
                return true;
            }
        }
        return false;
    }

    public void setShortsTranscriptionPreferences(boolean wordByWord, boolean dontShowAgain) {
        settingsPrefs.edit()
                .putBoolean(KEY_SHORTS_MODE_WORD_BY_WORD, wordByWord)
                .putBoolean(KEY_SHORTS_DONT_SHOW_AGAIN, dontShowAgain)
                .apply();
        shortsWordByWordDefault.setValue(wordByWord);
        skipShortsDialog.setValue(dontShowAgain);
    }

    // --- Video Queue logic ---

    private String generateThumbnail(Uri uri, long itemId) {
        android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
        try {
            retriever.setDataSource(getApplication(), uri);
            android.graphics.Bitmap bitmap = retriever.getFrameAtTime(1000000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (bitmap == null) {
                bitmap = retriever.getFrameAtTime();
            }
            if (bitmap != null) {
                int size = Math.min(bitmap.getWidth(), bitmap.getHeight());
                int x = (bitmap.getWidth() - size) / 2;
                int y = (bitmap.getHeight() - size) / 2;
                android.graphics.Bitmap cropped = android.graphics.Bitmap.createBitmap(bitmap, x, y, size, size);
                
                android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(cropped, 150, 150, true);
                
                if (cropped != bitmap) {
                    cropped.recycle();
                }
                
                java.io.File thumbFile = new java.io.File(getApplication().getFilesDir(), "thumb_" + itemId + ".png");
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(thumbFile)) {
                    scaled.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, fos);
                    scaled.recycle();
                    return thumbFile.getAbsolutePath();
                }
            }
        } catch (Exception e) {
            android.util.Log.e("MainViewModel", "Error generating thumbnail", e);
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {}
        }
        return "";
    }

    public void addVideosToQueue(List<Uri> uris, DisplayNameResolver nameResolver, RotationResolver rotationResolver) {
        addVideosToQueue(uris, nameResolver, rotationResolver, false);
    }

    public void addVideosToQueue(List<Uri> uris, DisplayNameResolver nameResolver,
                                 RotationResolver rotationResolver, boolean useVad) {
        new Thread(() -> {
            List<QueueItem> newItems = new ArrayList<>();
            boolean wordByWordCaptions = settingsPrefs.getBoolean(KEY_SHORTS_MODE_WORD_BY_WORD, false);
            for (Uri uri : uris) {
                QueueItem item = new QueueItem(uri, nameResolver.resolve(uri));
                // "Shorts mode" for an item means word-by-word captions, which only applies when the
                // video is vertical AND the user picked word-by-word in the Shorts dialog. A vertical
                // video with "Standard captions" is treated as a normal video.
                item.setShortsVideo(rotationResolver.isVertical(uri) && wordByWordCaptions);
                item.setUseVad(useVad);
                long id = queueStore.addItem(item);
                item.setId(id);
                
                String thumbPath = generateThumbnail(uri, id);
                item.setThumbnailPath(thumbPath);
                
                queueStore.updateItem(item);
                newItems.add(item);
            }
            
            handler.post(() -> {
                List<QueueItem> currentList = queueItems.getValue();
                if (currentList == null) currentList = new ArrayList<>();
                currentList.addAll(0, newItems);
                queueItems.setValue(new ArrayList<>(currentList));
                startQueue();
            });
        }).start();
    }

    public void startQueue() {
        if (useTaskService()) {
            runWhenTaskServiceReady(true, () -> taskService.startQueue());
            return;
        }
        if (!Boolean.TRUE.equals(modelReady.getValue())) {
            return;
        }
        if (singleGenerationRunning) {
            return;
        }
        if (Boolean.TRUE.equals(queueRunning.getValue())) return;

        List<QueueItem> current = queueItems.getValue();
        if (current == null || current.isEmpty()) return;

        boolean hasPending = false;
        for (QueueItem item : current) {
            if (item.getStatus() == QueueItem.Status.PENDING) {
                hasPending = true;
                break;
            }
        }
        if (!hasPending) {
            return;
        }
        queueRunning.setValue(true);
        queueCancelRequested = false;
        processNextQueueItem();
    }

    private void processNextQueueItem() {
        if (queueCancelRequested) {
            queueRunning.setValue(false);
            activeQueueItem = null;
            refreshQueue();
            return;
        }
        List<QueueItem> current = queueItems.getValue();
        if (current == null) {
            queueRunning.setValue(false);
            activeQueueItem = null;
            return;
        }

        QueueItem next = null;
        for (QueueItem item : current) {
            if (item.getStatus() == QueueItem.Status.PENDING) {
                next = item;
                break;
            }
        }
        if (next == null) {
            queueRunning.setValue(false);
            activeQueueItem = null;
            refreshQueue();
            return;
        }

        final QueueItem queueItem = next;
        activeQueueItem = queueItem;
        queueItem.setStatus(QueueItem.Status.PROCESSING);
        queueItem.setProgress(-1);
        queueItem.setMessage("Extracting audio...");
        
        String permanentAudioPath = new java.io.File(getApplication().getFilesDir(), "audio_" + queueItem.getId() + ".wav").getAbsolutePath();
        queueItem.setAudioPath(permanentAudioPath);
        
        refreshQueue();

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

        subtitleGenerator.generateSubtitles(queueItem.getVideoUri(), permanentAudioPath, new SubtitleGenerator.SubtitleGenerationCallback() {
            @Override
            public void onPartialSubtitlesGenerated(List<SubtitleGenerator.SubtitleEntry> partialSubtitles) {
                handler.post(() -> {
                    if (isRemovedActiveQueueItem(queueItem)) {
                        return;
                    }
                    queueItem.setSubtitles(partialSubtitles);
                    queueItem.setPreviewText(getPreviewTextHelper(partialSubtitles));
                    if (queueItem == selectedQueueItem.getValue()) {
                        subtitleEntries.setValue(partialSubtitles);
                    }
                    refreshQueue();
                });
            }

            @Override
            public void onSubtitlesGenerated(List<SubtitleGenerator.SubtitleEntry> entries) {
                if (isRemovedActiveQueueItem(queueItem)) {
                    handler.post(() -> finishRemovedActiveQueueItem(queueItem));
                    return;
                }
                
                NotificationHelper.cancelNotification(getApplication(), 2001);
                NotificationHelper.showSuccessNotification(
                        getApplication(),
                        2001,
                        "Subtitles Generated",
                        "Subtitles saved for " + queueItem.getDisplayName()
                );
                
                queueItem.setSubtitles(entries);
                queueItem.setTranslationSourceLanguage(subtitleGenerator.getResolvedTranslationSourceLanguage());
                queueItem.setTranslationTargetLanguage(subtitleGenerator.getTranslationTargetLanguage());
                queueItem.setTranslationStatus(SubtitleGenerator.hasTranslatedSubtitles(entries) ? "translated" : "");
                queueItem.setPreviewText(getPreviewTextHelper(entries));
                subtitleGenerator.saveSubtitlesToFile(entries, batchFormat.getValue(), queueItem.getVideoUri(), new SubtitleGenerator.SubtitleSaveCallback() {
                    @Override
                    public void onSubtitlesSaved(String filePath) {
                        handler.post(() -> {
                            if (isRemovedActiveQueueItem(queueItem)) {
                                finishRemovedActiveQueueItem(queueItem);
                                return;
                            }
                            String savedFormat = batchFormat.getValue() == null ? "srt" : batchFormat.getValue();
                            registerExport(filePath, ExportRecord.TYPE_SUBTITLE, queueItem.getVideoUri(),
                                    queueItem.getDisplayName(), savedFormat.toLowerCase(Locale.getDefault()) + "-subtitles",
                                    savedFormat);
                            queueItem.setStatus(QueueItem.Status.COMPLETED);
                            queueItem.setProgress(100);
                            queueItem.setOutputPath(filePath);
                            queueItem.setMessage("");
                            
                            String format = batchFormat.getValue().toLowerCase(Locale.getDefault());
                            if ("srt".equals(format)) {
                                queueItem.setSrtPath(filePath);
                            } else if ("vtt".equals(format)) {
                                queueItem.setVttPath(filePath);
                            }
                            
                            if (queueItem == selectedQueueItem.getValue()) {
                                subtitleEntries.setValue(entries);
                            }
                            refreshQueue();
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
                            queueItem.setStatus(QueueItem.Status.FAILED);
                            queueItem.setMessage(errorMessage);
                            refreshQueue();
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
                    NotificationHelper.cancelNotification(getApplication(), 2001);
                    queueItem.setStatus(QueueItem.Status.FAILED);
                    queueItem.setMessage(errorMessage);
                    refreshQueue();
                    processNextQueueItem();
                });
            }

            @Override
            public void onProgressUpdate(int progress) {
                handler.post(() -> {
                    if (isRemovedActiveQueueItem(queueItem)) {
                        return;
                    }
                    queueItem.setMessage(subtitleProgressMessage(progress));
                    queueItem.setProgress(progress);
                    refreshQueue();

                    NotificationHelper.showProgressNotification(
                            getApplication(),
                            2001,
                            "Generating Subtitles: " + queueItem.getDisplayName(),
                            progress < 0 ? subtitleProgressMessage(progress) : subtitleProgressMessage(progress) + " " + progress + "%",
                            progress
                    );
                });
            }

            @Override
            public void onCancelled() {
                handler.post(() -> {
                    if (isRemovedActiveQueueItem(queueItem)) {
                        finishRemovedActiveQueueItem(queueItem);
                        return;
                    }
                    NotificationHelper.cancelNotification(getApplication(), 2001);
                    queueItem.setStatus(QueueItem.Status.CANCELLED);
                    queueItem.setMessage("Cancelled");
                    activeQueueItem = null;
                    queueRunning.setValue(false);
                    refreshQueue();
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

    public void cancelCurrentQueueItem() {
        if (useTaskService()) {
            runWhenTaskServiceReady(false, () -> taskService.cancelCurrentQueueItem());
            return;
        }
        if (activeQueueItem != null) {
            queueCancelRequested = true;
            subtitleGenerator.cancelGeneration();
            if (isRemovedActiveQueueItem(activeQueueItem)) {
                finishRemovedActiveQueueItem(activeQueueItem);
            } else {
                activeQueueItem.setStatus(QueueItem.Status.CANCELLED);
                activeQueueItem.setMessage("Cancelled");
                activeQueueItem.setProgress(0);
                queueStore.updateItem(activeQueueItem);
                activeQueueItem = null;
                queueCancelRequested = false;
                queueRunning.setValue(false);
                refreshQueue();
            }
        }
    }

    public void retryQueueItem(QueueItem item) {
        item.setStatus(QueueItem.Status.PENDING);
        item.setProgress(0);
        item.setMessage("");
        item.setOutputPath("");
        item.setSrtPath("");
        item.setVttPath("");
        item.setSoftVideoPath("");
        item.setHardVideoPath("");
        item.setPreviewText("");
        item.setSubtitles(new ArrayList<>());
        item.setTranslationSourceLanguage("");
        item.setTranslationTargetLanguage("");
        item.setTranslationStatus("");
        refreshQueue();
        startQueue();
    }

    public void removeQueueItem(QueueItem item) {
        boolean removingActiveItem = isSameQueueItem(item, activeQueueItem)
                || item.getStatus() == QueueItem.Status.PROCESSING
                || item.getStatus() == QueueItem.Status.TRANSLATING;
        if (removingActiveItem) {
            removedActiveQueueItemIds.add(item.getId());
        }

        List<QueueItem> current = queueItems.getValue();
        if (current != null) {
            removeQueueItemFromList(current, item);
            queueStore.deleteItem(item.getId());
            queueItems.setValue(new ArrayList<>(current));
        }
        if (item == selectedQueueItem.getValue()) {
            clearPreview();
        }
        if (removingActiveItem) {
            runWhenTaskServiceReady(false, () -> {
                taskService.addRemovedActiveQueueItemId(item.getId());
                taskService.cancelCurrentQueueItem();
            });
        } else {
            refreshQueue();
        }
    }

    public void removeSelectedQueueItems() {
        List<QueueItem> current = queueItems.getValue();
        if (current != null) {
            List<QueueItem> toRemove = new ArrayList<>();
            List<Long> removedActiveIds = new ArrayList<>();
            boolean removingActiveItem = false;
            for (QueueItem item : current) {
                if (item.isSelected()) {
                    toRemove.add(item);
                }
            }
            if (!toRemove.isEmpty()) {
                for (QueueItem item : toRemove) {
                    if (isSameQueueItem(item, activeQueueItem)
                            || item.getStatus() == QueueItem.Status.PROCESSING
                            || item.getStatus() == QueueItem.Status.TRANSLATING) {
                        removingActiveItem = true;
                        removedActiveQueueItemIds.add(item.getId());
                        removedActiveIds.add(item.getId());
                    }
                    removeQueueItemFromList(current, item);
                    queueStore.deleteItem(item.getId());
                    if (item == selectedQueueItem.getValue()) {
                        clearPreview();
                    }
                }
                queueItems.setValue(new ArrayList<>(current));
                if (removingActiveItem) {
                    runWhenTaskServiceReady(false, () -> {
                        for (Long id : removedActiveIds) {
                            taskService.addRemovedActiveQueueItemId(id);
                        }
                        taskService.cancelCurrentQueueItem();
                    });
                    return;
                }
            }
        }
        refreshQueue();
    }

    private void removeQueueItemFromList(List<QueueItem> items, QueueItem itemToRemove) {
        if (items == null || itemToRemove == null) {
            return;
        }
        for (int i = items.size() - 1; i >= 0; i--) {
            if (isSameQueueItem(items.get(i), itemToRemove)) {
                items.remove(i);
            }
        }
    }

    private boolean isSameQueueItem(QueueItem first, QueueItem second) {
        return first != null && second != null && first.getId() == second.getId();
    }

    private boolean isRemovedActiveQueueItem(QueueItem item) {
        return item != null && removedActiveQueueItemIds.contains(item.getId());
    }

    private void finishRemovedActiveQueueItem(QueueItem item) {
        removedActiveQueueItemIds.remove(item.getId());
        if (isSameQueueItem(activeQueueItem, item)) {
            activeQueueItem = null;
        }
        queueCancelRequested = false;
        queueRunning.setValue(false);
        refreshQueue();
        startQueue();
    }

    private void refreshQueue() {
        List<QueueItem> current = queueItems.getValue();
        if (current == null) return;
        for (QueueItem item : current) {
            queueStore.updateItem(item);
        }
        queueItems.setValue(new ArrayList<>(current));
    }

    public void updateMovedExportPath(String oldPath, String newPath) {
        if (oldPath == null || newPath == null) {
            return;
        }
        exportStore.updatePath(oldPath, new File(newPath), new File(ApplicationPath.applicationPath(getApplication())));
        List<QueueItem> current = queueItems.getValue();
        if (current == null) {
            return;
        }
        boolean changed = false;
        for (QueueItem item : current) {
            if (oldPath.equals(item.getOutputPath())) {
                item.setOutputPath(newPath);
                changed = true;
            }
            if (oldPath.equals(item.getSrtPath())) {
                item.setSrtPath(newPath);
                changed = true;
            }
            if (oldPath.equals(item.getVttPath())) {
                item.setVttPath(newPath);
                changed = true;
            }
            if (oldPath.equals(item.getSoftVideoPath())) {
                item.setSoftVideoPath(newPath);
                changed = true;
            }
            if (oldPath.equals(item.getHardVideoPath())) {
                item.setHardVideoPath(newPath);
                changed = true;
            }
        }
        if (changed) {
            refreshQueue();
        }
    }

    private void registerExport(String filePath, String type, Uri sourceVideoUri, String sourceVideoName,
                                String exportKind, String format) {
        if (filePath == null || filePath.trim().isEmpty()) {
            return;
        }
        File file = new File(filePath);
        exportStore.addFile(file,
                new File(ApplicationPath.applicationPath(getApplication())),
                type,
                selectedModelInfo.getValue(),
                sourceVideoUri == null ? "" : sourceVideoUri.toString(),
                sourceVideoName,
                exportKind,
                format);
    }

    public String getThumbnailPathForUri(String sourceVideoUri) {
        if (sourceVideoUri == null || sourceVideoUri.isEmpty()) {
            return "";
        }
        List<QueueItem> items = queueItems.getValue();
        if (items != null) {
            for (QueueItem item : items) {
                if (item.getVideoUri() != null && sourceVideoUri.equals(item.getVideoUri().toString())) {
                    return item.getThumbnailPath();
                }
            }
        }
        return "";
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

    // --- Preview Screen API ---

    public void openQueueItemPreview(QueueItem item) {
        selectedQueueItem.setValue(item);
        currentVideoUri.setValue(item.getVideoUri());
        subtitleEntries.setValue(item.getSubtitles());
        shortsPreviewMode.setValue(item.isShortsVideo());
        shortsCaptionX.setValue(item.getShortsCaptionX());
        shortsCaptionY.setValue(item.getShortsCaptionY());
        shortsCaptionScale.setValue(item.getShortsCaptionScale());
        subtitleCaptionX.setValue(item.getSubtitleCaptionX());
        subtitleCaptionY.setValue(item.getSubtitleCaptionY());
        subtitleCaptionScale.setValue(item.getSubtitleCaptionScale());
        
        navigateToPreviewTrigger.setValue(true);
    }

    public void updateQueueItemVideoUri(QueueItem item, Uri newUri) {
        if (item != null && newUri != null) {
            item.setVideoUri(newUri);
            queueStore.updateItem(item);
            
            new Thread(() -> {
                String thumbPath = generateThumbnail(newUri, item.getId());
                item.setThumbnailPath(thumbPath);
                queueStore.updateItem(item);
                
                handler.post(() -> {
                    List<QueueItem> current = queueItems.getValue();
                    if (current != null) {
                        queueItems.setValue(new ArrayList<>(current));
                    }
                });
            }).start();

            QueueItem activePreview = selectedQueueItem.getValue();
            if (activePreview != null && activePreview.getId() == item.getId()) {
                currentVideoUri.setValue(newUri);
            }
        }
    }

    public void clearPreview() {
        selectedQueueItem.setValue(null);
        currentVideoUri.setValue(null);
        subtitleEntries.setValue(new ArrayList<>());
        shortsPreviewMode.setValue(false);
        shortsCaptionX.setValue(0.5f);
        shortsCaptionY.setValue(0.5f);
        shortsCaptionScale.setValue(1f);
        subtitleCaptionX.setValue(0.5f);
        subtitleCaptionY.setValue(0.88f);
        subtitleCaptionScale.setValue(1f);
    }

    public void persistCurrentPreviewEdits(List<SubtitleGenerator.SubtitleEntry> currentSubtitles) {
        QueueItem qItem = selectedQueueItem.getValue();
        if (qItem != null) {
            qItem.setSubtitles(currentSubtitles);
            qItem.setPreviewText(getPreviewTextHelper(currentSubtitles));
            refreshQueue();
        }
    }

    public void updateSubtitle(int position, String newText) {
        List<SubtitleGenerator.SubtitleEntry> entries = subtitleEntries.getValue();
        if (entries != null && position >= 0 && position < entries.size()) {
            entries.get(position).setText(newText);
            subtitleEntries.setValue(new ArrayList<>(entries));
            persistCurrentPreviewEdits(entries);
        }
    }

    public void updateSubtitle(int position, String originalText, String translationText) {
        List<SubtitleGenerator.SubtitleEntry> entries = subtitleEntries.getValue();
        if (entries != null && position >= 0 && position < entries.size()) {
            entries.get(position).setText(originalText);
            entries.get(position).setTranslationText(translationText);
            subtitleEntries.setValue(new ArrayList<>(entries));
            persistCurrentPreviewEdits(entries);
        }
    }

    public void deleteSubtitle(int position) {
        List<SubtitleGenerator.SubtitleEntry> entries = subtitleEntries.getValue();
        if (entries != null && position >= 0 && position < entries.size()) {
            entries.remove(position);
            for (int i = position; i < entries.size(); i++) {
                entries.get(i).setNumber(i + 1);
            }
            subtitleEntries.setValue(new ArrayList<>(entries));
            persistCurrentPreviewEdits(entries);
        }
    }

    public void mergeSelectedSubtitles(Set<Integer> selectedPositions) {
        List<SubtitleGenerator.SubtitleEntry> entries = subtitleEntries.getValue();
        if (entries == null || selectedPositions.size() < 2) return;

        List<Integer> sortedPositions = new ArrayList<>(selectedPositions);
        Collections.sort(sortedPositions);
        int startPosition = sortedPositions.get(0);
        int endPosition = sortedPositions.get(sortedPositions.size() - 1);

        SubtitleGenerator.SubtitleEntry mergedEntry = entries.get(startPosition);
        StringBuilder mergedText = new StringBuilder(mergedEntry.getText());
        StringBuilder mergedTranslation = new StringBuilder(mergedEntry.getTranslationText());
        List<WordTiming> mergedWords = new ArrayList<>(mergedEntry.getWords());

        for (int i = startPosition + 1; i <= endPosition; i++) {
            mergedText.append(" ").append(entries.get(i).getText());
            if (entries.get(i).hasTranslation()) {
                if (mergedTranslation.length() > 0) mergedTranslation.append(" ");
                mergedTranslation.append(entries.get(i).getTranslationText());
            }
            mergedWords.addAll(entries.get(i).getWords());
        }

        mergedEntry.setText(mergedText.toString());
        mergedEntry.setTranslationText(mergedTranslation.toString());
        mergedEntry.setEndTime(entries.get(endPosition).getEndTime());
        mergedEntry.setWords(mergedWords);

        for (int i = endPosition; i > startPosition; i--) {
            entries.remove(i);
        }

        for (int i = startPosition; i < entries.size(); i++) {
            entries.get(i).setNumber(i + 1);
        }

        subtitleEntries.setValue(new ArrayList<>(entries));
        persistCurrentPreviewEdits(entries);
    }

    public void deleteSelectedSubtitles(Set<Integer> selectedPositions) {
        List<SubtitleGenerator.SubtitleEntry> entries = subtitleEntries.getValue();
        if (entries == null) return;

        List<Integer> sortedPositions = new ArrayList<>(selectedPositions);
        Collections.sort(sortedPositions, Collections.reverseOrder());

        for (int position : sortedPositions) {
            entries.remove(position);
        }

        for (int i = 0; i < entries.size(); i++) {
            entries.get(i).setNumber(i + 1);
        }

        subtitleEntries.setValue(new ArrayList<>(entries));
        persistCurrentPreviewEdits(entries);
    }

    public void saveSubtitlesInFormat(String format, SubtitleGenerator.SubtitleSaveCallback callback) {
        saveSubtitlesInFormat(format, null, callback);
    }

    public void saveSubtitlesInFormat(String format, File outputDir, SubtitleGenerator.SubtitleSaveCallback callback) {
        saveSubtitlesInFormat(format, outputDir, SubtitleGenerator.SubtitleLayerMode.ORIGINAL, callback);
    }

    public void saveSubtitlesInFormat(String format, File outputDir, SubtitleGenerator.SubtitleLayerMode layerMode,
                                      SubtitleGenerator.SubtitleSaveCallback callback) {
        Uri videoUri = currentVideoUri.getValue();
        List<SubtitleGenerator.SubtitleEntry> entries = subtitleEntries.getValue();
        if (videoUri == null || entries == null || entries.isEmpty()) {
            callback.onError("No video or subtitles available to save");
            return;
        }

        if (useTaskService()) {
            VoskModelInfo modelInfo = selectedModelInfo.getValue();
            runWhenTaskServiceReady(true, () -> taskService.savePreviewSubtitles(entries, format, videoUri, outputDir, modelInfo, layerMode,
                    new SubtitleGenerator.SubtitleSaveCallback() {
                        @Override
                        public void onSubtitlesSaved(String filePath) {
                            handler.post(() -> {
                                QueueItem qItem = selectedQueueItem.getValue();
                                if (qItem != null) {
                                    String f = format.toLowerCase(Locale.getDefault());
                                    if ("srt".equals(f) && layerMode == SubtitleGenerator.SubtitleLayerMode.ORIGINAL) qItem.setSrtPath(filePath);
                                    if ("vtt".equals(f) && layerMode == SubtitleGenerator.SubtitleLayerMode.ORIGINAL) qItem.setVttPath(filePath);
                                    qItem.setOutputPath(filePath);
                                    refreshQueue();
                                }
                                callback.onSubtitlesSaved(filePath);
                            });
                        }

                        @Override
                        public void onError(String errorMessage) {
                            handler.post(() -> callback.onError(errorMessage));
                        }
                    }));
            return;
        }

        subtitleGenerator.saveSubtitlesToFile(entries, format, videoUri, outputDir, layerMode, new SubtitleGenerator.SubtitleSaveCallback() {
            @Override
            public void onSubtitlesSaved(String filePath) {
                handler.post(() -> {
                    registerExport(filePath, ExportRecord.TYPE_SUBTITLE, videoUri, getDisplayNameHelper(videoUri),
                            format.toLowerCase(Locale.getDefault()) + "-" + layerMode.name().toLowerCase(Locale.US) + "-subtitles", format);
                    QueueItem qItem = selectedQueueItem.getValue();
                    if (qItem != null) {
                        String f = format.toLowerCase(Locale.getDefault());
                        if ("srt".equals(f) && layerMode == SubtitleGenerator.SubtitleLayerMode.ORIGINAL) {
                            qItem.setSrtPath(filePath);
                        } else if ("vtt".equals(f) && layerMode == SubtitleGenerator.SubtitleLayerMode.ORIGINAL) {
                            qItem.setVttPath(filePath);
                        }
                        qItem.setOutputPath(filePath);
                        refreshQueue();
                    }
                    callback.onSubtitlesSaved(filePath);
                });
            }

            @Override
            public void onError(String errorMessage) {
                handler.post(() -> callback.onError(errorMessage));
            }
        });
    }

    public void exportVideoWithSubtitles(boolean burnSubtitles, String fontName, SubtitleGenerator.VideoExportCallback callback) {
        exportVideoWithSubtitles(burnSubtitles, fontName, false, callback);
    }

    public void exportVideoWithSubtitles(boolean burnSubtitles, String fontName, boolean forceMp4SoftSubtitles,
                                         SubtitleGenerator.VideoExportCallback callback) {
        exportVideoWithSubtitles(burnSubtitles, fontName, forceMp4SoftSubtitles, null, callback);
    }

    public void exportVideoWithSubtitles(boolean burnSubtitles, String fontName, boolean forceMp4SoftSubtitles,
                                         File outputDir, SubtitleGenerator.VideoExportCallback callback) {
        exportVideoWithSubtitles(burnSubtitles, fontName, forceMp4SoftSubtitles, outputDir,
                SubtitleGenerator.SubtitleLayerMode.ORIGINAL, callback);
    }

    public void exportVideoWithSubtitles(boolean burnSubtitles, String fontName, boolean forceMp4SoftSubtitles,
                                         File outputDir, SubtitleGenerator.SubtitleLayerMode layerMode,
                                         SubtitleGenerator.VideoExportCallback callback) {
        Uri videoUri = currentVideoUri.getValue();
        List<SubtitleGenerator.SubtitleEntry> entries = subtitleEntries.getValue();
        if (videoUri == null || entries == null || entries.isEmpty()) {
            callback.onError("No video or subtitles available to export");
            return;
        }

        if (useTaskService()) {
            SubtitleGenerator.ShortsSubtitleStyle shortsStyle = getSelectedShortsSubtitleStyle();
            VoskModelInfo modelInfo = selectedModelInfo.getValue();
            runWhenTaskServiceReady(true, () -> taskService.exportPreviewVideo(videoUri, entries, burnSubtitles, fontName,
                    shortsStyle, forceMp4SoftSubtitles, outputDir, modelInfo, layerMode, new SubtitleGenerator.VideoExportCallback() {
                        @Override
                        public void onVideoExported(String filePath) {
                            handler.post(() -> {
                                QueueItem qItem = selectedQueueItem.getValue();
                                if (qItem != null) {
                                    if (burnSubtitles) qItem.setHardVideoPath(filePath);
                                    else qItem.setSoftVideoPath(filePath);
                                    refreshQueue();
                                }
                                callback.onVideoExported(filePath);
                            });
                        }

                        @Override
                        public void onError(String errorMessage) {
                            handler.post(() -> callback.onError(errorMessage));
                        }

                        @Override
                        public void onProgressUpdate(int progress) {
                            handler.post(() -> callback.onProgressUpdate(progress));
                        }
                    }));
            return;
        }

        SubtitleGenerator.ShortsSubtitleStyle shortsStyle = getSelectedShortsSubtitleStyle();
        subtitleGenerator.exportVideoWithSubtitles(videoUri, entries, burnSubtitles, fontName, shortsStyle,
                forceMp4SoftSubtitles, outputDir, layerMode, new SubtitleGenerator.VideoExportCallback() {
            @Override
            public void onVideoExported(String filePath) {
                handler.post(() -> {
                    registerExport(filePath, ExportRecord.TYPE_VIDEO, videoUri, getDisplayNameHelper(videoUri),
                            (burnSubtitles ? "hard-" : "soft-") + layerMode.name().toLowerCase(Locale.US) + "-subtitles",
                            filePath.toLowerCase(Locale.getDefault()).endsWith(".mkv") ? "mkv" : "mp4");
                    QueueItem qItem = selectedQueueItem.getValue();
                    if (qItem != null) {
                        if (burnSubtitles) {
                            qItem.setHardVideoPath(filePath);
                        } else {
                            qItem.setSoftVideoPath(filePath);
                        }
                        refreshQueue();
                    }
                    
                    NotificationHelper.cancelNotification(getApplication(), 3001);
                    NotificationHelper.showSuccessNotification(
                            getApplication(),
                            3001,
                            "Video Export Complete",
                            "Video exported successfully."
                    );
                    
                    callback.onVideoExported(filePath);
                });
            }

            @Override
            public void onError(String errorMessage) {
                handler.post(() -> {
                    NotificationHelper.cancelNotification(getApplication(), 3001);
                    callback.onError(errorMessage);
                });
            }

            @Override
            public void onProgressUpdate(int progress) {
                handler.post(() -> {
                    NotificationHelper.showProgressNotification(
                            getApplication(),
                            3001,
                            "Exporting Video",
                            progress + "%",
                            progress
                    );
                    callback.onProgressUpdate(progress);
                });
            }
        });
    }

    private SubtitleGenerator.ShortsSubtitleStyle getSelectedShortsSubtitleStyle() {
        QueueItem item = selectedQueueItem.getValue();
        return getShortsSubtitleStyle(item);
    }

    private SubtitleGenerator.ShortsSubtitleStyle getShortsSubtitleStyle(QueueItem item) {
        if (item == null || !item.isShortsVideo()) {
            if (item == null || !item.isSubtitleCaptionPositionAdjusted()) {
                return null;
            }
            return new SubtitleGenerator.ShortsSubtitleStyle(
                    item.getSubtitleCaptionX(),
                    item.getSubtitleCaptionY(),
                    30f * item.getSubtitleCaptionScale(),
                    false,
                    false,
                    false);
        }
        Float size = shortsCaptionSize.getValue();
        Boolean uppercase = shortsUppercase.getValue();
        return new SubtitleGenerator.ShortsSubtitleStyle(
                item.getShortsCaptionX(),
                item.getShortsCaptionY(),
                (size == null ? 30f : size) * item.getShortsCaptionScale(),
                Boolean.TRUE.equals(uppercase),
                true,
                true);
    }

    public void saveSubtitlesForQueueItem(QueueItem item, String format, SubtitleGenerator.SubtitleSaveCallback callback) {
        saveSubtitlesForQueueItem(item, format, null, callback);
    }

    public void translateQueueItem(QueueItem item, SubtitleGenerator.TranslationCallback callback) {
        if (item == null || item.getSubtitles().isEmpty()) {
            callback.onError("No subtitles available to translate");
            return;
        }
        if (useTaskService()) {
            String sourceLanguage = settingsPrefs.getString(KEY_TRANSLATION_SOURCE_LANGUAGE, "auto");
            if ("auto".equalsIgnoreCase(sourceLanguage) && !item.getTranslationSourceLanguage().isEmpty()) {
                sourceLanguage = item.getTranslationSourceLanguage();
            }
            String effectiveSourceLanguage = sourceLanguage;
            runWhenTaskServiceReady(true, () -> taskService.translateQueueItem(item,
                    effectiveSourceLanguage,
                    settingsPrefs.getString(KEY_TRANSLATION_TARGET_LANGUAGE, "en"),
                    callback));
            return;
        }

        item.setStatus(QueueItem.Status.TRANSLATING);
        item.setProgress(-1);
        item.setMessage("Translating subtitles...");
        item.setTranslationStatus("translating");
        refreshQueue();

        subtitleGenerator.setTranslationSettings(true,
                effectiveTranslationSourceFor(item),
                settingsPrefs.getString(KEY_TRANSLATION_TARGET_LANGUAGE, "en"));
        subtitleGenerator.translateExistingSubtitles(item.getSubtitles(), new SubtitleGenerator.TranslationCallback() {
            @Override
            public void onTranslated(List<SubtitleGenerator.SubtitleEntry> subtitleEntries, String sourceLanguage, String targetLanguage) {
                handler.post(() -> {
                    item.setSubtitles(subtitleEntries);
                    item.setTranslationSourceLanguage(sourceLanguage);
                    item.setTranslationTargetLanguage(targetLanguage);
                    item.setTranslationStatus("translated");
                    item.setStatus(QueueItem.Status.COMPLETED);
                    item.setProgress(100);
                    item.setMessage("Translated subtitles");
                    item.setPreviewText(getPreviewTextHelper(subtitleEntries));
                    QueueItem selected = selectedQueueItem.getValue();
                    if (selected != null && selected.getId() == item.getId()) {
                        MainViewModel.this.subtitleEntries.setValue(new ArrayList<>(subtitleEntries));
                    }
                    refreshQueue();
                    callback.onTranslated(subtitleEntries, sourceLanguage, targetLanguage);
                });
            }

            @Override
            public void onError(String errorMessage) {
                handler.post(() -> {
                    item.setStatus(QueueItem.Status.COMPLETED);
                    item.setProgress(100);
                    item.setTranslationStatus("failed");
                    item.setMessage("Translation failed: " + errorMessage);
                    refreshQueue();
                    callback.onError(errorMessage);
                });
            }

            @Override
            public void onProgressUpdate(int progress) {
                handler.post(() -> {
                    item.setProgress(progress);
                    item.setMessage(progress < 0 ? "Translating subtitles..." : "Translating subtitles... " + progress + "%");
                    refreshQueue();
                    callback.onProgressUpdate(progress);
                });
            }
        });
    }

    private String effectiveTranslationSourceFor(QueueItem item) {
        String sourceLanguage = settingsPrefs.getString(KEY_TRANSLATION_SOURCE_LANGUAGE, "auto");
        if ("auto".equalsIgnoreCase(sourceLanguage) && item != null && !item.getTranslationSourceLanguage().isEmpty()) {
            return item.getTranslationSourceLanguage();
        }
        return sourceLanguage;
    }

    public void saveSubtitlesForQueueItem(QueueItem item, String format, File outputDir,
                                          SubtitleGenerator.SubtitleSaveCallback callback) {
        saveSubtitlesForQueueItem(item, format, outputDir, SubtitleGenerator.SubtitleLayerMode.ORIGINAL, callback);
    }

    public void saveSubtitlesForQueueItem(QueueItem item, String format, File outputDir,
                                          SubtitleGenerator.SubtitleLayerMode layerMode,
                                          SubtitleGenerator.SubtitleSaveCallback callback) {
        Uri videoUri = item.getVideoUri();
        List<SubtitleGenerator.SubtitleEntry> entries = item.getSubtitles();
        if (videoUri == null || entries == null || entries.isEmpty()) {
            callback.onError("No video or subtitles available to save");
            return;
        }

        if (useTaskService()) {
            VoskModelInfo modelInfo = selectedModelInfo.getValue();
            runWhenTaskServiceReady(true, () -> taskService.saveSubtitlesForQueueItem(item, format, outputDir, modelInfo, layerMode, callback));
            return;
        }

        item.setStatus(QueueItem.Status.EXPORTING);
        item.setProgress(0);
        item.setMessage("Saving subtitles...");
        refreshQueue();

        subtitleGenerator.saveSubtitlesToFile(entries, format, videoUri, outputDir, layerMode, new SubtitleGenerator.SubtitleSaveCallback() {
            @Override
            public void onSubtitlesSaved(String filePath) {
                handler.post(() -> {
                    registerExport(filePath, ExportRecord.TYPE_SUBTITLE, videoUri, item.getDisplayName(),
                            format.toLowerCase(Locale.getDefault()) + "-" + layerMode.name().toLowerCase(Locale.US) + "-subtitles", format);
                    String f = format.toLowerCase(Locale.getDefault());
                    if ("srt".equals(f) && layerMode == SubtitleGenerator.SubtitleLayerMode.ORIGINAL) {
                        item.setSrtPath(filePath);
                    } else if ("vtt".equals(f) && layerMode == SubtitleGenerator.SubtitleLayerMode.ORIGINAL) {
                        item.setVttPath(filePath);
                    }
                    item.setStatus(QueueItem.Status.COMPLETED);
                    item.setProgress(100);
                    item.setMessage("Subtitles saved: " + filePath);
                    refreshQueue();
                    callback.onSubtitlesSaved(filePath);
                });
            }

            @Override
            public void onError(String errorMessage) {
                handler.post(() -> {
                    item.setStatus(QueueItem.Status.COMPLETED);
                    item.setMessage("Failed to save: " + errorMessage);
                    refreshQueue();
                    callback.onError(errorMessage);
                });
            }
        });
    }

    public void exportVideoForQueueItem(QueueItem item, boolean burnSubtitles, String fontName, SubtitleGenerator.VideoExportCallback callback) {
        exportVideoForQueueItem(item, burnSubtitles, fontName, false, callback);
    }

    public void exportVideoForQueueItem(QueueItem item, boolean burnSubtitles, String fontName,
                                        boolean forceMp4SoftSubtitles,
                                        SubtitleGenerator.VideoExportCallback callback) {
        exportVideoForQueueItem(item, burnSubtitles, fontName, forceMp4SoftSubtitles, null, callback);
    }

    public void exportVideoForQueueItem(QueueItem item, boolean burnSubtitles, String fontName,
                                        boolean forceMp4SoftSubtitles, File outputDir,
                                        SubtitleGenerator.VideoExportCallback callback) {
        exportVideoForQueueItem(item, burnSubtitles, fontName, forceMp4SoftSubtitles, outputDir,
                SubtitleGenerator.SubtitleLayerMode.ORIGINAL, callback);
    }

    public void exportVideoForQueueItem(QueueItem item, boolean burnSubtitles, String fontName,
                                        boolean forceMp4SoftSubtitles, File outputDir,
                                        SubtitleGenerator.SubtitleLayerMode layerMode,
                                        SubtitleGenerator.VideoExportCallback callback) {
        Uri videoUri = item.getVideoUri();
        List<SubtitleGenerator.SubtitleEntry> entries = item.getSubtitles();
        if (videoUri == null || entries == null || entries.isEmpty()) {
            callback.onError("No video or subtitles available to export");
            return;
        }

        if (useTaskService()) {
            SubtitleGenerator.ShortsSubtitleStyle shortsStyle = getShortsSubtitleStyle(item);
            VoskModelInfo modelInfo = selectedModelInfo.getValue();
            runWhenTaskServiceReady(true, () -> taskService.exportVideoForQueueItem(item, burnSubtitles, fontName,
                    shortsStyle, forceMp4SoftSubtitles, outputDir, modelInfo, layerMode, callback));
            return;
        }

        item.setStatus(QueueItem.Status.EXPORTING);
        item.setProgress(-1);
        item.setMessage("Exporting video...");
        refreshQueue();

        SubtitleGenerator.ShortsSubtitleStyle shortsStyle = getShortsSubtitleStyle(item);
        subtitleGenerator.exportVideoWithSubtitles(videoUri, entries, burnSubtitles, fontName, shortsStyle,
                forceMp4SoftSubtitles, outputDir, layerMode, new SubtitleGenerator.VideoExportCallback() {
            @Override
            public void onVideoExported(String filePath) {
                handler.post(() -> {
                    registerExport(filePath, ExportRecord.TYPE_VIDEO, videoUri, item.getDisplayName(),
                            (burnSubtitles ? "hard-" : "soft-") + layerMode.name().toLowerCase(Locale.US) + "-subtitles",
                            filePath.toLowerCase(Locale.getDefault()).endsWith(".mkv") ? "mkv" : "mp4");
                    if (burnSubtitles) {
                        item.setHardVideoPath(filePath);
                    } else {
                        item.setSoftVideoPath(filePath);
                    }
                    item.setStatus(QueueItem.Status.COMPLETED);
                    item.setProgress(100);
                    item.setMessage("Video exported: " + filePath);
                    refreshQueue();
                    
                    NotificationHelper.cancelNotification(getApplication(), 3001);
                    NotificationHelper.showSuccessNotification(
                            getApplication(),
                            3001,
                            "Video Export Complete",
                            "Video exported successfully: " + item.getDisplayName()
                    );
                    
                    callback.onVideoExported(filePath);
                });
            }

            @Override
            public void onError(String errorMessage) {
                handler.post(() -> {
                    NotificationHelper.cancelNotification(getApplication(), 3001);
                    item.setStatus(QueueItem.Status.COMPLETED);
                    item.setMessage("Failed to export: " + errorMessage);
                    refreshQueue();
                    callback.onError(errorMessage);
                });
            }

            @Override
            public void onProgressUpdate(int progress) {
                handler.post(() -> {
                    if (progress < 0) {
                        item.setMessage("Exporting video...");
                    }
                    item.setProgress(progress);
                    refreshQueue();
                    
                    NotificationHelper.showProgressNotification(
                            getApplication(),
                            3001,
                            "Exporting Video: " + item.getDisplayName(),
                            progress + "%",
                            progress
                    );
                    
                    callback.onProgressUpdate(progress);
                });
            }
        });
    }

    public void batchSaveSubtitles(String format, SubtitleGenerator.SubtitleSaveCallback callback) {
        batchSaveSubtitles(format, null, callback);
    }

    public void batchSaveSubtitles(String format, File outputDir, SubtitleGenerator.SubtitleSaveCallback callback) {
        List<QueueItem> list = queueItems.getValue();
        if (list == null || list.isEmpty()) {
            callback.onError("No videos in the queue");
            return;
        }

        boolean hasSelection = false;
        for (QueueItem item : list) {
            if (item.isSelected()) {
                hasSelection = true;
                break;
            }
        }

        List<QueueItem> completedItems = new ArrayList<>();
        for (QueueItem item : list) {
            if (item.getStatus() == QueueItem.Status.COMPLETED && !item.getSubtitles().isEmpty()) {
                if (!hasSelection || item.isSelected()) {
                    completedItems.add(item);
                }
            }
        }

        if (completedItems.isEmpty()) {
            callback.onError(hasSelection ? "No completed selected items with subtitles to export" : "No completed items with subtitles to export");
            return;
        }

        if (useTaskService()) {
            VoskModelInfo modelInfo = selectedModelInfo.getValue();
            runWhenTaskServiceReady(true, () -> taskService.batchSaveSubtitles(completedItems, format, outputDir, modelInfo, callback));
            return;
        }

        batchSaveNext(completedItems, 0, format, outputDir, callback);
    }

    private void batchSaveNext(List<QueueItem> items, int index, String format, File outputDir,
                               SubtitleGenerator.SubtitleSaveCallback callback) {
        if (index >= items.size()) {
            callback.onSubtitlesSaved("All subtitles exported successfully!");
            return;
        }

        QueueItem item = items.get(index);
        saveSubtitlesForQueueItem(item, format, outputDir, new SubtitleGenerator.SubtitleSaveCallback() {
            @Override
            public void onSubtitlesSaved(String filePath) {
                handler.post(() -> {
                    batchSaveNext(items, index + 1, format, outputDir, callback);
                });
            }

            @Override
            public void onError(String errorMessage) {
                handler.post(() -> {
                    batchSaveNext(items, index + 1, format, outputDir, callback);
                });
            }
        });
    }

    public void batchExportVideos(boolean burnSubtitles, String fontName, SubtitleGenerator.VideoExportCallback callback) {
        batchExportVideos(burnSubtitles, fontName, null, callback);
    }

    public void batchExportVideos(boolean burnSubtitles, String fontName, File outputDir,
                                  SubtitleGenerator.VideoExportCallback callback) {
        List<QueueItem> list = queueItems.getValue();
        if (list == null || list.isEmpty()) {
            callback.onError("No videos in the queue");
            return;
        }

        boolean hasSelection = false;
        for (QueueItem item : list) {
            if (item.isSelected()) {
                hasSelection = true;
                break;
            }
        }

        List<QueueItem> completedItems = new ArrayList<>();
        for (QueueItem item : list) {
            if (item.getStatus() == QueueItem.Status.COMPLETED && !item.getSubtitles().isEmpty()) {
                if (!hasSelection || item.isSelected()) {
                    completedItems.add(item);
                }
            }
        }

        if (completedItems.isEmpty()) {
            callback.onError(hasSelection ? "No completed selected items with subtitles to export" : "No completed items with subtitles to export");
            return;
        }

        if (useTaskService()) {
            VoskModelInfo modelInfo = selectedModelInfo.getValue();
            runWhenTaskServiceReady(true, () -> taskService.batchExportVideos(completedItems, burnSubtitles, fontName,
                    outputDir, modelInfo, this::getShortsSubtitleStyle, callback));
            return;
        }

        batchExportNext(completedItems, 0, burnSubtitles, fontName, outputDir, callback);
    }

    private void batchExportNext(List<QueueItem> items, int index, boolean burnSubtitles, String fontName,
                                 File outputDir, SubtitleGenerator.VideoExportCallback callback) {
        if (index >= items.size()) {
            callback.onVideoExported("All videos exported successfully!");
            return;
        }

        QueueItem item = items.get(index);
        exportVideoForQueueItem(item, burnSubtitles, fontName, false, outputDir, new SubtitleGenerator.VideoExportCallback() {
            @Override
            public void onVideoExported(String filePath) {
                handler.post(() -> {
                    batchExportNext(items, index + 1, burnSubtitles, fontName, outputDir, callback);
                });
            }

            @Override
            public void onError(String errorMessage) {
                handler.post(() -> {
                    batchExportNext(items, index + 1, burnSubtitles, fontName, outputDir, callback);
                });
            }

            @Override
            public void onProgressUpdate(int progress) {
                // Handled inside exportVideoForQueueItem
            }
        });
    }

    private String getDisplayNameHelper(Uri uri) {
        try (android.database.Cursor cursor = getApplication().getContentResolver().query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) {
        }
        return "Video";
    }

    @Override
    protected void onCleared() {
        finishModelProbe();
        if (taskServiceBound && taskService != null) {
            taskService.removeListener(taskListener);
            getApplication().unbindService(taskServiceConnection);
            taskServiceBound = false;
            taskServiceBindingRequested = false;
            taskService = null;
        }
        stopRamUsagePolling();
        super.onCleared();
    }

    private Runnable ramPollRunnable;

    private void startRamUsagePolling() {
        ramPollRunnable = new Runnable() {
            @Override
            public void run() {
                updateRamUsage();
                handler.postDelayed(this, 2500);
            }
        };
        handler.post(ramPollRunnable);
    }

    private void stopRamUsagePolling() {
        if (ramPollRunnable != null) {
            handler.removeCallbacks(ramPollRunnable);
            ramPollRunnable = null;
        }
    }

    private void updateRamUsage() {
        try {
            long javaHeap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            long nativeHeap = android.os.Debug.getNativeHeapAllocatedSize();
            double totalMb = (javaHeap + nativeHeap) / (1024.0 * 1024.0);
            ramUsage.postValue(String.format(Locale.getDefault(), "RAM: %.1f MB", totalMb));
        } catch (Exception ignored) {
            try {
                long usedBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
                double heapMb = usedBytes / (1024.0 * 1024.0);
                ramUsage.postValue(String.format(Locale.getDefault(), "RAM: %.1f MB", heapMb));
            } catch (Exception ignoredInner) {
            }
        }
    }

    public List<File> getExistingExportsForVideo(Uri videoUri) {
        if (videoUri == null) return new ArrayList<>();
        return exportStore.getExistingExportsForVideo(videoUri.toString());
    }

    public void deleteExportsForVideo(Uri videoUri) {
        if (videoUri == null) return;
        exportStore.deleteExportsForVideo(videoUri.toString());
    }
}
