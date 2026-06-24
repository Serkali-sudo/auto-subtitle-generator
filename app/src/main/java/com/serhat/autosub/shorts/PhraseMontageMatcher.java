package com.serhat.autosub.shorts;

import com.serhat.autosub.subtitles.SubtitleGenerator;
import com.serhat.autosub.subtitles.WordTiming;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class PhraseMontageMatcher {
    private static final long MERGE_GAP_MS = 50;

    public static final class Match {
        private final long startMs;
        private final long endMs;
        private final int startSubtitleId;
        private final int endSubtitleId;

        Match(long startMs, long endMs, int startSubtitleId, int endSubtitleId) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.startSubtitleId = startSubtitleId;
            this.endSubtitleId = endSubtitleId;
        }

        public long getStartMs() { return startMs; }
        public long getEndMs() { return endMs; }
        public int getStartSubtitleId() { return startSubtitleId; }
        public int getEndSubtitleId() { return endSubtitleId; }
    }

    private static final class TimedWord {
        final String normalized;
        final long startMs;
        final long endMs;
        final long cueStartMs;
        final long cueEndMs;
        final int cueNumber;

        TimedWord(String normalized, WordTiming word, long cueStartMs, long cueEndMs, int cueNumber) {
            this.normalized = normalized;
            this.startMs = word.getStartMs();
            this.endMs = word.getEndMs();
            this.cueStartMs = cueStartMs;
            this.cueEndMs = cueEndMs;
            this.cueNumber = cueNumber;
        }
    }

    private PhraseMontageMatcher() {}

    public static List<Match> findMatches(List<SubtitleGenerator.SubtitleEntry> entries,
                                          String phrase, boolean keepWholeSubtitle) {
        List<String> query = tokenize(phrase);
        if (query.isEmpty()) throw new IllegalArgumentException("Enter a word or phrase");

        List<TimedWord> words = new ArrayList<>();
        List<Match> fallbackCueMatches = new ArrayList<>();
        if (entries != null) {
            for (SubtitleGenerator.SubtitleEntry entry : entries) {
                if (entry == null) continue;
                long cueStart = parseTimeMs(entry.getStartTime());
                long cueEnd = parseTimeMs(entry.getEndTime());
                if (cueEnd <= cueStart) continue;
                List<WordTiming> cueWords = entry.getWords();
                if (cueWords == null || cueWords.isEmpty()) {
                    if (containsSequence(tokenize(entry.getText()), query)) {
                        fallbackCueMatches.add(new Match(cueStart, cueEnd,
                                entry.getNumber(), entry.getNumber()));
                    }
                    continue;
                }
                for (WordTiming word : cueWords) {
                    String normalized = normalize(word.getWord());
                    if (!normalized.isEmpty() && word.getEndMs() > word.getStartMs()) {
                        words.add(new TimedWord(normalized, word, cueStart, cueEnd, entry.getNumber()));
                    }
                }
            }
        }

        List<Match> matches = new ArrayList<>();
        for (int i = 0; i + query.size() <= words.size(); i++) {
            boolean matched = true;
            for (int j = 0; j < query.size(); j++) {
                if (!query.get(j).equals(words.get(i + j).normalized)) {
                    matched = false;
                    break;
                }
            }
            if (!matched) continue;
            TimedWord first = words.get(i);
            TimedWord last = words.get(i + query.size() - 1);
            matches.add(new Match(
                    keepWholeSubtitle ? first.cueStartMs : first.startMs,
                    keepWholeSubtitle ? last.cueEndMs : last.endMs,
                    first.cueNumber, last.cueNumber));
        }
        matches.addAll(fallbackCueMatches);
        matches.sort((a, b) -> Long.compare(a.startMs, b.startMs));
        return merge(matches);
    }

    private static List<Match> merge(List<Match> matches) {
        List<Match> merged = new ArrayList<>();
        for (Match match : matches) {
            if (match.endMs <= match.startMs) continue;
            if (merged.isEmpty()) {
                merged.add(match);
                continue;
            }
            Match previous = merged.get(merged.size() - 1);
            if (match.startMs <= previous.endMs + MERGE_GAP_MS) {
                merged.set(merged.size() - 1,
                        new Match(previous.startMs, Math.max(previous.endMs, match.endMs),
                                previous.startSubtitleId, match.endSubtitleId));
            } else {
                merged.add(match);
            }
        }
        return merged;
    }

    private static boolean containsSequence(List<String> source, List<String> query) {
        for (int i = 0; i + query.size() <= source.size(); i++) {
            boolean match = true;
            for (int j = 0; j < query.size(); j++) {
                if (!source.get(i + j).equals(query.get(j))) { match = false; break; }
            }
            if (match) return true;
        }
        return false;
    }

    private static List<String> tokenize(String value) {
        List<String> result = new ArrayList<>();
        if (value == null) return result;
        for (String token : value.trim().split("\\s+")) {
            String normalized = normalize(token);
            if (!normalized.isEmpty()) result.add(normalized);
        }
        return result;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}']+", "")
                .trim();
    }

    private static long parseTimeMs(String value) {
        if (value == null) return 0;
        String[] parts = value.trim().split("[:,.]");
        if (parts.length != 4) return 0;
        try {
            return Long.parseLong(parts[0]) * 3_600_000L
                    + Long.parseLong(parts[1]) * 60_000L
                    + Long.parseLong(parts[2]) * 1_000L
                    + Long.parseLong(parts[3]);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
