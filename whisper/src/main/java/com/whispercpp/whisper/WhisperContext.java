package com.whispercpp.whisper;

import android.content.res.AssetManager;
import android.content.Context;
import android.os.Build;
import com.whispercpp.DebugLog;
import com.whispercpp.BuildConfig;

import java.io.File;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class WhisperContext {
    private static final String LOG_TAG = "LibWhisper";

    private long ptr;
    private final Object releaseLock = new Object();
    private volatile boolean released = false;
    private volatile String detectedLanguage;

    // Meet Whisper C++ constraint: Don't access from more than one thread at a time.
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private WhisperContext(long ptr) {
        this.ptr = ptr;
    }

    public void configureThreadTuning(Context context, String modelId) {
        WhisperCpuConfig.configureThreadTuning(context, modelId,
                WhisperLib.Companion.getLoadedLibraryName());
    }

    public void stopTranscription() {
        WhisperLib.Companion.stopTranscription();
    }

    public String transcribeData(
            final float[] data,
            final String language,
            final WhisperCallback callback
    ) {
        return transcribeData(data, language, true, SubtitleGeneratorDefaults.MAX_SUBTITLE_LENGTH, true, callback);
    }

    public String transcribeData(
            final float[] data,
            final String language,
            final boolean printTimestamp,
            final WhisperCallback callback
    ) {
        return transcribeData(data, language, printTimestamp, SubtitleGeneratorDefaults.MAX_SUBTITLE_LENGTH, true, callback);
    }

    public String transcribeData(
            final float[] data,
            final String language,
            final boolean printTimestamp,
            final int maxSegmentLength,
            final WhisperCallback callback
    ) {
        return transcribeData(data, language, printTimestamp, maxSegmentLength, true, callback);
    }

    public String transcribeData(
            final float[] data,
            final String language,
            final boolean printTimestamp,
            final int maxSegmentLength,
            final boolean suppressNonSpeechTokens,
            final WhisperCallback callback
    ) {
        return runOnExecutor(new Callable<String>() {
            @Override
            public String call() {
                try {
                    runFullTranscribe(data, language, maxSegmentLength, suppressNonSpeechTokens, callback);
                    return buildTranscriptionText(printTimestamp);
                } catch (Exception e) {
                    DebugLog.e(LOG_TAG, "Error during transcription", e);
                    return "";
                }
            }
        });
    }

    public void transcribeDataWithCallbacks(
            final float[] data,
            final String language,
            final int maxSegmentLength,
            final boolean suppressNonSpeechTokens,
            final WhisperCallback callback
    ) {
        runOnExecutor(new Callable<Void>() {
            @Override
            public Void call() {
                try {
                    runFullTranscribe(data, language, maxSegmentLength, suppressNonSpeechTokens, callback);
                } catch (Exception e) {
                    DebugLog.e(LOG_TAG, "Error during transcription", e);
                }
                return null;
            }
        });
    }

    private void runFullTranscribe(
            float[] data,
            String language,
            int maxSegmentLength,
            boolean suppressNonSpeechTokens,
            WhisperCallback callback
    ) {
        if (ptr == 0L) {
            throw new IllegalStateException("Whisper context has been released");
        }

        int numThreads = WhisperCpuConfig.getPreferredThreadCount();
        DebugLog.d(LOG_TAG, "Selecting " + numThreads + " threads");

        detectedLanguage = null;
        long startMs = System.currentTimeMillis();
        WhisperLib.Companion.fullTranscribe(ptr, numThreads, data, language,
                maxSegmentLength, suppressNonSpeechTokens, callback);
        long wallMs = System.currentTimeMillis() - startMs;
        int audioSamples = data == null ? 0 : data.length;
        logTranscriptionProfile(numThreads, audioSamples, wallMs);
        WhisperCpuConfig.reportTranscriptionTiming(numThreads, audioSamples, wallMs);
        detectedLanguage = WhisperLib.Companion.getDetectedLanguage(ptr);
    }

    private void logTranscriptionProfile(int numThreads, int audioSamples, long wallMs) {
        if (audioSamples <= 0 || wallMs <= 0) {
            return;
        }

        double audioSeconds = audioSamples / 16000.0;
        double wallSeconds = wallMs / 1000.0;
        double realtimeFactor = wallSeconds / audioSeconds;
        DebugLog.i(LOG_TAG, String.format(Locale.US,
                "Whisper profile: threads=%d, audio=%.2fs, wall=%.2fs, realtimeFactor=%.2fx",
                numThreads, audioSeconds, wallSeconds, realtimeFactor));
    }

    private String buildTranscriptionText(boolean printTimestamp) {
        int textCount = WhisperLib.Companion.getTextSegmentCount(ptr);
        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < textCount; i++) {
            if (printTimestamp) {
                String textTimestamp =
                        "[" + toTimestamp(WhisperLib.Companion.getTextSegmentT0(ptr, i)) +
                                " --> " +
                                toTimestamp(WhisperLib.Companion.getTextSegmentT1(ptr, i)) +
                                "]";

                String textSegment = WhisperLib.Companion.getTextSegment(ptr, i);
                builder.append(textTimestamp)
                        .append(": ")
                        .append(textSegment)
                        .append("\n");
            } else {
                builder.append(WhisperLib.Companion.getTextSegment(ptr, i));
            }
        }

        return builder.toString();
    }

    public String getDetectedLanguage() {
        return detectedLanguage;
    }

    public String benchMemory(final int nthreads) {
        return runOnExecutor(new Callable<String>() {
            @Override
            public String call() {
                return WhisperLib.Companion.benchMemcpy(nthreads);
            }
        });
    }

    public String benchGgmlMulMat(final int nthreads) {
        return runOnExecutor(new Callable<String>() {
            @Override
            public String call() {
                return WhisperLib.Companion.benchGgmlMulMat(nthreads);
            }
        });
    }

    public void release() {
        releaseInternal(true);
    }

    public void releaseAsync() {
        releaseInternal(false);
    }

    private void releaseInternal(boolean waitForRelease) {
        synchronized (releaseLock) {
            if (released) {
                return;
            }
            released = true;
        }

        try {
            if (!executor.isShutdown()) {
                Callable<Void> releaseTask = new Callable<Void>() {
                    @Override
                    public Void call() {
                        freeContextOnCurrentThread();
                        return null;
                    }
                };
                if (waitForRelease) {
                    runOnExecutor(releaseTask);
                } else {
                    executor.submit(releaseTask);
                }
            } else {
                freeContextOnCurrentThread();
            }
        } catch (RuntimeException e) {
            DebugLog.w(LOG_TAG, "Executor rejected Whisper release; freeing context directly", e);
            freeContextOnCurrentThread();
        } finally {
            executor.shutdown();
        }
    }

    private void freeContextOnCurrentThread() {
        if (ptr != 0L) {
            WhisperLib.Companion.freeContext(ptr);
            ptr = 0L;
        }
    }

    private boolean shouldAttemptFinalizeRelease() {
        synchronized (releaseLock) {
            return !released && ptr != 0L;
        }
    }

    private <T> T runOnExecutor(Callable<T> callable) {
        try {
            Future<T> future = executor.submit(callable);
            return future.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (shouldAttemptFinalizeRelease()) {
                release();
            }
        } finally {
            super.finalize();
        }
    }

    public static WhisperContext createContextFromFile(String filePath) {
        long ptr = WhisperLib.Companion.initContext(filePath);

        if (ptr == 0L) {
            throw new RuntimeException("Couldn't create context with path " + filePath);
        }

        return new WhisperContext(ptr);
    }

    public static WhisperContext createContextFromInputStream(InputStream stream) {
        long ptr = WhisperLib.Companion.initContextFromInputStream(stream);

        if (ptr == 0L) {
            throw new RuntimeException("Couldn't create context from input stream");
        }

        return new WhisperContext(ptr);
    }

    public static WhisperContext createContextFromAsset(
            AssetManager assetManager,
            String assetPath
    ) {
        long ptr = WhisperLib.Companion.initContextFromAsset(assetManager, assetPath);

        if (ptr == 0L) {
            throw new RuntimeException("Couldn't create context from asset " + assetPath);
        }

        return new WhisperContext(ptr);
    }

    public static String getSystemInfo() {
        return WhisperLib.Companion.getSystemInfo();
    }

    // 500 -> 00:00:05.000
    // 6000 -> 00:01:00.000
    private static String toTimestamp(long t) {
        return toTimestamp(t, false);
    }

    private static String toTimestamp(long t, boolean comma) {
        long msec = t * 10;

        long hr = msec / (1000 * 60 * 60);
        msec -= hr * (1000 * 60 * 60);

        long min = msec / (1000 * 60);
        msec -= min * (1000 * 60);

        long sec = msec / 1000;
        msec -= sec * 1000;

        String delimiter = comma ? "," : ".";

        return String.format(
                Locale.US,
                "%02d:%02d:%02d%s%03d",
                hr,
                min,
                sec,
                delimiter,
                msec
        );
    }
}

