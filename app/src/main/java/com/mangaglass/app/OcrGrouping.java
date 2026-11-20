package com.mangaglass.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Groups recognized lines into translation passages, following the page shape the detector measured.
 * Tight glyph bounds recover the text band on flat or smoothly varying backgrounds, same-baseline
 * fragments join with appearance and separator checks, horizontal pages respect paragraph/list/color
 * boundaries, and vertical manga pages read columns right to left and join strips of one balloon.
 * Pure Java types keep the whole pass under JVM tests.
 */
final class OcrGrouping {
    interface Pixels { int at(int x, int y); }
    private static final String PASSAGE_MARKERS = "◆◇●○◎■□▲△▼▽※【";
    private static final String LIST_MARKERS = "・•·‣◦▪▫★☆◆◇●○◎■□▲△▼▽※";
    private static final String[] SENTENCE_CLOSERS = {
            "。", "！", "？", "：", "”", "」", "』", "؟", "؛", "۔", "।", "॥"
    };

    static final class LineAppearance {
        boolean hasInk;
        int bgR, bgG, bgB;
        int inkR, inkG, inkB;
        int inkChroma() {
            int max = Math.max(inkR, Math.max(inkG, inkB));
            int min = Math.min(inkR, Math.min(inkG, inkB));
            return max - min;
        }
    }

    private OcrGrouping() {}

    /**
     * Recover the vertical glyph band of a detector rectangle on flat or smoothly varying
     * backgrounds; the detector keeps the horizontal extent.
     */
    static int[] tighten(int[] rect, Pixels pixels, int width, int height) {
        int[] source = clip(rect, width, height);
        if (!OcrRegion.valid(source) || pixels == null) return source;
        int boxW = source[2] - source[0];
        int boxH = source[3] - source[1];
        int chunkW = boxW > 128 ? 96 : boxW;
        int chunks = Math.max(1, (boxW + chunkW - 1) / chunkW);
        int[] chunkR = new int[chunks], chunkG = new int[chunks], chunkB = new int[chunks];
        int[] colors = new int[4096];
        int[] ring = new int[4096];
        for (int c = 0; c < chunks; c++) {
            int x0 = source[0] + c * chunkW;
            int x1 = Math.min(source[2], x0 + chunkW);
            Arrays.fill(colors, 0);
            int count = 0;
            for (int y = source[1]; y < source[3]; y++) {
                for (int x = x0; x < x1; x++) {
                    colors[bin12(pixels.at(x, y))]++;
                    count++;
                }
            }
            if (count == 0) return source;
            int mode = maxBin(colors);
            int r = ((mode >> 8) & 15) * 16 + 8;
            int g = ((mode >> 4) & 15) * 16 + 8;
            int b = (mode & 15) * 16 + 8;
            if (colors[mode] < count * .45) {
                Arrays.fill(ring, 0);
                int ringCount = 0;
                int rx0 = Math.max(0, x0 - 2), rx1 = Math.min(width, x1 + 2);
                int ry0 = Math.max(0, source[1] - 2), ry1 = Math.min(height, source[3] + 2);
                for (int y = ry0; y < ry1; y++) {
                    for (int x = rx0; x < rx1; x++) {
                        if (x >= x0 && x < x1 && y >= source[1] && y < source[3]) {
                            x = x1 - 1;
                            continue;
                        }
                        ring[bin12(pixels.at(x, y))]++;
                        ringCount++;
                    }
                }
                if (ringCount >= 8) {
                    int ringMode = maxBin(ring);
                    if (ring[ringMode] >= ringCount * .45) {
                        r = ((ringMode >> 8) & 15) * 16 + 8;
                        g = ((ringMode >> 4) & 15) * 16 + 8;
                        b = (ringMode & 15) * 16 + 8;
                    }
                }
            }
            chunkR[c] = r; chunkG[c] = g; chunkB[c] = b;
        }
        int[] rowPixels = new int[boxH];
        int[] rowMaxRun = new int[boxH];
        int[] rowLeft = new int[boxH];
        int[] rowRight = new int[boxH];
        boolean[] ruleCandidate = new boolean[boxH];
        int minPixels = Math.max(2, boxW / 500);
        int ruleRunLimit = Math.max(24, (int) (boxW * .55));
        for (int row = 0; row < boxH; row++) {
            int y = source[1] + row;
            int cnt = 0, l = source[2], rr = source[0];
            int currentRun = 0, maxRun = 0;
            for (int x = source[0]; x < source[2]; x++) {
                int c = (x - source[0]) / chunkW;
                int color = pixels.at(x, y);
                int delta = Math.abs(((color >> 16) & 255) - chunkR[c])
                        + Math.abs(((color >> 8) & 255) - chunkG[c])
                        + Math.abs((color & 255) - chunkB[c]);
                if (delta > 78) {
                    cnt++;
                    currentRun++;
                    if (currentRun > maxRun) maxRun = currentRun;
                    if (x < l) l = x;
                    if (x + 1 > rr) rr = x + 1;
                } else {
                    currentRun = 0;
                }
            }
            rowPixels[row] = cnt;
            rowMaxRun[row] = maxRun;
            rowLeft[row] = l;
            rowRight[row] = rr;
            ruleCandidate[row] = boxW >= 32 && maxRun > ruleRunLimit;
        }
        // Only thin (<= 3px tall) solid horizontal spans are UI rules/underlines; thick bands stay valid.
        boolean[] isRule = new boolean[boxH];
        int rIdx = 0;
        while (rIdx < boxH) {
            if (!ruleCandidate[rIdx]) { rIdx++; continue; }
            int rStart = rIdx;
            while (rIdx < boxH && ruleCandidate[rIdx]) rIdx++;
            if (rIdx - rStart <= 3) {
                for (int k = rStart; k < rIdx; k++) isRule[k] = true;
            }
        }
        int[] ink = {source[2], source[3], source[0], source[1]};
        for (int row = 0; row < boxH; row++) {
            int y = source[1] + row;
            int cnt = rowPixels[row];
            if (!isRule[row] && cnt >= minPixels && (cnt < boxW * .88 || rowMaxRun[row] > ruleRunLimit)) {
                ink[0] = Math.min(ink[0], rowLeft[row]);
                ink[2] = Math.max(ink[2], rowRight[row]);
                ink[1] = Math.min(ink[1], y);
                ink[3] = y + 1;
            }
        }
        if (!OcrRegion.valid(ink) || ink[3] - ink[1] < 3) return source;
        // Only the vertical extent is recovered here; horizontal extent belongs to the detector.
        ink[0] = source[0];
        ink[2] = source[2];
        float share = (ink[3] - ink[1]) / (float) boxH;
        if (share > .92f || share < .3f) return source;
        return ink;
    }

