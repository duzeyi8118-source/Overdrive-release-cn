package com.overdrive.app.surveillance.impact;

public final class ImpactDetector {
    public static final float THRESHOLD_HIGH = 8.0f;
    public static final float THRESHOLD_MEDIUM = 12.0f;
    public static final float THRESHOLD_LOW = 18.0f;

    public final float threshold;
    public float baseX;
    public float baseY;
    public float baseZ;
    public boolean initialized;
    public long lastSampleTime;
    public int spikeCount;
    public float currentPeak;
    public long lastReportedTime;

    public ImpactDetector(float threshold) {
        this.threshold = threshold;
        this.lastReportedTime = Long.MIN_VALUE;
    }

    public static ImpactDetector forSensitivity(String sensitivity) {
        float thresh = THRESHOLD_MEDIUM;
        if ("high".equalsIgnoreCase(sensitivity)) {
            thresh = THRESHOLD_HIGH;
        } else if ("low".equalsIgnoreCase(sensitivity)) {
            thresh = THRESHOLD_LOW;
        }
        return new ImpactDetector(thresh);
    }

    /**
     * Feeds 3-axis accelerometer readings. Returns peak m/s^2 if an impact is confirmed, or Float.NaN otherwise.
     */
    public float processSample(float x, float y, float z, long now) {
        if (!initialized) {
            baseX = x;
            baseY = y;
            baseZ = z;
            initialized = true;
            lastSampleTime = now;
            return Float.NaN;
        }

        float dx = x - baseX;
        float dy = y - baseY;
        float dz = z - baseZ;
        float magnitude = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);

        // Slow exponential moving average for gravity baseline drift compensation
        baseX += dx * 0.009f;
        baseY += dy * 0.009f;
        baseZ += dz * 0.009f;

        if (now - lastSampleTime > 3000L) {
            spikeCount = 0;
            currentPeak = 0f;
        }
        lastSampleTime = now;

        if (magnitude >= threshold) {
            spikeCount++;
            if (magnitude > currentPeak) {
                currentPeak = magnitude;
            }
            if (spikeCount >= 2) {
                if (now - lastReportedTime >= 30000L) {
                    float reported = currentPeak;
                    lastReportedTime = now;
                    spikeCount = 0;
                    currentPeak = 0f;
                    return reported;
                } else {
                    spikeCount = 0;
                    currentPeak = 0f;
                }
            }
        }
        return Float.NaN;
    }

    public static class Companion {
        public static final Companion INSTANCE = new Companion();
    }
}