class WhisperLib {
    public static final Companion Companion = new Companion();

    public static class Companion {
        private static final String LOG_TAG = "LibWhisper";
        private static String loadedLibraryName = "unknown";

        static {
            DebugLog.d(LOG_TAG, "Primary ABI: " + Build.SUPPORTED_ABIS[0]);

            boolean loadVfpv4 = false;
            boolean loadV8fp16 = false;
            boolean loadV8fp16Dotprod = false;

            if (isArmEabiV7a()) {
                // armeabi-v7a needs runtime detection support
                String cpuInfo = cpuInfo();

                if (cpuInfo != null) {
                    DebugLog.d(LOG_TAG, "CPU info: " + cpuInfo);

                    if (hasCpuFeature(cpuInfo, "vfpv4")) {
                        DebugLog.d(LOG_TAG, "CPU supports vfpv4");
                        loadVfpv4 = true;
                    }
                }

            } else if (isArmEabiV8a()) {
                // ARMv8.2a needs runtime detection support
                String cpuInfo = cpuInfo();

                if (cpuInfo != null) {
                    DebugLog.d(LOG_TAG, "CPU info: " + cpuInfo);

                    boolean supportsFp16Arithmetic = hasCpuFeature(cpuInfo, "fphp");
                    boolean supportsDotprod = hasCpuFeature(cpuInfo, "asimddp")
                            || hasCpuFeature(cpuInfo, "dotprod");

                    if (supportsFp16Arithmetic) {
                        DebugLog.d(LOG_TAG, "CPU supports fp16 arithmetic");
                        loadV8fp16 = true;
                    }

                    if (supportsFp16Arithmetic && supportsDotprod) {
                        DebugLog.d(LOG_TAG, "CPU supports fp16 arithmetic + dotprod");
                        loadV8fp16Dotprod = true;
                    }
                }
            }

            boolean loaded = loadVfpv4 && tryLoadLibrary("whisper_vfpv4");
            loaded = loaded || (loadV8fp16Dotprod && tryLoadLibrary("whisper_v8fp16_dotprod_va"));
            loaded = loaded || (loadV8fp16 && tryLoadLibrary("whisper_v8fp16_va"));
            loaded = loaded || tryLoadLibrary("whisper");

            if (!loaded) {
                throw new UnsatisfiedLinkError("Unable to load any Whisper native library");
            }
            setDebugLogging(BuildConfig.DEBUG);
        }