    static List<OcrRegion> group(List<OcrLine> input, Pixels pixels, int width, int height) {
        if (width <= 0 || height <= 0) return Collections.emptyList();
        List<OcrLine> lines = new ArrayList<>();
        for (OcrLine line : input) {
            int[] rect = clip(line.rect, width, height);
            if (!OcrRegion.valid(rect) || line.text == null || line.text.isEmpty()) continue;
            lines.add(new OcrLine(rect, line.text, line.confidence));
        }
        removeRedundant(lines);
        boolean allUnread = true;
        for (OcrLine line : lines) {
            if (!isUnreadText(line.text)) { allUnread = false; break; }
        }
        joinFragments(lines, pixels, width, height, allUnread);
        boolean columnPage = isColumnPage(lines);
        lines.sort(columnPage ? COLUMN_ORDER : ROW_ORDER);
        List<OcrRegion> regions = groupParagraphs(lines, pixels, width, height, columnPage);
        if (columnPage) joinStrips(regions, pixels, width, height);
        markVerticalRegions(regions, columnPage);
        for (int i = 0; i < regions.size(); i++) regions.get(i).id = i + 1;
        return regions;
    }

    /** A page whose vertical boxes make up the majority reads right to left. */
    static boolean isColumnPage(List<OcrLine> lines) {
        int vertical = 0;
        for (OcrLine line : lines) if (line.height() > line.width()) vertical++;
        return vertical * 2 > lines.size();
    }

    static final Comparator<OcrLine> COLUMN_ORDER = (a, b) -> {
        int hOverlap = Math.min(a.right(), b.right()) - Math.max(a.left(), b.left());
        int vOverlap = Math.min(a.bottom(), b.bottom()) - Math.max(a.top(), b.top());
        boolean sameColumn = hOverlap * 10 >= Math.min(a.width(), b.width()) * 4
                && vOverlap * 10 < Math.min(a.height(), b.height()) * 3;
        if (!sameColumn) return Integer.compare(b.left() + b.right(), a.left() + a.right());
        return Integer.compare(a.top(), b.top());
    };

    static final Comparator<OcrLine> ROW_ORDER = (a, b) -> {
        if (a.top() != b.top()) return Integer.compare(a.top(), b.top());
        return Integer.compare(a.left(), b.left());
    };

    static List<OcrRegion> groupParagraphs(List<OcrLine> lines, Pixels pixels, int width, int height) {
        return groupParagraphs(lines, pixels, width, height, isColumnPage(lines));
    }

