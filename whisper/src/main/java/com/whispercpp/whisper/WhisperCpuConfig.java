package com.whispercpp.whisper;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

public final class WhisperCpuConfig {
    private static final String LOG_TAG = "WhisperCpuConfig";
    private static final String PREFS_NAME = "whisper_cpu_tuning";
    private static final String KEY_THREAD_COUNT_PREFIX = "thread_count:";
    private static final String TUNING_CACHE_VERSION = "v2";
    private static final int PREFERRED_MIN_THREAD_COUNT = 4;
    private static final int PREFERRED_MAX_THREAD_COUNT = 6;
    private static final int MIN_TIMED_AUDIO_SAMPLES = 16000 * 8;
    private static final double MIN_GAIN_FOR_MORE_THREADS = 1.05;

    private static Context appContext;
    private static String activeCacheKey;
    private static int cachedThreadCount;
    private static int[] trialThreadCounts = new int[0];
    private static double[] trialThroughput = new double[0];
    private static int trialIndex;

    private WhisperCpuConfig() {
    }

    public static synchronized void configureThreadTuning(Context context, String modelId) {
        configureThreadTuning(context, modelId, "unknown");
    }

    public static synchronized void configureThreadTuning(
            Context context,
            String modelId,
            String nativeLibraryId
    ) {
        if (context == null) {
            return;
        }

        appContext = context.getApplicationContext();
        String nextCacheKey = buildCacheKey(modelId, nativeLibraryId);
        if (nextCacheKey.equals(activeCacheKey)) {
            return;
        }

        activeCacheKey = nextCacheKey;
        cachedThreadCount = readCachedThreadCount(nextCacheKey);
        trialThreadCounts = cachedThreadCount > 0
                ? new int[0]
                : buildTrialThreadCounts(computeHeuristicThreadCount());
        trialThroughput = new double[trialThreadCounts.length];
        trialIndex = 0;

        if (cachedThreadCount > 0) {
            Log.i(LOG_TAG, "Using cached Whisper thread count " + cachedThreadCount);
        } else {
            Log.i(LOG_TAG, "Starting Whisper thread auto-tune candidates: "
                    + formatCandidates(trialThreadCounts));
        }
    }

    // Prefer big cores, but do not ask whisper.cpp for more workers than the CPU can run well.
    public static synchronized int getPreferredThreadCount() {
        if (cachedThreadCount > 0) {
            return cachedThreadCount;
        }
        if (trialIndex < trialThreadCounts.length) {
            return trialThreadCounts[trialIndex];
        }
        return computeHeuristicThreadCount();
    }

    public static synchronized void reportTranscriptionTiming(int threads, int audioSamples, long wallTimeMs) {
        if (cachedThreadCount > 0
                || activeCacheKey == null
                || trialIndex >= trialThreadCounts.length
                || threads != trialThreadCounts[trialIndex]
                || audioSamples < MIN_TIMED_AUDIO_SAMPLES
                || wallTimeMs <= 0) {
            return;
        }

        double samplesPerMs = audioSamples / (double) wallTimeMs;
        trialThroughput[trialIndex] = samplesPerMs;
        Log.i(LOG_TAG, "Whisper thread auto-tune sample: threads=" + threads
                + ", audioMs=" + (audioSamples * 1000L / 16000L)
                + ", wallMs=" + wallTimeMs
                + ", samplesPerMs=" + String.format(java.util.Locale.US, "%.2f", samplesPerMs));

        trialIndex++;
        if (trialIndex < trialThreadCounts.length) {
            return;
        }

        int bestThreadCount = chooseBestThreadCount();
        double bestThroughput = throughputForThreadCount(bestThreadCount);

        if (bestThroughput > 0.0) {
            cachedThreadCount = bestThreadCount;
            writeCachedThreadCount(activeCacheKey, cachedThreadCount);
            Log.i(LOG_TAG, "Cached Whisper thread count " + cachedThreadCount
                    + " for " + activeCacheKey);
        }
    }

