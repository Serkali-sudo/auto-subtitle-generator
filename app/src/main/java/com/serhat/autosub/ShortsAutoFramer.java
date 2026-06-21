package com.serhat.autosub;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import com.google.mlkit.vision.face.FaceLandmark;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Lightweight offline face following with mouth-motion active-speaker heuristics. */
public final class ShortsAutoFramer {
    private static final long SAMPLE_INTERVAL_MS = 200;
    private static final long MIN_KEYFRAME_GAP_MS = 200;

    public interface ProgressListener { void onProgress(int percent, String message); }
    public interface CancellationSignal { boolean isCancelled(); }

    private final Context context;

    public ShortsAutoFramer(Context context) { this.context = context.getApplicationContext(); }

    public List<ShortsCropKeyframe> analyze(Uri videoUri, ShortsCandidate candidate,
                                             ProgressListener progress,
                                             CancellationSignal cancellation) throws Exception {
        if (videoUri == null || candidate == null || candidate.getDurationMs() <= 0) {
            throw new IllegalArgumentException("Invalid clip for automatic framing");
        }
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setMinFaceSize(0.08f)
                .enableTracking()
                .build();
        FaceDetector detector = FaceDetection.getClient(options);
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setDataSource(context, videoUri);
        int sourceWidth = Math.max(1, metadataInt(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH, 480));
        int sourceHeight = Math.max(1, metadataInt(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT, 270));
        int rotation = metadataInt(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION, 0);
        if (rotation == 90 || rotation == 270) {
            int swap = sourceWidth; sourceWidth = sourceHeight; sourceHeight = swap;
        }
        float sampleScale = Math.min(1f, 480f / Math.max(sourceWidth, sourceHeight));
        int sampleWidth = Math.max(1, Math.round(sourceWidth * sampleScale));
        int sampleHeight = Math.max(1, Math.round(sourceHeight * sampleScale));
        Map<Integer, TrackState> tracks = new HashMap<>();
        List<ShortsCropKeyframe> keyframes = new ArrayList<>();
        long duration = candidate.getDurationMs();
        int samples = Math.max(1, (int) Math.ceil(duration / (double) SAMPLE_INTERVAL_MS));
        int nextSyntheticId = -1;
        float cropPosition = candidate.getCropPosition();
        boolean framingInitialized = false;
        int framesWithFaces = 0;

        try {
            for (int sample = 0; sample <= samples; sample++) {
                if (cancellation != null && cancellation.isCancelled()) {
                    throw new InterruptedException("Automatic framing cancelled");
                }
                long localMs = Math.min(duration, sample * SAMPLE_INTERVAL_MS);
                long sourceUs = (candidate.getStartMs() + localMs) * 1000L;
                Bitmap frame = frameAt(retriever, sourceUs, sampleWidth, sampleHeight);
                if (frame == null) continue;
                try {
                    List<Face> faces = Tasks.await(detector.process(InputImage.fromBitmap(frame, 0)),
                            12, TimeUnit.SECONDS);
                    if (!faces.isEmpty()) framesWithFaces++;
                    Set<Integer> used = new HashSet<>();
                    TrackState best = null;
                    TrackState selectedTrack = null;
                    for (Face face : faces) {
                        int id;
                        if (face.getTrackingId() != null) id = face.getTrackingId();
                        else {
                            id = nearestSyntheticTrack(tracks, used, face, frame.getWidth(), localMs);
                            if (id == Integer.MIN_VALUE) id = nextSyntheticId--;
                        }
                        used.add(id);
                        TrackState state = tracks.get(id);
                        if (state == null) { state = new TrackState(id); tracks.put(id, state); }
                        updateTrack(state, face, frame.getWidth(), frame.getHeight(), localMs);
                        if (best == null || state.score > best.score) best = state;
                    }
                    boolean justInitialized = false;
                    if (!framingInitialized && best != null) {
                        // Never begin centered while a face is already known. Lock the first
                        // visible face at t=0; speaking activity can hard-cut to another face later.
                        cropPosition = cropPositionForFace(best.centerX,
                                    frame.getWidth(), frame.getHeight());
                        framingInitialized = true;
                        justInitialized = true;
                        selectedTrack = best;
                        keyframes.add(faceKeyframe(0, cropPosition, best));
                    }
                    if (framingInitialized && !justInitialized && best != null) {
                        // A single visible face always wins immediately. With multiple faces,
                        // mouth activity decides the target without an extra temporal hold.
                        cropPosition = cropPositionForFace(best.centerX,
                                frame.getWidth(), frame.getHeight());
                        selectedTrack = best;
                    }
                    if (framingInitialized) addSparseKeyframe(keyframes, localMs, cropPosition, selectedTrack);
                    tracks.entrySet().removeIf(entry -> localMs - entry.getValue().lastSeenMs > 2_000);
                } finally {
                    frame.recycle();
                }
                if (progress != null && (sample % 3 == 0 || sample == samples)) {
                    int percent = Math.min(100, sample * 100 / samples);
                    progress.onProgress(percent, "Tracking faces " + percent + "%");
                }
            }
        } finally {
            detector.close();
            retriever.release();
        }
        if (framesWithFaces == 0) throw new IllegalStateException("No faces were found in this clip");
        if (keyframes.isEmpty()) keyframes.add(new ShortsCropKeyframe(0, candidate.getCropPosition()));
        ShortsCropKeyframe last = keyframes.get(keyframes.size() - 1);
        if (last.getTimeMs() < duration) keyframes.add(copyAt(last, duration));
        return keyframes;
    }

