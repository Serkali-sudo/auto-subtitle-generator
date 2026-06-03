package com.whispercpp.whisper;

import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

public final class WhisperCpuConfig {
    private WhisperCpuConfig() {
    }

    // Always use at least 2 threads.
    public static int getPreferredThreadCount() {
        return Math.max(CpuInfo.getHighPerfCpuCount(), 2);
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

        return count;
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