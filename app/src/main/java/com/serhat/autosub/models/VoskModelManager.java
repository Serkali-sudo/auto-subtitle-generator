package com.serhat.autosub.models;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class VoskModelManager {
    private static final String PREFS_NAME = "vosk_models";
    private static final String KEY_SELECTED_MODEL_ID = "selected_model_id";
    private static final String KEY_COMPATIBILITY_PREFIX = "compatibility_";
    private static final String KEY_LOAD_MODE_PREFIX = "load_mode_";
    public static final String DEFAULT_MODEL_ID = "whisper-base-en-q5_1";

    public enum ModelLoadMode {
        FULL_QUALITY("Full quality"),
        COMPATIBILITY("Memory saver"),
        LEGACY_COMPATIBILITY("Last resort");

        private final String label;

        ModelLoadMode(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    private final Context context;
    private final SharedPreferences preferences;
    private final File modelsDir;
    private final List<VoskModelInfo> catalog = new ArrayList<>();

    public VoskModelManager(Context context) {
        this.context = context.getApplicationContext();
        this.preferences = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        File externalDir = this.context.getExternalFilesDir(null);
        this.modelsDir = new File(externalDir != null ? externalDir : this.context.getFilesDir(), "vosk_models");
        if (!modelsDir.exists()) {
            modelsDir.mkdirs();
        }
    }

    public List<VoskModelInfo> loadCatalog() throws IOException {
        catalog.clear();
        try (InputStream inputStream = context.getAssets().open("vosk_models.json")) {
            String json = readText(inputStream);
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                catalog.add(VoskModelInfo.fromJson(array.getJSONObject(i)));
            }
        } catch (Exception e) {
            throw new IOException("Could not load Vosk model catalog", e);
        }

        Collections.sort(catalog, Comparator.comparingInt(this::catalogPriority)
                .thenComparing(VoskModelInfo::getLanguage)
                .thenComparing(VoskModelInfo::getId));
        return new ArrayList<>(catalog);
    }

    private int catalogPriority(VoskModelInfo modelInfo) {
        if (modelInfo == null) {
            return 100;
        }
        if (DEFAULT_MODEL_ID.equals(modelInfo.getId())) {
            return 0;
        }
        if ("whisper-base-en".equals(modelInfo.getId())) {
            return 1;
        }
        if ("whisper-base".equals(modelInfo.getId())) {
            return 2;
        }
        return 100;
    }

    public List<VoskModelInfo> getCatalog() {
        return new ArrayList<>(catalog);
    }

    public List<VoskModelInfo> getInstalledModels() {
        List<VoskModelInfo> installed = new ArrayList<>();
        for (VoskModelInfo modelInfo : catalog) {
            if (isInstalled(modelInfo.getId())) {
                installed.add(modelInfo);
            }
        }
        return installed;
    }

    public VoskModelInfo getSelectedModel() {
        ensureCatalogLoaded();
        String selectedId = preferences.getString(KEY_SELECTED_MODEL_ID, DEFAULT_MODEL_ID);
        VoskModelInfo selected = findById(selectedId);
        if (selected != null && isInstalled(selected.getId())) {
            return selected;
        }
        VoskModelInfo fallback = findById(DEFAULT_MODEL_ID);
        if (fallback == null && !catalog.isEmpty()) {
            fallback = catalog.get(0);
        }
        if (fallback != null) {
            selectModel(fallback.getId());
        }
        return fallback;
    }

    public void selectModel(String modelId) {
        preferences.edit().putString(KEY_SELECTED_MODEL_ID, modelId).apply();
    }



    public boolean isInstalled(String modelId) {
        VoskModelInfo modelInfo = findById(modelId);
        if (modelInfo == null) {
            return false;
        }
        if (modelInfo.isBundled()) {
            return true;
        }
        return isNonEmptyDirectory(getModelDirectory(modelInfo));
    }

    public File getModelDirectory(VoskModelInfo modelInfo) {
        return new File(modelsDir, modelInfo.getId());
    }

    public void prepareForMobileLoad(VoskModelInfo modelInfo) {
        if (modelInfo == null || modelInfo.isBundled() || modelInfo.isWhisper()) {
            return;
        }

        File modelDirectory = getModelDirectory(modelInfo);
        restoreOptionalFolders(modelInfo);

        ModelLoadMode loadMode = getModelLoadMode(modelInfo);
        if (loadMode == ModelLoadMode.COMPATIBILITY) {
            disableOptionalFolder(modelDirectory, "rnnlm");
        } else if (loadMode == ModelLoadMode.LEGACY_COMPATIBILITY) {
            disableOptionalFolder(modelDirectory, "rescore");
            disableOptionalFolder(modelDirectory, "rnnlm");
        }
    }

    public boolean shouldUseCompatibilityMode(VoskModelInfo modelInfo) {
        return getModelLoadMode(modelInfo) != ModelLoadMode.FULL_QUALITY;
    }

    public ModelLoadMode getModelLoadMode(VoskModelInfo modelInfo) {
        if (modelInfo == null) {
            return ModelLoadMode.FULL_QUALITY;
        }

        String storedMode = preferences.getString(KEY_LOAD_MODE_PREFIX + modelInfo.getId(), null);
        if (storedMode != null) {
            try {
                return ModelLoadMode.valueOf(storedMode);
            } catch (IllegalArgumentException ignored) {
                return ModelLoadMode.FULL_QUALITY;
            }
        }

        boolean oldCompatibility = preferences.getBoolean(KEY_COMPATIBILITY_PREFIX + modelInfo.getId(), false);
        return oldCompatibility ? ModelLoadMode.COMPATIBILITY : ModelLoadMode.FULL_QUALITY;
    }

    public void setCompatibilityMode(VoskModelInfo modelInfo, boolean enabled) {
        setModelLoadMode(modelInfo, enabled ? ModelLoadMode.COMPATIBILITY : ModelLoadMode.FULL_QUALITY);
    }

    public void setModelLoadMode(VoskModelInfo modelInfo, ModelLoadMode loadMode) {
        if (modelInfo == null) {
            return;
        }
        ModelLoadMode mode = loadMode == null ? ModelLoadMode.FULL_QUALITY : loadMode;
        preferences.edit()
                .putString(KEY_LOAD_MODE_PREFIX + modelInfo.getId(), mode.name())
                .putBoolean(KEY_COMPATIBILITY_PREFIX + modelInfo.getId(), mode != ModelLoadMode.FULL_QUALITY)
                .apply();
        prepareForMobileLoad(modelInfo);
    }

    public void restoreOptionalFolders(VoskModelInfo modelInfo) {
        if (modelInfo == null || modelInfo.isBundled() || modelInfo.isWhisper()) {
            return;
        }
        File modelDirectory = getModelDirectory(modelInfo);
        restoreOptionalFolder(modelDirectory, "rescore");
        restoreOptionalFolder(modelDirectory, "rnnlm");
    }

    public DownloadTask downloadModel(String modelId, DownloadCallback callback) {
        ensureCatalogLoaded();
        VoskModelInfo modelInfo = findById(modelId);
        DownloadTask task = new DownloadTask(modelInfo, callback);
        new Thread(task, "vosk-model-download").start();
        return task;
    }

    public boolean deleteModel(String modelId) {
        VoskModelInfo modelInfo = findById(modelId);
        if (modelInfo == null || modelInfo.isBundled()) {
            return false;
        }
        File zipFile = new File(modelsDir, modelId + ".download");
        if (zipFile.exists()) {
            zipFile.delete();
        }
        clearDownloadProgress(modelId);
        File target = getModelDirectory(modelInfo);
        boolean deleted = deleteRecursively(target);
        if (modelId.equals(preferences.getString(KEY_SELECTED_MODEL_ID, DEFAULT_MODEL_ID))) {
            selectModel(DEFAULT_MODEL_ID);
        }
        return deleted;
    }

    public VoskModelInfo findById(String modelId) {
        for (VoskModelInfo modelInfo : catalog) {
            if (modelInfo.getId().equals(modelId)) {
                return modelInfo;
            }
        }
        return null;
    }

    public List<VoskModelInfo> search(String query) {
        ensureCatalogLoaded();
        String normalizedQuery = query == null ? "" : query.toLowerCase(Locale.US).trim();
        if (normalizedQuery.isEmpty()) {
            return getCatalog();
        }

        List<VoskModelInfo> results = new ArrayList<>();
        for (VoskModelInfo modelInfo : catalog) {
            String haystack = (modelInfo.getLanguage() + " " + modelInfo.getId() + " "
                    + modelInfo.getLocale() + " " + modelInfo.getLicense()).toLowerCase(Locale.US);
            if (haystack.contains(normalizedQuery)) {
                results.add(modelInfo);
            }
        }
        return results;
    }

    private void ensureCatalogLoaded() {
        if (!catalog.isEmpty()) {
            return;
        }
        try {
            loadCatalog();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void unzipSafely(File zipFile, File destination) throws IOException {
        File stagingDir = new File(destination.getParentFile(), destination.getName() + ".extracting");
        deleteRecursively(stagingDir);
        if (!stagingDir.mkdirs()) {
            throw new IOException("Could not create model staging directory");
        }

        try (ZipInputStream zipInputStream = new ZipInputStream(new BufferedInputStream(new java.io.FileInputStream(zipFile)))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            String stagingPath = stagingDir.getCanonicalPath() + File.separator;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                File outputFile = new File(stagingDir, entry.getName());
                String outputPath = outputFile.getCanonicalPath();
                if (!outputPath.startsWith(stagingPath)) {
                    throw new IOException("Unsafe zip entry: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    if (!outputFile.exists() && !outputFile.mkdirs()) {
                        throw new IOException("Could not create directory: " + outputFile.getName());
                    }
                } else {
                    File parent = outputFile.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        throw new IOException("Could not create directory: " + parent.getName());
                    }
                    try (BufferedOutputStream outputStream = new BufferedOutputStream(new FileOutputStream(outputFile))) {
                        int count;
                        while ((count = zipInputStream.read(buffer)) != -1) {
                            outputStream.write(buffer, 0, count);
                        }
                    }
                }
                zipInputStream.closeEntry();
            }
        }

        File extractedModelDirectory = chooseExtractedModelRoot(stagingDir);
        deleteRecursively(destination);
        if (!extractedModelDirectory.renameTo(destination)) {
            copyDirectory(extractedModelDirectory, destination);
        }
        deleteRecursively(stagingDir);
    }

    private File chooseExtractedModelRoot(File directory) {
        File[] children = directory.listFiles();
        if (children == null) {
            return directory;
        }
        File onlyDirectory = null;
        int directoryCount = 0;
        int fileCount = 0;
        for (File child : children) {
            if (child.isDirectory()) {
                onlyDirectory = child;
                directoryCount++;
            } else if (child.isFile()) {
                fileCount++;
            }
        }
        if (directoryCount == 1 && fileCount == 0) {
            return onlyDirectory;
        }
        return directory;
    }

    private boolean isNonEmptyDirectory(File directory) {
        if (directory == null || !directory.isDirectory()) {
            return false;
        }
        File[] children = directory.listFiles();
        return children != null && children.length > 0;
    }

    private void disableOptionalFolder(File modelDirectory, String folderName) {
        File folder = new File(modelDirectory, folderName);
        if (!folder.isDirectory()) {
            return;
        }

        File disabledFolder = new File(modelDirectory, folderName + ".disabled");
        if (disabledFolder.exists()) {
            return;
        }

        folder.renameTo(disabledFolder);
    }

    private void restoreOptionalFolder(File modelDirectory, String folderName) {
        File folder = new File(modelDirectory, folderName);
        File disabledFolder = new File(modelDirectory, folderName + ".disabled");
        if (folder.exists() || !disabledFolder.isDirectory()) {
            return;
        }
        disabledFolder.renameTo(folder);
    }

    private void copyDirectory(File source, File destination) throws IOException {
        if (source.isDirectory()) {
            if (!destination.exists() && !destination.mkdirs()) {
                throw new IOException("Could not create directory: " + destination.getName());
            }
            File[] children = source.listFiles();
            if (children != null) {
                for (File child : children) {
                    copyDirectory(child, new File(destination, child.getName()));
                }
            }
            return;
        }

        try (InputStream inputStream = new BufferedInputStream(new java.io.FileInputStream(source));
             FileOutputStream outputStream = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
        }
    }

    private boolean deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return true;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        return file.delete();
    }

    private String readText(InputStream inputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, read);
        }
        return outputStream.toString(StandardCharsets.UTF_8.name());
    }

    public boolean hasPartialDownload(String modelId) {
        File zipFile = new File(modelsDir, modelId + ".download");
        return zipFile.exists() && zipFile.length() > 0;
    }

    public void setDownloadProgress(String modelId, int progress) {
        preferences.edit().putInt("download_progress_" + modelId, progress).apply();
    }

    public int getDownloadProgress(String modelId) {
        return preferences.getInt("download_progress_" + modelId, 0);
    }

    public void clearDownloadProgress(String modelId) {
        preferences.edit().remove("download_progress_" + modelId).apply();
    }

    public interface DownloadCallback {
        void onProgress(int progress, long bytesDownloaded, long totalBytes);
        void onComplete(VoskModelInfo modelInfo);
        void onCancelled();
        void onPaused();
        void onError(String errorMessage);
    }

    public class DownloadTask implements Runnable {
        private final VoskModelInfo modelInfo;
        private final DownloadCallback callback;
        private volatile boolean cancelled = false;
        private volatile boolean paused = false;
        private volatile HttpURLConnection activeConnection;
        private volatile InputStream activeInputStream;

        DownloadTask(VoskModelInfo modelInfo, DownloadCallback callback) {
            this.modelInfo = modelInfo;
            this.callback = callback;
        }

        public void cancel() {
            cancelled = true;
            closeActiveConnection();
        }

        public void pause() {
            paused = true;
            closeActiveConnection();
        }

        public boolean isPaused() {
            return paused;
        }

        @Override
        public void run() {
            if (modelInfo == null) {
                callback.onError("Unknown model");
                return;
            }
            if (modelInfo.getDownloadUrl().isEmpty()) {
                callback.onError("This model does not have a download URL");
                return;
            }

            File zipFile = new File(modelsDir, modelInfo.getId() + ".download");
            File targetDir = getModelDirectory(modelInfo);
            HttpURLConnection connection = null;

            try {
                long existingBytes = zipFile.exists() ? zipFile.length() : 0;
                if (existingBytes == 0) {
                    deleteRecursively(targetDir);
                }
                
                URL url = new URL(modelInfo.getDownloadUrl());
                connection = (HttpURLConnection) url.openConnection();
                activeConnection = connection;
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                
                if (existingBytes > 0) {
                    connection.setRequestProperty("Range", "bytes=" + existingBytes + "-");
                }
                connection.connect();

                int responseCode = connection.getResponseCode();
                boolean isPartial = (responseCode == HttpURLConnection.HTTP_PARTIAL);
                
                if (responseCode < 200 || responseCode >= 300) {
                    throw new IOException("Download failed with HTTP " + responseCode);
                }

                long totalBytes = connection.getContentLengthLong();
                String contentRange = connection.getHeaderField("Content-Range");
                if (contentRange != null) {
                    try {
                        int slashIndex = contentRange.lastIndexOf('/');
                        if (slashIndex >= 0) {
                            totalBytes = Long.parseLong(contentRange.substring(slashIndex + 1));
                        }
                    } catch (Exception ignored) {}
                } else if (isPartial && totalBytes > 0) {
                    totalBytes += existingBytes;
                }

                long downloadedBytes = isPartial ? existingBytes : 0;
                byte[] buffer = new byte[8192];
                int lastProgress = -1;

                try (InputStream inputStream = new BufferedInputStream(connection.getInputStream());
                     FileOutputStream outputStream = new FileOutputStream(zipFile, isPartial)) {
                    activeInputStream = inputStream;
                    int read;
                    while ((read = inputStream.read(buffer)) != -1) {
                        if (cancelled) {
                            callback.onCancelled();
                            return;
                        }
                        if (paused) {
                            callback.onPaused();
                            return;
                        }
                        outputStream.write(buffer, 0, read);
                        downloadedBytes += read;
                        int progress = totalBytes > 0 ? (int) ((downloadedBytes * 100) / totalBytes) : 0;
                        if (progress != lastProgress) {
                            lastProgress = progress;
                            setDownloadProgress(modelInfo.getId(), progress);
                            callback.onProgress(progress, downloadedBytes, totalBytes);
                        }
                    }
                }
                activeInputStream = null;

                if (cancelled) {
                    callback.onCancelled();
                    return;
                }
                if (paused) {
                    callback.onPaused();
                    return;
                }

                if (modelInfo.isWhisper()) {
                    if (!targetDir.exists() && !targetDir.mkdirs()) {
                        throw new IOException("Could not create model directory");
                    }
                    File destinationFile = new File(targetDir, modelInfo.getId() + ".bin");
                    if (!zipFile.renameTo(destinationFile)) {
                        copyDirectory(zipFile, destinationFile);
                        zipFile.delete();
                    }
                } else {
                    unzipSafely(zipFile, targetDir);
                    if (!isNonEmptyDirectory(targetDir)) {
                        throw new IOException("Downloaded archive was empty");
                    }
                }
                clearDownloadProgress(modelInfo.getId());
                callback.onProgress(100, downloadedBytes, totalBytes);
                callback.onComplete(modelInfo);
            } catch (Exception e) {
                if (paused) {
                    callback.onPaused();
                    return;
                }
                if (cancelled) {
                    callback.onCancelled();
                    return;
                }
                if (!paused) {
                    deleteRecursively(targetDir);
                    deleteRecursively(new File(modelsDir, modelInfo.getId() + ".extracting"));
                    clearDownloadProgress(modelInfo.getId());
                }
                callback.onError(e.getMessage() == null ? "Model download failed" : e.getMessage());
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
                activeConnection = null;
                activeInputStream = null;
                if (zipFile.exists() && !paused) {
                    zipFile.delete();
                    clearDownloadProgress(modelInfo.getId());
                }
                if (cancelled) {
                    deleteRecursively(targetDir);
                    deleteRecursively(new File(modelsDir, modelInfo.getId() + ".extracting"));
                    clearDownloadProgress(modelInfo.getId());
                }
            }
        }

        private void closeActiveConnection() {
            try {
                if (activeInputStream != null) {
                    activeInputStream.close();
                }
            } catch (Exception ignored) {
            }
            HttpURLConnection connection = activeConnection;
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
