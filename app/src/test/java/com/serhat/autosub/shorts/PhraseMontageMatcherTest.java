package com.serhat.autosub.shorts;

import com.serhat.autosub.*;
import com.serhat.autosub.core.*;
import com.serhat.autosub.exports.*;
import com.serhat.autosub.models.*;
import com.serhat.autosub.queue.*;
import com.serhat.autosub.service.*;
import com.serhat.autosub.shorts.*;
import com.serhat.autosub.subtitles.*;
import com.serhat.autosub.ui.common.*;
import com.serhat.autosub.ui.generate.*;
import com.serhat.autosub.ui.main.*;
import com.serhat.autosub.ui.preview.*;
import com.serhat.autosub.ui.settings.*;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class PhraseMontageMatcherTest {
    @Test public void findsEveryPhraseUsingExactWordTimings() {
        List<SubtitleGenerator.SubtitleEntry> entries = Arrays.asList(
                cue(1, 0, 2000, "hello world", word("hello", 100, 500), word("world", 600, 1000)),
                cue(2, 3000, 5000, "hello world", word("hello", 3200, 3600), word("world", 3700, 4100)));

        List<PhraseMontageMatcher.Match> matches =
                PhraseMontageMatcher.findMatches(entries, "hello world", false);

        assertEquals(2, matches.size());
        assertEquals(100, matches.get(0).getStartMs());
        assertEquals(1000, matches.get(0).getEndMs());
        assertEquals(3200, matches.get(1).getStartMs());
        assertEquals(4100, matches.get(1).getEndMs());
    }

    @Test public void canExpandMatchToWholeSubtitleCue() {
        List<SubtitleGenerator.SubtitleEntry> entries = Arrays.asList(
                cue(1, 1000, 3000, "say hello now", word("say", 1100, 1400),
                        word("hello", 1500, 2000), word("now", 2100, 2500)));

        List<PhraseMontageMatcher.Match> matches =
                PhraseMontageMatcher.findMatches(entries, "hello", true);

        assertEquals(1, matches.size());
        assertEquals(1000, matches.get(0).getStartMs());
        assertEquals(3000, matches.get(0).getEndMs());
    }

    private static SubtitleGenerator.SubtitleEntry cue(int number, long start, long end, String text,
                                                        WordTiming... words) {
        return new SubtitleGenerator.SubtitleEntry(number, time(start), time(end), text,
                new ArrayList<>(Arrays.asList(words)));
    }

    private static WordTiming word(String text, long start, long end) {
        return new WordTiming(text, start, end, 1.0);
    }

    private static String time(long ms) {
        long seconds = ms / 1000;
        return String.format("00:00:%02d,%03d", seconds, ms % 1000);
    }
}
