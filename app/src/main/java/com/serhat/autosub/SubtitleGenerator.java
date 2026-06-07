package com.serhat.autosub;

import android.content.Context;

import android.net.Uri;
import android.util.Log;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;
import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.konovalov.vad.webrtc.Vad;
import com.konovalov.vad.webrtc.VadWebRTC;
import com.konovalov.vad.webrtc.config.FrameSize;
import com.konovalov.vad.webrtc.config.Mode;
import com.konovalov.vad.webrtc.config.SampleRate;
import com.whispercpp.whisper.WhisperCallback;
import com.whispercpp.whisper.WhisperContext;

import org.json.JSONObject;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.StorageService;
import org.json.JSONArray;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.BufferedReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;

public class SubtitleGenerator {
    private static final String TAG = "SubtitleGenerator";
    private static final boolean VERBOSE_SUBTITLE_LOGS = false;
    private static final long PARTIAL_UI_UPDATE_INTERVAL_MS = 1000;
    private static final long PROGRESS_UI_UPDATE_INTERVAL_MS = 1000;
    private static final int PROGRESS_UI_UPDATE_STEP = 5;
    private static final int WHISPER_SAMPLE_RATE = 16000;
    private static final int WHISPER_CHUNK_SECONDS = 180;
    private static final int WHISPER_VAD_BATCH_SECONDS = 600;
    private static final int WEBRTC_VAD_FRAME_SAMPLES = 320;
    private static final int SILERO_VAD_FRAME_SAMPLES = 1536;
    private static final int YAMNET_VAD_FRAME_SAMPLES = 975;
    private static final int VAD_SPEECH_DURATION_MS = 50;
    private static final int VAD_SILENCE_DURATION_MS = 300;
    private static final int YAMNET_VAD_SPEECH_DURATION_MS = 30;
    private static final int YAMNET_VAD_SILENCE_DURATION_MS = 30;
    private static final int VAD_PADDING_MS = 250;
    private static final int VAD_MERGE_GAP_MS = 750;
    private static final long WHISPER_WORD_GAP_SPLIT_MS = 1200;
    private static final int WAV_HEADER_BYTES = 44;
    public static final int PROGRESS_EXTRACTING_AUDIO = -1;
    public static final int PROGRESS_PREPARING_AUDIO = -2;
    public static final int PROGRESS_DETECTING_LANGUAGE = -3;
    public static final int PROGRESS_TRANSLATING = -4;
    public static final int PROGRESS_SCANNING_SPEECH = -5;
    public static final int DEFAULT_MAX_SUBTITLE_LENGTH = 42;
    public static final int MIN_SUBTITLE_LENGTH = 24;
    public static final int MAX_SUBTITLE_LENGTH_LIMIT = 96;
    public static final boolean DEFAULT_KEEP_SENTENCES_TOGETHER = true;
    public static final String VAD_MODEL_WEBRTC = "webrtc";
    public static final String VAD_MODEL_SILERO = "silero";
    public static final String VAD_MODEL_YAMNET = "yamnet";
    public static final String VAD_AGGRESSIVENESS_NORMAL = "normal";
    public static final String VAD_AGGRESSIVENESS_AGGRESSIVE = "aggressive";
    public static final String VAD_AGGRESSIVENESS_VERY_AGGRESSIVE = "very_aggressive";
    public enum SubtitleLayerMode {
        ORIGINAL,
        TRANSLATION,
        DOUBLE
    }

    public static boolean isScanningSpeechProgress(int progress) {
        return progress == PROGRESS_SCANNING_SPEECH;
    }

    private final Context context;
    private volatile Model model;
    private volatile WhisperContext whisperContext;
    private final Object modelLock = new Object();
    private long currentLoadSessionId = 0;
    private VoskModelInfo currentModelInfo;
    private final ExecutorService executorService;
    private volatile boolean isCancelled = false;
    private File audioFile;
    private boolean wordByWordMode = false;
    private volatile int maxSubtitleLength = DEFAULT_MAX_SUBTITLE_LENGTH;
    private volatile boolean keepSentencesTogether = DEFAULT_KEEP_SENTENCES_TOGETHER;
    private volatile boolean suppressWhisperSdh = true;
    private volatile boolean whisperVadEnabled = true;
    private volatile String whisperVadModel = VAD_MODEL_WEBRTC;
    private volatile String whisperVadAggressiveness = VAD_AGGRESSIVENESS_NORMAL;
    private volatile String whisperLanguage = "auto";
    private volatile String lastTranscriptionLanguage = null;
    private volatile boolean translationEnabled = false;
    private volatile String translationSourceLanguage = "auto";
    private volatile String translationTargetLanguage = "en";

    public void setWordByWordMode(boolean enabled) {
        this.wordByWordMode = enabled;
    }

    public void setMaxSubtitleLength(int maxSubtitleLength) {
        this.maxSubtitleLength = Math.max(MIN_SUBTITLE_LENGTH,
                Math.min(MAX_SUBTITLE_LENGTH_LIMIT, maxSubtitleLength));
    }

    public void setKeepSentencesTogether(boolean keepSentencesTogether) {
        this.keepSentencesTogether = keepSentencesTogether;
    }

    public void setSuppressWhisperSdh(boolean suppressWhisperSdh) {
        this.suppressWhisperSdh = suppressWhisperSdh;
    }

    public void setWhisperVadEnabled(boolean whisperVadEnabled) {
        this.whisperVadEnabled = whisperVadEnabled;
    }

    public void setWhisperVadModel(String whisperVadModel) {
        this.whisperVadModel = normalizeVadModel(whisperVadModel);
    }

    public void setWhisperVadAggressiveness(String whisperVadAggressiveness) {
        this.whisperVadAggressiveness = normalizeVadAggressiveness(whisperVadAggressiveness);
    }

    public void setWhisperLanguage(String whisperLanguage) {
        if (whisperLanguage == null || whisperLanguage.trim().isEmpty()) {
            this.whisperLanguage = "auto";
            return;
        }
        this.whisperLanguage = whisperLanguage.trim().toLowerCase(Locale.US);
    }

    public void setTranslationSettings(boolean enabled, String sourceLanguage, String targetLanguage) {
        this.translationEnabled = enabled;
        this.translationSourceLanguage = normalizeLanguageSetting(sourceLanguage, "auto");
        this.translationTargetLanguage = normalizeLanguageSetting(targetLanguage, "en");
    }

    public String getResolvedTranslationSourceLanguage() {
        String resolved = resolveTranslationSourceLanguage();
        return resolved == null ? "" : resolved;
    }

    public String getTranslationTargetLanguage() {
        String resolved = resolveMlKitLanguage(translationTargetLanguage);
        return resolved == null ? "" : resolved;
    }

    private String normalizeLanguageSetting(String language, String fallback) {
        if (language == null || language.trim().isEmpty()) {
            return fallback;
        }
        return language.trim().toLowerCase(Locale.US);
    }

    public SubtitleGenerator(Context context) {
        this.context = context;
        this.executorService = Executors.newSingleThreadExecutor();
        LibVosk.setLogLevel(LogLevel.INFO);
        setupFontDirectories();
    }

    public interface ModelInitCallback {
        void onModelInitialized();
        void onError(String errorMessage);
    }

    public void initModel(VoskModelInfo modelInfo, ModelInitCallback callback) {
        if (modelInfo == null) {
            callback.onError("No model selected");
            return;
        }

        Log.d(TAG, "Called Model Init for " + modelInfo.getId());

        final long sessionId;
        Model previousModel;
        WhisperContext previousWhisperContext;
        synchronized (modelLock) {
            currentLoadSessionId++;
            sessionId = currentLoadSessionId;
            previousModel = this.model;
            previousWhisperContext = this.whisperContext;
            this.model = null;
            this.whisperContext = null;
        }

        releasePreviousModel(previousModel);
        releasePreviousWhisperContext(previousWhisperContext);

        currentModelInfo = modelInfo;
        lastTranscriptionLanguage = null;

        if (modelInfo.isWhisper()) {
            executorService.execute(() -> {
                try {
                    WhisperContext loadedContext;
                    if (modelInfo.isBundled()) {
                        loadedContext = WhisperContext.createContextFromAsset(
                                context.getAssets(), modelInfo.getBundledAssetName());
                    } else {
                        VoskModelManager modelManager = new VoskModelManager(context);
                        modelManager.loadCatalog();
                        File modelDirectory = modelManager.getModelDirectory(modelInfo);
                        File modelFile = new File(modelDirectory, modelInfo.getId() + ".bin");
                        if (!modelFile.exists()) {
                            synchronized (modelLock) {
                                if (sessionId == currentLoadSessionId) {
                                    callback.onError("Model is not downloaded: " + modelInfo.getLanguage());
                                }
                            }
                            return;
                        }
                        loadedContext = WhisperContext.createContextFromFile(modelFile.getAbsolutePath());
                    }
                    loadedContext.configureThreadTuning(context, modelInfo.getId());

                    synchronized (modelLock) {
                        if (sessionId != currentLoadSessionId) {
                            Log.d(TAG, "Obsolete Whisper model loaded for session " + sessionId + ", releasing it immediately.");
                            try {
                                loadedContext.release();
                            } catch (Throwable e) {
                                Log.e(TAG, "Error releasing obsolete Whisper context", e);
                            }
                            return;
                        }
                        this.whisperContext = loadedContext;
                        this.currentModelInfo = modelInfo;
                    }

                    Log.d(TAG, "Whisper model initialized: " + modelInfo.getId());
                    callback.onModelInitialized();
                } catch (Throwable e) {
                    Log.e(TAG, "Failed to load Whisper model", e);
                    synchronized (modelLock) {
                        if (sessionId == currentLoadSessionId) {
                            callback.onError(e.getMessage() == null ? "Failed to load model" : e.getMessage());
                        }
                    }
                }
            });
            return;
        }

        if (modelInfo.isBundled()) {
            StorageService.unpack(context, modelInfo.getBundledAssetName(), "model",
                    (loadedModel) -> {
                        synchronized (modelLock) {
                            if (sessionId != currentLoadSessionId) {
                                Log.d(TAG, "Obsolete bundled model loaded for session " + sessionId + ", closing it immediately.");
                                try {
                                    loadedModel.close();
                                } catch (Throwable e) {
                                    Log.e(TAG, "Error closing obsolete bundled model", e);
                                }
                                return;
                            }
                            this.model = loadedModel;
                            this.currentModelInfo = modelInfo;
                        }
                        Log.d(TAG, "Bundled model initialized: " + modelInfo.getId());
                        callback.onModelInitialized();
                    },
                    (exception) -> {
                        Log.e(TAG, "Failed to unpack the model: " + exception.getMessage());
                        synchronized (modelLock) {
                            if (sessionId == currentLoadSessionId) {
                                callback.onError(exception.getMessage());
                            }
                        }
                    });
            return;
        }

        executorService.execute(() -> {
            try {
                VoskModelManager modelManager = new VoskModelManager(context);
                modelManager.loadCatalog();
                File modelDirectory = modelManager.getModelDirectory(modelInfo);
                if (!modelDirectory.isDirectory()) {
                    synchronized (modelLock) {
                        if (sessionId == currentLoadSessionId) {
                            callback.onError("Model is not downloaded: " + modelInfo.getLanguage());
                        }
                    }
                    return;
                }

                modelManager.prepareForMobileLoad(modelInfo);
                Model loadedModel = new Model(modelDirectory.getAbsolutePath());

                synchronized (modelLock) {
                    if (sessionId != currentLoadSessionId) {
                        Log.d(TAG, "Obsolete downloaded model loaded for session " + sessionId + ", closing it immediately.");
                        try {
                            loadedModel.close();
                        } catch (Throwable e) {
                            Log.e(TAG, "Error closing obsolete downloaded model", e);
                        }
                        return;
                    }
                    this.model = loadedModel;
                    this.currentModelInfo = modelInfo;
                }

                Log.d(TAG, "Downloaded model initialized: " + modelInfo.getId());
                callback.onModelInitialized();
            } catch (Throwable e) {
                Log.e(TAG, "Failed to load downloaded model", e);
                synchronized (modelLock) {
                    if (sessionId == currentLoadSessionId) {
                        callback.onError(e.getMessage() == null ? "Failed to load model" : e.getMessage());
                    }
                }
            }
        });
    }

    public VoskModelInfo getCurrentModelInfo() {
        return currentModelInfo;
    }

    public void release() {
        cancelGeneration();
        final WhisperContext contextToRelease;
        synchronized (modelLock) {
            currentLoadSessionId++;
            contextToRelease = whisperContext;
            whisperContext = null;
            model = null;
            currentModelInfo = null;
        }
        releasePreviousWhisperContext(contextToRelease);
        executorService.shutdown();
    }

