package com.mangaglass.app;

import android.graphics.Rect;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Adopts locally detected row geometry for Vision-translated passages, ported from
 * ScreenAITranslator's SourceMatching.cpp. Pairing happens at row level so every passage lands on
 * the rows the artwork actually uses instead of an approximate model rectangle, while preserving
 * vertical manga balloon strips as whole passages.
 */
final class SourceMatcher {
    private static final float MIN_HORIZONTAL_SHARE = .30f;
    private static final float MIN_OVERLAPPING_SHARE = .20f;
    private static final float MAX_CENTRE_DRIFT = 2.6f;
    private static final float MIN_WIDTH_CLOSENESS = .30f;
    private static final float MIN_SCORE = .30f;
    private static final int MAX_PASSAGE_ROWS = 6;

    private static final class SourceLine {
        final int[] rect;
        final float height;
        final float confidence;
        final int regionId;
        final List<int[]> strips;
        boolean claimed;

        SourceLine(int[] rect, float height, float confidence, int regionId, List<int[]> strips) {
            this.rect = rect;
            this.height = height;
            this.confidence = confidence;
            this.regionId = regionId;
            this.strips = strips;
        }
    }

    private SourceMatcher() {}

    static List<TextRegion> attach(List<TextRegion> segments, List<OcrRegion> detected, int width, int height) {
        if (segments == null || segments.isEmpty() || detected == null || detected.isEmpty()
                || width <= 0 || height <= 0) {
            return segments == null ? Collections.emptyList() : segments;
        }
        List<SourceLine> lines = flatten(detected);
        if (lines.isEmpty()) return segments;

        int n = segments.size();
        List<List<Integer>> owned = new ArrayList<>(n);
        for (int i = 0; i < n; i++) owned.add(new ArrayList<>());

        for (int s = 0; s < n; s++) {
            TextRegion segment = segments.get(s);
            if (segment.bounds.isEmpty() || segment.hasSourceGeometry) continue;
            int[] model = {segment.bounds.left, segment.bounds.top, segment.bounds.right, segment.bounds.bottom};
            int anchor = -1;
            float anchorScore = MIN_SCORE;
            for (int l = 0; l < lines.size(); l++) {
                SourceLine sl = lines.get(l);
                if (sl.claimed) continue;
                float horizontal = horizontalShare(model, sl.rect);
                if (horizontal < MIN_HORIZONTAL_SHARE) continue;
                float vertical = verticalShare(model, sl.rect);
                float closeness = widthCloseness(model, sl.rect);
                float drift = centreDrift(model, sl.rect);
                if (vertical < MIN_OVERLAPPING_SHARE
                        && (drift > MAX_CENTRE_DRIFT || closeness < MIN_WIDTH_CLOSENESS)) continue;
                float score = 1f * vertical + 2.4f * horizontal + 2f * closeness - .5f * drift;
                if (score <= MIN_SCORE) continue;
                if (score > anchorScore) {
                    anchorScore = score;
                    anchor = l;
                }
            }
            if (anchor < 0) {
                float bestDistance = Float.MAX_VALUE;
                for (int l = 0; l < lines.size(); l++) {
                    SourceLine sl = lines.get(l);
                    if (sl.claimed || horizontalShare(model, sl.rect) < .35f
                            || widthCloseness(model, sl.rect) < .35f) continue;
                    float distance = centreDrift(model, sl.rect);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        anchor = l;
                    }
                }
            }
            if (anchor < 0) continue;
            lines.get(anchor).claimed = true;
            owned.get(s).add(anchor);
            if (!lines.get(anchor).strips.isEmpty()) continue;

            double em = estimatedEm(segment.translated);
            double sourceEm = estimatedEm(segment.source);
            double rowWidth = Math.max(8f, (float) rectWidth(lines.get(anchor).rect));
            double bandSize = lines.get(anchor).height / .86;
            boolean outgrows = em * bandSize > rowWidth * 1.15;
            while (owned.get(s).size() < MAX_PASSAGE_ROWS) {
                int[] last = lines.get(owned.get(s).get(owned.get(s).size() - 1)).rect;
                int best = -1;
                float bestGap = Float.MAX_VALUE;
                for (int l = 0; l < lines.size(); l++) {
                    SourceLine sl = lines.get(l);
                    if (sl.claimed || !sl.strips.isEmpty() || sl.rect[1] < last[1]) continue;
                    if (horizontalShare(last, sl.rect) < .20f) continue;
                    boolean insideModel = verticalShare(model, sl.rect) >= .35f
                            && horizontalShare(model, sl.rect) >= .30f;
                    if (!insideModel && (!outgrows || owned.get(s).size() >= 2
                            || rectWidth(sl.rect) > rectWidth(last) * .95f)) continue;
                    float gap = sl.rect[1] - last[3];
                    if (gap < 0 || gap > Math.max(8f, sl.height * .95f)) continue;
                    if (gap < bestGap) {
                        bestGap = gap;
                        best = l;
                    }
                }
                if (best < 0) break;
                if (verticalShare(model, lines.get(best).rect) < .35f && sourceEm <= rowWidth / bandSize * .9)
                    break;
                lines.get(best).claimed = true;
                owned.get(s).add(best);
            }
        }

