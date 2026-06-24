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
    // Temporal smoothing so the crop eases toward the active speaker instead of snapping on
    // every sample. Sub-deadzone wobble is ignored so the camera holds steady; a large delta
    // (typically a speaker switch) still hard-cuts straight to the new framing.
    private static final float MOTION_DEADZONE = 0.03f;
    private static final float SNAP_THRESHOLD = 0.16f;
    private static final float SMOOTHING_FACTOR = 0.35f;
    // The clip opens on the framing it settles on within this window, so there is no visible
    // pan from the initial lock to the active speaker — it just starts on the right face.
    private static final long OPENING_SETTLE_MS = 200;
    // Active-speaker hysteresis: stay locked on the committed face and only switch to another
    // once it has clearly out-scored the current one for several consecutive samples. This stops
    // the frame ping-ponging between people when more than one face is visible.
    private static final int SWITCH_DWELL = 2;
    private static final float SWITCH_MARGIN = 1.15f;
    // A clearly dominant face (committed one is silent / much weaker) takes over on the very next
    // sample, skipping the dwell — that's the case where the wait felt like lag.
    private static final float STRONG_SWITCH_MARGIN = 2.2f;
    private static final long LOST_GRACE_MS = 300;
    private static final float SPEECH_THRESHOLD = 0.10f;

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
                .setMinFaceSize(0.10f)
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
        int committedId = Integer.MIN_VALUE;
        int challengerId = Integer.MIN_VALUE;
        int challengerStreak = 0;
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
                        if (best == null) {
                            best = state;
                        } else {
                            boolean stateSpeaking = state.activity > SPEECH_THRESHOLD;
                            boolean bestSpeaking = best.activity > SPEECH_THRESHOLD;
                            if (stateSpeaking && !bestSpeaking) {
                                best = state;
                            } else if (!stateSpeaking && bestSpeaking) {
                                // Keep best
                            } else {
                                if (state.score > best.score) best = state;
                            }
                        }
                    }
                    // Pick the face to frame with hysteresis. Keep the committed speaker locked
                    // unless a different face has clearly led for SWITCH_DWELL samples in a row, or
                    // the committed face has been gone past the grace window.
                    TrackState committed = tracks.get(committedId);
                    boolean committedAlive = committed != null
                            && localMs - committed.lastSeenMs <= LOST_GRACE_MS;
                    TrackState target;
                    if (!committedAlive) {
                        target = best;
                        challengerId = Integer.MIN_VALUE;
                        challengerStreak = 0;
                    } else if (best == null || best == committed) {
                        target = committed;
                        challengerId = Integer.MIN_VALUE;
                        challengerStreak = 0;
                    } else {
                        boolean bestSpeaking = best.activity > SPEECH_THRESHOLD;
                        boolean committedSpeaking = committed.activity > SPEECH_THRESHOLD;
                        if (bestSpeaking && !committedSpeaking) {
                            // Switch immediately to the speaking face if the committed one is silent
                            target = best;
                            challengerId = Integer.MIN_VALUE;
                            challengerStreak = 0;
                        } else {
                            if (best.id == challengerId) challengerStreak++;
                            else { challengerId = best.id; challengerStreak = 1; }
                            boolean clearLead = best.score > committed.score * SWITCH_MARGIN;
                            boolean dominantLead = best.score > committed.score * STRONG_SWITCH_MARGIN;
                            if (dominantLead || (challengerStreak >= SWITCH_DWELL && clearLead)) {
                                target = best;
                                challengerId = Integer.MIN_VALUE;
                                challengerStreak = 0;
                            } else {
                                target = committed;
                            }
                        }
                    }

                    boolean isHardCut = false;
                    if (target != null) {
                        float desired = cropPositionForFace(target.centerX,
                                frame.getWidth(), frame.getHeight());
                        if (!framingInitialized) {
                            // Lock the first speaker at t=0 instead of starting centered.
                            cropPosition = desired;
                            framingInitialized = true;
                            keyframes.add(faceKeyframe(0, cropPosition, target));
                        } else if (target.id != committedId) {
                            // A real, sustained speaker change — cut straight to the new face.
                            if (cropPosition != desired) {
                                isHardCut = true;
                            }
                            cropPosition = desired;
                        } else {
                            // Same speaker — ease toward them and ignore sub-deadzone wobble.
                            float nextPos = smoothCropPosition(cropPosition, desired);
                            if (nextPos == desired && Math.abs(desired - cropPosition) >= SNAP_THRESHOLD) {
                                isHardCut = true;
                            }
                            cropPosition = nextPos;
                        }
                        committedId = target.id;
                        selectedTrack = target;
                    }
                    if (framingInitialized) {
                        if (isHardCut && !keyframes.isEmpty()) {
                            ShortsCropKeyframe last = keyframes.get(keyframes.size() - 1);
                            if (localMs - 1 > last.getTimeMs()) {
                                keyframes.add(copyAt(last, localMs - 1));
                            }
                        }
                        addSparseKeyframe(keyframes, localMs, cropPosition, selectedTrack);
                    }
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
        anchorOpeningFraming(keyframes);
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

    static float smoothCropPosition(float current, float target) {
        float delta = target - current;
        float magnitude = Math.abs(delta);
        if (magnitude <= MOTION_DEADZONE) return current;   // hold steady through small head wobble
        if (magnitude >= SNAP_THRESHOLD) return target;     // speaker switch / large move: hard cut
        return clamp(current + delta * SMOOTHING_FACTOR);   // ease toward the target
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

    /**
     * Collapses the initial lock-on pan: open the clip on whatever framing it settles on shortly
     * after the start, instead of beginning on the first (possibly wrong or centered) detection and
     * visibly sliding to the active speaker.
     */
    static void anchorOpeningFraming(List<ShortsCropKeyframe> keyframes) {
        if (keyframes.size() < 2) return;
        int settleIndex = 0;
        for (int i = 1; i < keyframes.size(); i++) {
            if (keyframes.get(i).getTimeMs() > OPENING_SETTLE_MS) break;
            settleIndex = i;
        }
        if (settleIndex == 0) return;
        ShortsCropKeyframe settle = keyframes.get(settleIndex);
        for (int i = settleIndex; i >= 1; i--) keyframes.remove(i);
        keyframes.set(0, copyAt(settle, 0));
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
