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

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.Arrays;

import static org.junit.Assert.*;

public class ShortsProjectStoreTest {
    private Context context;
    private ShortsProjectStore store;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.deleteDatabase("autosub_shorts.db");
        store = new ShortsProjectStore(context);
    }

    @After public void tearDown() {
        store.close();
        context.deleteDatabase("autosub_shorts.db");
    }

    @Test public void projectAndCandidateRoundTrip() {
        ShortsProject project = new ShortsProject(42, "tutorials", 5, 20, 60);
        ShortsCandidate candidate = new ShortsCandidate(1, 4, 1_000, 25_000, "Title", "Hook", "Reason", 91);
        candidate.setCropPosition(0.8f);
        candidate.setCropKeyframes(Arrays.asList(
                new ShortsCropKeyframe(0, 0.2f),
                new ShortsCropKeyframe(5_000, 0.7f)));
        candidate.setCaptionLayer(SubtitleGenerator.SubtitleLayerMode.DOUBLE);
        project.setCandidates(Collections.singletonList(candidate));
        store.save(project);

        ShortsProject loaded = store.loadForQueueItem(42);
        assertNotNull(loaded);
        assertEquals(1, loaded.getCandidates().size());
        assertEquals(0.8f, loaded.getCandidates().get(0).getCropPosition(), 0.001f);
        assertEquals(2, loaded.getCandidates().get(0).getCropKeyframes().size());
        assertEquals(0.7f, loaded.getCandidates().get(0).getCropKeyframes().get(1).getPosition(), 0.001f);
        assertEquals(SubtitleGenerator.SubtitleLayerMode.DOUBLE, loaded.getCandidates().get(0).getCaptionLayer());
    }
}