        // Merge same-band horizontal neighbours so a split label/value keeps full source width.
        for (int s = 0; s < n; s++) {
            if (owned.get(s).isEmpty()) continue;
            SourceLine firstLine = lines.get(owned.get(s).get(0));
            if (!firstLine.strips.isEmpty()) continue;
            TextRegion segment = segments.get(s);
            int[] model = {segment.bounds.left, segment.bounds.top, segment.bounds.right, segment.bounds.bottom};
            int[] band = firstLine.rect;
            int bandHeight = rectHeight(band);
            int anchorRegion = firstLine.regionId;
            boolean grew = true;
            while (grew && owned.get(s).size() < MAX_PASSAGE_ROWS) {
                grew = false;
                int[] united = band.clone();
                for (int l : owned.get(s)) united = OcrRegion.union(united, lines.get(l).rect);
                for (int l = 0; l < lines.size(); l++) {
                    SourceLine sl = lines.get(l);
                    if (sl.claimed || !sl.strips.isEmpty()) continue;
                    int[] row = sl.rect;
                    int sharedRows = Math.min(band[3], row[3]) - Math.max(band[1], row[1]);
                    if (sharedRows < bandHeight * .7f) continue;
                    int gap = Math.max(united[0], row[0]) - Math.min(united[2], row[2]);
                    boolean sameRegion = sl.regionId == anchorRegion;
                    if (!sameRegion && (gap > Math.max(2, bandHeight / 4) || horizontalShare(model, row) < .65f))
                        continue;
                    if (gap > Math.max(4, rectWidth(row) / 2)) continue;
                    int[] merged = OcrRegion.union(united, row);
                    if (rectWidth(merged) > rectWidth(model) * 1.45f) continue;
                    sl.claimed = true;
                    owned.get(s).add(l);
                    grew = true;
                }
            }
        }