    public void cancelGeneration() {
        isCancelled = true;
        FFmpegKit.cancel();
        if (whisperContext != null) {
            try {
                whisperContext.stopTranscription();
            } catch (Throwable ignored) {}
        }
    }

    public void generateSubtitles(Uri videoUri, String permanentAudioPath, SubtitleGenerationCallback callback) {
        executorService.execute(() -> {
            try {
                if (model == null && whisperContext == null) {
                    callback.onError("Speech model is not ready");
                    return;
                }
                isCancelled = false;
                Log.d(TAG, "Starting subtitle generation process");
                callback.onProgressUpdate(PROGRESS_EXTRACTING_AUDIO);

                Log.d(TAG, "Extracting audio from video");
                audioFile = extractAudioFromVideo(videoUri);

                if (isCancelled) {
                    callback.onCancelled();
                    return;
                }

                Log.d(TAG, "Performing speech recognition");
                callback.onProgressUpdate(PROGRESS_PREPARING_AUDIO);
                List<SubtitleEntry> subtitleEntries = processAudioFile(audioFile, callback);

                if (isCancelled) {
                    callback.onCancelled();
                    return;
                }

                subtitleEntries = translateSubtitlesIfNeeded(subtitleEntries, callback);

                if (isCancelled) {
                    callback.onCancelled();
                    return;
                }

                if (permanentAudioPath != null && !permanentAudioPath.isEmpty()) {
                    try {
                        copyFile(audioFile, new File(permanentAudioPath));
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to copy audio permanently", e);
                    }
                }

                callback.onProgressUpdate(100);

                Log.d(TAG, "Subtitle generation completed");
                callback.onSubtitlesGenerated(subtitleEntries);
            } catch (Exception e) {
                Log.e(TAG, "Error generating subtitles", e);
                if (isCancelled) {
                    callback.onCancelled();
                } else {
                    callback.onError("Error generating subtitles: " + e.getMessage());
                }
            }
        });
    }

    private File extractAudioFromVideo(Uri videoUri) throws IOException {
        audioFile = new File(context.getCacheDir(), "temp_audio.wav");
        String outputPath = audioFile.getAbsolutePath();

        String inputPath = FFmpegKitConfig.getSafParameterForRead(context, videoUri);
        String command = String.format("-y -i %s -vn -acodec pcm_s16le -ar 16000 -ac 1 %s", inputPath, outputPath);
        
        Log.d(TAG, "Executing FFmpeg command: " + command);

        FFmpegSession session = FFmpegKit.execute(command);

        if (ReturnCode.isSuccess(session.getReturnCode())) {
            return audioFile;
        } else {
            String errorMessage = session.getOutput() + "\n" + session.getLogsAsString();
            Log.e(TAG, "FFmpeg error: " + errorMessage);
            throw new IOException("FFmpeg command failed with state " + session.getState() 
                + " and rc " + session.getReturnCode() + ". Error: " + errorMessage);
        }

    }

    private List<SubtitleEntry> processAudioFile(File audioFile, SubtitleGenerationCallback callback) throws IOException {
        if (currentModelInfo != null && currentModelInfo.isWhisper()) {
            return processAudioFileWithWhisper(audioFile, callback);
        }
        List<SubtitleEntry> subtitles = new ArrayList<>();
        Recognizer recognizer = null;
        
        try {
            recognizer = new Recognizer(model, 16000.0f);
            recognizer.setWords(true);
            long[] lastPartialUpdateMs = {0};
            callback.onProgressUpdate(0);
            
            try (FileInputStream fis = new FileInputStream(audioFile)) {
                if (fis.skip(44) != 44) throw new IOException("Audio file too short");

                byte[] buffer = new byte[4096];
                int bytesRead;
                long totalBytes = audioFile.length() - 44;
                long processedBytes = 0;
                int lastReportedProgress = 0;
                long[] lastProgressUpdateMs = {0};
                int[] lastDispatchedProgress = {-1};

                while ((bytesRead = fis.read(buffer)) != -1) {
                    if (isCancelled) {
                        throw new IOException("Process cancelled");
                    }

                    if (recognizer.acceptWaveForm(buffer, bytesRead)) {
                        String result = recognizer.getResult();
                        processRecognitionResult(result, subtitles);
                        if (shouldDispatchPartialUpdate(lastPartialUpdateMs)) {
                            callback.onPartialSubtitlesGenerated(new ArrayList<>(subtitles));
                        }
                    }

                    processedBytes += bytesRead;
                    int currentProgress = totalBytes > 0 ? (int) (processedBytes * 100 / totalBytes) : 100;
                    if (currentProgress > lastReportedProgress) {
                        lastReportedProgress = currentProgress;
                        if (shouldDispatchProgressUpdate(lastProgressUpdateMs, lastDispatchedProgress, currentProgress)) {
                            callback.onProgressUpdate(currentProgress);
                        }
                    }
                }

                String finalResult = recognizer.getFinalResult();
                processRecognitionResult(finalResult, subtitles);
            }
        } finally {
            if (recognizer != null) {
                recognizer.close();
            }
        }
        
        return subtitles;
    }

    private void processRecognitionResult(String result, List<SubtitleEntry> subtitles) {
        try {
            JSONObject jsonResult = new JSONObject(result);
            JSONArray wordsArray = jsonResult.optJSONArray("result");
            if (wordsArray == null || wordsArray.length() == 0) {
                return;
            }
            
            StringBuilder currentSubtitle = new StringBuilder();
            List<WordTiming> currentWords = new ArrayList<>();
            double startTime = 0;
            double endTime = 0;
            
            for (int i = 0; i < wordsArray.length(); i++) {
                JSONObject wordObj = wordsArray.getJSONObject(i);
                String word = wordObj.getString("word");
                double wordStart = wordObj.getDouble("start");
                double wordEnd = wordObj.getDouble("end");
                double confidence = wordObj.optDouble("conf", 0);
                WordTiming timing = new WordTiming(word, (long) (wordStart * 1000), (long) (wordEnd * 1000), confidence);
                
                if (wordByWordMode) {
                    List<WordTiming> singleWordList = new ArrayList<>();
                    singleWordList.add(timing);
                    if (VERBOSE_SUBTITLE_LOGS) {
                        Log.d(TAG, "Vosk subtitle entry (word): startMs=" + (long)(wordStart * 1000) + ", endMs=" + (long)(wordEnd * 1000) + ", text=\"" + word + "\"");
                    }
                    subtitles.add(new SubtitleEntry(subtitles.size() + 1,
                        formatTime((long)(wordStart * 1000)),
                        formatTime((long)(wordEnd * 1000)),
                        word,
                        singleWordList));
                } else {
                    if (currentSubtitle.length() == 0) {
                        startTime = wordStart;
                    }
                    
                    if (currentSubtitle.length() + word.length() + 1 > maxSubtitleLength) {
                        if (VERBOSE_SUBTITLE_LOGS) {
                            Log.d(TAG, "Vosk subtitle entry: startMs=" + (long)(startTime * 1000) + ", endMs=" + (long)(endTime * 1000) + ", text=\"" + currentSubtitle.toString().trim() + "\"");
                        }
                        subtitles.add(new SubtitleEntry(subtitles.size() + 1,
                            formatTime((long)(startTime * 1000)),
                            formatTime((long)(endTime * 1000)),
                            currentSubtitle.toString().trim(),
                            new ArrayList<>(currentWords)));

                        currentSubtitle = new StringBuilder(word);
                        currentWords = new ArrayList<>();
                        currentWords.add(timing);
                        startTime = wordStart;
                    } else {
                        if (currentSubtitle.length() > 0) {
                            currentSubtitle.append(" ");
                        }
                        currentSubtitle.append(word);
                        currentWords.add(timing);
                    }
                    
                    endTime = wordEnd;
                }
            }

            if (!wordByWordMode && currentSubtitle.length() > 0) {
                if (VERBOSE_SUBTITLE_LOGS) {
                    Log.d(TAG, "Vosk subtitle entry (final): startMs=" + (long)(startTime * 1000) + ", endMs=" + (long)(endTime * 1000) + ", text=\"" + currentSubtitle.toString().trim() + "\"");
                }
                subtitles.add(new SubtitleEntry(subtitles.size() + 1,
                    formatTime((long)(startTime * 1000)),
                    formatTime((long)(endTime * 1000)),
                    currentSubtitle.toString().trim(),
                    new ArrayList<>(currentWords)));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing recognition result", e);
        }
    }

    private void releasePreviousModel(Model modelToRelease) {
        if (modelToRelease == null) {
            return;
        }
        try {
            modelToRelease.close();
            Log.d(TAG, "Released previous native speech model C++ memory allocation");
        } catch (Throwable e) {
            Log.e(TAG, "Error releasing previous model memory", e);
        }
    }

    private void releasePreviousWhisperContext(WhisperContext contextToRelease) {
        if (contextToRelease == null) {
            return;
        }
        try {
            contextToRelease.stopTranscription();
            contextToRelease.releaseAsync();
            Log.d(TAG, "Queued previous Whisper model context for release");
        } catch (Throwable e) {
            Log.e(TAG, "Error queueing previous Whisper model context release", e);
        }
    }

    private boolean shouldDispatchPartialUpdate(long[] lastPartialUpdateMs) {
        long now = System.currentTimeMillis();
        if (lastPartialUpdateMs[0] == 0 || now - lastPartialUpdateMs[0] >= PARTIAL_UI_UPDATE_INTERVAL_MS) {
            lastPartialUpdateMs[0] = now;
            return true;
        }
        return false;
    }

    private boolean shouldDispatchProgressUpdate(long[] lastProgressUpdateMs,
                                                 int[] lastDispatchedProgress,
                                                 int progress) {
        if (progress < 0 || progress >= 100 || lastDispatchedProgress[0] < 0) {
            lastProgressUpdateMs[0] = System.currentTimeMillis();
            lastDispatchedProgress[0] = progress;
            return true;
        }

        long now = System.currentTimeMillis();
        if (progress - lastDispatchedProgress[0] >= PROGRESS_UI_UPDATE_STEP
                || now - lastProgressUpdateMs[0] >= PROGRESS_UI_UPDATE_INTERVAL_MS) {
            lastProgressUpdateMs[0] = now;
            lastDispatchedProgress[0] = progress;
            return true;
        }

        return false;
    }

    private List<String> splitSubtitle(String text) {
        List<String> result = new ArrayList<>();
        List<String> sentences = splitIntoSentenceChunks(text);
        StringBuilder currentLine = new StringBuilder();

        for (String sentence : sentences) {
            if (sentence.length() > maxSubtitleLength) {
                if (currentLine.length() > 0) {
                    result.add(currentLine.toString().trim());
                    currentLine = new StringBuilder();
                }
                addWrappedWords(sentence, result);
                continue;
            }

            int nextLength = currentLine.length() == 0
                    ? sentence.length()
                    : currentLine.length() + 1 + sentence.length();
            if (nextLength > maxSubtitleLength) {
                result.add(currentLine.toString().trim());
                currentLine = new StringBuilder(sentence);
            } else {
                if (currentLine.length() > 0) {
                    currentLine.append(" ");
                }
                currentLine.append(sentence);
            }
        }

        if (currentLine.length() > 0) {
            result.add(currentLine.toString().trim());
        }

        return result;
    }

    private List<String> splitIntoSentenceChunks(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            return chunks;
        }

        StringBuilder current = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            current.append(c);

            if (c == '.' || c == '!' || c == '?') {
                String chunk = current.toString().trim();
                if (!chunk.isEmpty()) {
                    chunks.add(chunk);
                }
                current = new StringBuilder();
            }
        }

        String remaining = current.toString().trim();
        if (!remaining.isEmpty()) {
            chunks.add(remaining);
        }

        if (chunks.isEmpty()) {
            chunks.add(text.trim());
        }