    static List<OcrRegion> groupParagraphs(List<OcrLine> lines, Pixels pixels, int width, int height,
                                           boolean columnPage) {
        boolean allUnread = true;
        for (OcrLine line : lines) {
            if (!isUnreadText(line.text)) { allUnread = false; break; }
        }
        List<LineAppearance> appearances = new ArrayList<>(lines.size());
        for (OcrLine line : lines) appearances.add(measureLineAppearance(pixels, width, height, line.rect));
        List<OcrRegion> regions = new ArrayList<>();
        List<LineAppearance> regionApps = new ArrayList<>();
        List<String> regionLastText = new ArrayList<>();
        for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
            OcrLine line = lines.get(lineIndex);
            LineAppearance lineApp = appearances.get(lineIndex);
            boolean lineUnread = isUnreadText(line.text);
            int bestIndex = -1;
            int bestGap = Integer.MAX_VALUE;
            boolean opensPassage = startsPassage(line.text);
            boolean lineStartsList = startsListMarker(line.text);
            for (int rIdx = 0; rIdx < regions.size(); rIdx++) {
                if (opensPassage) continue;
                OcrRegion region = regions.get(rIdx);
                boolean regionUnread = isUnreadText(region.text);
                if (lineUnread != regionUnread || (!allUnread && lineUnread)) continue;
                int[] last = region.lines.get(region.lines.size() - 1);
                int gap = line.top() - last[3];
                float h = Math.max(1f, region.lineHeight);
                int overlap = Math.min(last[2], line.right()) - Math.max(last[0], line.left());
                boolean sentenceEnded = endsSentence(region.text);
                float maxGap = columnPage ? h * .9f : h * .55f;
                float minRatio = columnPage ? .65f : (sentenceEnded ? .80f : .55f);
                float maxRatio = columnPage ? 1.45f : 1.24f;
                if (gap < -h * .25f || gap > maxGap || gap >= bestGap
                        || line.height() < h * minRatio || line.height() > h * maxRatio) continue;
                int centerLast = (last[0] + last[2]) / 2;
                int centerLine = (line.left() + line.right()) / 2;
                boolean centerAligned = Math.abs(centerLast - centerLine) <= Math.max(6, Math.round(h * .75f));
                boolean alignedHorizontally = Math.abs(line.left() - last[0]) <= h * 2
                        || Math.abs(line.right() - last[2]) <= h || centerAligned;
                if (!alignedHorizontally
                        || overlap < (centerAligned ? .50f : .65f) * Math.min(last[2] - last[0], line.width()))
                    continue;
                if (!columnPage) {
                    // Stacked list items (e.g. "・★5(SSR) ..." followed by "・★4(SR) ...") stay separate.
                    if (lineStartsList) continue;
                    // Compare ink & overlapping-span background so textured/translucent game modals
                    // keep same-color wrapped prose together while separating red/cyan notes & headings.
                    if (!appearanceMatchesVertical(regionApps.get(rIdx), lineApp, pixels, width, height, last, line.rect))
                        continue;
                    boolean rtlRegion = hasRtlScript(region.text) || hasRtlScript(line.text);
                    if (!centerAligned && (rtlRegion
                            ? (last[2] - line.right() > h * 1.1f
                                || (region.lines.size() >= 2 && line.right() - last[2] > h * 1.1f))
                            : (line.left() - last[0] > h * 1.1f
                                || (region.lines.size() >= 2 && last[0] - line.left() > h * 1.1f))))
                        continue;
                    int lastW = last[2] - last[0];
                    if (line.width() > h * 4 && lastW < line.width() * .72f
                            && (!centerAligned || sentenceEnded || gap > h * .45f))
                        continue;
                    if (region.lines.size() >= 2 && !centerAligned
                            && (rtlRegion ? (last[0] > region.left() + (int) (h * 1.2f))
                                          : (last[2] < region.right() - (int) (h * 1.2f))))
                        continue;
                    if (sentenceEnded
                            && (lineStartsList || gap > h * .30f || line.height() < h * .86f
                                || lastW < Math.max(region.width(), line.width()) * .88f))
                        continue;
                    if (lastW < h * 5.5f && line.width() < h * 5.5f && gap > h * .32f)
                        continue;
                    if (region.lines.size() >= 2) {
                        int[] intraGaps = new int[region.lines.size() - 1];
                        for (int k = 1; k < region.lines.size(); k++) {
                            intraGaps[k - 1] = Math.max(0, region.lines.get(k)[1] - region.lines.get(k - 1)[3]);
                        }
                        Arrays.sort(intraGaps);
                        int medianIntra = intraGaps[intraGaps.length / 2];
                        if (gap > Math.max(h * .45f, medianIntra * 1.45f + 2f)) continue;
                    }
                    // A preceding bullet/list heading does not swallow a different indented or list item.
                    if (startsListMarker(regionLastText.get(rIdx)) && sentenceEnded) continue;
                }
                if (region.text.length() + line.text.length() + 1 > 8192
                        || hasSeparator(pixels, width, height, last, line.rect)) continue;
                int[] joined = OcrRegion.union(region.rect, line.rect);
                if (crossesOtherLine(joined, line, region, lines)) continue;
                bestIndex = rIdx;
                bestGap = gap;
            }
            if (bestIndex < 0) {
                regions.add(new OcrRegion(line.rect, line.text, line.rect, line.height(), line.confidence));
                regionApps.add(lineApp);
                regionLastText.add(line.text);
                continue;
            }
            OcrRegion best = regions.get(bestIndex);
            if (lineApp.hasInk) regionApps.set(bestIndex, lineApp);
            regionLastText.set(bestIndex, line.text);
            best.confidence = Math.min(best.confidence, line.confidence);
            best.text = best.text + "\n" + line.text;
            best.lines.add(line.rect.clone());
            best.rect = OcrRegion.union(best.rect, line.rect);
            best.lineHeight = medianHeight(best.lines);
        }
        return regions;
    }

    /** One balloon wraps a sentence across strips; joining them keeps the passage readable without crossing borders. */
    private static void joinStrips(List<OcrRegion> regions, Pixels pixels, int width, int height) {
        int n = regions.size();
        if (n < 2) {
            for (OcrRegion region : regions) if (isStrip(region)) region.lineHeight = medianLineWidth(region);
            return;
        }
        boolean[] strip = new boolean[n];
        int[] colWidth = new int[n];
        int[] parent = new int[n];
        LineAppearance[] apps = new LineAppearance[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
            strip[i] = isStrip(regions.get(i));
            if (strip[i]) colWidth[i] = medianLineWidth(regions.get(i));
            apps[i] = measureLineAppearance(pixels, width, height, regions.get(i).rect);
        }
        for (int i = 0; i < n; i++) {
            if (!strip[i]) continue;
            for (int j = i + 1; j < n; j++) {
                boolean tailJ = strip[j] || isColumnTail(regions.get(j), colWidth[i]);
                if (!tailJ) continue;
                int wJ = strip[j] ? colWidth[j] : regions.get(j).width();
                if (canJoinStrips(regions.get(i), colWidth[i], regions.get(j), wJ,
                        apps[i], apps[j], pixels, width, height)) {
                    int rootA = findRoot(parent, i), rootB = findRoot(parent, j);
                    if (rootA != rootB) parent[Math.max(rootA, rootB)] = Math.min(rootA, rootB);
                }
            }
        }
        List<List<OcrRegion>> grouped = new ArrayList<>();
        int[] groupIndex = new int[n];
        Arrays.fill(groupIndex, -1);
        for (int i = 0; i < n; i++) {
            int root = findRoot(parent, i);
            if (groupIndex[root] < 0) {
                groupIndex[root] = grouped.size();
                grouped.add(new ArrayList<>());
            }
            grouped.get(groupIndex[root]).add(regions.get(i));
        }
        List<OcrRegion> merged = new ArrayList<>(grouped.size());
        for (List<OcrRegion> group : grouped) {
            if (group.size() == 1) {
                OcrRegion single = group.get(0);
                if (isStrip(single)) single.lineHeight = medianLineWidth(single);
                merged.add(single);
                continue;
            }
            group.sort((a, b) -> {
                int r = Integer.compare(b.right(), a.right());
                return r != 0 ? r : Integer.compare(a.top(), b.top());
            });
            OcrRegion first = group.get(0);
            for (int k = 1; k < group.size(); k++) first.join(group.get(k), " ");
            first.lineHeight = medianLineWidth(first);
            merged.add(first);
        }
        regions.clear();
        regions.addAll(merged);
    }

    private static boolean isColumnTail(OcrRegion region, float stripW) {
        if (isStrip(region)) return true;
        if (region.lines.size() != 1) return false;
        int[] line = region.lines.get(0);
        int w = line[2] - line[0], h = line[3] - line[1];
        return w >= stripW * .55f && w <= stripW * 1.45f && h * 2 >= w && h <= stripW * 2.5f;
    }

    private static boolean canJoinStrips(OcrRegion a, int widthA, OcrRegion b, int widthB,
                                         LineAppearance appA, LineAppearance appB,
                                         Pixels pixels, int width, int height) {
        int centerXA = (a.left() + a.right()) / 2, centerXB = (b.left() + b.right()) / 2;
        OcrRegion right = centerXA >= centerXB ? a : b;
        OcrRegion left = right == a ? b : a;
        int minCol = Math.max(1, Math.min(widthA, widthB));
        int maxCol = Math.max(widthA, widthB);
        if (maxCol > minCol * 2.4f) return false;
        int spacing = right.left() - left.right();
        if (spacing < -minCol * .5f || spacing > minCol * 1.85f) return false;
        int overlap = Math.min(right.bottom(), left.bottom()) - Math.max(right.top(), left.top());
        int minH = Math.min(right.height(), left.height());
        if (overlap <= 0 || overlap * 100L <= minH * 45L) return false;
        float centerYA = (right.top() + right.bottom()) / 2f;
        float centerYB = (left.top() + left.bottom()) / 2f;
        if (Math.abs(centerYA - centerYB) > Math.max(right.height(), left.height()) * .55f) return false;
        if (!appearanceMatches(appA, appB)) return false;
        return !hasVerticalStripBarrier(pixels, width, height, right.rect, left.rect);
    }

    private static int findRoot(int[] parent, int x) {
        while (parent[x] != x) {
            parent[x] = parent[parent[x]];
            x = parent[x];
        }
        return x;
    }

    private static int medianLineWidth(OcrRegion region) {
        int[] widths = new int[region.lines.size()];
        for (int i = 0; i < widths.length; i++) widths[i] = region.lines.get(i)[2] - region.lines.get(i)[0];
        Arrays.sort(widths);
        return Math.max(1, widths[widths.length / 2]);
    }

    private static void markVerticalRegions(List<OcrRegion> regions, boolean columnPage) {
        for (OcrRegion region : regions) {
            if (isStrip(region)) {
                region.vertical = true;
            } else if (columnPage && region.lines.size() == 1
                    && region.height() >= region.width() && hasCjk(region.text)) {
                region.vertical = true;
            } else {
                region.vertical = false;
            }
        }
    }

    private static boolean hasCjk(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            if (script == Character.UnicodeScript.HAN
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA) return true;
        }
        return false;
    }

    /** Detects a continuous panel border, bubble outline, or background jump between two adjacent strips. */
    private static boolean hasVerticalStripBarrier(Pixels pixels, int width, int height, int[] rightBox, int[] leftBox) {
        if (pixels == null || width <= 0 || height <= 0) return false;
        int x0 = Math.max(0, Math.min(leftBox[2], rightBox[0]) - 2);
        int x1 = Math.min(width, Math.max(leftBox[2], rightBox[0]) + 2);
        if (x1 <= x0) return false;
        int y0 = Math.max(0, Math.max(leftBox[1], rightBox[1]));
        int y1 = Math.min(height, Math.min(leftBox[3], rightBox[3]));
        int overlapH = y1 - y0;
        if (overlapH < 6) return false;

        int[] bgRight = sampleBackground(pixels, rightBox, width, height);
        int[] bgLeft = sampleBackground(pixels, leftBox, width, height);
        if (Math.abs(bgRight[0] - bgLeft[0]) + Math.abs(bgRight[1] - bgLeft[1])
                + Math.abs(bgRight[2] - bgLeft[2]) > 150) {
            return true;
        }
        int bgR = (bgRight[0] + bgLeft[0]) / 2;
        int bgG = (bgRight[1] + bgLeft[1]) / 2;
        int bgB = (bgRight[2] + bgLeft[2]) / 2;

        int span = x1 - x0;
        int[] prevRun = new int[span];
        int[] currRun = new int[span];
        int[] colHits = new int[span];
        int maxRun = 0;
        int rowsWithBarrier = 0;
        for (int y = y0; y < y1; y++) {
            boolean rowHit = false;
            for (int c = 0; c < span; c++) {
                int color = pixels.at(x0 + c, y);
                int delta = Math.abs(((color >> 16) & 255) - bgR)
                        + Math.abs(((color >> 8) & 255) - bgG)
                        + Math.abs((color & 255) - bgB);
                if (delta > 110) {
                    rowHit = true;
                    colHits[c]++;
                    int bestPrev = 0;
                    for (int dc = -2; dc <= 2; dc++) {
                        int pc = c + dc;
                        if (pc >= 0 && pc < span) bestPrev = Math.max(bestPrev, prevRun[pc]);
                    }
                    currRun[c] = bestPrev + 1;
                    if (currRun[c] > maxRun) maxRun = currRun[c];
                } else {
                    currRun[c] = 0;
                }
            }
            if (rowHit) rowsWithBarrier++;
            int[] swap = prevRun;
            prevRun = currRun;
            currRun = swap;
        }
        if (maxRun >= Math.max(14, Math.round(overlapH * .55f))) return true;
        if (rowsWithBarrier >= overlapH * .80f && maxRun >= Math.max(12, Math.round(overlapH * .38f))) return true;

        int extPad = Math.max(8, Math.min(24, overlapH / 4));
        int extY0 = Math.max(0, y0 - extPad);
        int extY1 = Math.min(height, y1 + extPad);
        int topLen = y0 - extY0;
        int botLen = extY1 - y1;
        if (topLen >= 4 && botLen >= 4 && rowsWithBarrier >= overlapH * .55f) {
            for (int c = 0; c < span; c++) {
                int inRange = colHits[c];
                if (c > 0) inRange = Math.max(inRange, colHits[c - 1]);
                if (c + 1 < span) inRange = Math.max(inRange, colHits[c + 1]);
                if (inRange < overlapH * .50f) continue;
                int topHits = 0, botHits = 0;
                for (int y = extY0; y < y0; y++) if (columnHasBarrier(pixels, x0, span, c, y, bgR, bgG, bgB)) topHits++;
                for (int y = y1; y < extY1; y++) if (columnHasBarrier(pixels, x0, span, c, y, bgR, bgG, bgB)) botHits++;
                if (topHits >= topLen * .60f && botHits >= botLen * .60f) return true;
            }
        }
        return false;
    }

    /** Detects a vertical UI divider or column border between two horizontal neighbours. */
    private static boolean hasVerticalDivider(Pixels pixels, int width, int height, int[] leftRect, int[] rightRect) {
        if (pixels == null || width <= 2 || height <= 2) return false;
        int top = Math.max(0, Math.max(leftRect[1], rightRect[1]) - 2);
        int bottom = Math.min(height, Math.min(leftRect[3], rightRect[3]) + 2);
        int span = bottom - top;
        if (span < 8) return false;
        int startX = Math.max(1, Math.min(leftRect[2], rightRect[0]) - 4);
        int endX = Math.min(width - 2, Math.max(leftRect[2], rightRect[0]) + 4);
        if (endX <= startX) return false;
        for (int x = startX; x <= endX; x++) {
            int edges = 0;
            int minR = 255, maxR = 0, minG = 255, maxG = 0, minB = 255, maxB = 0;
            for (int y = top; y < bottom; y++) {
                int c = pixels.at(x, y);
                int cl = pixels.at(x - 1, y);
                int cr = pixels.at(x + 1, y);
                int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                int deltaL = Math.abs(r - ((cl >> 16) & 255)) + Math.abs(g - ((cl >> 8) & 255)) + Math.abs(b - (cl & 255));
                int deltaR = Math.abs(r - ((cr >> 16) & 255)) + Math.abs(g - ((cr >> 8) & 255)) + Math.abs(b - (cr & 255));
                if (Math.max(deltaL, deltaR) > 16) edges++;
                if (r < minR) minR = r; if (r > maxR) maxR = r;
                if (g < minG) minG = g; if (g > maxG) maxG = g;
                if (b < minB) minB = b; if (b > maxB) maxB = b;
            }
            boolean verticalEdge = edges > span * .55;
            boolean uniformStroke = (maxR - minR) + (maxG - minG) + (maxB - minB) <= 36 && edges > span * .35;
            if (verticalEdge || uniformStroke) return true;
        }
        return false;
    }

    private static boolean columnHasBarrier(Pixels pixels, int x0, int span, int c, int y, int bgR, int bgG, int bgB) {
        int cStart = Math.max(0, c - 1), cEnd = Math.min(span - 1, c + 1);
        for (int cc = cStart; cc <= cEnd; cc++) {
            int color = pixels.at(x0 + cc, y);
            int delta = Math.abs(((color >> 16) & 255) - bgR)
                    + Math.abs(((color >> 8) & 255) - bgG)
                    + Math.abs((color & 255) - bgB);
            if (delta > 110) return true;
        }
        return false;
    }

    private static int[] sampleBackground(Pixels pixels, int[] box, int width, int height) {
        int x0 = Math.max(0, box[0]), y0 = Math.max(0, box[1]);
        int x1 = Math.min(width, box[2]), y1 = Math.min(height, box[3]);
        if (x1 <= x0 || y1 <= y0) return new int[]{255, 255, 255};
        int[] counts = new int[4096];
        int[] sumR = new int[4096], sumG = new int[4096], sumB = new int[4096];
        int stepX = Math.max(1, (x1 - x0) / 16), stepY = Math.max(1, (y1 - y0) / 16);
        int winner = 0;
        for (int y = y0; y < y1; y += stepY) {
            for (int x = x0; x < x1; x += stepX) {
                int c = pixels.at(x, y);
                int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                int key = ((r >> 4) << 8) | ((g >> 4) << 4) | (b >> 4);
                sumR[key] += r; sumG[key] += g; sumB[key] += b;
                if (++counts[key] > counts[winner]) winner = key;
            }
        }
        int n = Math.max(1, counts[winner]);
        return new int[]{sumR[winner] / n, sumG[winner] / n, sumB[winner] / n};
    }

    /**
     * Measures dominant background and ink color using 96px horizontal tiles so wide lines on
     * textured or translucent game backgrounds still measure accurate ink colors (e.g. white vs red).
     */
    static LineAppearance measureLineAppearance(Pixels pixels, int width, int height, int[] rect) {
        LineAppearance result = new LineAppearance();
        if (pixels == null || !OcrRegion.valid(rect)) return result;
        int x0 = Math.max(0, rect[0]), y0 = Math.max(0, rect[1]);
        int x1 = Math.min(width, rect[2]), y1 = Math.min(height, rect[3]);
        int bw = x1 - x0, bh = y1 - y0;
        if (bw <= 0 || bh <= 0) return result;
        int tileW = bw > 140 ? 96 : bw;
        int numTiles = Math.max(1, (bw + tileW - 1) / tileW);
        int stepX = Math.max(1, bw / 160);
        int[] bins = new int[4096];
        long bgSumR = 0, bgSumG = 0, bgSumB = 0, bgCount = 0;
        long totalSampled = 0;
        int[] tileBgR = new int[numTiles], tileBgG = new int[numTiles], tileBgB = new int[numTiles];
        for (int t = 0; t < numTiles; t++) {
            int tx0 = x0 + t * tileW;
            int tx1 = Math.min(x1, tx0 + tileW);
            Arrays.fill(bins, 0);
            int sampled = 0;
            for (int y = y0; y < y1; y++) {
                for (int x = tx0; x < tx1; x += stepX) {
                    bins[bin12(pixels.at(x, y))]++;
                    sampled++;
                }
            }
            if (sampled == 0) continue;
            totalSampled += sampled;
            int mode = maxBin(bins);
            if (bins[mode] < sampled * .45) {
                int rx0 = Math.max(0, tx0 - 2), ry0 = Math.max(0, y0 - 2);
                int rx1 = Math.min(width, tx1 + 2), ry1 = Math.min(height, y1 + 2);
                for (int y = ry0; y < ry1; y++) {
                    for (int x = rx0; x < rx1; x += stepX) {
                        if (x >= tx0 && x < tx1 && y >= y0 && y < y1) continue;
                        bins[bin12(pixels.at(x, y))] += 2;
                    }
                }
                mode = maxBin(bins);
            }
            int br = ((mode >> 8) & 15) * 16 + 8;
            int bg = ((mode >> 4) & 15) * 16 + 8;
            int bb = (mode & 15) * 16 + 8;
            long tBgR = 0, tBgG = 0, tBgB = 0, tBgCnt = 0;
            for (int y = y0; y < y1; y++) {
                for (int x = tx0; x < tx1; x += stepX) {
                    int c = pixels.at(x, y);
                    int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                    int dist = Math.abs(r - br) + Math.abs(g - bg) + Math.abs(b - bb);
                    if (dist < 48) {
                        tBgR += r; tBgG += g; tBgB += b; tBgCnt++;
                    }
                }
            }
            if (tBgCnt > 0) {
                tileBgR[t] = (int) (tBgR / tBgCnt);
                tileBgG[t] = (int) (tBgG / tBgCnt);
                tileBgB[t] = (int) (tBgB / tBgCnt);
                bgSumR += tBgR; bgSumG += tBgG; bgSumB += tBgB; bgCount += tBgCnt;
            } else {
                tileBgR[t] = br; tileBgG[t] = bg; tileBgB[t] = bb;
            }
        }
        if (totalSampled == 0 || bgCount < totalSampled * .22) return result;
        int avgBgR = (int) (bgSumR / bgCount);
        int avgBgG = (int) (bgSumG / bgCount);
        int avgBgB = (int) (bgSumB / bgCount);
        // Pick the highest-contrast dominant ink color and also blend in overall foreground mean
        // so glowing game text (white core + red/cyan halo) preserves its true chromatic tint.
        int[] fgCount = new int[4096];
        long[] fgR = new long[4096], fgG = new long[4096], fgB = new long[4096];
        long allFgR = 0, allFgG = 0, allFgB = 0;
        long inkTotal = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x += stepX) {
                int t = Math.min(numTiles - 1, (x - x0) / tileW);
                int c = pixels.at(x, y);
                int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                int dist = Math.abs(r - tileBgR[t]) + Math.abs(g - tileBgG[t]) + Math.abs(b - tileBgB[t]);
                if (dist >= 54) {
                    int k = bin12(c);
                    fgCount[k]++;
                    fgR[k] += r; fgG[k] += g; fgB[k] += b;
                    allFgR += r; allFgG += g; allFgB += b;
                    inkTotal++;
                }
            }
        }
        if (inkTotal < 4 || inkTotal > totalSampled * .88) return result;
        double maxDist = 0;
        for (int i = 0; i < 4096; i++) {
            if (fgCount[i] < 2) continue;
            double d = Math.abs(fgR[i] / (double) fgCount[i] - avgBgR)
                    + Math.abs(fgG[i] / (double) fgCount[i] - avgBgG)
                    + Math.abs(fgB[i] / (double) fgCount[i] - avgBgB);
            if (d > maxDist) maxDist = d;
        }
        double bestWeight = 0;
        int selected = -1;
        for (int i = 0; i < 4096; i++) {
            if (fgCount[i] < 2) continue;
            double d = Math.abs(fgR[i] / (double) fgCount[i] - avgBgR)
                    + Math.abs(fgG[i] / (double) fgCount[i] - avgBgG)
                    + Math.abs(fgB[i] / (double) fgCount[i] - avgBgB);
            if (d < maxDist * .55) continue;
            double w = fgCount[i] * d * d;
            if (w > bestWeight) { bestWeight = w; selected = i; }
        }
        if (selected < 0) return result;
        int peakR = (int) (fgR[selected] / fgCount[selected]);
        int peakG = (int) (fgG[selected] / fgCount[selected]);
        int peakB = (int) (fgB[selected] / fgCount[selected]);
        result.hasInk = true;
        result.bgR = avgBgR; result.bgG = avgBgG; result.bgB = avgBgB;
        result.inkR = peakR; result.inkG = peakG; result.inkB = peakB;
        return result;
    }

    static boolean appearanceMatches(LineAppearance a, LineAppearance b) {
        if (!a.hasInk || !b.hasInk) return true;
        int bgDiff = Math.abs(a.bgR - b.bgR) + Math.abs(a.bgG - b.bgG) + Math.abs(a.bgB - b.bgB);
        if (bgDiff > 42) return false;
        return inkMatches(a, b);
    }

    private static boolean inkMatches(LineAppearance a, LineAppearance b) {
        int inkDiff = Math.abs(a.inkR - b.inkR) + Math.abs(a.inkG - b.inkG) + Math.abs(a.inkB - b.inkB);
        if (inkDiff > 72) return false;
        // Distinctly chromatic text (e.g. red warning / cyan heading) must never merge with neutral text.
        int chromaA = a.inkChroma(), chromaB = b.inkChroma();
        if ((chromaA >= 42 && chromaB <= 22) || (chromaB >= 42 && chromaA <= 22)) return false;
        return true;
    }

    /**
     * Vertical paragraph appearance check: compares ink colors strictly, and compares background over
     * the shared horizontal overlap so a long line over a textured modal still merges with its short
     * wrapped tail while separating genuinely different panels/bars.
     */
    private static boolean appearanceMatchesVertical(LineAppearance a, LineAppearance b, Pixels pixels,
                                                     int width, int height, int[] rectA, int[] rectB) {
        if (!a.hasInk || !b.hasInk) return true;
        if (!inkMatches(a, b)) return false;
        int bgDiff = Math.abs(a.bgR - b.bgR) + Math.abs(a.bgG - b.bgG) + Math.abs(a.bgB - b.bgB);
        if (bgDiff <= 42) return true;
        if (pixels == null) return false;
        int ovL = Math.max(rectA[0], rectB[0]);
        int ovR = Math.min(rectA[2], rectB[2]);
        if (ovR - ovL < 16) return false;
        int[] subA = sampleBackground(pixels, new int[]{ovL, rectA[1], ovR, rectA[3]}, width, height);
        int[] subB = sampleBackground(pixels, new int[]{ovL, rectB[1], ovR, rectB[3]}, width, height);
        int localBgDiff = Math.abs(subA[0] - subB[0]) + Math.abs(subA[1] - subB[1]) + Math.abs(subA[2] - subB[2]);
        return localBgDiff <= 48;
    }

    private static int[] horizontalInkSpan(Pixels pixels, int[] rect, LineAppearance app) {
        if (pixels == null || !OcrRegion.valid(rect) || !app.hasInk) return new int[]{rect[0], rect[2]};
        int minX = rect[2], maxX = rect[0];
        for (int y = rect[1]; y < rect[3]; y++) {
            for (int x = rect[0]; x < rect[2]; x++) {
                int c = pixels.at(x, y);
                int dist = Math.abs(((c >> 16) & 255) - app.bgR)
                        + Math.abs(((c >> 8) & 255) - app.bgG)
                        + Math.abs((c & 255) - app.bgB);
                if (dist >= 48) {
                    if (x < minX) minX = x;
                    if (x + 1 > maxX) maxX = x + 1;
                }
            }
        }
        if (minX >= maxX) return new int[]{rect[0], rect[2]};
        return new int[]{minX, maxX};
    }

    private static boolean isStrip(OcrRegion region) {
        if (region.lines.isEmpty()) return false;
        int tall = 0;
        for (int[] line : region.lines) {
            int w = line[2] - line[0], h = line[3] - line[1];
            if (w > 0 && h * 5 > w * 6) tall++;
        }
        return tall >= 1 && tall * 2 >= region.lines.size();
    }

    private static void removeRedundant(List<OcrLine> lines) {
        for (int i = 0; i < lines.size(); ) {
            boolean redundant = false;
            for (int j = 0; j < lines.size(); j++) {
                if (i == j || lines.get(j).confidence < .65f || lines.get(i).confidence >= .65f) continue;
                int[] a = lines.get(i).rect, b = lines.get(j).rect;
                int[] overlap = OcrRegion.intersect(a, b);
                boolean unread = isUnreadText(lines.get(i).text);
                if (OcrRegion.valid(overlap)
                        && (long) (overlap[2] - overlap[0]) * (overlap[3] - overlap[1])
                        >= (unread ? .45 : .65) * (long) (a[2] - a[0]) * (a[3] - a[1])
                        && (a[2] - a[0]) < (b[2] - b[0]) * .4f) {
                    redundant = true;
                    break;
                }
            }
            if (redundant) lines.remove(i);
            else i++;
        }
    }

    private static void joinFragments(List<OcrLine> lines, Pixels pixels, int width, int height, boolean allUnread) {
        for (int i = 0; i < lines.size(); i++) {
            for (int j = i + 1; j < lines.size(); ) {
                int[] a = lines.get(i).rect, b = lines.get(j).rect;
                boolean unreadA = isUnreadText(lines.get(i).text);
                boolean unreadB = isUnreadText(lines.get(j).text);
                if (!allUnread && (unreadA || unreadB)) { j++; continue; }
                // A vertical column is its own reading unit; same-column rows join in the pass below.
                if ((a[3] - a[1]) > (a[2] - a[0]) * 3 / 2 || (b[3] - b[1]) > (b[2] - b[0]) * 3 / 2) { j++; continue; }
                int hA = a[3] - a[1], hB = b[3] - b[1];
                int lineHeight = Math.min(hA, hB);
                if (Math.max(hA, hB) > Math.round(lineHeight * 1.32f)) { j++; continue; }
                int centreA = (a[1] + a[3]) / 2, centreB = (b[1] + b[3]) / 2;
                if (Math.abs(centreA - centreB) > Math.max(3, lineHeight / 3)) { j++; continue; }
                LineAppearance appA = measureLineAppearance(pixels, width, height, a);
                LineAppearance appB = measureLineAppearance(pixels, width, height, b);
                if (!appearanceMatches(appA, appB)) { j++; continue; }
                int[] leftRect = a[0] <= b[0] ? a : b;
                int[] rightRect = a[0] <= b[0] ? b : a;
                if (hasVerticalDivider(pixels, width, height, leftRect, rightRect)) { j++; continue; }
                int[] spanA = horizontalInkSpan(pixels, a, appA);
                int[] spanB = horizontalInkSpan(pixels, b, appB);
                int inkGap = Math.max(spanA[0], spanB[0]) - Math.min(spanA[1], spanB[1]);
                int maxInkGap = Math.max(10, Math.round(lineHeight * 1.45f));
                int[] overlap = OcrRegion.intersect(a, b);
                boolean touching = OcrRegion.valid(overlap) && (overlap[3] - overlap[1]) >= .75f * lineHeight
                        && (overlap[2] - overlap[0]) < .5f * Math.min(a[2] - a[0], b[2] - b[0])
                        && inkGap <= maxInkGap;
                int gap = Math.max(a[0], b[0]) - Math.min(a[2], b[2]);
                boolean adjacent = gap <= Math.max(6, Math.round(lineHeight * .85f))
                        && gap >= -Math.max(4, lineHeight / 4) && inkGap <= maxInkGap;
                if (touching || adjacent) {
                    OcrLine keep = lines.get(i), join = lines.get(j);
                    boolean rtl = hasRtlScript(keep.text) || hasRtlScript(join.text);
                    keep.text = (rtl ? a[0] > b[0] : a[0] < b[0])
                            ? keep.text + " " + join.text : join.text + " " + keep.text;
                    keep.confidence = Math.min(keep.confidence, join.confidence);
                    keep.rect = OcrRegion.union(a, b);
                    lines.remove(j);
                } else j++;
            }
        }
    }

    private static boolean crossesOtherLine(int[] joined, OcrLine line, OcrRegion region, List<OcrLine> lines) {
        for (OcrLine other : lines) {
            if (other == line) continue;
            boolean member = false;
            for (int[] rect : region.lines) if (Arrays.equals(rect, other.rect)) { member = true; break; }
            if (member) continue;
            int[] intersection = OcrRegion.intersect(joined, other.rect);
            if (OcrRegion.valid(intersection)
                    && (intersection[3] - intersection[1]) > Math.min(other.height(), line.height()) * .25f) return true;
        }
        return false;
    }

    private static float medianHeight(List<int[]> lines) {
        int[] heights = new int[lines.size()];
        for (int i = 0; i < heights.length; i++) heights[i] = lines.get(i)[3] - lines.get(i)[1];
        Arrays.sort(heights);
        return heights[heights.length / 2];
    }

    static boolean startsPassage(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t') continue;
            return PASSAGE_MARKERS.indexOf(c) >= 0;
        }
        return false;
    }

    static boolean startsListMarker(String text) {
        if (text == null) return false;
        int first = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != ' ' && c != '\t') { first = i; break; }
        }
        if (first < 0) return false;
        char ch = text.charAt(first);
        if (LIST_MARKERS.indexOf(ch) >= 0) return true;
        if (ch >= 0x2460 && ch <= 0x2473) return true;
        if ((ch == '(' || ch == '（') && first + 1 < text.length()) {
            char next = text.charAt(first + 1);
            if ((next >= '0' && next <= '9') || (next >= '０' && next <= '９')) return true;
        }
        return false;
    }

    static boolean endsSentence(String text) {
        if (text == null) return false;
        int end = text.length();
        while (end > 0) {
            char c = text.charAt(end - 1);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') end--;
            else break;
        }
        if (end == 0) return false;
        char c = text.charAt(end - 1);
        if (c == '.' || c == '!' || c == '?' || c == ':') return true;
        for (String closer : SENTENCE_CLOSERS) {
            int len = closer.length();
            if (end >= len && text.regionMatches(end - len, closer, 0, len)) return true;
        }
        return false;
    }

    private static boolean isUnreadText(String text) {
        return text == null || text.trim().isEmpty();
    }

    static boolean hasRtlScript(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0x0590 && c <= 0x08FF) || (c >= 0xFB1D && c <= 0xFDFF) || (c >= 0xFE70 && c <= 0xFEFF))
                return true;
        }
        return false;
    }

    private static boolean hasSeparator(Pixels pixels, int width, int height, int[] a, int[] b) {
        if (pixels == null) return false;
        int left = Math.max(a[0], b[0]), right = Math.min(a[2], b[2]);
        int span = right - left;
        if (span < 20 || b[1] <= a[3]) return false;
        int startY = Math.max(1, a[3]);
        int endY = Math.min(height, b[1]);
        for (int y = startY; y < endY; y++) {
            int edges = 0, currentRun = 0, maxRun = 0, gap = 0;
            for (int x = left; x < right; x++) {
                int upper = pixels.at(x, y - 1), current = pixels.at(x, y);
                int delta = Math.abs(((current >> 16) & 255) - ((upper >> 16) & 255))
                        + Math.abs(((current >> 8) & 255) - ((upper >> 8) & 255))
                        + Math.abs((current & 255) - (upper & 255));
                if (delta > 28) {
                    edges++;
                    currentRun += 1 + gap;
                    gap = 0;
                    if (currentRun > maxRun) maxRun = currentRun;
                } else if (currentRun > 0 && gap < 1) {
                    gap++;
                } else {
                    currentRun = 0;
                    gap = 0;
                }
            }
            if ((edges > .60 * span && maxRun >= Math.max(16, (int) (span * .40))) || edges > .75 * span)
                return true;
        }
        return false;
    }

    private static int bin12(int color) {
        return (((color >> 20) & 15) << 8) | (((color >> 12) & 15) << 4) | ((color >> 4) & 15);
    }

    private static int maxBin(int[] bins) {
        int mode = 0;
        for (int i = 1; i < bins.length; i++) if (bins[i] > bins[mode]) mode = i;
        return mode;
    }

    private static int[] clip(int[] rect, int width, int height) {
        int[] out = {Math.max(0, rect[0]), Math.max(0, rect[1]),
                Math.min(width, rect[2]), Math.min(height, rect[3])};
        return OcrRegion.valid(out) ? out : new int[]{0, 0, 0, 0};
    }

    /** How many units of a recognized line the layout should attribute to one glyph row. */
    static float coverage(String text, int width, int height) {
        if (width <= 0 || height <= 0) return 0f;
        double units = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0x2E80 && c <= 0x9FFF) || (c >= 0xAC00 && c <= 0xD7AF)
                    || (c >= 0xF900 && c <= 0xFAFF) || (c >= 0xFF00 && c <= 0xFFEF))
                units += .95;
            else
                units += .55;
        }
        return (float) Math.min(1, units * height / (double) width);
    }
}
