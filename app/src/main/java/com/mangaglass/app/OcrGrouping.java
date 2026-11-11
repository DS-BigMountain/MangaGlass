package com.mangaglass.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Groups recognized lines into translation passages, following the page shape the detector measured.
 * Tight glyph bounds recover the text band on flat backgrounds, fragments that share one baseline
 * join, a vertical page reads columns right to left, and the strips of one balloon join into a
 * single passage. Pure Java types keep the whole pass under JVM tests.
 */
final class OcrGrouping {
    interface Pixels { int at(int x, int y); }
    private static final String PASSAGE_MARKERS = "◆◇●○◎■□▲△※";

    private OcrGrouping() {}

    /** Recover the glyph band of a detector rectangle; the detector keeps the horizontal extent. */
    static int[] tighten(int[] rect, Pixels pixels, int width, int height) {
        int[] source = clip(rect, width, height);
        if (!OcrRegion.valid(source)) return source;
        int[] histogram = new int[32768];
        int count = 0;
        for (int y = source[1]; y < source[3]; y++)
            for (int x = source[0]; x < source[2]; x++) {
                int color = pixels.at(x, y);
                histogram[(((color >> 19) & 31) << 10) | (((color >> 11) & 31) << 5) | ((color >> 3) & 31)]++;
                count++;
            }
        if (count == 0) return source;
        int mode = 0;
        for (int i = 1; i < histogram.length; i++) if (histogram[i] > histogram[mode]) mode = i;
        int r = ((mode >> 10) & 31) * 8 + 4, g = ((mode >> 5) & 31) * 8 + 4, b = (mode & 31) * 8 + 4;
        int[] ink = {source[0], source[3], source[2], source[1]};
        for (int y = source[1]; y < source[3]; y++) {
            int inside = 0, left = source[2], right = source[0];
            for (int x = source[0]; x < source[2]; x++) {
                int color = pixels.at(x, y);
                int delta = Math.abs(((color >> 16) & 255) - r) + Math.abs(((color >> 8) & 255) - g)
                        + Math.abs((color & 255) - b);
                if (delta > 150) {
                    inside++;
                    left = Math.min(left, x);
                    right = Math.max(right, x + 1);
                }
            }
            if (inside >= Math.max(2, (source[2] - source[0]) / 500)) {
                ink[0] = Math.min(ink[0], left);
                ink[2] = Math.max(ink[2], right);
                ink[1] = Math.min(ink[1], y);
                ink[3] = y + 1;
            }
        }
        if (!OcrRegion.valid(ink) || ink[3] - ink[1] < 3) return source;
        ink[0] = source[0];
        ink[2] = source[2];
        float share = (ink[3] - ink[1]) / (float) (source[3] - source[1]);
        // A dense heading fills its box with glyph pixels and inverts the measurement.
        if (share > .92f || share < .3f) return source;
        return ink;
    }

    static List<OcrRegion> group(List<OcrLine> input, Pixels pixels, int width, int height) {
        if (width <= 0 || height <= 0) return Collections.emptyList();
        List<OcrLine> lines = new ArrayList<>();
        for (OcrLine line : input) {
            int[] rect = clip(line.rect, width, height);
            if (!OcrRegion.valid(rect) || line.text.trim().isEmpty()) continue;
            lines.add(new OcrLine(rect, line.text, line.confidence));
        }
        removeRedundant(lines);
        joinFragments(lines);
        boolean columnPage = isColumnPage(lines);
        lines.sort(columnPage ? COLUMN_ORDER : ROW_ORDER);
        List<OcrRegion> regions = groupParagraphs(lines, pixels, width, height);
        if (columnPage) joinStrips(regions);
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
        int tolerance = Math.max(8, Math.min(a.width(), a.height()) / 2);
        if (Math.abs(a.left() - b.left()) > tolerance) return Integer.compare(b.right(), a.right());
        return Integer.compare(a.top(), b.top());
    };

    static final Comparator<OcrLine> ROW_ORDER = (a, b) -> {
        if (a.top() != b.top()) return Integer.compare(a.top(), b.top());
        return Integer.compare(a.left(), b.left());
    };

    static List<OcrRegion> groupParagraphs(List<OcrLine> lines, Pixels pixels, int width, int height) {
        List<OcrRegion> regions = new ArrayList<>();
        for (OcrLine line : lines) {
            OcrRegion best = null;
            int bestGap = Integer.MAX_VALUE;
            boolean opensPassage = startsPassage(line.text);
            for (OcrRegion region : regions) {
                if (opensPassage) continue;
                int[] last = region.lines.get(region.lines.size() - 1);
                int gap = line.top() - last[3];
                float h = Math.max(1f, region.lineHeight);
                int overlap = Math.min(last[2], line.right()) - Math.max(last[0], line.left());
                if (gap < -h * .25f || gap > h * .9f || gap >= bestGap
                        || line.height() < h * .65f || line.height() > h * 1.45f) continue;
                if ((Math.abs(line.left() - last[0]) > h * 2 && Math.abs(line.right() - last[2]) > h)
                        || overlap < .65f * Math.min(last[2] - last[0], line.width())) continue;
                if (region.text.length() + line.text.length() + 1 > 8192
                        || hasSeparator(pixels, last, line.rect)) continue;
                int[] joined = OcrRegion.union(region.rect, line.rect);
                if (crossesOtherLine(joined, line, region, lines)) continue;
                best = region;
                bestGap = gap;
            }
            if (best == null) {
                regions.add(new OcrRegion(line.rect, line.text, line.rect, line.height(), line.confidence));
                continue;
            }
            best.confidence = Math.min(best.confidence, line.confidence);
            best.text = best.text + "\n" + line.text;
            best.lines.add(line.rect.clone());
            best.rect = OcrRegion.union(best.rect, line.rect);
            best.lineHeight = medianHeight(best.lines);
        }
        return regions;
    }

