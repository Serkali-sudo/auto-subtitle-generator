package com.serhat.autosub;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SubtitleGeneratorTimingTest {

    @Test
    public void talkOnlyTextDetectionAcceptsEveryScriptButRejectsSymbols() {
        assertFalse(SubtitleGenerator.containsLetterOrDigit("♪ ♫ 🎵"));
        assertFalse(SubtitleGenerator.containsLetterOrDigit("… — !"));
        assertTrue(SubtitleGenerator.containsLetterOrDigit("hello"));
        assertTrue(SubtitleGenerator.containsLetterOrDigit("مرحبا"));
        assertTrue(SubtitleGenerator.containsLetterOrDigit("你好"));
        assertTrue(SubtitleGenerator.containsLetterOrDigit("नमस्ते"));
        assertTrue(SubtitleGenerator.containsLetterOrDigit("123"));
    }
    @Test
    public void normalizeSubtitleTimings_trimsEarlierOverlappingCue() {
        List<WordTiming> firstWords = Arrays.asList(
                new WordTiming("first", 5280, 7000, 0.9));
        List<SubtitleGenerator.SubtitleEntry> entries = new ArrayList<>(Arrays.asList(
                new SubtitleGenerator.SubtitleEntry(
                        1, "00:00:05,280", "00:00:07,000", "first", firstWords),
                new SubtitleGenerator.SubtitleEntry(
                        2, "000:00:06:900", "00:00:08,200", "second")));

        SubtitleGenerator.normalizeSubtitleTimings(entries);

        assertEquals("00:00:06,900", entries.get(0).getEndTime());
        assertEquals(6900, entries.get(0).getWords().get(0).getEndMs());
        assertEquals("000:00:06:900", entries.get(1).getStartTime());
    }

    @Test
    public void normalizeSubtitleTimings_keepsNonOverlappingCuesUnchanged() {
        List<SubtitleGenerator.SubtitleEntry> entries = new ArrayList<>(Arrays.asList(
                new SubtitleGenerator.SubtitleEntry(
                        1, "00:00:05,280", "00:00:06,800", "first"),
                new SubtitleGenerator.SubtitleEntry(
                        2, "00:00:06,900", "00:00:08,200", "second")));

        SubtitleGenerator.normalizeSubtitleTimings(entries);

        assertEquals("00:00:06,800", entries.get(0).getEndTime());
    }
}
