package com.mangaglass.app;

/**
 * Pure-logic timer state machine for continuous mode stabilization:
 * waits 100 ms after touch/dismissal, then compares samples every 100 ms until 3 consecutive
 * intervals match.
 */
final class ContinuousStabilityTracker {
    static final long INITIAL_DELAY_MS = 100;
    static final long POLL_INTERVAL_MS = 100;
    static final int REQUIRED_SAME_COUNT = 3;

    enum Decision { IGNORE, CONTINUE, STABILIZED }

    private boolean watching;
    private long watchToken;
    private int sameCount;
    private CenterSimilarity.Sample previousTickSample;
    private CenterSimilarity.Sample latestSample;

    long start(CenterSimilarity.Sample initialFallback) {
        watching = true;
        watchToken++;
        sameCount = 0;
        previousTickSample = null;
        latestSample = initialFallback;
        return watchToken;
    }

    void stop() {
        watching = false;
        watchToken++;
        sameCount = 0;
        previousTickSample = null;
        latestSample = null;
    }

    boolean isWatching() { return watching; }

    long token() { return watchToken; }

    int sameCount() { return sameCount; }

    CenterSimilarity.Sample latestSample() { return latestSample; }

    void onFrameSample(CenterSimilarity.Sample sample) {
        if (watching && sample != null) {
            latestSample = sample;
        }
    }

    void provideFallbackIfMissing(CenterSimilarity.Sample fallback) {
        if (watching && latestSample == null && fallback != null) {
            latestSample = fallback;
        }
    }

    Decision onTick(long token) {
        if (!watching || token != watchToken) return Decision.IGNORE;
        CenterSimilarity.Sample current = latestSample;
        if (current == null) return Decision.CONTINUE;
        if (previousTickSample == null) {
            previousTickSample = current;
            sameCount = 0;
            return Decision.CONTINUE;
        }
        if (CenterSimilarity.isFrameSteady(previousTickSample, current)) {
            sameCount++;
            previousTickSample = current;
            if (sameCount >= REQUIRED_SAME_COUNT) {
                watching = false;
                watchToken++;
                return Decision.STABILIZED;
            }
            return Decision.CONTINUE;
        }
        sameCount = 0;
        previousTickSample = current;
        return Decision.CONTINUE;
    }
}