        return chunks;
    }

    private void addWrappedWords(String text, List<String> result) {
        String[] words = text.split("\\s+");
        StringBuilder currentLine = new StringBuilder();

        for (String word : words) {
            int nextLength = currentLine.length() == 0
                    ? word.length()
                    : currentLine.length() + 1 + word.length();
            if (nextLength > maxSubtitleLength && currentLine.length() > 0) {
                result.add(currentLine.toString().trim());
                currentLine = new StringBuilder();
            }
            if (currentLine.length() > 0) {
                currentLine.append(" ");
            }
            currentLine.append(word);
        }

        if (currentLine.length() > 0) {
            result.add(currentLine.toString().trim());
        }
    }

    public void saveSubtitlesToFile(List<SubtitleEntry> entries, String format, Uri videoUri, SubtitleSaveCallback callback) {
        saveSubtitlesToFile(entries, format, videoUri, null, callback);
    }

    public void saveSubtitlesToFile(List<SubtitleEntry> entries, String format, Uri videoUri,
                                    File outputDir, SubtitleSaveCallback callback) {
        saveSubtitlesToFile(entries, format, videoUri, outputDir, SubtitleLayerMode.ORIGINAL, callback);
    }

    public void saveSubtitlesToFile(List<SubtitleEntry> entries, String format, Uri videoUri,
                                    File outputDir, SubtitleLayerMode layerMode, SubtitleSaveCallback callback) {
        executorService.execute(() -> {
            try {
                File exportDir = resolveOutputDir(outputDir);
                String videoName = getVideoNameFromUri(videoUri);
                String extension = format.toLowerCase(Locale.US);
                String uniqueFileName = buildExportFileName(extension + "-" + subtitleLayerSlug(layerMode) + "-subtitles", videoName, extension);
                
                File subtitleFile = new File(exportDir, uniqueFileName);
                if (subtitleFile.exists()) {
                    callback.onError("Already exported subtitles for this video and model: " + subtitleFile.getName());
                    return;
                }
                FileOutputStream fos = new FileOutputStream(subtitleFile);
                List<SubtitleEntry> exportEntries = projectSubtitleEntries(entries, layerMode);

                if (format.equalsIgnoreCase("srt")) {
                    writeSrtSubtitles(exportEntries, fos);
                } else if (format.equalsIgnoreCase("vtt")) {
                    writeVttSubtitles(exportEntries, fos);
                } else {
                    throw new IllegalArgumentException("Unsupported subtitle format: " + format);
                }

                fos.close();
                callback.onSubtitlesSaved(subtitleFile.getAbsolutePath());
            } catch (IOException e) {
                callback.onError("Error saving subtitles: " + e.getMessage());
            }
        });
    }

    private String subtitleLayerSlug(SubtitleLayerMode layerMode) {
        if (layerMode == SubtitleLayerMode.TRANSLATION) return "translated";
        if (layerMode == SubtitleLayerMode.DOUBLE) return "double";
        return "original";
    }

    public static boolean hasTranslatedSubtitles(List<SubtitleEntry> entries) {
        if (entries == null) return false;
        for (SubtitleEntry entry : entries) {
            if (entry != null && entry.hasTranslation()) {
                return true;
            }
        }
        return false;
    }

    public static List<SubtitleEntry> projectSubtitleEntries(List<SubtitleEntry> entries, SubtitleLayerMode layerMode) {
        List<SubtitleEntry> projected = new ArrayList<>();
        if (entries == null) return projected;
        for (SubtitleEntry entry : entries) {
            if (entry == null) continue;
            String text = entry.getText();
            if (layerMode == SubtitleLayerMode.TRANSLATION) {
                text = entry.hasTranslation() ? entry.getTranslationText() : entry.getText();
            } else if (layerMode == SubtitleLayerMode.DOUBLE && entry.hasTranslation()) {
                text = entry.getText() + "\n" + entry.getTranslationText();
            }
            SubtitleEntry copy = new SubtitleEntry(entry.getNumber(), entry.getStartTime(), entry.getEndTime(),
                    text, new ArrayList<>(entry.getWords()));
            copy.setTranslationText(entry.getTranslationText());
            projected.add(copy);
        }
        return projected;
    }

    private void writeSrtSubtitles(List<SubtitleEntry> subtitles, FileOutputStream fos) throws IOException {
        try (Writer writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            for (SubtitleEntry entry : subtitles) {
                writer.write(String.format("%d\n%s --> %s\n%s\n\n",
                        entry.getNumber(),
                        entry.getStartTime(),
                        entry.getEndTime(),
                        entry.getText()));
            }
        }
    }

    private void writeVttSubtitles(List<SubtitleEntry> entries, FileOutputStream fos) throws IOException {
        fos.write("WEBVTT\n\n".getBytes());
        for (SubtitleEntry entry : entries) {
            String vttEntry = String.format("%s --> %s\n%s\n\n",
                    formatTimeVtt(entry.getStartTime()),
                    formatTimeVtt(entry.getEndTime()),
                    entry.getText());
            fos.write(vttEntry.getBytes());
        }
    }

    private String formatTimeVtt(String time) {
        return time.replace(',', '.');
    }

    private String formatTime(long timeMs) {
        long hours = timeMs / 3600000;
        long minutes = (timeMs % 3600000) / 60000;
        long seconds = (timeMs % 60000) / 1000;
        long milliseconds = timeMs % 1000;

        return String.format("%02d:%02d:%02d,%03d", hours, minutes, seconds, milliseconds);
    }

    public static class SubtitleEntry {
        private int number;
        private String startTime;
        private String endTime;
        private String text;
        private String translationText = "";
        private List<WordTiming> words;

        public SubtitleEntry(int number, String startTime, String endTime, String text) {
            this(number, startTime, endTime, text, new ArrayList<>());
        }

        public SubtitleEntry(int number, String startTime, String endTime, String text, List<WordTiming> words) {
            this.number = number;
            this.startTime = startTime;
            this.endTime = endTime;
            this.text = text;
            this.words = words == null ? new ArrayList<>() : words;
        }

        public int getNumber() { return number; }
        public String getStartTime() { return startTime; }
        public String getEndTime() { return endTime; }
        public String getText() { return text; }
        public String getTranslationText() { return translationText == null ? "" : translationText; }
        public List<WordTiming> getWords() { return words; }
        public boolean hasTranslation() { return translationText != null && !translationText.trim().isEmpty(); }

        public void setNumber(int number) { this.number = number; }
        public void setText(String text) { this.text = text; }
        public void setTranslationText(String translationText) {
            this.translationText = translationText == null ? "" : translationText;
        }
        public void setEndTime(String endTime) {
            this.endTime = endTime;
        }
        public void setWords(List<WordTiming> words) {
            this.words = words == null ? new ArrayList<>() : words;
        }
    }

    public interface SubtitleGenerationCallback {
        void onPartialSubtitlesGenerated(List<SubtitleEntry> partialSubtitles);
        void onSubtitlesGenerated(List<SubtitleEntry> subtitleEntries);
        void onError(String errorMessage);
        void onProgressUpdate(int progress);
        void onCancelled();
    }

    public interface SubtitleSaveCallback {
        void onSubtitlesSaved(String filePath);
        void onError(String errorMessage);
    }

    public interface TranslationCallback {
        void onTranslated(List<SubtitleEntry> subtitleEntries, String sourceLanguage, String targetLanguage);
        void onError(String errorMessage);
        void onProgressUpdate(int progress);
    }

    private void setupFontDirectories() {
        List<String> fontDirectories = new ArrayList<>();
        fontDirectories.add("/system/fonts");
        
        File customFontsDir = new File(context.getFilesDir(), "fonts");
        if (!customFontsDir.exists()) {
            customFontsDir.mkdirs();
        }
        fontDirectories.add(customFontsDir.getAbsolutePath());

        copyFontsFromAssets(customFontsDir);

        FFmpegKitConfig.setFontDirectoryList(context, fontDirectories, Collections.emptyMap());
    }

    private void copyFontsFromAssets(File customFontsDir) {
        try {
            String[] fonts = context.getAssets().list("fonts");
            if (fonts != null) {
                for (String font : fonts) {
                    File outFile = new File(customFontsDir, font);
                    if (!outFile.exists()) {
                        InputStream in = context.getAssets().open("fonts/" + font);
                        FileOutputStream out = new FileOutputStream(outFile);
                        byte[] buffer = new byte[1024];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                        }
                        in.close();
                        out.close();
                    }
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Error copying fonts from assets", e);
        }
    }

    public void exportVideoWithSubtitles(Uri videoUri, List<SubtitleEntry> subtitles, boolean burnSubtitles, String fontName, VideoExportCallback callback) {
        exportVideoWithSubtitles(videoUri, subtitles, burnSubtitles, fontName, null, callback);
    }

    public void exportVideoWithSubtitles(Uri videoUri, List<SubtitleEntry> subtitles, boolean burnSubtitles, String fontName,
                                         ShortsSubtitleStyle shortsStyle, VideoExportCallback callback) {
        exportVideoWithSubtitles(videoUri, subtitles, burnSubtitles, fontName, shortsStyle, false, callback);
    }

    public void exportVideoWithSubtitles(Uri videoUri, List<SubtitleEntry> subtitles, boolean burnSubtitles, String fontName,
                                         ShortsSubtitleStyle shortsStyle, boolean forceMp4SoftSubtitles,
                                         VideoExportCallback callback) {
        exportVideoWithSubtitles(videoUri, subtitles, burnSubtitles, fontName, shortsStyle,
                forceMp4SoftSubtitles, null, callback);
    }

    public void exportVideoWithSubtitles(Uri videoUri, List<SubtitleEntry> subtitles, boolean burnSubtitles, String fontName,
                                         ShortsSubtitleStyle shortsStyle, boolean forceMp4SoftSubtitles,
                                         File outputDir, VideoExportCallback callback) {
        exportVideoWithSubtitles(videoUri, subtitles, burnSubtitles, fontName, shortsStyle,
                forceMp4SoftSubtitles, outputDir, SubtitleLayerMode.ORIGINAL, callback);
    }

    public void exportVideoWithSubtitles(Uri videoUri, List<SubtitleEntry> subtitles, boolean burnSubtitles, String fontName,
                                         ShortsSubtitleStyle shortsStyle, boolean forceMp4SoftSubtitles,
                                         File outputDir, SubtitleLayerMode layerMode, VideoExportCallback callback) {
        executorService.execute(() -> {
            File subtitleFile = null;
            try {
                setupFontDirectories();
                File exportDir = resolveOutputDir(outputDir);

                boolean styledShorts = shortsStyle != null && (!forceMp4SoftSubtitles || burnSubtitles);
                boolean styledDouble = layerMode == SubtitleLayerMode.DOUBLE && (burnSubtitles || styledShorts);
                List<SubtitleEntry> exportEntries = projectSubtitleEntries(subtitles, layerMode);
                subtitleFile = new File(context.getCacheDir(), (styledShorts || styledDouble) ? "temp_subtitles.ass" : "temp_subtitles.srt");
                FileOutputStream fos = new FileOutputStream(subtitleFile);
                if (styledShorts) {
                    writeAssSubtitles(subtitles, fos, shortsStyle, fontName == null ? "RobotoRegular" : fontName, layerMode);
                } else if (styledDouble) {
                    ShortsSubtitleStyle defaultStyle = new ShortsSubtitleStyle(0.5f, 0.88f, 30f, false, false, false);
                    writeAssSubtitles(subtitles, fos, defaultStyle, fontName == null ? "RobotoRegular" : fontName, layerMode);
                } else {
                    writeSrtSubtitles(exportEntries, fos);
                }
                fos.close();

//                logSrtFileContents(srtFile);

                String videoName = getVideoNameFromUri(videoUri);
                String outputExtension = (styledShorts || styledDouble) && !burnSubtitles && !forceMp4SoftSubtitles ? "mkv" : "mp4";
                String uniqueFileName = buildExportFileName((burnSubtitles ? "hard-" : "soft-") + subtitleLayerSlug(layerMode) + "-subtitles",
                        videoName, outputExtension);
                Log.d(TAG,"File Name:" + uniqueFileName);
                File outputFile = new File(exportDir, uniqueFileName);
                if (outputFile.exists()) {
                    callback.onError("Already exported this video with this model: " + outputFile.getName());
                    return;
                }

                String inputPath = FFmpegKitConfig.getSafParameterForRead(context, videoUri);
                String outputPath = outputFile.getAbsolutePath();
                String subtitlePath = subtitleFile.getAbsolutePath();

                String command;
                if ((styledShorts || styledDouble) && burnSubtitles) {
                    command = String.format("-i %s -vf ass=%s -q:v 1 -c:a copy %s",
                            inputPath, subtitlePath, outputPath);
                } else if (styledShorts || styledDouble) {
                    command = String.format("-i %s -i %s -c copy -c:s ass %s",
                            inputPath, subtitlePath, outputPath);
                } else if (burnSubtitles) {
//                    command = String.format("-i %s -vf subtitles=%s:force_style='FontName=%s' -c:v mpeg4 -c:a copy %s",
//                            inputPath, subtitlePath, fontName, outputPath);
                     command = String.format("-i %s -vf subtitles=%s:force_style='FontName=%s' -q:v 1 -c:a copy %s",
                                                inputPath, subtitlePath, fontName, outputPath);
//                    command = String.format(
//                            "-i %s -vf subtitles=%s:force_style='FontName=%s' -c:v h264 -preset veryslow -c:a copy %s",
//                            inputPath, subtitlePath, fontName, outputPath
//                    );

                } else {
                    String subtitleLanguage = currentModelInfo != null ? currentModelInfo.getSubtitleLanguageCode() : "und";
                    command = String.format("-i %s -i %s -c copy -c:s mov_text -metadata:s:s:0 language=%s %s",
                            inputPath, subtitlePath, subtitleLanguage, outputPath);
                }

                Log.d(TAG, "Executing FFmpeg command: " + command);

                callback.onProgressUpdate(-1);
                FFmpegSession session = FFmpegKit.execute(command);

                if (ReturnCode.isSuccess(session.getReturnCode())) {
                    callback.onVideoExported(outputPath);
                } else {
                    String errorMessage = session.getOutput() + "\n" + session.getLogsAsString();
                    Log.e(TAG, "FFmpeg error: " + errorMessage);
                    callback.onError("FFmpeg command failed: " + errorMessage);
                }

            } catch (IOException e) {
                Log.e(TAG, "Error exporting video with subtitles", e);
                callback.onError("Error exporting video: " + e.getMessage());
            } finally {
                if (subtitleFile != null && subtitleFile.exists()) {
                    subtitleFile.delete();
                }
            }
        });
    }

    private File resolveOutputDir(File outputDir) throws IOException {
        File exportDir = outputDir == null
                ? new File(ApplicationPath.applicationPath(context))
                : outputDir;
        if (!exportDir.isDirectory() && !exportDir.mkdirs()) {
            throw new IOException("Could not create export folder");
        }
        return exportDir;
    }

    private void writeAssSubtitles(List<SubtitleEntry> subtitles, FileOutputStream fos,
                                   ShortsSubtitleStyle style, String fontName, SubtitleLayerMode layerMode) throws IOException {
        try (Writer writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            int playResX = style.isVerticalVideo() ? 1080 : 1920;
            int playResY = style.isVerticalVideo() ? 1920 : 1080;
            int x = Math.round(style.getX() * playResX);
            int y = Math.round(style.getY() * playResY);
            int fontSize = Math.max(16, Math.round(style.getTextSizeSp() * 2.4f));

            writer.write("[Script Info]\n");
            writer.write("ScriptType: v4.00+\n");
            writer.write("WrapStyle: 2\n");
            writer.write("ScaledBorderAndShadow: yes\n");
            writer.write("PlayResX: " + playResX + "\n");
            writer.write("PlayResY: " + playResY + "\n\n");
            writer.write("[V4+ Styles]\n");
            writer.write("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n");
            writer.write(String.format(Locale.US,
                    "Style: Default,%s,%d,&H00FFFFFF,&H00FFFFFF,&H00000000,&H80000000,-1,0,0,0,100,100,0,0,1,4,0,5,0,0,0,1\n",
                    fontName, fontSize));
            writer.write(String.format(Locale.US,
                    "Style: Translation,%s,%d,&H0000FFFF,&H0000FFFF,&H00000000,&H80000000,-1,0,0,0,100,100,0,0,1,4,0,5,0,0,0,1\n\n",
                    fontName, Math.max(14, Math.round(fontSize * 0.86f))));
            writer.write("[Events]\n");
            writer.write("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n");

            for (SubtitleEntry entry : subtitles) {
                if (style.isWordByWord() && layerMode != SubtitleLayerMode.DOUBLE
                        && entry.getWords() != null && !entry.getWords().isEmpty()) {
                    for (WordTiming word : entry.getWords()) {
                        writeAssDialogue(writer, word.getStartMs(), word.getEndMs(), word.getWord(), style, x, y);
                    }
                } else if (layerMode == SubtitleLayerMode.DOUBLE && entry.hasTranslation()) {
                    long startMs = parseSubtitleTime(entry.getStartTime());
                    long endMs = parseSubtitleTime(entry.getEndTime());
                    writeAssDialogue(writer, startMs, endMs, entry.getText(), "Default", style, x, y - Math.max(28, fontSize));
                    writeAssDialogue(writer, startMs, endMs, entry.getTranslationText(), "Translation", style, x, y + Math.max(28, Math.round(fontSize * 0.35f)));
                } else {
                    String text = layerMode == SubtitleLayerMode.TRANSLATION && entry.hasTranslation()
                            ? entry.getTranslationText()
                            : entry.getText();
                    writeAssDialogue(writer, parseSubtitleTime(entry.getStartTime()), parseSubtitleTime(entry.getEndTime()),
                            text, style, x, y);
                }
            }
        }
    }

    private void writeAssDialogue(Writer writer, long startMs, long endMs, String text,
                                  ShortsSubtitleStyle style, int x, int y) throws IOException {
        writeAssDialogue(writer, startMs, endMs, text, "Default", style, x, y);
    }

    private void writeAssDialogue(Writer writer, long startMs, long endMs, String text, String assStyle,
                                  ShortsSubtitleStyle style, int x, int y) throws IOException {
        if (endMs <= startMs || text == null || text.trim().isEmpty()) {
            return;
        }
        String displayText = style.isUppercase() ? text.toUpperCase(Locale.getDefault()) : text;
        writer.write(String.format(Locale.US, "Dialogue: 0,%s,%s,%s,,0,0,0,,{\\pos(%d,%d)}%s\n",
                formatAssTime(startMs), formatAssTime(endMs), assStyle, x, y, escapeAssText(displayText)));
    }

    private long parseSubtitleTime(String timeString) {
        String[] parts = timeString.split("[:,]");
        return Long.parseLong(parts[0]) * 3600000L +
                Long.parseLong(parts[1]) * 60000L +
                Long.parseLong(parts[2]) * 1000L +
                Long.parseLong(parts[3]);
    }

    private String formatAssTime(long timeMs) {
        long hours = timeMs / 3600000;
        long minutes = (timeMs % 3600000) / 60000;
        long seconds = (timeMs % 60000) / 1000;
        long centiseconds = (timeMs % 1000) / 10;
        return String.format(Locale.US, "%d:%02d:%02d.%02d", hours, minutes, seconds, centiseconds);
    }

    private String escapeAssText(String text) {
        return text.replace("\\", "\\\\")
                .replace("{", "\\{")
                .replace("}", "\\}")
                .replace("\n", "\\N");
    }

    public static class ShortsSubtitleStyle {
        private final float x;
        private final float y;
        private final float textSizeSp;
        private final boolean uppercase;
        private final boolean wordByWord;
        private final boolean verticalVideo;

        public ShortsSubtitleStyle(float x, float y, float textSizeSp, boolean uppercase) {
            this(x, y, textSizeSp, uppercase, true, true);
        }

        public ShortsSubtitleStyle(float x, float y, float textSizeSp, boolean uppercase, boolean wordByWord) {
            this(x, y, textSizeSp, uppercase, wordByWord, true);
        }

        public ShortsSubtitleStyle(float x, float y, float textSizeSp, boolean uppercase, boolean wordByWord, boolean verticalVideo) {
            this.x = clamp01(x);
            this.y = clamp01(y);
            this.textSizeSp = textSizeSp;
            this.uppercase = uppercase;
            this.wordByWord = wordByWord;
            this.verticalVideo = verticalVideo;
        }

        public float getX() {
            return x;
        }

        public float getY() {
            return y;
        }

        public float getTextSizeSp() {
            return textSizeSp;
        }

        public boolean isUppercase() {
            return uppercase;
        }

        public boolean isWordByWord() {
            return wordByWord;
        }

        public boolean isVerticalVideo() {
            return verticalVideo;
        }

        private static float clamp01(float value) {
            if (Float.isNaN(value)) return 0.5f;
            return Math.max(0f, Math.min(1f, value));
        }
    }

    public interface VideoExportCallback {
        void onVideoExported(String filePath);
        void onError(String errorMessage);
        void onProgressUpdate(int progress);
    }

    private void logSrtFileContents(File srtFile) {
        Log.d(TAG, "SRT file contents:");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(srtFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Log.d(TAG, line);
            }
        } catch (IOException e) {
            Log.e(TAG, "Error reading SRT file", e);
        }
    }

    private String getVideoNameFromUri(Uri videoUri) {
        String fileName = "video";
        try {
            String[] projection = {android.provider.MediaStore.MediaColumns.DISPLAY_NAME};
            try (android.database.Cursor cursor = context.getContentResolver().query(videoUri, projection, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    String fullName = cursor.getString(0);
                    fileName = fullName.replaceFirst("[.][^.]+$", "");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting video name", e);
        }
        return fileName;
    }

//    private String getVideoNameFromUri(Uri videoUri) {
//        String fileName = "video";
//
//        if (videoUri != null) {
//            String path = videoUri.getPath();
//            if (path != null) {
//                int lastSlashIndex = path.lastIndexOf('/');
//                if (lastSlashIndex != -1 && lastSlashIndex < path.length() - 1) {
//                    fileName = path.substring(lastSlashIndex + 1).replaceFirst("[.][^.]+$", "");
//                }
//            }
//        }
//
//        return fileName;
//    }


    private String buildExportFileName(String exportKind, String videoName, String extension) {
        return "(" + shortExportKind(exportKind) + "-" + slug(modelSlug()) + ")_"
                + slug(videoName) + "." + extension;
    }

    private String shortExportKind(String exportKind) {
        String slug = slug(exportKind);
        if ("hard-subtitles".equals(slug)) return "hard";
        if ("soft-subtitles".equals(slug)) return "soft";
        if ("srt-subtitles".equals(slug)) return "srt";
        if ("vtt-subtitles".equals(slug)) return "vtt";
        return slug;
    }

    private String modelSlug() {
        if (currentModelInfo == null) {
            return "model";
        }
        String locale = currentModelInfo.getLocale();
        if (locale != null && !locale.trim().isEmpty()) {
            return locale;
        }
        String id = currentModelInfo.getId();
        if (id == null || id.trim().isEmpty()) {
            return "model";
        }
        return id;
    }

    private String slug(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.US);
        normalized = normalized.replaceAll("[^a-z0-9]+", "-");
        normalized = normalized.replaceAll("(^-+|-+$)", "");
        return normalized.isEmpty() ? "export" : normalized;
    }

    private void copyFile(File src, File dst) throws IOException {
        try (FileInputStream inStream = new FileInputStream(src);
             FileOutputStream outStream = new FileOutputStream(dst)) {
            byte[] buffer = new byte[1024 * 4];
            int length;
            while ((length = inStream.read(buffer)) > 0) {
                outStream.write(buffer, 0, length);
            }
        }
    }

    private List<SubtitleEntry> translateSubtitlesIfNeeded(List<SubtitleEntry> subtitles,
                                                           SubtitleGenerationCallback callback) throws IOException {
        if (!translationEnabled || subtitles == null || subtitles.isEmpty()) {
            return subtitles;
        }

        translateSubtitlesBlocking(subtitles, callback::onProgressUpdate);
        return subtitles;
    }

    public void translateExistingSubtitles(List<SubtitleEntry> subtitles, TranslationCallback callback) {
        executorService.execute(() -> {
            try {
                translateSubtitlesBlocking(subtitles, callback::onProgressUpdate);
                callback.onTranslated(subtitles, getResolvedTranslationSourceLanguage(), getTranslationTargetLanguage());
            } catch (IOException e) {
                callback.onError(e.getMessage());
            }
        });
    }

    private interface TranslationProgressCallback {
        void onProgressUpdate(int progress);
    }

    private void translateSubtitlesBlocking(List<SubtitleEntry> subtitles,
                                            TranslationProgressCallback callback) throws IOException {
        String sourceLanguage = resolveTranslationSourceLanguage();
        String targetLanguage = resolveMlKitLanguage(translationTargetLanguage);
        if (sourceLanguage == null) {
            throw new IOException("Choose a subtitle translation source language. Auto source cannot be used with this model.");
        }
        if (targetLanguage == null) {
            throw new IOException("Unsupported subtitle translation target language: " + translationTargetLanguage);
        }
        if (sourceLanguage.equals(targetLanguage)) {
            for (SubtitleEntry entry : subtitles) {
                entry.setTranslationText(entry.getText());
            }
            return;
        }

        callback.onProgressUpdate(PROGRESS_TRANSLATING);

        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(sourceLanguage)
                .setTargetLanguage(targetLanguage)
                .build();
        Translator translator = Translation.getClient(options);
        try {
            DownloadConditions conditions = new DownloadConditions.Builder().build();
            Tasks.await(translator.downloadModelIfNeeded(conditions));
            int index = 0;
            while (index < subtitles.size()) {
                List<SubtitleEntry> sentenceEntries = new ArrayList<>();
                StringBuilder sentenceText = new StringBuilder();

                while (index < subtitles.size()) {
                    if (isCancelled) {
                        break;
                    }
                    SubtitleEntry entry = subtitles.get(index++);
                    String text = entry.getText();
                    if (text == null || text.trim().isEmpty()) {
                        continue;
                    }
                    if (sentenceText.length() > 0) {
                        sentenceText.append(" ");
                    }
                    sentenceText.append(text.trim());
                    sentenceEntries.add(entry);

                    if (endsTranslationSentence(text) || sentenceText.length() >= 1200) {
                        break;
                    }
                }

                if (isCancelled || sentenceEntries.isEmpty()) {
                    continue;
                }

                String translatedText = Tasks.await(translator.translate(sentenceText.toString()));
                applyTranslatedSentence(sentenceEntries, translatedText);
            }
        } catch (Exception e) {
            throw new IOException("Subtitle translation failed: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), e);
        } finally {
            translator.close();
        }
    }

    private boolean endsTranslationSentence(String text) {
        String trimmed = text == null ? "" : text.trim();
        for (int i = trimmed.length() - 1; i >= 0; i--) {
            char c = trimmed.charAt(i);
            if (c == '"' || c == '\'' || c == ')' || c == ']' || c == '}') {
                continue;
            }
            return c == '.' || c == '!' || c == '?';
        }
        return false;
    }

    private void applyTranslatedSentence(List<SubtitleEntry> entries, String translatedText) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        String translated = translatedText == null ? "" : translatedText.trim();
        if (entries.size() == 1) {
            SubtitleEntry entry = entries.get(0);
            entry.setTranslationText(translated);
            return;
        }

        List<String> chunks = splitTranslatedSentence(translated, entries);
        for (int i = 0; i < entries.size(); i++) {
            String chunk = i < chunks.size() ? chunks.get(i) : "";
            SubtitleEntry entry = entries.get(i);
            entry.setTranslationText(chunk);
        }
    }

    private List<String> splitTranslatedSentence(String translatedText, List<SubtitleEntry> entries) {
        List<String> chunks = new ArrayList<>();
        String translated = translatedText == null ? "" : translatedText.trim();
        if (translated.isEmpty()) {
            for (int i = 0; i < entries.size(); i++) {
                chunks.add("");
            }
            return chunks;
        }

        String[] words = translated.split("\\s+");
        int totalOriginalChars = 0;
        for (SubtitleEntry entry : entries) {
            totalOriginalChars += subtitleTextLength(entry);
        }
        if (totalOriginalChars <= 0) {
            totalOriginalChars = entries.size();
        }

        int wordIndex = 0;
        int cumulativeOriginalChars = 0;
        for (int i = 0; i < entries.size(); i++) {
            int remainingSlots = entries.size() - i - 1;
            if (i == entries.size() - 1 || wordIndex >= words.length) {
                chunks.add(joinWords(words, wordIndex, words.length));
                wordIndex = words.length;
                continue;
            }

            cumulativeOriginalChars += Math.max(1, subtitleTextLength(entries.get(i)));
            int idealWordEnd = Math.round(words.length * (cumulativeOriginalChars / (float) totalOriginalChars));
            int maxWordEnd = Math.max(wordIndex, words.length - remainingSlots);
            int wordEnd = Math.max(wordIndex + 1, Math.min(idealWordEnd, maxWordEnd));
            chunks.add(joinWords(words, wordIndex, wordEnd));
            wordIndex = wordEnd;
        }

        return chunks;
    }

    private int subtitleTextLength(SubtitleEntry entry) {
        String text = entry == null ? null : entry.getText();
        return text == null ? 0 : text.trim().length();
    }

    private String joinWords(String[] words, int start, int end) {
        if (words == null || start >= end || start >= words.length) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        int safeEnd = Math.min(end, words.length);
        for (int i = Math.max(0, start); i < safeEnd; i++) {
            if (builder.length() > 0) {
                builder.append(" ");
            }
            builder.append(words[i]);
        }
        return builder.toString();
    }

    private String resolveTranslationSourceLanguage() {
        if (translationSourceLanguage != null && !"auto".equals(translationSourceLanguage)) {
            return resolveMlKitLanguage(translationSourceLanguage);
        }

        String transcriptionSource = resolveMlKitLanguage(lastTranscriptionLanguage);
        if (transcriptionSource != null) {
            return transcriptionSource;
        }

        if (currentModelInfo != null && currentModelInfo.isWhisper()
                && whisperLanguage != null && !"auto".equals(whisperLanguage)) {
            String whisperSource = resolveMlKitLanguage(whisperLanguage);
            if (whisperSource != null) {
                return whisperSource;
            }
        }

        if (currentModelInfo != null && currentModelInfo.getLocale() != null) {
            String locale = currentModelInfo.getLocale().trim().toLowerCase(Locale.US);
            if (!locale.isEmpty() && !"multilingual".equals(locale)) {
                String[] parts = locale.split("[-_]");
                if (parts.length > 0) {
                    String modelSource = resolveMlKitLanguage(parts[0]);
                    if (modelSource != null) {
                        return modelSource;
                    }
                }
            }
        }

        return null;
    }

    private String resolveMlKitLanguage(String language) {
        if (language == null || language.trim().isEmpty() || "auto".equalsIgnoreCase(language)) {
            return null;
        }
        String normalized = language.trim().toLowerCase(Locale.US);
        if (normalized.equals("cn")) {
            normalized = "zh";
        }
        String[] parts = normalized.split("[-_]");
        String languageCode = parts.length > 0 ? parts[0] : normalized;
        String mlKitLanguage = TranslateLanguage.fromLanguageTag(languageCode);
        if (mlKitLanguage != null) {
            return mlKitLanguage;
        }
        return TranslateLanguage.fromLanguageTag(normalized);
    }

    private void replaceWordTimingsWithTranslatedSpan(SubtitleEntry entry, String translatedText) {
        List<WordTiming> words = entry.getWords();
        if (words == null || words.isEmpty() || translatedText == null || translatedText.trim().isEmpty()) {
            return;
        }
        long startMs = words.get(0).getStartMs();
        long endMs = words.get(words.size() - 1).getEndMs();
        if (endMs <= startMs) {
            return;
        }
        List<WordTiming> translatedWords = new ArrayList<>();
        translatedWords.add(new WordTiming(translatedText, startMs, endMs, 0.0));
        entry.setWords(translatedWords);
    }

    private List<SubtitleEntry> processAudioFileWithWhisper(File audioFile, SubtitleGenerationCallback callback) throws IOException {
        List<SubtitleEntry> subtitles = new ArrayList<>();
        WhisperContext activeWhisperContext = whisperContext;
        if (activeWhisperContext == null) {
            throw new IOException("Whisper context not initialized");
        }

        if (audioFile.length() <= WAV_HEADER_BYTES) {
            return subtitles;
        }
        callback.onProgressUpdate(0);

        String language = whisperLanguage;
        if ((language == null || language.isEmpty() || language.equals("auto"))
                && currentModelInfo != null && currentModelInfo.getLocale() != null) {
            String locale = currentModelInfo.getLocale().toLowerCase(Locale.US);
            if (!locale.equals("multilingual") && !locale.isEmpty()) {
                String[] parts = locale.split("-");
                if (parts.length > 0) {
                    language = parts[0];
                }
            }
        }
        if (language == null || language.isEmpty()) {
            language = "auto";
        }
        lastTranscriptionLanguage = "auto".equals(language) ? null : language;
        if ("auto".equals(language)) {
            callback.onProgressUpdate(PROGRESS_DETECTING_LANGUAGE);
        }

        long[] lastPartialUpdateMs = {0};
        long[] lastProgressUpdateMs = {0};
        int[] lastDispatchedProgress = {-1};
        int nativeMaxSegmentLength = keepSentencesTogether ? 0 : maxSubtitleLength;
        long totalSamples = Math.max(0, (audioFile.length() - WAV_HEADER_BYTES) / 2);
        int maxChunkSamples = WHISPER_SAMPLE_RATE * WHISPER_CHUNK_SECONDS;
        int maxVadBatchSamples = WHISPER_SAMPLE_RATE * WHISPER_VAD_BATCH_SECONDS;
        String activeLanguage = language;
        long whisperPipelineStartMs = System.currentTimeMillis();
        boolean useVad = whisperVadEnabled;
        if (!useVad) {
            long processedSamples = 0;
            long whisperWallMs = 0;

            try (FileInputStream inputStream = new FileInputStream(audioFile)) {
                skipFully(inputStream, WAV_HEADER_BYTES);

                while (!isCancelled && processedSamples < totalSamples) {
                    int samplesToRead = (int) Math.min(maxChunkSamples, totalSamples - processedSamples);
                    float[] audioData = readWavFloatChunk(inputStream, samplesToRead);
                    if (audioData.length == 0) {
                        break;
                    }
                    long chunkOffsetMs = processedSamples * 1000L / WHISPER_SAMPLE_RATE;
                    int chunkStartProgress = totalSamples > 0
                            ? (int) Math.min(100, processedSamples * 100 / totalSamples)
                            : 0;
                    int chunkProgressSpan = totalSamples > 0
                            ? Math.max(1, (int) Math.min(100, audioData.length * 100L / totalSamples))
                            : 100;

                    long chunkTranscribeStartMs = System.currentTimeMillis();
                    transcribeWhisperChunk(activeWhisperContext, audioData, activeLanguage, chunkOffsetMs,
                            nativeMaxSegmentLength, lastPartialUpdateMs, lastProgressUpdateMs,
                            lastDispatchedProgress, chunkStartProgress, chunkProgressSpan, subtitles, callback);
                    whisperWallMs += System.currentTimeMillis() - chunkTranscribeStartMs;

                    String detectedLanguage = activeWhisperContext.getDetectedLanguage();
                    String resolvedDetectedLanguage = resolveMlKitLanguage(detectedLanguage);
                    if (resolvedDetectedLanguage != null) {
                        lastTranscriptionLanguage = resolvedDetectedLanguage;
                        if ("auto".equals(activeLanguage)) {
                            activeLanguage = resolvedDetectedLanguage;
                        }
                        Log.d(TAG, "Whisper transcription language for translation: " + resolvedDetectedLanguage);
                    }

                    processedSamples += audioData.length;
                }
            }

            long totalWallMs = System.currentTimeMillis() - whisperPipelineStartMs;
            Log.i(TAG, "Whisper VAD disabled: whisperInput="
                    + formatDurationForLog(samplesToMs(processedSamples))
                    + ", skipped=0:00.000 (0.0%)"
                    + ", whisperWall=" + whisperWallMs + "ms"
                    + ", totalWhisperPipelineWall=" + totalWallMs + "ms");
            return subtitles;
        }

        long vadStartMs = System.currentTimeMillis();
        String activeVadModel = normalizeVadModel(whisperVadModel);
        String activeVadAggressiveness = normalizeVadAggressiveness(whisperVadAggressiveness);
        callback.onProgressUpdate(PROGRESS_SCANNING_SPEECH);
        List<SpeechWindow> speechWindows = detectSpeechWindows(audioFile, totalSamples,
                activeVadModel, activeVadAggressiveness);
        List<WhisperVadBatch> vadBatches = buildWhisperVadBatches(audioFile, speechWindows, maxVadBatchSamples);
        long vadWallMs = System.currentTimeMillis() - vadStartMs;
        long speechSamples = sumSpeechWindowSamples(speechWindows);
        long skippedSamples = Math.max(0, totalSamples - speechSamples);
        double skippedPercent = totalSamples > 0 ? skippedSamples * 100.0 / totalSamples : 0.0;
        Log.i(TAG, "Whisper VAD scan: model=" + activeVadModel
                + ", aggressiveness=" + activeVadAggressiveness
                + ", windows=" + speechWindows.size()
                + ", batches=" + vadBatches.size()
                + ", totalAudio=" + formatDurationForLog(samplesToMs(totalSamples))
                + ", whisperInput=" + formatDurationForLog(samplesToMs(speechSamples))
                + ", skipped=" + formatDurationForLog(samplesToMs(skippedSamples))
                + " (" + String.format(Locale.US, "%.1f", skippedPercent) + "%)"
                + ", vadWall=" + vadWallMs + "ms");

        long whisperInputSamples = 0;
        long whisperWallMs = 0;
        for (WhisperVadBatch vadBatch : vadBatches) {
            if (isCancelled || vadBatch.audioData.length == 0) {
                break;
            }
            int chunkStartProgress = totalSamples > 0
                    ? (int) Math.min(100, vadBatch.firstOriginalSample * 100 / totalSamples)
                    : 0;
            int chunkProgressSpan = totalSamples > 0
                    ? Math.max(1, (int) Math.min(100, vadBatch.audioData.length * 100L / totalSamples))
                    : 100;

            long chunkTranscribeStartMs = System.currentTimeMillis();
            transcribeWhisperChunk(activeWhisperContext, vadBatch.audioData, activeLanguage,
                    vadBatch.timeMapper, nativeMaxSegmentLength, lastPartialUpdateMs, lastProgressUpdateMs,
                    lastDispatchedProgress, chunkStartProgress, chunkProgressSpan, subtitles, callback);
            whisperWallMs += System.currentTimeMillis() - chunkTranscribeStartMs;
            whisperInputSamples += vadBatch.audioData.length;

            String detectedLanguage = activeWhisperContext.getDetectedLanguage();
            String resolvedDetectedLanguage = resolveMlKitLanguage(detectedLanguage);
            if (resolvedDetectedLanguage != null) {
                lastTranscriptionLanguage = resolvedDetectedLanguage;
                if ("auto".equals(activeLanguage)) {
                    activeLanguage = resolvedDetectedLanguage;
                }
                Log.d(TAG, "Whisper transcription language for translation: " + resolvedDetectedLanguage);
            }
        }

        long totalWallMs = System.currentTimeMillis() - whisperPipelineStartMs;
        Log.i(TAG, "Whisper VAD result: model=" + activeVadModel
                + ", aggressiveness=" + activeVadAggressiveness
                + ", whisperInput="
                + formatDurationForLog(samplesToMs(whisperInputSamples))
                + ", skipped=" + formatDurationForLog(samplesToMs(Math.max(0, totalSamples - whisperInputSamples)))
                + " (" + String.format(Locale.US, "%.1f", totalSamples > 0
                ? Math.max(0, totalSamples - whisperInputSamples) * 100.0 / totalSamples
                : 0.0) + "%)"
                + ", vadWall=" + vadWallMs + "ms"
                + ", whisperWall=" + whisperWallMs + "ms"
                + ", totalVadPipelineWall=" + totalWallMs + "ms");
        return subtitles;
    }

    private long sumSpeechWindowSamples(List<SpeechWindow> speechWindows) {
        long total = 0;
        for (SpeechWindow speechWindow : speechWindows) {
            total += Math.max(0, speechWindow.endSample - speechWindow.startSample);
        }
        return total;
    }

    private List<WhisperVadBatch> buildWhisperVadBatches(File audioFile,
                                                         List<SpeechWindow> speechWindows,
                                                         int maxBatchSamples) throws IOException {
        List<WhisperVadBatch> batches = new ArrayList<>();
        WhisperVadBatchBuilder builder = new WhisperVadBatchBuilder(maxBatchSamples);

        for (SpeechWindow speechWindow : speechWindows) {
            long windowOffsetSamples = speechWindow.startSample;
            while (!isCancelled && windowOffsetSamples < speechWindow.endSample) {
                int samplesToRead = (int) Math.min(maxBatchSamples,
                        speechWindow.endSample - windowOffsetSamples);
                if (builder.hasAudio() && builder.sampleCount() + samplesToRead > maxBatchSamples) {
                    batches.add(builder.build());
                    builder = new WhisperVadBatchBuilder(maxBatchSamples);
                }

                float[] audioData = readWavFloatRange(audioFile, windowOffsetSamples, samplesToRead);
                if (audioData.length == 0) {
                    break;
                }
                builder.append(audioData, windowOffsetSamples);
                windowOffsetSamples += audioData.length;

                if (builder.sampleCount() >= maxBatchSamples) {
                    batches.add(builder.build());
                    builder = new WhisperVadBatchBuilder(maxBatchSamples);
                }
            }
        }

        if (builder.hasAudio()) {
            batches.add(builder.build());
        }
        return batches;
    }

    private long samplesToMs(long samples) {
        return samples * 1000L / WHISPER_SAMPLE_RATE;
    }

    private String formatDurationForLog(long durationMs) {
        long minutes = durationMs / 60000L;
        long seconds = (durationMs % 60000L) / 1000L;
        long milliseconds = durationMs % 1000L;
        return String.format(Locale.US, "%d:%02d.%03d", minutes, seconds, milliseconds);
    }

    private List<SpeechWindow> detectSpeechWindows(File audioFile,
                                                   long totalSamples,
                                                   String vadModel,
                                                   String vadAggressiveness) throws IOException {
        if (VAD_MODEL_SILERO.equals(vadModel)) {
            return detectSpeechWindowsWithSilero(audioFile, totalSamples, vadAggressiveness);
        }
        if (VAD_MODEL_YAMNET.equals(vadModel)) {
            return detectSpeechWindowsWithYamnet(audioFile, totalSamples, vadAggressiveness);
        }
        return detectSpeechWindowsWithWebRtc(audioFile, totalSamples, vadAggressiveness);
    }

    private String normalizeVadModel(String vadModel) {
        if (VAD_MODEL_SILERO.equalsIgnoreCase(vadModel)) {
            return VAD_MODEL_SILERO;
        }
        if (VAD_MODEL_YAMNET.equalsIgnoreCase(vadModel)) {
            return VAD_MODEL_YAMNET;
        }
        return VAD_MODEL_WEBRTC;
    }

    private String normalizeVadAggressiveness(String aggressiveness) {
        if (VAD_AGGRESSIVENESS_NORMAL.equalsIgnoreCase(aggressiveness)) {
            return VAD_AGGRESSIVENESS_NORMAL;
        }
        if (VAD_AGGRESSIVENESS_AGGRESSIVE.equalsIgnoreCase(aggressiveness)) {
            return VAD_AGGRESSIVENESS_AGGRESSIVE;
        }
        return VAD_AGGRESSIVENESS_VERY_AGGRESSIVE;
    }

    private Mode toWebRtcVadMode(String aggressiveness) {
        if (VAD_AGGRESSIVENESS_NORMAL.equals(aggressiveness)) {
            return Mode.NORMAL;
        }
        if (VAD_AGGRESSIVENESS_AGGRESSIVE.equals(aggressiveness)) {
            return Mode.AGGRESSIVE;
        }
        return Mode.VERY_AGGRESSIVE;
    }

    private com.konovalov.vad.silero.config.Mode toSileroVadMode(String aggressiveness) {
        if (VAD_AGGRESSIVENESS_NORMAL.equals(aggressiveness)) {
            return com.konovalov.vad.silero.config.Mode.NORMAL;
        }
        if (VAD_AGGRESSIVENESS_AGGRESSIVE.equals(aggressiveness)) {
            return com.konovalov.vad.silero.config.Mode.AGGRESSIVE;
        }
        return com.konovalov.vad.silero.config.Mode.VERY_AGGRESSIVE;
    }

    private com.konovalov.vad.yamnet.config.Mode toYamnetVadMode(String aggressiveness) {
        if (VAD_AGGRESSIVENESS_NORMAL.equals(aggressiveness)) {
            return com.konovalov.vad.yamnet.config.Mode.NORMAL;
        }
        if (VAD_AGGRESSIVENESS_AGGRESSIVE.equals(aggressiveness)) {
            return com.konovalov.vad.yamnet.config.Mode.AGGRESSIVE;
        }
        return com.konovalov.vad.yamnet.config.Mode.VERY_AGGRESSIVE;
    }

    private List<SpeechWindow> detectSpeechWindowsWithWebRtc(File audioFile,
                                                             long totalSamples,
                                                             String vadAggressiveness) throws IOException {
        List<SpeechWindow> windows = new ArrayList<>();
        if (totalSamples <= 0) {
            return windows;
        }

        VadWebRTC vad = Vad.builder()
                .setSampleRate(SampleRate.SAMPLE_RATE_16K)
                .setFrameSize(FrameSize.FRAME_SIZE_320)
                .setMode(toWebRtcVadMode(vadAggressiveness))
                .setSpeechDurationMs(VAD_SPEECH_DURATION_MS)
                .setSilenceDurationMs(VAD_SILENCE_DURATION_MS)
                .build();

        long paddingSamples = msToSamples(VAD_PADDING_MS);
        long currentSpeechStartSample = -1;
        long currentSpeechEndSample = -1;
        long frameStartSample = 0;
        int frameBytes = WEBRTC_VAD_FRAME_SAMPLES * 2;

        try (FileInputStream inputStream = new FileInputStream(audioFile)) {
            skipFully(inputStream, WAV_HEADER_BYTES);
            byte[] frame = new byte[frameBytes];

            while (!isCancelled && frameStartSample < totalSamples) {
                int bytesRead = readFrame(inputStream, frame);
                if (bytesRead <= 0) {
                    break;
                }
                if (bytesRead < frameBytes) {
                    for (int i = bytesRead; i < frameBytes; i++) {
                        frame[i] = 0;
                    }
                }

                long actualFrameSamples = Math.min(WEBRTC_VAD_FRAME_SAMPLES, totalSamples - frameStartSample);
                boolean isSpeech = vad.isSpeech(frame);
                if (isSpeech) {
                    if (currentSpeechStartSample < 0) {
                        currentSpeechStartSample = Math.max(0, frameStartSample - paddingSamples);
                    }
                    currentSpeechEndSample = Math.min(totalSamples,
                            frameStartSample + actualFrameSamples + paddingSamples);
                } else if (currentSpeechStartSample >= 0) {
                    appendSpeechWindow(windows, currentSpeechStartSample, currentSpeechEndSample, totalSamples);
                    currentSpeechStartSample = -1;
                    currentSpeechEndSample = -1;
                }

                frameStartSample += actualFrameSamples;
            }
        } finally {
            try {
                vad.close();
            } catch (Throwable e) {
                Log.w(TAG, "Error closing WebRTC VAD", e);
            }
        }

        if (currentSpeechStartSample >= 0) {
            appendSpeechWindow(windows, currentSpeechStartSample, currentSpeechEndSample, totalSamples);
        }
        return windows;
    }

    private List<SpeechWindow> detectSpeechWindowsWithSilero(File audioFile,
                                                             long totalSamples,
                                                             String vadAggressiveness) throws IOException {
        List<SpeechWindow> windows = new ArrayList<>();
        if (totalSamples <= 0) {
            return windows;
        }

        com.konovalov.vad.silero.VadSilero vad = com.konovalov.vad.silero.Vad.builder()
                .setContext(context)
                .setSampleRate(com.konovalov.vad.silero.config.SampleRate.SAMPLE_RATE_16K)
                .setFrameSize(com.konovalov.vad.silero.config.FrameSize.FRAME_SIZE_1536)
                .setMode(toSileroVadMode(vadAggressiveness))
                .setSpeechDurationMs(VAD_SPEECH_DURATION_MS)
                .setSilenceDurationMs(VAD_SILENCE_DURATION_MS)
                .build();

        long paddingSamples = msToSamples(VAD_PADDING_MS);
        long currentSpeechStartSample = -1;
        long currentSpeechEndSample = -1;
        long frameStartSample = 0;
        int frameBytes = SILERO_VAD_FRAME_SAMPLES * 2;

        try (FileInputStream inputStream = new FileInputStream(audioFile)) {
            skipFully(inputStream, WAV_HEADER_BYTES);
            byte[] frame = new byte[frameBytes];
            float[] floatFrame = new float[SILERO_VAD_FRAME_SAMPLES];

            while (!isCancelled && frameStartSample < totalSamples) {
                int bytesRead = readFrame(inputStream, frame);
                if (bytesRead <= 0) {
                    break;
                }
                if (bytesRead < frameBytes) {
                    for (int i = bytesRead; i < frameBytes; i++) {
                        frame[i] = 0;
                    }
                }

                long actualFrameSamples = Math.min(SILERO_VAD_FRAME_SAMPLES, totalSamples - frameStartSample);
                fillPcm16LeFloatFrame(frame, floatFrame);
                boolean isSpeech = vad.isSpeech(floatFrame);
                if (isSpeech) {
                    if (currentSpeechStartSample < 0) {
                        currentSpeechStartSample = Math.max(0, frameStartSample - paddingSamples);
                    }
                    currentSpeechEndSample = Math.min(totalSamples,
                            frameStartSample + actualFrameSamples + paddingSamples);
                } else if (currentSpeechStartSample >= 0) {
                    appendSpeechWindow(windows, currentSpeechStartSample, currentSpeechEndSample, totalSamples);
                    currentSpeechStartSample = -1;
                    currentSpeechEndSample = -1;
                }

                frameStartSample += actualFrameSamples;
            }
        } finally {
            try {
                vad.close();
            } catch (Throwable e) {
                Log.w(TAG, "Error closing Silero VAD", e);
            }
        }

        if (currentSpeechStartSample >= 0) {
            appendSpeechWindow(windows, currentSpeechStartSample, currentSpeechEndSample, totalSamples);
        }
        return windows;
    }

    private List<SpeechWindow> detectSpeechWindowsWithYamnet(File audioFile,
                                                             long totalSamples,
                                                             String vadAggressiveness) throws IOException {
        List<SpeechWindow> windows = new ArrayList<>();
        if (totalSamples <= 0) {
            return windows;
        }

        com.konovalov.vad.yamnet.VadYamnet vad = com.konovalov.vad.yamnet.Vad.builder()
                .setContext(context)
                .setSampleRate(com.konovalov.vad.yamnet.config.SampleRate.SAMPLE_RATE_16K)
                .setFrameSize(com.konovalov.vad.yamnet.config.FrameSize.FRAME_SIZE_975)
                .setMode(toYamnetVadMode(vadAggressiveness))
                .setSpeechDurationMs(YAMNET_VAD_SPEECH_DURATION_MS)
                .setSilenceDurationMs(YAMNET_VAD_SILENCE_DURATION_MS)
                .build();

        long paddingSamples = msToSamples(VAD_PADDING_MS);
        long currentSpeechStartSample = -1;
        long currentSpeechEndSample = -1;
        long frameStartSample = 0;
        int frameBytes = YAMNET_VAD_FRAME_SAMPLES * 2;

        try (FileInputStream inputStream = new FileInputStream(audioFile)) {
            skipFully(inputStream, WAV_HEADER_BYTES);
            byte[] frame = new byte[frameBytes];
            float[] floatFrame = new float[YAMNET_VAD_FRAME_SAMPLES];

            while (!isCancelled && frameStartSample < totalSamples) {
                int bytesRead = readFrame(inputStream, frame);
                if (bytesRead <= 0) {
                    break;
                }
                if (bytesRead < frameBytes) {
                    for (int i = bytesRead; i < frameBytes; i++) {
                        frame[i] = 0;
                    }
                }

                long actualFrameSamples = Math.min(YAMNET_VAD_FRAME_SAMPLES, totalSamples - frameStartSample);
                fillPcm16LeFloatFrame(frame, floatFrame);
                com.konovalov.vad.yamnet.SoundCategory speechCategory =
                        vad.classifyAudio("Speech", floatFrame);
                boolean isSpeech = speechCategory != null
                        && "Speech".equals(speechCategory.getLabel());
                if (isSpeech) {
                    if (currentSpeechStartSample < 0) {
                        currentSpeechStartSample = Math.max(0, frameStartSample - paddingSamples);
                    }
                    currentSpeechEndSample = Math.min(totalSamples,
                            frameStartSample + actualFrameSamples + paddingSamples);
                } else if (currentSpeechStartSample >= 0) {
                    appendSpeechWindow(windows, currentSpeechStartSample, currentSpeechEndSample, totalSamples);
                    currentSpeechStartSample = -1;
                    currentSpeechEndSample = -1;
                }

                frameStartSample += actualFrameSamples;
            }
        } finally {
            try {
                vad.close();
            } catch (Throwable e) {
                Log.w(TAG, "Error closing Yamnet VAD", e);
            }
        }

        if (currentSpeechStartSample >= 0) {
            appendSpeechWindow(windows, currentSpeechStartSample, currentSpeechEndSample, totalSamples);
        }
        return windows;
    }

    private void appendSpeechWindow(List<SpeechWindow> windows, long startSample,
                                    long endSample, long totalSamples) {
        long normalizedStart = Math.max(0, Math.min(startSample, totalSamples));
        long normalizedEnd = Math.max(normalizedStart, Math.min(endSample, totalSamples));
        if (normalizedEnd <= normalizedStart) {
            return;
        }

        long mergeGapSamples = msToSamples(VAD_MERGE_GAP_MS);
        if (!windows.isEmpty()) {
            SpeechWindow previous = windows.get(windows.size() - 1);
            if (normalizedStart <= previous.endSample + mergeGapSamples) {
                previous.endSample = Math.max(previous.endSample, normalizedEnd);
                return;
            }
        }
        windows.add(new SpeechWindow(normalizedStart, normalizedEnd));
    }

    private long msToSamples(long milliseconds) {
        return milliseconds * WHISPER_SAMPLE_RATE / 1000L;
    }

    private int readFrame(FileInputStream inputStream, byte[] frame) throws IOException {
        int totalRead = 0;
        while (totalRead < frame.length) {
            int read = inputStream.read(frame, totalRead, frame.length - totalRead);
            if (read == -1) {
                break;
            }
            totalRead += read;
        }
        return totalRead;
    }

    private void fillPcm16LeFloatFrame(byte[] pcmFrame, float[] floatFrame) {
        int samples = Math.min(floatFrame.length, pcmFrame.length / 2);
        for (int i = 0; i < samples; i++) {
            int sampleIndex = i * 2;
            short sample = (short) ((pcmFrame[sampleIndex] & 0xFF) | (pcmFrame[sampleIndex + 1] << 8));
            floatFrame[i] = sample / 32768.0f;
        }
        for (int i = samples; i < floatFrame.length; i++) {
            floatFrame[i] = 0.0f;
        }
    }

    private void skipFully(FileInputStream inputStream, long bytesToSkip) throws IOException {
        long skippedTotal = 0;
        while (skippedTotal < bytesToSkip) {
            long skipped = inputStream.skip(bytesToSkip - skippedTotal);
            if (skipped <= 0) {
                if (inputStream.read() == -1) {
                    throw new IOException("Audio file too short");
                }
                skipped = 1;
            }
            skippedTotal += skipped;
        }
    }

    private float[] readWavFloatRange(File file, long startSample, int requestedSamples) throws IOException {
        if (requestedSamples <= 0) {
            return new float[0];
        }

        try (FileInputStream inputStream = new FileInputStream(file)) {
            skipFully(inputStream, WAV_HEADER_BYTES + startSample * 2L);
            return readWavFloatChunk(inputStream, requestedSamples);
        }
    }

    private void transcribeWhisperChunk(WhisperContext activeWhisperContext,
                                        float[] audioData,
                                        String language,
                                        long chunkOffsetMs,
                                        int nativeMaxSegmentLength,
                                        long[] lastPartialUpdateMs,
                                        long[] lastProgressUpdateMs,
                                        int[] lastDispatchedProgress,
                                        int chunkStartProgress,
                                        int chunkProgressSpan,
                                        List<SubtitleEntry> subtitles,
                                        SubtitleGenerationCallback callback) {
        transcribeWhisperChunk(activeWhisperContext, audioData, language, null, chunkOffsetMs,
                nativeMaxSegmentLength, lastPartialUpdateMs, lastProgressUpdateMs,
                lastDispatchedProgress, chunkStartProgress, chunkProgressSpan, subtitles, callback);
    }

    private void transcribeWhisperChunk(WhisperContext activeWhisperContext,
                                        float[] audioData,
                                        String language,
                                        TimeMapper timeMapper,
                                        int nativeMaxSegmentLength,
                                        long[] lastPartialUpdateMs,
                                        long[] lastProgressUpdateMs,
                                        int[] lastDispatchedProgress,
                                        int chunkStartProgress,
                                        int chunkProgressSpan,
                                        List<SubtitleEntry> subtitles,
                                        SubtitleGenerationCallback callback) {
        transcribeWhisperChunk(activeWhisperContext, audioData, language, timeMapper, 0,
                nativeMaxSegmentLength, lastPartialUpdateMs, lastProgressUpdateMs,
                lastDispatchedProgress, chunkStartProgress, chunkProgressSpan, subtitles, callback);
    }

    private void transcribeWhisperChunk(WhisperContext activeWhisperContext,
                                        float[] audioData,
                                        String language,
                                        TimeMapper timeMapper,
                                        long chunkOffsetMs,
                                        int nativeMaxSegmentLength,
                                        long[] lastPartialUpdateMs,
                                        long[] lastProgressUpdateMs,
                                        int[] lastDispatchedProgress,
                                        int chunkStartProgress,
                                        int chunkProgressSpan,
                                        List<SubtitleEntry> subtitles,
                                        SubtitleGenerationCallback callback) {
        activeWhisperContext.transcribeDataWithCallbacks(audioData, language,
                nativeMaxSegmentLength, suppressWhisperSdh, new WhisperCallback() {
            @Override
            public void onNewSegment(long startMs, long endMs, String text, String tokenTimingsJson) {
                if (VERBOSE_SUBTITLE_LOGS) {
                    Log.d(TAG, "onNewSegment callback: segmentStartUnits=" + startMs + ", segmentEndUnits=" + endMs
                            + ", tokenTimings=" + (tokenTimingsJson == null ? 0 : tokenTimingsJson.length())
                            + ", text=\"" + text + "\"");
                }
                String displayText = cleanWhisperDisplayText(text);
                if (isCancelled || displayText.trim().isEmpty()) {
                    return;
                }
                if (suppressWhisperSdh && isWhisperSdhCaption(displayText)) {
                    Log.d(TAG, "Skipping Whisper SDH caption: \"" + displayText.trim() + "\"");
                    return;
                }
                long compactSegmentStartMs = startMs * 10;
                long compactSegmentEndMs = endMs * 10;
                long segmentStartMs = timeMapper == null
                        ? chunkOffsetMs + compactSegmentStartMs
                        : timeMapper.mapCompactMsToOriginalMs(compactSegmentStartMs);
                long segmentEndMs = timeMapper == null
                        ? chunkOffsetMs + compactSegmentEndMs
                        : timeMapper.mapCompactMsToOriginalMs(compactSegmentEndMs);
                List<WordTiming> timedWords = parseWhisperTokenTimings(
                        tokenTimingsJson, chunkOffsetMs, timeMapper,
                        segmentStartMs, segmentEndMs, displayText);
                if (VERBOSE_SUBTITLE_LOGS && !timedWords.isEmpty()) {
                    Log.d(TAG, "Whisper word timing range: startMs=" + timedWords.get(0).getStartMs()
                            + ", endMs=" + timedWords.get(timedWords.size() - 1).getEndMs()
                            + ", words=" + timedWords.size());
                }

                if (wordByWordMode) {
                    for (WordTiming word : timedWords) {
                        List<WordTiming> singleWordList = new ArrayList<>();
                        singleWordList.add(word);
                        synchronized (subtitles) {
                            subtitles.add(new SubtitleEntry(subtitles.size() + 1,
                                    formatTime(word.getStartMs()),
                                    formatTime(word.getEndMs()),
                                    word.getWord(),
                                    singleWordList));
                        }
                    }
                } else {
                    synchronized (subtitles) {
                        addTimedSubtitleChunks(subtitles, timedWords);
                    }
                }

                synchronized (subtitles) {
                    if (shouldDispatchPartialUpdate(lastPartialUpdateMs)) {
                        callback.onPartialSubtitlesGenerated(new ArrayList<>(subtitles));
                    }
                }
            }

            @Override
            public void onProgress(int progress) {
                int globalProgress = Math.min(100,
                        chunkStartProgress + Math.max(0, progress) * chunkProgressSpan / 100);
                if (shouldDispatchProgressUpdate(lastProgressUpdateMs, lastDispatchedProgress, globalProgress)) {
                    callback.onProgressUpdate(globalProgress);
                }
            }

            @Override
            public void onComplete() {
            }
        });
    }

    private List<WordTiming> parseWhisperTokenTimings(String tokenTimingsJson,
                                                       long tokenOffsetMs,
                                                       TimeMapper timeMapper,
                                                       long segmentStartMs,
                                                       long segmentEndMs,
                                                       String fallbackText) {
        List<WordTiming> words = new ArrayList<>();
        if (tokenTimingsJson == null || tokenTimingsJson.trim().isEmpty()) {
            addFallbackWhisperWords(words, fallbackText, segmentStartMs, segmentEndMs);
            return words;
        }

        try {
            JSONArray tokens = new JSONArray(tokenTimingsJson);
            StringBuilder currentWord = new StringBuilder();
            long currentStartMs = -1;
            long currentEndMs = -1;
            double confidenceSum = 0.0;
            int confidenceCount = 0;

            for (int i = 0; i < tokens.length(); i++) {
                JSONObject token = tokens.getJSONObject(i);
                String tokenText = token.optString("text", "");
                long startMs = token.optLong("startMs", -1);
                long endMs = token.optLong("endMs", -1);
                if (timeMapper != null && startMs >= 0) {
                    startMs = timeMapper.mapCompactMsToOriginalMs(startMs);
                } else if (startMs >= 0) {
                    startMs += tokenOffsetMs;
                }
                if (timeMapper != null && endMs >= 0) {
                    endMs = timeMapper.mapCompactMsToOriginalMs(endMs);
                } else if (endMs >= 0) {
                    endMs += tokenOffsetMs;
                }
                double confidence = token.optDouble("confidence", 0.0);
                if (tokenText.isEmpty() || startMs < 0 || endMs <= startMs) {
                    continue;
                }

                String trimmed = tokenText.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }

                boolean punctuationOnly = isPunctuationOnly(trimmed);
                boolean startsNewWord = currentWord.length() == 0 || Character.isWhitespace(tokenText.charAt(0));
                if (startsNewWord && currentWord.length() > 0 && !punctuationOnly) {
                    addCurrentWhisperWord(words, currentWord, currentStartMs, currentEndMs,
                            confidenceSum, confidenceCount);
                    currentWord = new StringBuilder();
                    currentStartMs = -1;
                    currentEndMs = -1;
                    confidenceSum = 0.0;
                    confidenceCount = 0;
                }

                if (currentWord.length() == 0) {
                    currentStartMs = startMs;
                }
                currentWord.append(trimmed);
                currentEndMs = Math.max(currentEndMs, endMs);
                confidenceSum += confidence;
                confidenceCount++;
            }

            addCurrentWhisperWord(words, currentWord, currentStartMs, currentEndMs,
                    confidenceSum, confidenceCount);
        } catch (Exception e) {
            Log.e(TAG, "Error parsing Whisper token timings", e);
            words.clear();
        }

        if (words.isEmpty() || shouldUseFallbackWhisperWords(words, fallbackText)) {
            words.clear();
            addFallbackWhisperWords(words, fallbackText, segmentStartMs, segmentEndMs);
        }

        return words;
    }

    private String cleanWhisperDisplayText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        return text.replace('\uFFFD', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private void addCurrentWhisperWord(List<WordTiming> words, StringBuilder currentWord,
                                       long startMs, long endMs,
                                       double confidenceSum, int confidenceCount) {
        String word = currentWord.toString().trim();
        if (word.isEmpty() || startMs < 0 || endMs <= startMs) {
            return;
        }
        double confidence = confidenceCount == 0 ? 0.0 : confidenceSum / confidenceCount;
        words.add(new WordTiming(word, startMs, endMs, confidence));
    }

    private void addFallbackWhisperWords(List<WordTiming> words, String text, long startMs, long endMs) {
        String fallback = text == null ? "" : text.trim();
        if (fallback.isEmpty() || endMs <= startMs) {
            return;
        }

        String[] parts = fallback.split("\\s+");
        if (parts.length == 0) {
            return;
        }

        long duration = Math.max(1, endMs - startMs);
        for (int i = 0; i < parts.length; i++) {
            long wordStartMs = startMs + (duration * i) / parts.length;
            long wordEndMs = i == parts.length - 1
                    ? endMs
                    : startMs + (duration * (i + 1)) / parts.length;
            if (wordEndMs <= wordStartMs) {
                wordEndMs = wordStartMs + 1;
            }
            words.add(new WordTiming(parts[i], wordStartMs, wordEndMs, 0.0));
        }
    }

    private boolean shouldUseFallbackWhisperWords(List<WordTiming> words, String fallbackText) {
        String fallback = fallbackText == null ? "" : fallbackText.trim();
        if (fallback.isEmpty()) {
            return false;
        }

        StringBuilder parsed = new StringBuilder();
        for (WordTiming word : words) {
            String wordText = word.getWord();
            if (wordText == null) {
                continue;
            }
            if (wordText.indexOf('\uFFFD') >= 0) {
                return true;
            }
            if (parsed.length() > 0) {
                parsed.append(' ');
            }
            parsed.append(wordText);
        }

        String parsedComparable = normalizeWhisperComparisonText(parsed.toString());
        String fallbackComparable = normalizeWhisperComparisonText(fallback);
        if (fallbackComparable.isEmpty()) {
            return false;
        }
        if (parsedComparable.isEmpty()) {
            return true;
        }

        int commonChars = 0;
        int[] parsedCounts = new int[Character.MAX_VALUE + 1];
        for (int i = 0; i < parsedComparable.length(); i++) {
            parsedCounts[parsedComparable.charAt(i)]++;
        }
        for (int i = 0; i < fallbackComparable.length(); i++) {
            char c = fallbackComparable.charAt(i);
            if (parsedCounts[c] > 0) {
                parsedCounts[c]--;
                commonChars++;
            }
        }

        double coverage = (double) commonChars / fallbackComparable.length();
        double lengthRatio = (double) parsedComparable.length() / fallbackComparable.length();
        return coverage < 0.55 || lengthRatio < 0.45 || lengthRatio > 1.8;
    }

    private String normalizeWhisperComparisonText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        StringBuilder normalized = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                normalized.append(Character.toLowerCase(c));
            }
        }
        return normalized.toString();
    }

    private boolean isPunctuationOnly(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetterOrDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private void addTimedSubtitleChunks(List<SubtitleEntry> subtitles, List<WordTiming> words) {
        List<WordTiming> currentWords = new ArrayList<>();
        StringBuilder currentText = new StringBuilder();

        for (WordTiming word : words) {
            String wordText = word.getWord();
            if (wordText == null || wordText.trim().isEmpty()) {
                continue;
            }

            int nextLength = currentText.length() == 0
                    ? wordText.length()
                    : currentText.length() + 1 + wordText.length();
            boolean timeGapBoundary = !currentWords.isEmpty()
                    && word.getStartMs() - currentWords.get(currentWords.size() - 1).getEndMs()
                    > WHISPER_WORD_GAP_SPLIT_MS;
            boolean sentenceBoundary = currentText.length() > 0 && endsSentence(currentText.toString());
            boolean lengthBoundary = !keepSentencesTogether && nextLength > maxSubtitleLength;
            if (!currentWords.isEmpty() && (timeGapBoundary || lengthBoundary || sentenceBoundary)) {
                addSubtitleChunk(subtitles, currentWords, currentText.toString());
                currentWords = new ArrayList<>();
                currentText = new StringBuilder();
            }

            if (currentText.length() > 0) {
                currentText.append(" ");
            }
            currentText.append(wordText);
            currentWords.add(word);
        }

        if (!currentWords.isEmpty()) {
            addSubtitleChunk(subtitles, currentWords, currentText.toString());
        }
    }

    private boolean endsSentence(String text) {
        String trimmed = text == null ? "" : text.trim();
        return trimmed.endsWith(".") || trimmed.endsWith("!") || trimmed.endsWith("?");
    }

    private boolean isWhisperSdhCaption(String text) {
        String trimmed = text == null ? "" : text.trim();
        return trimmed.length() >= 3
                && ((trimmed.startsWith("[") && trimmed.endsWith("]"))
                || (trimmed.startsWith("(") && trimmed.endsWith(")")));
    }

    private void addSubtitleChunk(List<SubtitleEntry> subtitles, List<WordTiming> words, String text) {
        if (words.isEmpty()) {
            return;
        }
        long startMs = words.get(0).getStartMs();
        long endMs = words.get(words.size() - 1).getEndMs();
        if (endMs <= startMs) {
            return;
        }
        subtitles.add(new SubtitleEntry(subtitles.size() + 1,
                formatTime(startMs),
                formatTime(endMs),
                text.trim(),
                new ArrayList<>(words)));
    }

    private float[] readWavToFloatArray(File file) throws IOException {
        byte[] bytes = new byte[(int) file.length()];
        try (FileInputStream fis = new FileInputStream(file)) {
            int totalRead = 0;
            while (totalRead < bytes.length) {
                int read = fis.read(bytes, totalRead, bytes.length - totalRead);
                if (read == -1) break;
                totalRead += read;
            }
            if (totalRead < 44) {
                throw new IOException("Audio file too short");
            }
        }

        int pcmLength = (bytes.length - 44) / 2;
        float[] floats = new float[pcmLength];
        for (int i = 0; i < pcmLength; i++) {
            int sampleIndex = 44 + i * 2;
            short sample = (short) ((bytes[sampleIndex] & 0xFF) | (bytes[sampleIndex + 1] << 8));
            floats[i] = sample / 32768.0f;
        }
        return floats;
    }

    private float[] readWavFloatChunk(FileInputStream inputStream, int requestedSamples) throws IOException {
        if (requestedSamples <= 0) {
            return new float[0];
        }
        byte[] bytes = new byte[requestedSamples * 2];
        int totalRead = 0;
        while (totalRead < bytes.length) {
            int read = inputStream.read(bytes, totalRead, bytes.length - totalRead);
            if (read == -1) {
                break;
            }
            totalRead += read;
        }
        int sampleCount = totalRead / 2;
        float[] floats = new float[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            int sampleIndex = i * 2;
            short sample = (short) ((bytes[sampleIndex] & 0xFF) | (bytes[sampleIndex + 1] << 8));
            floats[i] = sample / 32768.0f;
        }
        return floats;
    }

    private static class SpeechWindow {
        private final long startSample;
        private long endSample;

        private SpeechWindow(long startSample, long endSample) {
            this.startSample = startSample;
            this.endSample = endSample;
        }
    }

    private static class WhisperVadBatch {
        private final float[] audioData;
        private final TimeMapper timeMapper;
        private final long firstOriginalSample;

        private WhisperVadBatch(float[] audioData, TimeMapper timeMapper, long firstOriginalSample) {
            this.audioData = audioData;
            this.timeMapper = timeMapper;
            this.firstOriginalSample = firstOriginalSample;
        }
    }

    private static class WhisperVadBatchBuilder {
        private float[] audioData;
        private int sampleCount;
        private long firstOriginalSample = -1;
        private final TimeMapper timeMapper = new TimeMapper();

        private WhisperVadBatchBuilder(int initialCapacity) {
            audioData = new float[Math.max(1, Math.min(initialCapacity, WHISPER_SAMPLE_RATE * 30))];
        }

        private boolean hasAudio() {
            return sampleCount > 0;
        }

        private int sampleCount() {
            return sampleCount;
        }

        private void append(float[] samples, long originalStartSample) {
            if (samples == null || samples.length == 0) {
                return;
            }
            if (firstOriginalSample < 0) {
                firstOriginalSample = originalStartSample;
            }
            ensureCapacity(sampleCount + samples.length);
            System.arraycopy(samples, 0, audioData, sampleCount, samples.length);
            timeMapper.addRange(sampleCount, samples.length, originalStartSample);
            sampleCount += samples.length;
        }

        private WhisperVadBatch build() {
            return new WhisperVadBatch(Arrays.copyOf(audioData, sampleCount),
                    timeMapper, firstOriginalSample < 0 ? 0 : firstOriginalSample);
        }

        private void ensureCapacity(int requiredCapacity) {
            if (requiredCapacity <= audioData.length) {
                return;
            }
            int newCapacity = audioData.length;
            while (newCapacity < requiredCapacity) {
                newCapacity *= 2;
            }
            audioData = Arrays.copyOf(audioData, newCapacity);
        }
    }

    private static class TimeMapper {
        private final List<TimeMapRange> ranges = new ArrayList<>();

        private void addRange(long compactStartSample, long sampleCount, long originalStartSample) {
            if (sampleCount <= 0) {
                return;
            }
            ranges.add(new TimeMapRange(compactStartSample,
                    compactStartSample + sampleCount,
                    originalStartSample));
        }

        private long mapCompactMsToOriginalMs(long compactMs) {
            long compactSample = compactMs * WHISPER_SAMPLE_RATE / 1000L;
            return mapCompactSampleToOriginalSample(compactSample) * 1000L / WHISPER_SAMPLE_RATE;
        }

        private long mapCompactSampleToOriginalSample(long compactSample) {
            if (ranges.isEmpty()) {
                return compactSample;
            }
            for (TimeMapRange range : ranges) {
                if (compactSample >= range.compactStartSample && compactSample < range.compactEndSample) {
                    return range.originalStartSample + compactSample - range.compactStartSample;
                }
            }
            TimeMapRange lastRange = ranges.get(ranges.size() - 1);
            if (compactSample >= lastRange.compactEndSample) {
                return lastRange.originalStartSample
                        + lastRange.compactEndSample
                        - lastRange.compactStartSample;
            }
            TimeMapRange firstRange = ranges.get(0);
            return firstRange.originalStartSample;
        }
    }

    private static class TimeMapRange {
        private final long compactStartSample;
        private final long compactEndSample;
        private final long originalStartSample;

        private TimeMapRange(long compactStartSample, long compactEndSample, long originalStartSample) {
            this.compactStartSample = compactStartSample;
            this.compactEndSample = compactEndSample;
            this.originalStartSample = originalStartSample;
        }
    }
}