    private Bitmap frameAt(MediaMetadataRetriever retriever, long sourceUs, int targetWidth, int targetHeight) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            return retriever.getScaledFrameAtTime(sourceUs, MediaMetadataRetriever.OPTION_CLOSEST,
                    targetWidth, targetHeight);
        }
        Bitmap original = retriever.getFrameAtTime(sourceUs, MediaMetadataRetriever.OPTION_CLOSEST);
        if (original == null || (original.getWidth() <= targetWidth && original.getHeight() <= targetHeight)) return original;
        Bitmap scaled = Bitmap.createScaledBitmap(original, targetWidth, targetHeight, true);
        if (scaled != original) original.recycle();
        return scaled;
    }

    private static int metadataInt(MediaMetadataRetriever retriever, int key, int fallback) {
        try {
            String value = retriever.extractMetadata(key);
            return value == null ? fallback : Math.max(0, Integer.parseInt(value));
        } catch (Exception ignored) { return fallback; }
    }

    private static void updateTrack(TrackState state, Face face, int width, int height, long timeMs) {
        float centerX = face.getBoundingBox().exactCenterX();
        float normalizedCenter = centerX / Math.max(1f, width);
        float openness = mouthOpenness(face);
        float motion = state.hasMouth ? Math.min(1f, Math.abs(openness - state.lastMouth) * 22f) : 0f;
        state.activity = state.activity * 0.68f + motion * 0.32f;
        state.lastMouth = openness;
        state.hasMouth = true;
        state.centerX = centerX;
        state.normalizedCenterX = normalizedCenter;
        state.normalizedCenterY = face.getBoundingBox().exactCenterY() / Math.max(1f, height);
        state.normalizedWidth = face.getBoundingBox().width() / Math.max(1f, width);
        state.normalizedHeight = face.getBoundingBox().height() / Math.max(1f, height);
        state.lastSeenMs = timeMs;
        float area = face.getBoundingBox().width() * face.getBoundingBox().height() /
                Math.max(1f, width * (float) height);
        state.faceArea = area;
        // Raw motion makes a newly speaking face win on the current sample. The decayed
        // activity still provides continuity between mouth movements.
        state.score = Math.max(state.activity, motion * 0.9f) + Math.min(0.12f, area * 0.45f);
    }

    private static float mouthOpenness(Face face) {
        FaceLandmark mouth = face.getLandmark(FaceLandmark.MOUTH_BOTTOM);
        FaceLandmark nose = face.getLandmark(FaceLandmark.NOSE_BASE);
        if (mouth == null || nose == null || face.getBoundingBox().height() <= 0) return 0f;
        PointF mouthPoint = mouth.getPosition();
        PointF nosePoint = nose.getPosition();
        return (mouthPoint.y - nosePoint.y) / face.getBoundingBox().height();
    }

    private static int nearestSyntheticTrack(Map<Integer, TrackState> tracks, Set<Integer> used,
                                             Face face, int width, long timeMs) {
        float x = face.getBoundingBox().exactCenterX() / Math.max(1f, width);
        int bestId = Integer.MIN_VALUE;
        float bestDistance = 0.14f;
        for (TrackState state : tracks.values()) {
            if (state.id >= 0 || used.contains(state.id) || timeMs - state.lastSeenMs > 1_200) continue;
            float distance = Math.abs(state.normalizedCenterX - x);
            if (distance < bestDistance) { bestDistance = distance; bestId = state.id; }
        }
        return bestId;
    }

    static float cropPositionForFace(float centerX, int width, int height) {
        if (width <= 0 || height <= 0) return 0.5f;
        float cropWidth = height * 9f / 16f;
        if (cropWidth >= width) return 0.5f;
        float left = centerX - cropWidth / 2f;
        return clamp(left / (width - cropWidth));
    }

    static void addSparseKeyframe(List<ShortsCropKeyframe> frames, long timeMs, float position) {
        addSparseKeyframe(frames, timeMs, position, null);
    }

    private static void addSparseKeyframe(List<ShortsCropKeyframe> frames, long timeMs,
                                          float position, TrackState face) {
        if (frames.isEmpty()) { frames.add(new ShortsCropKeyframe(timeMs, position)); return; }
        ShortsCropKeyframe last = frames.get(frames.size() - 1);
        if (timeMs - last.getTimeMs() >= MIN_KEYFRAME_GAP_MS &&
                Math.abs(position - last.getPosition()) >= 0.018f) {
            frames.add(face == null ? new ShortsCropKeyframe(timeMs, position)
                    : faceKeyframe(timeMs, position, face));
        }
    }

    private static ShortsCropKeyframe faceKeyframe(long timeMs, float position, TrackState face) {
        return new ShortsCropKeyframe(timeMs, position, face.normalizedCenterX,
                face.normalizedCenterY, face.normalizedWidth, face.normalizedHeight);
    }

    private static ShortsCropKeyframe copyAt(ShortsCropKeyframe source, long timeMs) {
        if (!source.hasFaceBounds()) return new ShortsCropKeyframe(timeMs, source.getPosition());
        return new ShortsCropKeyframe(timeMs, source.getPosition(), source.getFaceCenterX(),
                source.getFaceCenterY(), source.getFaceWidth(), source.getFaceHeight());
    }

    private static float clamp(float value) { return Math.max(0f, Math.min(1f, value)); }

    private static final class TrackState {
        final int id;
        long lastSeenMs;
        float centerX;
        float normalizedCenterX;
        float normalizedCenterY;
        float normalizedWidth;
        float normalizedHeight;
        float lastMouth;
        float activity;
        float faceArea;
        float score;
        boolean hasMouth;
        TrackState(int id) { this.id = id; }
    }
}