    private static int computeHeuristicThreadCount() {
        int availableProcessors = Math.max(1, Runtime.getRuntime().availableProcessors());
        int highPerfCpuCount = CpuInfo.getHighPerfCpuCount();
        int candidate = highPerfCpuCount > 0
                ? highPerfCpuCount
                : Math.min(PREFERRED_MIN_THREAD_COUNT, availableProcessors);

        if (availableProcessors >= PREFERRED_MIN_THREAD_COUNT) {
            candidate = Math.max(candidate, PREFERRED_MIN_THREAD_COUNT);
        }

        return Math.max(1, Math.min(candidate,
                Math.min(availableProcessors, PREFERRED_MAX_THREAD_COUNT)));
    }

    private static String buildCacheKey(String modelId, String nativeLibraryId) {
        String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown";
        String normalizedModelId = modelId == null || modelId.trim().isEmpty()
                ? "unknown"
                : modelId.trim();
        String normalizedNativeLibraryId = nativeLibraryId == null || nativeLibraryId.trim().isEmpty()
                ? "unknown"
                : nativeLibraryId.trim();
        return TUNING_CACHE_VERSION + "|" + Build.FINGERPRINT + "|" + abi + "|" + normalizedNativeLibraryId
                + "|" + normalizedModelId;
    }

    private static int readCachedThreadCount(String cacheKey) {
        if (appContext == null || cacheKey == null) {
            return 0;
        }
        int cached = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_THREAD_COUNT_PREFIX + cacheKey, 0);
        int maxThreads = Math.min(Math.max(1, Runtime.getRuntime().availableProcessors()),
                PREFERRED_MAX_THREAD_COUNT);
        return cached >= 1 && cached <= maxThreads ? cached : 0;
    }

    private static void writeCachedThreadCount(String cacheKey, int threadCount) {
        if (appContext == null || cacheKey == null || threadCount <= 0) {
            return;
        }
        SharedPreferences preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        preferences.edit().putInt(KEY_THREAD_COUNT_PREFIX + cacheKey, threadCount).apply();
    }

    private static int[] buildTrialThreadCounts(int heuristic) {
        int maxThreads = Math.min(Math.max(1, Runtime.getRuntime().availableProcessors()),
                PREFERRED_MAX_THREAD_COUNT);
        int[] rawCandidates = {
                heuristic,
                heuristic > 1 ? heuristic - 1 : heuristic,
                heuristic < maxThreads ? heuristic + 1 : heuristic,
                Math.min(PREFERRED_MIN_THREAD_COUNT, maxThreads)
        };

        List<Integer> unique = new ArrayList<>();
        for (int candidate : rawCandidates) {
            int bounded = Math.max(1, Math.min(candidate, maxThreads));
            if (!unique.contains(bounded)) {
                unique.add(bounded);
            }
        }

        int[] result = new int[unique.size()];
        for (int i = 0; i < unique.size(); i++) {
            result[i] = unique.get(i);
        }
        return result;
    }

    private static int chooseBestThreadCount() {
        int bestThreadCount = trialThreadCounts[0];
        double bestThroughput = trialThroughput[0];
        for (int i = 1; i < trialThreadCounts.length; i++) {
            if (trialThroughput[i] > bestThroughput) {
                bestThroughput = trialThroughput[i];
                bestThreadCount = trialThreadCounts[i];
            }
        }

        for (int i = 0; i < trialThreadCounts.length; i++) {
            int lowerThreadCount = trialThreadCounts[i];
            double lowerThroughput = trialThroughput[i];
            if (lowerThreadCount >= bestThreadCount || lowerThroughput <= 0.0) {
                continue;
            }
            if (bestThroughput < lowerThroughput * MIN_GAIN_FOR_MORE_THREADS) {
                bestThreadCount = lowerThreadCount;
                bestThroughput = lowerThroughput;
            }
        }

        return bestThreadCount;
    }

    private static double throughputForThreadCount(int threadCount) {
        for (int i = 0; i < trialThreadCounts.length; i++) {
            if (trialThreadCounts[i] == threadCount) {
                return trialThroughput[i];
            }
        }
        return 0.0;
    }

    private static String formatCandidates(int[] candidates) {
        if (candidates == null || candidates.length == 0) {
            return "[]";
        }
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < candidates.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(candidates[i]);
        }
        return builder.append(']').toString();
    }
}

