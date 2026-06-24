package com.serhat.autosub;

import com.google.gson.Gson;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class ShortsMediaTest {

    @Test public void cropFilterClampsAndProducesVerticalCanvas() {
        String left = SubtitleGenerator.buildVerticalCropFilter(-5f);
        String right = SubtitleGenerator.buildVerticalCropFilter(5f);
        assertTrue(left.contains("crop=1080:1920"));
        assertTrue(left.contains("*0.0000"));
        assertTrue(right.contains("*1.0000"));
    }

    @Test public void modelSizeFormattingSupportsLargeFiles() {
        assertTrue(GemmaModelManager.formatBytes(GemmaModelManager.EXPECTED_SIZE).contains("GB"));
    }

    @Test public void cropKeyframesHardCutAndBuildSteppedFilter() {
        ShortsCandidate candidate = new ShortsCandidate(1, 2, 0, 20_000, "", "", "", 80);
        candidate.setCropKeyframes(Arrays.asList(
                new ShortsCropKeyframe(0, 0.2f),
                new ShortsCropKeyframe(10_000, 0.8f)));

        assertEquals(0.2f, candidate.getCropPositionAt(5_000), 0.001f);
        assertEquals(0.8f, candidate.getCropPositionAt(10_000), 0.001f);
        String filter = SubtitleGenerator.buildVerticalCropFilter(candidate);
        assertTrue(filter.contains("crop=1080:1920"));
        assertTrue(filter.contains("lt(t\\,10.000)"));
        assertFalse(filter.contains("clip("));
    }

    @Test public void faceCenterMapsToHorizontalCropRange() {
        assertEquals(0f, ShortsAutoFramer.cropPositionForFace(100, 1920, 1080), 0.001f);
        assertEquals(0.5f, ShortsAutoFramer.cropPositionForFace(960, 1920, 1080), 0.001f);
        assertEquals(1f, ShortsAutoFramer.cropPositionForFace(1820, 1920, 1080), 0.001f);
        assertEquals(0.5f, ShortsAutoFramer.cropPositionForFace(540, 1080, 1920), 0.001f);
    }

    @Test public void automaticFramingRecordsAnInstantPositionChange() {
        List<ShortsCropKeyframe> frames = new ArrayList<>();
        frames.add(new ShortsCropKeyframe(0, 0.2f));

        ShortsAutoFramer.addSparseKeyframe(frames, 5_000, 0.8f);

        assertEquals(2, frames.size());
        assertEquals(5_000, frames.get(1).getTimeMs());
        assertEquals(0.8f, frames.get(1).getPosition(), 0.001f);

        ShortsCandidate candidate = new ShortsCandidate(1, 2, 0, 20_000, "", "", "", 80);
        candidate.setCropKeyframes(frames);
        assertEquals(0.2f, candidate.getCropPositionAt(4_000), 0.001f);
        assertEquals(0.8f, candidate.getCropPositionAt(5_000), 0.001f);
    }

    @Test public void faceDebugBoundsSurviveProjectJsonStorage() {
        ShortsCropKeyframe original = new ShortsCropKeyframe(1_200, 0.7f,
                0.72f, 0.38f, 0.18f, 0.32f);

        ShortsCropKeyframe restored = new Gson().fromJson(
                new Gson().toJson(original), ShortsCropKeyframe.class);

        assertTrue(restored.hasFaceBounds());
        assertEquals(0.72f, restored.getFaceCenterX(), 0.001f);
        assertEquals(0.38f, restored.getFaceCenterY(), 0.001f);
        assertEquals(0.18f, restored.getFaceWidth(), 0.001f);
        assertEquals(0.32f, restored.getFaceHeight(), 0.001f);
    }
}