        private Companion() {
        }

        public String getLoadedLibraryName() {
            return loadedLibraryName;
        }

        // JNI methods
        public static native void setDebugLogging(boolean enabled);

        public native long initContextFromInputStream(InputStream inputStream);

        public native long initContextFromAsset(AssetManager assetManager, String assetPath);

        public native long initContext(String modelPath);

        public native void freeContext(long contextPtr);

        public native void resetAbort();

        public native void stopTranscription();

        public native void fullTranscribe(
                long contextPtr,
                int numThreads,
                float[] audioData,
                String language,
                int maxSegmentLength,
                boolean suppressNonSpeechTokens,
                WhisperCallback callback
        );

        public native int getTextSegmentCount(long contextPtr);

        public native String getTextSegment(long contextPtr, int index);

        public native long getTextSegmentT0(long contextPtr, int index);

        public native long getTextSegmentT1(long contextPtr, int index);

        public native String getDetectedLanguage(long contextPtr);

        public native String getSystemInfo();

        public native String benchMemcpy(int nthread);

        public native String benchGgmlMulMat(int nthread);

        private static boolean isArmEabiV7a() {
            return Build.SUPPORTED_ABIS[0].equals("armeabi-v7a");
        }

        private static boolean isArmEabiV8a() {
            return Build.SUPPORTED_ABIS[0].equals("arm64-v8a");
        }

        private static boolean tryLoadLibrary(String libraryName) {
            try {
                DebugLog.d(LOG_TAG, "Loading lib" + libraryName + ".so");
                System.loadLibrary(libraryName);
                loadedLibraryName = libraryName;
                return true;
            } catch (UnsatisfiedLinkError e) {
                DebugLog.w(LOG_TAG, "Couldn't load lib" + libraryName + ".so", e);
                return false;
            }
        }

        private static boolean hasCpuFeature(String cpuInfo, String feature) {
            String normalizedCpuInfo = " " + cpuInfo.toLowerCase(Locale.US)
                    .replace(':', ' ')
                    .replace('\n', ' ')
                    .replace('\t', ' ') + " ";
            return normalizedCpuInfo.contains(" " + feature.toLowerCase(Locale.US) + " ");
        }

        private static String cpuInfo() {
            try {
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.FileReader(new File("/proc/cpuinfo"))
                );

                try {
                    StringBuilder builder = new StringBuilder();
                    String line;

                    while ((line = reader.readLine()) != null) {
                        builder.append(line).append("\n");
                    }

                    return builder.toString();
                } finally {
                    reader.close();
                }

            } catch (Exception e) {
                DebugLog.w(LOG_TAG, "Couldn't read /proc/cpuinfo", e);
                return null;
            }
        }
    }
}

final class SubtitleGeneratorDefaults {
    static final int MAX_SUBTITLE_LENGTH = 42;

    private SubtitleGeneratorDefaults() {
    }
}
