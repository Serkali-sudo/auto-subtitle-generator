package com.serhat.autosub;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;
import com.arthenica.ffmpegkit.FFmpegKitConfig;

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
import java.util.Collections;

public class SubtitleGenerator {
    private static final String TAG = "SubtitleGenerator";
    private final Context context;
    private volatile Model model;
    private final Object modelLock = new Object();
    private long currentLoadSessionId = 0;
    private VoskModelInfo currentModelInfo;
    private final ExecutorService executorService;
    private static final int MAX_SUBTITLE_LENGTH = 42; 
    private volatile boolean isCancelled = false;
    private File audioFile;
    private boolean wordByWordMode = false;

    public void setWordByWordMode(boolean enabled) {
        this.wordByWordMode = enabled;
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
        synchronized (modelLock) {
            currentLoadSessionId++;
            sessionId = currentLoadSessionId;

            if (this.model != null) {
                try {
                    this.model.close();
                    Log.d(TAG, "Released previous native speech model C++ memory allocation");
                } catch (Throwable e) {
                    Log.e(TAG, "Error releasing previous model memory", e);
                } finally {
                    this.model = null;
                }
            }
        }

        currentModelInfo = modelInfo;

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

    public void cancelGeneration() {
        isCancelled = true;
        FFmpegKit.cancel();
    }

    public void generateSubtitles(Uri videoUri, String permanentAudioPath, SubtitleGenerationCallback callback) {
        executorService.execute(() -> {
            try {
                if (model == null) {
                    callback.onError("Speech model is not ready");
                    return;
                }
                isCancelled = false;
                Log.d(TAG, "Starting subtitle generation process");
                callback.onProgressUpdate(-1);

                Log.d(TAG, "Extracting audio from video");
                audioFile = extractAudioFromVideo(videoUri);

                if (permanentAudioPath != null && !permanentAudioPath.isEmpty()) {
                    try {
                        copyFile(audioFile, new File(permanentAudioPath));
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to copy audio permanently", e);
                    }
                }

                if (isCancelled) {
                    callback.onCancelled();
                    return;
                }

                Log.d(TAG, "Performing speech recognition");
                callback.onProgressUpdate(0);
                List<SubtitleEntry> subtitleEntries = processAudioFile(audioFile, callback);

                if (isCancelled) {
                    callback.onCancelled();
                    return;
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
        List<SubtitleEntry> subtitles = new ArrayList<>();
        Recognizer recognizer = null;
        
        try {
            recognizer = new Recognizer(model, 16000.0f);
            recognizer.setWords(true);
            
            try (FileInputStream fis = new FileInputStream(audioFile)) {
                if (fis.skip(44) != 44) throw new IOException("Audio file too short");

                byte[] buffer = new byte[4096];
                int bytesRead;
                long totalBytes = audioFile.length() - 44;
                long processedBytes = 0;
                int lastReportedProgress = 0;

                while ((bytesRead = fis.read(buffer)) != -1) {
                    if (isCancelled) {
                        throw new IOException("Process cancelled");
                    }

                    if (recognizer.acceptWaveForm(buffer, bytesRead)) {
                        String result = recognizer.getResult();
                        processRecognitionResult(result, subtitles);
                        callback.onPartialSubtitlesGenerated(new ArrayList<>(subtitles));
                    }

                    processedBytes += bytesRead;
                    int currentProgress = totalBytes > 0 ? (int) (processedBytes * 100 / totalBytes) : 100;
                    if (currentProgress > lastReportedProgress) {
                        lastReportedProgress = currentProgress;
                        callback.onProgressUpdate(currentProgress);
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
            JSONArray wordsArray = jsonResult.getJSONArray("result");
            
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
                    subtitles.add(new SubtitleEntry(subtitles.size() + 1,
                        formatTime((long)(wordStart * 1000)),
                        formatTime((long)(wordEnd * 1000)),
                        word,
                        singleWordList));
                } else {
                    if (currentSubtitle.length() == 0) {
                        startTime = wordStart;
                    }
                    
                    if (currentSubtitle.length() + word.length() + 1 > MAX_SUBTITLE_LENGTH) {
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


    private List<String> splitSubtitle(String text) {
        List<String> result = new ArrayList<>();
        String[] words = text.split("\\s+");
        StringBuilder currentLine = new StringBuilder();

        for (String word : words) {
            if (currentLine.length() + word.length() + 1 > MAX_SUBTITLE_LENGTH) {
                if (currentLine.length() > 0) {
                    result.add(currentLine.toString().trim());
                    currentLine = new StringBuilder();
                }
            }
            if (currentLine.length() > 0) {
                currentLine.append(" ");
            }
            currentLine.append(word);
        }

        if (currentLine.length() > 0) {
            result.add(currentLine.toString().trim());
        }

        return result;
    }

    public void saveSubtitlesToFile(List<SubtitleEntry> entries, String format, Uri videoUri, SubtitleSaveCallback callback) {
        saveSubtitlesToFile(entries, format, videoUri, null, callback);
    }

    public void saveSubtitlesToFile(List<SubtitleEntry> entries, String format, Uri videoUri,
                                    File outputDir, SubtitleSaveCallback callback) {
        executorService.execute(() -> {
            try {
                File exportDir = resolveOutputDir(outputDir);
                String videoName = getVideoNameFromUri(videoUri);
                String extension = format.toLowerCase(Locale.US);
                String uniqueFileName = buildExportFileName(extension + "-subtitles", videoName, extension);
                
                File subtitleFile = new File(exportDir, uniqueFileName);
                if (subtitleFile.exists()) {
                    callback.onError("Already exported subtitles for this video and model: " + subtitleFile.getName());
                    return;
                }
                FileOutputStream fos = new FileOutputStream(subtitleFile);

                if (format.equalsIgnoreCase("srt")) {
                    writeSrtSubtitles(entries, fos);
                } else if (format.equalsIgnoreCase("vtt")) {
                    writeVttSubtitles(entries, fos);
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
        public List<WordTiming> getWords() { return words; }

        public void setNumber(int number) { this.number = number; }
        public void setText(String text) { this.text = text; }
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
        executorService.execute(() -> {
            File subtitleFile = null;
            try {
                setupFontDirectories();
                File exportDir = resolveOutputDir(outputDir);

                boolean styledShorts = shortsStyle != null && (!forceMp4SoftSubtitles || burnSubtitles);
                subtitleFile = new File(context.getCacheDir(), styledShorts ? "temp_subtitles.ass" : "temp_subtitles.srt");
                FileOutputStream fos = new FileOutputStream(subtitleFile);
                if (styledShorts) {
                    writeAssSubtitles(subtitles, fos, shortsStyle, fontName == null ? "RobotoRegular" : fontName);
                } else {
                    writeSrtSubtitles(subtitles, fos);
                }
                fos.close();

//                logSrtFileContents(srtFile);

                String videoName = getVideoNameFromUri(videoUri);
                String outputExtension = styledShorts && !burnSubtitles && !forceMp4SoftSubtitles ? "mkv" : "mp4";
                String uniqueFileName = buildExportFileName(burnSubtitles ? "hard-subtitles" : "soft-subtitles",
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
                if (styledShorts && burnSubtitles) {
                    command = String.format("-i %s -vf ass=%s -q:v 1 -c:a copy %s",
                            inputPath, subtitlePath, outputPath);
                } else if (styledShorts) {
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
                                   ShortsSubtitleStyle style, String fontName) throws IOException {
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
                    "Style: Default,%s,%d,&H00FFFFFF,&H00FFFFFF,&H00000000,&H80000000,-1,0,0,0,100,100,0,0,1,4,0,5,0,0,0,1\n\n",
                    fontName, fontSize));
            writer.write("[Events]\n");
            writer.write("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n");

            for (SubtitleEntry entry : subtitles) {
                if (style.isWordByWord() && entry.getWords() != null && !entry.getWords().isEmpty()) {
                    for (WordTiming word : entry.getWords()) {
                        writeAssDialogue(writer, word.getStartMs(), word.getEndMs(), word.getWord(), style, x, y);
                    }
                } else {
                    writeAssDialogue(writer, parseSubtitleTime(entry.getStartTime()), parseSubtitleTime(entry.getEndTime()),
                            entry.getText(), style, x, y);
                }
            }
        }
    }

    private void writeAssDialogue(Writer writer, long startMs, long endMs, String text,
                                  ShortsSubtitleStyle style, int x, int y) throws IOException {
        if (endMs <= startMs || text == null || text.trim().isEmpty()) {
            return;
        }
        String displayText = style.isUppercase() ? text.toUpperCase(Locale.getDefault()) : text;
        writer.write(String.format(Locale.US, "Dialogue: 0,%s,%s,Default,,0,0,0,,{\\pos(%d,%d)}%s\n",
                formatAssTime(startMs), formatAssTime(endMs), x, y, escapeAssText(displayText)));
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
}
