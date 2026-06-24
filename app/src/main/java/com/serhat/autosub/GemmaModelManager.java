package com.serhat.autosub;

import android.app.ActivityManager;
import android.content.Context;
import android.os.StatFs;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

public final class GemmaModelManager {
    public static final String MODEL_ID = "gemma-4-e2b-it";
    public static final String DISPLAY_NAME = "Gemma 4 E2B (Shorts editor)";
    public static final String FILE_NAME = "gemma-4-E2B-it.litertlm";
    public static final long EXPECTED_SIZE = 2_588_147_712L;
    public static final long MIN_TOTAL_MEMORY = 8L * 1024 * 1024 * 1024;
    private static final long STORAGE_HEADROOM = 512L * 1024 * 1024;
    private static final String URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/6e5c4f1e395deb959c494953478fa5cec4b8008f/gemma-4-E2B-it.litertlm?download=true";

    public interface DownloadCallback {
        void onProgress(int progress, long received, long total, String speed, String eta);
        void onComplete(File file);
        void onPaused();
        void onCancelled();
        void onError(String message);
    }

    public final class DownloadTask implements Runnable {
        private final DownloadCallback callback;
        private volatile boolean cancelled;
        private volatile boolean paused;
        private volatile HttpURLConnection connection;
        private volatile InputStream input;

        DownloadTask(DownloadCallback callback) { this.callback = callback; }
        public void pause() { paused = true; closeConnection(); }
        public void cancel() { cancelled = true; closeConnection(); }

        @Override public void run() {
            File part = getPartialFile();
            try {
                ensureStorageAvailable();
                long existing = part.isFile() ? part.length() : 0;
                if (existing == EXPECTED_SIZE) {
                    File target = getModelFile();
                    if (target.exists()) target.delete();
                    if (!part.renameTo(target)) throw new IllegalStateException("Could not finalize the completed model download");
                    callback.onComplete(target);
                    return;
                }
                connection = (HttpURLConnection) new URL(URL).openConnection();
                connection.setConnectTimeout(30_000);
                connection.setReadTimeout(30_000);
                connection.setRequestProperty("Accept-Encoding", "identity");
                connection.setRequestProperty("User-Agent", "AutoSub/1.3");
                if (existing > 0) connection.setRequestProperty("Range", "bytes=" + existing + "-");
                connection.connect();
                int code = connection.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                    throw new IllegalStateException("Model server returned HTTP " + code);
                }
                boolean append = code == HttpURLConnection.HTTP_PARTIAL && existing > 0;
                if (!append) existing = 0;
                long total = parseTotalSize(connection, existing);
                input = new BufferedInputStream(connection.getInputStream());
                long received = existing;
                long lastBytes = received;
                long lastTime = System.currentTimeMillis();
                byte[] buffer = new byte[128 * 1024];
                try (FileOutputStream output = new FileOutputStream(part, append)) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        if (cancelled || paused) break;
                        output.write(buffer, 0, read);
                        received += read;
                        long now = System.currentTimeMillis();
                        // Gemma is multi-gigabyte; updating Android UI and notifications twice a
                        // second for the entire download causes avoidable main-thread churn.
                        if (now - lastTime >= 1000) {
                            long bytesPerSecond = Math.max(1, (received - lastBytes) * 1000 / (now - lastTime));
                            long remainingSeconds = total > received ? (total - received) / bytesPerSecond : 0;
                            callback.onProgress(total > 0 ? (int) Math.min(99, received * 100 / total) : -1,
                                    received, total, formatBytes(bytesPerSecond) + "/s", formatEta(remainingSeconds));
                            lastBytes = received; lastTime = now;
                        }
                    }
                    output.getFD().sync();
                }
                if (cancelled) {
                    if (part.exists()) part.delete();
                    callback.onCancelled();
                    return;
                }
                if (paused) { callback.onPaused(); return; }
                if (total > 0 && part.length() != total) throw new IllegalStateException("Download ended before the model was complete");
                File target = getModelFile();
                if (target.exists() && !target.delete()) throw new IllegalStateException("Could not replace the existing model");
                if (!part.renameTo(target)) throw new IllegalStateException("Could not finalize the model download");
                callback.onProgress(100, target.length(), target.length(), "", "");
                callback.onComplete(target);
            } catch (Exception e) {
                if (cancelled) {
                    if (part.exists()) part.delete();
                    callback.onCancelled();
                }
                else if (paused) callback.onPaused();
                else callback.onError(e.getMessage() == null ? "Gemma model download failed" : e.getMessage());
            } finally { closeConnection(); }
        }

        private void closeConnection() {
            try { if (input != null) input.close(); } catch (Exception ignored) {}
            if (connection != null) connection.disconnect();
        }
    }

    private final Context context;
    private final File modelDir;

    public GemmaModelManager(Context context) {
        this.context = context.getApplicationContext();
        File external = context.getExternalFilesDir("models");
        File root = external == null ? new File(context.getFilesDir(), "models") : external;
        modelDir = new File(root, MODEL_ID);
        if (!modelDir.exists()) modelDir.mkdirs();
    }

    public File getModelFile() { return new File(modelDir, FILE_NAME); }
    public File getPartialFile() { return new File(modelDir, FILE_NAME + ".part"); }
    public boolean isInstalled() { return getModelFile().isFile() && getModelFile().length() > 0; }
    public long getDownloadedBytes() { return isInstalled() ? getModelFile().length() : getPartialFile().length(); }
    public DownloadTask startDownload(DownloadCallback callback) {
        DownloadTask task = new DownloadTask(callback);
        new Thread(task, "gemma-model-download").start();
        return task;
    }
    public boolean deleteModel() {
        boolean result = true;
        if (getModelFile().exists()) result = getModelFile().delete();
        if (getPartialFile().exists()) result = getPartialFile().delete() && result;
        return result;
    }
    public boolean isLowMemoryDevice() { return getTotalMemory() > 0 && getTotalMemory() < MIN_TOTAL_MEMORY; }
    public long getTotalMemory() {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) return 0;
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(info);
        return info.totalMem;
    }

    private void ensureStorageAvailable() {
        StatFs fs = new StatFs(modelDir.getAbsolutePath());
        long remaining = Math.max(0, EXPECTED_SIZE - getPartialFile().length());
        if (fs.getAvailableBytes() < remaining + STORAGE_HEADROOM) {
            throw new IllegalStateException("Not enough free space. Gemma needs about " + formatBytes(remaining + STORAGE_HEADROOM) + " available.");
        }
    }

    private long parseTotalSize(HttpURLConnection connection, long existing) {
        String range = connection.getHeaderField("Content-Range");
        if (range != null && range.contains("/")) {
            try { return Long.parseLong(range.substring(range.lastIndexOf('/') + 1)); } catch (Exception ignored) {}
        }
        long content = connection.getContentLengthLong();
        return content > 0 ? existing + content : EXPECTED_SIZE;
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double value = bytes / 1024d;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++; }
        return String.format(Locale.US, "%.1f %s", value, units[unit]);
    }

    private static String formatEta(long seconds) {
        if (seconds <= 0) return "";
        if (seconds < 60) return seconds + "s left";
        if (seconds < 3600) return (seconds / 60) + "m left";
        return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m left";
    }
}
