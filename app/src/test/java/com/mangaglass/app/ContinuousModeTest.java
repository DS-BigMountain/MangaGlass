package com.mangaglass.app;

import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class ContinuousModeTest {
    private static int[] solidPixels(int width, int height, int color) {
        int[] pixels = new int[width * height];
        Arrays.fill(pixels, color);
        return pixels;
    }

    @Test public void centerBoundsCoversCenteredEightyPercent() {
        int[] bounds = CenterSimilarity.centerBounds(1000, 2000);
        assertEquals(100, bounds[0]);
        assertEquals(200, bounds[1]);
        assertEquals(900, bounds[2]);
        assertEquals(1800, bounds[3]);
    }

    @Test public void peripheralChangesOutsideCenterEightyPercentAreIgnored() {
        int w = 200, h = 200;
        int[] a = solidPixels(w, h, 0xFFFFFFFF);
        int[] b = solidPixels(w, h, 0xFFFFFFFF);
        // Paint top/bottom 10% bars and side 10% margins black (outside center 80%).
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (x < 20 || x >= 180 || y < 20 || y >= 180) {
                    b[y * w + x] = 0xFF000000;
                }
            }
        }
        CenterSimilarity.Sample sa = CenterSimilarity.sampleArgb(a, w, h, 0, 0, 0, 0);
        CenterSimilarity.Sample sb = CenterSimilarity.sampleArgb(b, w, h, 0, 0, 0, 0);
        assertTrue(CenterSimilarity.isFrameSteady(sa, sb));
        assertTrue(CenterSimilarity.isSamePage(sa, sb));
    }

    @Test public void excludedBallRectChangesDoNotTriggerFalseDiff() {
        int w = 200, h = 200;
        int[] a = solidPixels(w, h, 0xFFFFFFFF);
        int[] b = solidPixels(w, h, 0xFFFFFFFF);
        // Simulate floating ball changing color inside center-right edge [140..180, 70..120].
        for (int y = 70; y < 120; y++) {
            for (int x = 140; x < 180; x++) {
                b[y * w + x] = 0xFF167956;
            }
        }
        CenterSimilarity.Sample clean = CenterSimilarity.sampleArgb(a, w, h, 0, 0, 0, 0);
        CenterSimilarity.Sample withBallExcluded = CenterSimilarity.sampleArgb(b, w, h, 140, 70, 180, 120);
        assertTrue(CenterSimilarity.isFrameSteady(clean, withBallExcluded));
        assertTrue(CenterSimilarity.isSamePage(clean, withBallExcluded));
    }

    @Test public void centerPageContentChangeIsDetectedAcrossFormats() {
        int w = 200, h = 200;
        int[] page1 = solidPixels(w, h, 0xFFFFFFFF);
        // Draw dark comic panel lines inside the center region on page 2.
        int rowStride = w * 4 + 16; // include non-zero row padding
        ByteBuffer rgba = ByteBuffer.allocate(h * rowStride);
        for (int y = 0; y < h; y++) {
            int rowBase = y * rowStride;
            for (int x = 0; x < w; x++) {
                boolean darkInk = x >= 40 && x < 160 && y >= 40 && y < 160 && (y % 4 == 0);
                byte c = (byte) (darkInk ? 20 : 250);
                int off = rowBase + x * 4;
                rgba.put(off, c);
                rgba.put(off + 1, c);
                rgba.put(off + 2, c);
                rgba.put(off + 3, (byte) 255);
            }
        }
        CenterSimilarity.Sample s1 = CenterSimilarity.sampleArgb(page1, w, h, 0, 0, 0, 0);
        CenterSimilarity.Sample s2 = CenterSimilarity.sampleRgba(rgba, w, h, rowStride, 4, 0, 0, 0, 0);
        assertFalse(CenterSimilarity.isFrameSteady(s1, s2));
        assertFalse(CenterSimilarity.isSamePage(s1, s2));
        assertTrue(CenterSimilarity.isMovedWhileShowing(s1, s2));
    }

    @Test public void trackerRequiresThreeConsecutiveMatchingIntervalsAndResetsOnMotion() {
        int w = 100, h = 100;
        CenterSimilarity.Sample pageA = CenterSimilarity.sampleArgb(solidPixels(w, h, 0xFFFFFFFF), w, h, 0, 0, 0, 0);
        CenterSimilarity.Sample pageMid = CenterSimilarity.sampleArgb(solidPixels(w, h, 0xFF808080), w, h, 0, 0, 0, 0);
        CenterSimilarity.Sample pageB = CenterSimilarity.sampleArgb(solidPixels(w, h, 0xFF101010), w, h, 0, 0, 0, 0);

        ContinuousStabilityTracker tracker = new ContinuousStabilityTracker();
        long token = tracker.start(null);

        // Before first frame arrives, tick continues waiting.
        assertEquals(ContinuousStabilityTracker.Decision.CONTINUE, tracker.onTick(token));

        // Initial post-100ms frame (pageA) sets baseline.
        tracker.onFrameSample(pageA);
        assertEquals(ContinuousStabilityTracker.Decision.CONTINUE, tracker.onTick(token));
        assertEquals(0, tracker.sameCount());

        // Two steady intervals on pageA -> not yet 3.
        assertEquals(ContinuousStabilityTracker.Decision.CONTINUE, tracker.onTick(token));
        assertEquals(1, tracker.sameCount());
        assertEquals(ContinuousStabilityTracker.Decision.CONTINUE, tracker.onTick(token));
        assertEquals(2, tracker.sameCount());

        // Motion happens -> resets consecutive counter to 0.
        tracker.onFrameSample(pageMid);
        assertEquals(ContinuousStabilityTracker.Decision.CONTINUE, tracker.onTick(token));
        assertEquals(0, tracker.sameCount());

        // Settles on pageB -> requires 3 consecutive steady ticks (baseline already shifted to pageB on next tick).
        tracker.onFrameSample(pageB);
        assertEquals(ContinuousStabilityTracker.Decision.CONTINUE, tracker.onTick(token));
        assertEquals(0, tracker.sameCount());
        assertEquals(ContinuousStabilityTracker.Decision.CONTINUE, tracker.onTick(token));
        assertEquals(1, tracker.sameCount());
        assertEquals(ContinuousStabilityTracker.Decision.CONTINUE, tracker.onTick(token));
        assertEquals(2, tracker.sameCount());
        assertEquals(ContinuousStabilityTracker.Decision.STABILIZED, tracker.onTick(token));
        assertFalse(tracker.isWatching());

        // Stale token after stabilization is ignored.
        assertEquals(ContinuousStabilityTracker.Decision.IGNORE, tracker.onTick(token));
    }
}