class CpuInfo {
    private static final String LOG_TAG = "WhisperCpuConfig";

    private final List<String> lines;

    private CpuInfo(List<String> lines) {
        this.lines = lines;
    }

    private int getHighPerfCpuCountInternal() {
        try {
            return getHighPerfCpuCountByFrequencies();
        } catch (Exception e) {
            Log.d(LOG_TAG, "Couldn't read CPU frequencies", e);
            return getHighPerfCpuCountByVariant();
        }
    }

    private int getHighPerfCpuCountByFrequencies() {
        List<Integer> values = getCpuValues("processor", new CpuValueMapper() {
            @Override
            public int map(String value) throws Exception {
                return getMaxCpuFrequency(Integer.parseInt(value));
            }
        });

        Log.d(LOG_TAG, "Binned cpu frequencies (frequency, count): " + binnedValues(values));

        return countDroppingMin(values);
    }

    private int getHighPerfCpuCountByVariant() {
        List<Integer> values = getCpuValues("CPU variant", new CpuValueMapper() {
            @Override
            public int map(String value) {
                String hex = value;

                if (hex.contains("0x")) {
                    hex = hex.substring(hex.indexOf("0x") + 2);
                }

                return Integer.parseInt(hex, 16);
            }
        });

        Log.d(LOG_TAG, "Binned cpu variants (variant, count): " + binnedValues(values));

        return countKeepingMin(values);
    }

    private List<Integer> getCpuValues(String property, CpuValueMapper mapper) {
        List<Integer> result = new ArrayList<>();

        for (String line : lines) {
            if (line.startsWith(property)) {
                try {
                    String value = line.substring(line.indexOf(':') + 1).trim();
                    result.add(mapper.map(value));
                } catch (Exception ignored) {
                }
            }
        }

        Collections.sort(result);
        return result;
    }

    private HashMap<Integer, Integer> binnedValues(List<Integer> values) {
        HashMap<Integer, Integer> bins = new HashMap<>();

        for (Integer value : values) {
            Integer count = bins.get(value);

            if (count == null) {
                bins.put(value, 1);
            } else {
                bins.put(value, count + 1);
            }
        }

        return bins;
    }

    private int countDroppingMin(List<Integer> values) {
        if (values.isEmpty()) {
            return 0;
        }

        int min = Collections.min(values);
        int count = 0;

        for (int value : values) {
            if (value > min) {
                count++;
            }
        }

        return count == 0 ? values.size() : count;
    }

    private int countKeepingMin(List<Integer> values) {
        if (values.isEmpty()) {
            return 0;
        }

        int min = Collections.min(values);
        int count = 0;

        for (int value : values) {
            if (value == min) {
                count++;
            }
        }

        return count;
    }

    public static int getHighPerfCpuCount() {
        try {
            return readCpuInfo().getHighPerfCpuCountInternal();
        } catch (Exception e) {
            Log.d(LOG_TAG, "Couldn't read CPU info", e);

            // Our best guess -- just return the # of CPUs minus 4.
            return Math.max(Runtime.getRuntime().availableProcessors() - 4, 0);
        }
    }

    private static CpuInfo readCpuInfo() throws Exception {
        BufferedReader reader = new BufferedReader(new FileReader("/proc/cpuinfo"));

        try {
            List<String> lines = new ArrayList<>();
            String line;

            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }

            return new CpuInfo(lines);
        } finally {
            reader.close();
        }
    }

    private static int getMaxCpuFrequency(int cpuIndex) throws Exception {
        String path = "/sys/devices/system/cpu/cpu" + cpuIndex + "/cpufreq/cpuinfo_max_freq";

        BufferedReader reader = new BufferedReader(new FileReader(path));

        try {
            String maxFreq = reader.readLine();
            return Integer.parseInt(maxFreq);
        } finally {
            reader.close();
        }
    }

    private interface CpuValueMapper {
        int map(String value) throws Exception;
    }
}
