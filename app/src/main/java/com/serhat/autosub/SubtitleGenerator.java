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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.os.Environment;
import java.io.BufferedReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.HashMap;
import java.io.InputStream;
import java.util.Collections;

public class SubtitleGenerator {
    private static final String TAG = "SubtitleGenerator";
    private final Context context;
    private Model model;
    private final ExecutorService executorService;
    private static final int MAX_SUBTITLE_LENGTH = 42; 
    private volatile boolean isCancelled = false;
    private File audioFile;

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

    public void initModel(ModelInitCallback callback) {
        Log.d(TAG,"Called Model Init");
        StorageService.unpack(context, "model-en-us", "model",
                (model) -> {
                    this.model = model;
                    Log.d(TAG, "Model initialized");
                    callback.onModelInitialized();
                },
                (exception) -> {
                    Log.e(TAG, "Failed to unpack the model: " + exception.getMessage());
                    callback.onError(exception.getMessage());
                });
    }

    public void cancelGeneration() {
        isCancelled = true;
    }

    public void generateSubtitles(Uri videoUri, SubtitleGenerationCallback callback) {
        executorService.execute(() -> {
            try {
                isCancelled = false;
                Log.d(TAG, "Starting subtitle generation process");
                callback.onProgressUpdate(0);

                Log.d(TAG, "Extracting audio from video");
                audioFile = extractAudioFromVideo(videoUri);
                callback.onProgressUpdate(20);

                if (isCancelled) {
                    callback.onCancelled();
                    return;
                }

                Log.d(TAG, "Performing speech recognition");
                List<SubtitleEntry> subtitleEntries = processAudioFile(audioFile, callback);
                callback.onProgressUpdate(95);

                if (isCancelled) {
                    callback.onCancelled();
                    return;
                }

                callback.onProgressUpdate(100);

                Log.d(TAG, "Subtitle generation completed");
                callback.onSubtitlesGenerated(subtitleEntries);
            } catch (Exception e) {
                Log.e(TAG, "Error generating subtitles", e);
                callback.onError("Error generating subtitles: " + e.getMessage());
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
            
            FileInputStream fis = new FileInputStream(audioFile);
            if (fis.skip(44) != 44) throw new IOException("Audio file too short");
            
            byte[] buffer = new byte[4096];
            int bytesRead;
            long totalBytes = audioFile.length() - 44;
            long processedBytes = 0;
            int lastReportedProgress = 20;
            
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
                int currentProgress = (int) (20 + (processedBytes * 75 / totalBytes));
                if (currentProgress > lastReportedProgress) {
                    lastReportedProgress = currentProgress;
                    callback.onProgressUpdate(currentProgress);
                }
            }

            String finalResult = recognizer.getFinalResult();
            processRecognitionResult(finalResult, subtitles);
            
            fis.close();
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
            double startTime = 0;
            double endTime = 0;
            
            for (int i = 0; i < wordsArray.length(); i++) {
                JSONObject wordObj = wordsArray.getJSONObject(i);
                String word = wordObj.getString("word");
                double wordStart = wordObj.getDouble("start");
                double wordEnd = wordObj.getDouble("end");
                
                if (currentSubtitle.length() == 0) {
                    startTime = wordStart;
                }
                
                if (currentSubtitle.length() + word.length() + 1 > MAX_SUBTITLE_LENGTH) {
                    subtitles.add(new SubtitleEntry(subtitles.size() + 1,
                        formatTime((long)(startTime * 1000)),
                        formatTime((long)(endTime * 1000)),
                        currentSubtitle.toString().trim()));

                    currentSubtitle = new StringBuilder(word);
                    startTime = wordStart;
                } else {
                    if (currentSubtitle.length() > 0) {
                        currentSubtitle.append(" ");
                    }
                    currentSubtitle.append(word);
                }
                
                endTime = wordEnd;
            }

            if (currentSubtitle.length() > 0) {
                subtitles.add(new SubtitleEntry(subtitles.size() + 1,
                    formatTime((long)(startTime * 1000)),
                    formatTime((long)(endTime * 1000)),
                    currentSubtitle.toString().trim()));
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
        executorService.execute(() -> {
            try {
                String videoName = getVideoNameFromUri(videoUri);
                String baseName = videoName + "_subtitles";
                String uniqueFileName = getUniqueFileName(ApplicationPath.applicationPath(context), baseName, format.toLowerCase());
                
                File subtitleFile = new File(ApplicationPath.applicationPath(context), uniqueFileName);
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

        public SubtitleEntry(int number, String startTime, String endTime, String text) {
            this.number = number;
            this.startTime = startTime;
            this.endTime = endTime;
            this.text = text;
        }

        public int getNumber() { return number; }
        public String getStartTime() { return startTime; }
        public String getEndTime() { return endTime; }
        public String getText() { return text; }

        public void setNumber(int number) { this.number = number; }
        public void setText(String text) { this.text = text; }
        public void setEndTime(String endTime) {
            this.endTime = endTime;
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
        executorService.execute(() -> {
            File srtFile = null;
            try {
                setupFontDirectories();

                srtFile = new File(context.getCacheDir(), "temp_subtitles.srt");
                FileOutputStream fos = new FileOutputStream(srtFile);
                writeSrtSubtitles(subtitles, fos);
                fos.close();

//                logSrtFileContents(srtFile);

                String videoName = getVideoNameFromUri(videoUri);
                String baseName = videoName + (burnSubtitles ? "_hard_subtitles" : "_soft_subtitles");
                String uniqueFileName = getUniqueFileName(ApplicationPath.applicationPath(context), baseName, "mp4");
                Log.d(TAG,"File Name:" + uniqueFileName);
                File outputFile = new File(ApplicationPath.applicationPath(context), uniqueFileName);

                String inputPath = FFmpegKitConfig.getSafParameterForRead(context, videoUri);
                String outputPath = outputFile.getAbsolutePath();
                String subtitlePath = srtFile.getAbsolutePath();

                String command;
                if (burnSubtitles) {
//                    command = String.format("-i %s -vf subtitles=%s:force_style='FontName=%s' -c:v mpeg4 -c:a copy %s",
//                            inputPath, subtitlePath, fontName, outputPath);
                     command = String.format("-i %s -vf subtitles=%s:force_style='FontName=%s' -q:v 1 -c:a copy %s",
                                                inputPath, subtitlePath, fontName, outputPath);
//                    command = String.format(
//                            "-i %s -vf subtitles=%s:force_style='FontName=%s' -c:v h264 -preset veryslow -c:a copy %s",
//                            inputPath, subtitlePath, fontName, outputPath
//                    );

                } else {
                    command = String.format("-i %s -i %s -c copy -c:s mov_text -metadata:s:s:0 language=eng %s",
                            inputPath, subtitlePath, outputPath);
                }

                Log.d(TAG, "Executing FFmpeg command: " + command);

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
                if (srtFile != null && srtFile.exists()) {
                    srtFile.delete();
                }
            }
        });
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


    private String getUniqueFileName(String baseDir, String baseName, String extension) {
        baseName = baseName.replace(" ", "_");
        
        File file = new File(baseDir, baseName + "." + extension);
        if (!file.exists()) {
            return baseName + "." + extension;
        }

        int counter = 1;
        while (true) {
            String newName = baseName + "_" + counter + "." + extension;
            file = new File(baseDir, newName);
            if (!file.exists()) {
                return newName;
            }
            counter++;
        }
    }
}