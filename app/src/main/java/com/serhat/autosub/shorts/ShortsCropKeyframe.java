package com.serhat.autosub.shorts;

/** Clip-local horizontal framing position at a point in time. */
public final class ShortsCropKeyframe {
    private long timeMs;
    private float position;
    private boolean hasFaceBounds;
    private float faceCenterX;
    private float faceCenterY;
    private float faceWidth;
    private float faceHeight;

    public ShortsCropKeyframe(long timeMs, float position) {
        this.timeMs = Math.max(0, timeMs);
        this.position = clamp(position);
    }

    public ShortsCropKeyframe(long timeMs, float position, float faceCenterX, float faceCenterY,
                              float faceWidth, float faceHeight) {
        this(timeMs, position);
        this.hasFaceBounds = true;
        this.faceCenterX = clamp(faceCenterX);
        this.faceCenterY = clamp(faceCenterY);
        this.faceWidth = clamp(faceWidth);
        this.faceHeight = clamp(faceHeight);
    }

    public long getTimeMs() { return timeMs; }
    public float getPosition() { return position; }
    public boolean hasFaceBounds() { return hasFaceBounds; }
    public float getFaceCenterX() { return faceCenterX; }
    public float getFaceCenterY() { return faceCenterY; }
    public float getFaceWidth() { return faceWidth; }
    public float getFaceHeight() { return faceHeight; }

    private static float clamp(float value) { return Math.max(0f, Math.min(1f, value)); }
}