        List<TextRegion> out = new ArrayList<>(n);
        for (int s = 0; s < n; s++) {
            TextRegion segment = segments.get(s);
            if (owned.get(s).isEmpty()) {
                out.add(segment);
                continue;
            }
            SourceLine anchorLine = lines.get(owned.get(s).get(0));
            if (!anchorLine.strips.isEmpty()) {
                List<Rect> stripRects = new ArrayList<>(anchorLine.strips.size());
                int[] widths = new int[anchorLine.strips.size()];
                for (int k = 0; k < anchorLine.strips.size(); k++) {
                    int[] st = anchorLine.strips.get(k);
                    stripRects.add(new Rect(st[0], st[1], st[2], st[3]));
                    widths[k] = rectWidth(st);
                }
                Arrays.sort(widths);
                float lineH = Math.max(1, widths[widths.length / 2]);
                Rect bounds = new Rect(anchorLine.rect[0], anchorLine.rect[1], anchorLine.rect[2], anchorLine.rect[3]);
                out.add(new TextRegion(bounds, segment.bubble, segment.source, segment.translated,
                        Boolean.TRUE, stripRects, lineH, true, segment.untranslated, false));
                continue;
            }
            List<int[]> rows = new ArrayList<>(owned.get(s).size());
            for (int l : owned.get(s)) rows.add(lines.get(l).rect);
            rows.sort(Comparator.comparingInt(r -> r[1]));
            int[] united = rows.get(0).clone();
            float[] heights = new float[rows.size()];
            List<Rect> rowRects = new ArrayList<>(rows.size());
            for (int k = 0; k < rows.size(); k++) {
                int[] r = rows.get(k);
                united = OcrRegion.union(united, r);
                heights[k] = rectHeight(r);
                rowRects.add(new Rect(r[0], r[1], r[2], r[3]));
            }
            Arrays.sort(heights);
            float medianH = heights[heights.length / 2];
            Rect bounds = new Rect(united[0], united[1], united[2], united[3]);
            out.add(new TextRegion(bounds, segment.bubble, segment.source, segment.translated,
                    segment.vertical, rowRects, medianH, true, segment.untranslated, false));
        }
        return out;
    }

    private static List<SourceLine> flatten(List<OcrRegion> detected) {
        List<SourceLine> lines = new ArrayList<>();
        for (OcrRegion region : detected) {
            int tallStrips = 0;
            for (int[] line : region.lines) {
                int w = rectWidth(line), h = rectHeight(line);
                if (w > 0 && h * 5 > w * 6) tallStrips++;
            }
            boolean column = !region.lines.isEmpty() && tallStrips >= 1 && tallStrips * 2 >= region.lines.size();
            if (!region.lines.isEmpty() && !column) {
                for (int[] line : region.lines) {
                    if (OcrRegion.valid(line)) {
                        lines.add(new SourceLine(line, rectHeight(line), region.confidence,
                                region.id, Collections.emptyList()));
                    }
                }
                continue;
            }
            if (OcrRegion.valid(region.rect)) {
                lines.add(new SourceLine(region.rect, rectHeight(region.rect), region.confidence,
                        region.id, column ? region.lines : Collections.emptyList()));
            }
        }
        return lines;
    }

    static double estimatedEm(String text) {
        if (text == null || text.isEmpty()) return 0;
        double em = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK) continue;
            if ((cp >= 0x2E80 && cp <= 0x9FFF) || (cp >= 0xAC00 && cp <= 0xD7AF)
                    || (cp >= 0xF900 && cp <= 0xFAFF) || (cp >= 0xFF00 && cp <= 0xFF60)) {
                em += 1.0;
            } else {
                em += 0.55;
            }
        }
        return em;
    }

    private static int rectWidth(int[] r) { return Math.max(0, r[2] - r[0]); }
    private static int rectHeight(int[] r) { return Math.max(0, r[3] - r[1]); }

    private static float horizontalShare(int[] a, int[] b) {
        int l = Math.max(a[0], b[0]), r = Math.min(a[2], b[2]);
        if (r <= l) return 0f;
        int wider = Math.max(rectWidth(a), rectWidth(b));
        return wider > 0 ? (r - l) / (float) wider : 0f;
    }

    private static float verticalShare(int[] a, int[] b) {
        int t = Math.max(a[1], b[1]), bt = Math.min(a[3], b[3]);
        if (bt <= t) return 0f;
        int smaller = Math.min(rectHeight(a), rectHeight(b));
        return smaller > 0 ? (bt - t) / (float) smaller : 0f;
    }

    private static float widthCloseness(int[] a, int[] b) {
        int wide = Math.max(rectWidth(a), rectWidth(b));
        int narrow = Math.min(rectWidth(a), rectWidth(b));
        return wide > 0 ? narrow / (float) wide : 0f;
    }

    private static float centreDrift(int[] a, int[] b) {
        float h = Math.max(6f, Math.min(rectHeight(a), rectHeight(b)));
        float first = (a[1] + a[3]) * .5f;
        float second = (b[1] + b[3]) * .5f;
        return Math.abs(first - second) / h;
    }
}