    /** One balloon wraps a sentence across strips; joining them keeps the passage readable. */
    private static void joinStrips(List<OcrRegion> regions) {
        for (int i = 0; i < regions.size(); i++) {
            for (int j = i + 1; j < regions.size(); ) {
                OcrRegion keep = regions.get(i), join = regions.get(j);
                float strip = Math.max(1, keep.lines.get(keep.lines.size() - 1)[2] - keep.lines.get(keep.lines.size() - 1)[0]);
                int overlap = Math.min(keep.rect[3], join.rect[3]) - Math.max(keep.rect[1], join.rect[1]);
                int spacing = keep.rect[0] - join.rect[2];
                if (!isStrip(keep) || !isStrip(join) || spacing < -strip * .5f || spacing > strip * 1.9f
                        || overlap * 100L <= Math.min(keep.rect[3] - keep.rect[1], join.rect[3] - join.rect[1]) * 45L) {
                    j++;
                    continue;
                }
                keep.text = keep.text + " " + join.text;
                keep.lines.addAll(join.lines);
                keep.rect = OcrRegion.union(keep.rect, join.rect);
                keep.confidence = Math.min(keep.confidence, join.confidence);
                regions.remove(j);
            }
        }
        // A strip is one glyph wide, so its width is the glyph size the layout follows.
        for (OcrRegion region : regions) {
            if (!isStrip(region)) continue;
            int[] widths = new int[region.lines.size()];
            for (int i = 0; i < widths.length; i++) widths[i] = region.lines.get(i)[2] - region.lines.get(i)[0];
            Arrays.sort(widths);
            region.lineHeight = widths[widths.length / 2];
        }
    }

    private static boolean isStrip(OcrRegion region) {
        if (region.lines.isEmpty()) return false;
        for (int[] line : region.lines) {
            int w = line[2] - line[0], h = line[3] - line[1];
            if (!(w > 0 && h * 5 > w * 6)) return false;
        }
        return true;
    }

    private static void removeRedundant(List<OcrLine> lines) {
        for (int i = 0; i < lines.size(); ) {
            boolean redundant = false;
            for (int j = 0; j < lines.size(); j++) {
                if (i == j || lines.get(j).confidence < .65f || lines.get(i).confidence >= .65f) continue;
                int[] a = lines.get(i).rect, b = lines.get(j).rect;
                int[] overlap = OcrRegion.intersect(a, b);
                boolean unread = lines.get(i).text.trim().isEmpty();
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

    private static void joinFragments(List<OcrLine> lines) {
        for (int i = 0; i < lines.size(); i++) {
            for (int j = i + 1; j < lines.size(); ) {
                int[] a = lines.get(i).rect, b = lines.get(j).rect;
                // A vertical column is its own reading unit; same-column rows join in the pass below.
                if ((a[3] - a[1]) > (a[2] - a[0]) * 3 / 2 || (b[3] - b[1]) > (b[2] - b[0]) * 3 / 2) { j++; continue; }
                int lineHeight = Math.min(a[3] - a[1], b[3] - b[1]);
                int centreA = (a[1] + a[3]) / 2, centreB = (b[1] + b[3]) / 2;
                int[] overlap = OcrRegion.intersect(a, b);
                boolean touching = OcrRegion.valid(overlap) && (overlap[3] - overlap[1]) >= .75f * lineHeight
                        && (overlap[2] - overlap[0]) < .5f * Math.min(a[2] - a[0], b[2] - b[0]);
                int gap = Math.max(a[0], b[0]) - Math.min(a[2], b[2]);
                boolean adjacent = Math.abs(centreA - centreB) <= lineHeight / 2
                        && gap <= lineHeight && gap >= -Math.max(4, lineHeight / 4);
                if (touching || adjacent) {
                    OcrLine keep = lines.get(i), join = lines.get(j);
                    keep.text = a[0] < b[0] ? keep.text + " " + join.text : join.text + " " + keep.text;
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

    private static boolean startsPassage(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t') continue;
            return PASSAGE_MARKERS.indexOf(c) >= 0;
        }
        return false;
    }

    private static boolean hasSeparator(Pixels pixels, int[] a, int[] b) {
        int left = Math.max(a[0], b[0]), right = Math.min(a[2], b[2]);
        if (right - left < 20) return false;
        for (int y = a[3] + 1; y < b[1] - 1; y++) {
            int edges = 0;
            for (int x = left; x < right; x++) {
                int upper = pixels.at(x, y - 1), current = pixels.at(x, y);
                int delta = Math.abs(((current >> 16) & 255) - ((upper >> 16) & 255))
                        + Math.abs(((current >> 8) & 255) - ((upper >> 8) & 255))
                        + Math.abs((current & 255) - (upper & 255));
                if (delta > 24) edges++;
            }
            if (edges > .65f * (right - left)) return true;
        }
        return false;
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
        for (int i = 0; i < text.length(); i++) units += text.charAt(i) < 128 ? .55 : .95;
        return (float) Math.min(1, units * height / (double) width);
    }
}
