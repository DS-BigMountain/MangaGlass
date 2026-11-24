package com.mangaglass.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Pure-Java spatial layout planner ported from ScreenAITranslator's SpatialLayout.cpp.
 * Handles pixel-level ink/background inspection, peer color harmonization, WCAG readability,
 * alignment and border detection, exact source font-size estimation, Pass A/B peer font-size
 * harmonization, and pixel-level stroke-only surface repair. Zero Android imports so JVM tests
 * and offline visual verification exercise the exact production code.
 */
final class SpatialLayoutPlanner {
    static final float BASE_OVERHANG_ALLOWANCE = 24f;
    private static final String PROSE_PUNCT = "。，、；：？！“”‘’「」『』【】（）()[].,;:?!\"؟؛،।॥";
    private static final String SEPARATE_LINE_CLOSERS = "。！？.!?】]؟؛۔।॥";
    private static final ExecutorService COVER_POOL = Executors.newFixedThreadPool(
            Math.min(4, Math.max(2, Runtime.getRuntime().availableProcessors() / 2)),
            r -> {
                Thread t = new Thread(r, "mangaglass-cover");
                t.setDaemon(true);
                return t;
            });

    static final class Input {
        final int[] bounds;
        final List<int[]> sourceLines;
        final float sourceLineHeight;
        final String source;
        final String translated;
        final Boolean vertical;
        final boolean hasSourceGeometry;
        final boolean untranslated;
        final boolean coverOnly;

        Input(int[] bounds, List<int[]> sourceLines, float sourceLineHeight, String source,
              String translated, Boolean vertical, boolean hasSourceGeometry,
              boolean untranslated, boolean coverOnly) {
            this.bounds = bounds == null ? new int[]{0, 0, 0, 0} : bounds.clone();
            this.sourceLines = sourceLines == null ? Collections.emptyList() : sourceLines;
            this.sourceLineHeight = sourceLineHeight;
            this.source = source == null ? "" : source;
            this.translated = translated == null ? "" : translated;
            this.vertical = vertical;
            this.hasSourceGeometry = hasSourceGeometry;
            this.untranslated = untranslated;
            this.coverOnly = coverOnly;
        }
    }

    static final class Item {
        final int index;
        boolean suppressed;
        boolean coverOnly;
        boolean column;
        boolean bold;
        boolean centerAligned;
        boolean wrapText;
        boolean isProse;
        boolean isShortLabel;
        boolean hasCore;
        boolean hasSurface;
        boolean drawRim;

        int[] sourceRect;
        int[] areaRect;
        List<int[]> detectedLines = new ArrayList<>();
        int[] coverRect;
        int[] coverArgb;

        int backgroundColor;
        int textColor;
        int rimColor;

        float detectedHeight;
        float coreHeight;
        float preferredFontSize;
        float harmonizedFontSize;
        float pitchRatio = 1.15f;
        float maxOverhang;
        float maxOverhangLeft;
        int padding;
        int allowedRows;
        float availableWidth;
        float availableHeight;

        String displayText = "";
        List<int[]> strips = new ArrayList<>();
        int[] stripBottoms = new int[0];
        String[] glyphs = new String[0];
        List<VerticalTypography.Token> verticalTokens = Collections.emptyList();

        Item(int index) { this.index = index; }
    }

    static final class PixelText {
        boolean hasColor;
        int color;
        boolean hasBackground;
        int background;
        final List<int[]> lines = new ArrayList<>();
        float lineHeight;
        float xHeight;
    }

    static final class TextTraits {
        boolean cjk, upper, lower, ascender, descender, digit, complexScript, stackedMarks;
    }

    private SpatialLayoutPlanner() {}

    /**
     * Builds the complete layout plan for all segments, including Pass A harmonized font sizes
     * and pixel-level stroke-only repaired cover bitmaps.
     */
    static List<Item> plan(int[] argb, int width, int height, List<Input> inputs, float scale)
            throws InterruptedException {
        if (width <= 0 || height <= 0 || argb == null || argb.length < width * height) {
            return Collections.emptyList();
        }
        float effectiveScale = Math.max(1f, scale);
        float overhangAllowance = BASE_OVERHANG_ALLOWANCE * Math.min(2.2f, Math.max(1f, effectiveScale * .65f));
        int n = inputs.size();
        List<Item> items = new ArrayList<>(n);
        float[] lineHeights = new float[n];
        float[] coreHeights = new float[n];
        boolean[] hasSurface = new boolean[n];
        int[] surfaceColors = new int[n];
        boolean[] hasTextColor = new boolean[n];
        int[] textColors = new int[n];
        boolean[] columns = new boolean[n];
        List<int[]> protectedRegions = new ArrayList<>(n);

        // Pass 1: Clip, keep-original filter, parallel pixel inspection, vertical classification.
        int[][] clippedBoxes = new int[n][];
        boolean[] activeMask = new boolean[n];
        int activeCount = 0;
        for (int i = 0; i < n; i++) {
            Input s = inputs.get(i);
            Item item = new Item(i);
            item.coverOnly = s.coverOnly;
            items.add(item);

            int[] clipped = clip(s.bounds, width, height);
            clippedBoxes[i] = clipped;
            if (!OcrRegion.valid(clipped) || s.untranslated) {
                item.suppressed = true;
                if (OcrRegion.valid(clipped)) protectedRegions.add(clipped);
                continue;
            }
            if (!s.coverOnly && shouldKeepOriginalPixels(s)) {
                item.suppressed = true;
                protectedRegions.add(clipped);
                continue;
            }
            activeMask[i] = true;
            activeCount++;
        }

        PixelText[] pixelResults = new PixelText[n];
        if (activeCount <= 2) {
            for (int i = 0; i < n; i++) {
                if (!activeMask[i]) continue;
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                pixelResults[i] = inspectTextPixels(argb, width, height, clippedBoxes[i]);
            }
        } else {
            List<Future<?>> inspectFutures = new ArrayList<>(activeCount);
            for (int i = 0; i < n; i++) {
                if (!activeMask[i]) continue;
                final int idx = i;
                inspectFutures.add(COVER_POOL.submit(() ->
                        pixelResults[idx] = inspectTextPixels(argb, width, height, clippedBoxes[idx])));
            }
            try {
                for (Future<?> f : inspectFutures) f.get();
            } catch (InterruptedException interrupted) {
                for (Future<?> f : inspectFutures) f.cancel(true);
                throw interrupted;
            } catch (ExecutionException ex) {
                for (Future<?> f : inspectFutures) f.cancel(true);
                Throwable cause = ex.getCause();
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                throw new RuntimeException(cause);
            }
        }

        for (int i = 0; i < n; i++) {
            if (!activeMask[i]) continue;
            Input s = inputs.get(i);
            Item item = items.get(i);
            int[] clipped = clippedBoxes[i];
            PixelText pixels = pixelResults[i];
            for (int[] l : s.sourceLines) {
                int[] cl = clip(l, width, height);
                if (OcrRegion.valid(cl)) item.detectedLines.add(cl);
            }
            lineHeights[i] = s.sourceLineHeight;
            boolean isCol = (Boolean.TRUE.equals(s.vertical)
                    && (isVerticalSource(item.detectedLines, clipped) || VerticalTypography.hasCjk(s.translated)))
                    || (s.vertical == null && isVerticalSource(item.detectedLines, clipped));
            if (isCol) {
                columns[i] = true;
                int[] widths;
                if (!item.detectedLines.isEmpty()) {
                    widths = new int[item.detectedLines.size()];
                    for (int k = 0; k < widths.length; k++) {
                        int[] l = item.detectedLines.get(k);
                        widths[k] = Math.max(1, l[2] - l[0]);
                    }
                    Arrays.sort(widths);
                    lineHeights[i] = Math.max(1f, widths[widths.length / 2]);
                } else {
                    int bw = Math.max(1, clipped[2] - clipped[0]);
                    int bh = Math.max(1, clipped[3] - clipped[1]);
                    if (bw * 3 >= bh * 2 && s.translated.length() >= 6) {
                        int estCols = Math.max(2, Math.min(6, Math.round((float) bw / Math.max(14f, bh * .26f))));
                        lineHeights[i] = Math.max(12f, (float) bw / estCols);
                    } else {
                        lineHeights[i] = Math.max(1f, bw);
                    }
                }
            }
            boolean detectedRows = !pixels.lines.isEmpty() && pixels.lineHeight >= 8f;
            if (!s.hasSourceGeometry && detectedRows) {
                item.detectedLines.clear();
                item.detectedLines.addAll(pixels.lines);
                if (!columns[i]) lineHeights[i] = pixels.lineHeight;
            } else if (!columns[i] && s.hasSourceGeometry && detectedRows
                    && pixels.lines.size() == item.detectedLines.size()
                    && pixels.lineHeight >= lineHeights[i] * .65f
                    && pixels.lineHeight <= lineHeights[i] * .95f
                    && !analyzeText(s.source).cjk) {
                lineHeights[i] = pixels.lineHeight;
            }
            if (lineHeights[i] <= 0f) {
                lineHeights[i] = pixels.lineHeight > 0f ? pixels.lineHeight : (clipped[3] - clipped[1]);
            }
            coreHeights[i] = pixels.xHeight;
            hasTextColor[i] = pixels.hasColor;
            textColors[i] = pixels.color;
            hasSurface[i] = pixels.hasBackground;
            surfaceColors[i] = pixels.background;
            item.sourceRect = clipped;
            protectedRegions.add(clipped);
        }

        // Pass 2: Peer glyph color harmonization on the same surface.
        harmonizeColors(argb, width, height, items, inputs, lineHeights, hasSurface, surfaceColors,
                hasTextColor, textColors);

        // Pass 3: Conflict check, alignment, border-clamped area bounds, pixel-level cover repair,
        // text preparation, and initial preferred font size.
        List<Runnable> coverJobs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            if ((i & 1) == 0 && Thread.currentThread().isInterrupted()) throw new InterruptedException();
            Item item = items.get(i);
            Input s = inputs.get(i);
            if (item.suppressed || item.sourceRect == null || !OcrRegion.valid(item.sourceRect)) {
                item.suppressed = true;
                continue;
            }
            int[] source = item.sourceRect;
            if (!item.coverOnly && s.hasSourceGeometry) {
                long own = area(source);
                for (int j = 0; j < n; j++) {
                    if (i == j || items.get(j).sourceRect == null) continue;
                    int[] other = items.get(j).sourceRect;
                    if (!conflicts(source, other)) continue;
                    long otherArea = area(other);
                    if (items.get(j).suppressed && otherArea < own) continue;
                    if (otherArea >= own) item.suppressed = true;
                }
            }
            if (item.suppressed) continue;

            int extBg = analyzeBackground(argb, width, height, source);
            int bgColor = hasSurface[i] ? surfaceColors[i] : extBg;
            item.backgroundColor = bgColor;
            item.hasSurface = hasSurface[i];
            float detectedHeight = lineHeights[i];
            item.detectedHeight = detectedHeight;
            item.coreHeight = coreHeights[i];
            item.column = columns[i];

            int pad = Math.max(1, (int) Math.ceil(2.0 * Math.min(2f, effectiveScale * .5f)));
            int margin = detectedHeight > 0 ? pad + clamp((int) Math.ceil(detectedHeight * .18f), 2, 8) : pad;
            int symExpand = pad;

            if (!columns[i] && !item.coverOnly) {
                symExpand = detectAlignmentAndBold(argb, width, height, i, items, inputs,
                        lineHeights, hasSurface, surfaceColors, pad, overhangAllowance);
            }

            int[] area = {
                    Math.max(0, source[0] - symExpand),
                    Math.max(0, source[1] - margin),
                    Math.min(width, source[2] + symExpand),
                    Math.min(height, source[3] + margin)
            };
            for (int[] other : protectedRegions) {
                if (!OcrRegion.valid(other) || Arrays.equals(other, source)) continue;
                if (conflicts(source, other)) {
                    if (s.hasSourceGeometry && area(other) >= area(source)) item.suppressed = true;
                    continue;
                }
                if (s.hasSourceGeometry && other[0] < area[2] && other[2] > area[0]) {
                    if (other[3] <= source[1] + 4 && other[1] < source[1])
                        area[1] = Math.max(area[1], Math.min(source[1], (source[1] + other[3] + 1) / 2));
                    if (other[1] >= source[3] - 4 && other[3] > source[3])
                        area[3] = Math.min(area[3], Math.max(source[3], (source[3] + other[1]) / 2));
                }
                if (s.hasSourceGeometry && other[1] < area[3] && other[3] > area[1]) {
                    if (other[2] <= source[0] + 4 && other[0] < source[0])
                        area[0] = Math.max(area[0], Math.min(source[0], (source[0] + other[2] + 1) / 2));
                    if (other[0] >= source[2] - 4 && other[2] > source[2])
                        area[2] = Math.min(area[2], Math.max(source[2], (source[2] + other[0]) / 2));
                }
            }
            if (item.suppressed) continue;

            if (!item.coverOnly) {
                for (int y = source[1] - 1; y >= area[1]; y--) {
                    if (isHBorderRow(argb, width, height, y, source[0], source[2], bgColor)) {
                        area[1] = Math.min(source[1], y + 1);
                        break;
                    }
                }
                for (int y = source[3]; y < area[3]; y++) {
                    if (isHBorderRow(argb, width, height, y, source[0], source[2], bgColor)) {
                        area[3] = Math.max(source[3], y);
                        break;
                    }
                }
                for (int x = source[0] - 1; x >= area[0]; x--) {
                    if (isVBorderCol(argb, width, height, x, source[1], source[3], bgColor)) {
                        area[0] = Math.min(source[0], x + 1);
                        break;
                    }
                }
                for (int x = source[2]; x < area[2]; x++) {
                    if (isVBorderCol(argb, width, height, x, source[1], source[3], bgColor)) {
                        area[2] = Math.max(source[2], x);
                        break;
                    }
                }
            }
            if (item.centerAligned) {
                int leftM = source[0] - area[0];
                int rightM = area[2] - source[2];
                int symM = Math.max(0, Math.min(leftM, rightM));
                area[0] = source[0] - symM;
                area[2] = source[2] + symM;
            }
            float maxOverhang = overhangAllowance;
            float maxOverhangLeft = overhangAllowance;
            if (!item.coverOnly && s.hasSourceGeometry) {
                for (int[] other : protectedRegions) {
                    if (!OcrRegion.valid(other) || Arrays.equals(other, source) || conflicts(source, other)) continue;
                    if (other[1] < area[3] && other[3] > area[1]) {
                        if (other[0] >= source[2]) {
                            maxOverhang = Math.min(maxOverhang, Math.max(0, other[0] - area[2] - 2));
                        }
                        if (other[2] <= source[0]) {
                            maxOverhangLeft = Math.min(maxOverhangLeft, Math.max(0, area[0] - other[2] - 2));
                        }
                    }
                }
                int scanEnd = Math.min(width, area[2] + (int) overhangAllowance);
                for (int x = area[2]; x < scanEnd; x++) {
                    if (isVBorderCol(argb, width, height, x, source[1], source[3], bgColor)) {
                        maxOverhang = Math.min(maxOverhang, Math.max(0, x - area[2]));
                        break;
                    }
                }
                int scanLeft = Math.max(0, area[0] - (int) overhangAllowance);
                for (int x = area[0] - 1; x >= scanLeft; x--) {
                    if (isVBorderCol(argb, width, height, x, source[1], source[3], bgColor)) {
                        maxOverhangLeft = Math.min(maxOverhangLeft, Math.max(0, area[0] - (x + 1)));
                        break;
                    }
                }
            }
            if (item.centerAligned) {
                float symCap = Math.min(maxOverhang, maxOverhangLeft);
                maxOverhang = symCap * 2f;
                maxOverhangLeft = symCap;
            }
            item.areaRect = area;
            item.maxOverhang = maxOverhang;
            item.maxOverhangLeft = maxOverhangLeft;
            int areaW = Math.max(1, area[2] - area[0]);
            int areaH = Math.max(1, area[3] - area[1]);
            item.padding = Math.max(0, Math.min(pad, (Math.min(areaW, areaH) - 1) / 2));
            item.availableWidth = Math.max(1f, areaW - 2 * item.padding);
            item.availableHeight = Math.max(1f, areaH - 2 * item.padding);

            // Pixel-level stroke-only surface repair.
            int grow = Math.max(4, (int) Math.ceil((detectedHeight > 0 ? detectedHeight : 12f) * .12f));
            int rightGrow = grow;
            if (columns[i] && hasSurface[i] && detectedHeight >= 16f) {
                // Extend slightly to the right of vertical Japanese columns to cleanly erase adjacent undetected furigana.
                int rubyPad = Math.max(grow, Math.min(24, Math.round(detectedHeight * .36f)));
                int safeRight = Math.min(width, area[2] + rubyPad);
                for (int x = area[2]; x < safeRight; x++) {
                    if (isVBorderCol(argb, width, height, x, source[1], source[3], bgColor)) {
                        safeRight = x;
                        break;
                    }
                }
                for (int[] other : protectedRegions) {
                    if (!OcrRegion.valid(other) || Arrays.equals(other, source)) continue;
                    if (other[1] < area[3] && other[3] > area[1] && other[0] >= source[2]) {
                        safeRight = Math.min(safeRight, other[0]);
                    }
                }
                rightGrow = Math.max(grow, safeRight - area[2]);
            }
            int[] coverRect = {
                    Math.max(0, area[0] - grow),
                    Math.max(0, area[1] - grow),
                    Math.min(width, area[2] + rightGrow),
                    Math.min(height, area[3] + grow)
            };
            if (OcrRegion.valid(coverRect)) {
                item.coverRect = coverRect;
                final Item targetItem = item;
                final int[] cRect = coverRect;
                final int[] cSource = source;
                final int cBg = bgColor;
                final boolean cReliable = hasSurface[i];
                final boolean cHasInk = hasTextColor[i];
                final int cInk = textColors[i];
                final int cGrow = grow;
                final int cRightGrow = rightGrow;
                final float cDetH = detectedHeight;
                final boolean cCol = columns[i];
                coverJobs.add(() -> targetItem.coverArgb = buildCover(argb, width, height, cRect, cSource,
                        targetItem.detectedLines, cBg, cReliable, cHasInk, cInk, cGrow, cRightGrow, cDetH, cCol));
            }

            // Text color and rim readability.
            int rawTextColor = hasTextColor[i] ? textColors[i]
                    : (luminance(bgColor) >= 140 ? 0xff202020 : 0xfff2f2f2);
            item.textColor = readableTextColor(rawTextColor, bgColor);
            float surfLum = luminance(bgColor);
            float fillLum = luminance(item.textColor);
            if (hasSurface[i] && surfLum >= 150f && fillLum < surfLum) {
                // Crisp dark or colored ink on verified uniform light surface: keep native look without crust.
                item.drawRim = false;
                item.rimColor = bgColor;
            } else {
                // Light/colored ink or non-uniform/gradient/artwork background: contrasting rim guarantees legibility.
                item.drawRim = true;
                item.rimColor = fillLum >= 128f ? 0xff000000 : 0xffffffff;
            }

            if (item.coverOnly) continue;

            if (columns[i]) {
                prepareVerticalColumn(argb, width, height, item, s, hasSurface[i], surfaceColors[i]);
            } else {
                prepareHorizontalText(item, s, areaH);
            }
        }

        // Run independent pixel-level stroke-only surface repairs in parallel across CPU cores.
        if (coverJobs.size() <= 2) {
            for (Runnable job : coverJobs) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                job.run();
            }
        } else {
            List<Future<?>> futures = new ArrayList<>(coverJobs.size());
            for (Runnable job : coverJobs) futures.add(COVER_POOL.submit(job));
            try {
                for (Future<?> f : futures) f.get();
            } catch (InterruptedException interrupted) {
                for (Future<?> f : futures) f.cancel(true);
                throw interrupted;
            } catch (ExecutionException ex) {
                for (Future<?> f : futures) f.cancel(true);
                Throwable cause = ex.getCause();
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                throw new RuntimeException(cause);
            }
        }

        // Pass 4 (Pass A): Harmonize preferred font sizes across peer segments on the same surface.
        harmonizePreferredSizes(items);
        return items;
    }

    /**
     * Pass B: When one passage in a tight dialogue/article cluster shrinks slightly to fit its box,
     * bring closely coupled peer passages down to the same face so adjacent bubbles and paragraphs
     * read as one coherent block.
     */
    static float[] harmonizeFinalSizes(List<Item> items, float[] fittedSizes, float scale) {
        int n = items.size();
        float[] finalSizes = fittedSizes.clone();
        float minFloor = 10f * Math.max(1f, scale * .5f);
        for (int i = 0; i < n; i++) {
            Item itemI = items.get(i);
            if (itemI.suppressed || itemI.coverOnly || fittedSizes[i] <= 0f) continue;
            float hpI = itemI.harmonizedFontSize;
            float minPeer = fittedSizes[i];
            for (int j = 0; j < n; j++) {
                if (i == j) continue;
                Item itemJ = items.get(j);
                if (itemJ.suppressed || itemJ.coverOnly || itemJ.column != itemI.column) continue;
                int bgTol = (!itemI.column && itemI.isProse && itemJ.isProse) ? 68 : 45;
                if (colorDist(itemI.backgroundColor, itemJ.backgroundColor) > bgTol) continue;
                float hpJ = itemJ.harmonizedFontSize;
                if (Math.max(hpI, hpJ) > Math.min(hpI, hpJ) * 1.18f) continue;
                if (fittedSizes[j] >= hpJ - .5f) continue;
                int[] ri = itemI.sourceRect, rj = itemJ.sourceRect;
                boolean coupled = false;
                if (itemI.column) {
                    // A cramped 1-column bubble (e.g. a narrow side exclamation) must not shrink roomy multi-column peers.
                    boolean roomyCol = itemJ.strips.size() >= 2 || itemI.strips.size() <= 1;
                    coupled = roomyCol && rectGap(ri, rj) <= Math.max(140, Math.round(hpI * 7f));
                } else {
                    boolean roomyPeer = itemJ.availableWidth >= hpJ * 4.5f || itemJ.detectedLines.size() >= 2;
                    if (!roomyPeer) continue;
                    int vGap = rj[1] >= ri[3] ? (rj[1] - ri[3]) : (ri[1] >= rj[3] ? (ri[1] - rj[3]) : 0);
                    int cxI = (ri[0] + ri[2]) / 2, cxJ = (rj[0] + rj[2]) / 2;
                    boolean aligned = Math.abs(ri[0] - rj[0]) <= Math.max(28, Math.round(hpI * 1.8f))
                            || Math.abs(cxI - cxJ) <= Math.max(24, Math.round(hpI * 1.5f));
                    if (itemI.isProse && itemJ.isProse && aligned && vGap <= Math.max(140, Math.round(hpI * 6f))) {
                        coupled = true;
                    } else if (rectGap(ri, rj) <= Math.max(48, Math.round(hpI * 2.5f))) {
                        coupled = true;
                    }
                }
                if (coupled && fittedSizes[j] >= Math.max(minFloor, hpI * .55f)) {
                    minPeer = Math.min(minPeer, fittedSizes[j]);
                }
            }
            if (fittedSizes[i] > minPeer * 1.06f) finalSizes[i] = minPeer;
        }
        return finalSizes;
    }

    private static void harmonizePreferredSizes(List<Item> items) {
        int n = items.size();
        for (int i = 0; i < n; i++) items.get(i).harmonizedFontSize = items.get(i).preferredFontSize;
        for (int i = 0; i < n; i++) {
            Item itemI = items.get(i);
            if (itemI.suppressed || itemI.coverOnly || itemI.preferredFontSize <= 0f) continue;
            float pi = itemI.preferredFontSize;
            int[] ri = itemI.sourceRect;
            List<Float> group = new ArrayList<>();
            for (int j = 0; j < n; j++) {
                Item itemJ = items.get(j);
                if (itemJ.suppressed || itemJ.coverOnly || itemJ.preferredFontSize <= 0f
                        || itemJ.column != itemI.column) continue;
                if (colorDist(itemI.backgroundColor, itemJ.backgroundColor) > 45) continue;
                float pj = itemJ.preferredFontSize;
                float maxRatio = (itemI.isProse != itemJ.isProse || itemI.bold != itemJ.bold) ? 1.12f : 1.24f;
                if (Math.max(pi, pj) > Math.min(pi, pj) * maxRatio) continue;
                int[] rj = itemJ.sourceRect;
                boolean related = (i == j);
                if (!related && itemI.column) {
                    related = rectGap(ri, rj) <= Math.max(220, Math.round(pi * 12f))
                            || Math.max(pi, pj) <= Math.min(pi, pj) * 1.18f;
                } else if (!related) {
                    int vGap = rj[1] >= ri[3] ? (rj[1] - ri[3]) : (ri[1] >= rj[3] ? (ri[1] - rj[3]) : 0);
                    int hGap = rj[0] >= ri[2] ? (rj[0] - ri[2]) : (ri[0] >= rj[2] ? (ri[0] - rj[2]) : 0);
                    int cxI = (ri[0] + ri[2]) / 2, cxJ = (rj[0] + rj[2]) / 2;
                    boolean aligned = Math.abs(ri[0] - rj[0]) <= Math.max(24, Math.round(pi * 1.6f))
                            || Math.abs(ri[2] - rj[2]) <= Math.max(24, Math.round(pi * 1.6f))
                            || Math.abs(cxI - cxJ) <= Math.max(20, Math.round(pi * 1.3f));
                    if (aligned && vGap <= Math.max(240, Math.round(pi * 14f))) {
                        related = true;
                    } else if (itemI.isProse && itemJ.isProse
                            && Math.max(pi, pj) <= Math.min(pi, pj) * 1.18f) {
                        related = true;
                    } else if (vGap <= Math.max(60, Math.round(pi * 3.5f))
                            && hGap <= Math.max(160, Math.round(pi * 8f))
                            && Math.max(pi, pj) <= Math.min(pi, pj) * 1.16f) {
                        related = true;
                    }
                }
                if (related) {
                    group.add(pj);
                    if (itemJ.detectedLines.size() >= 2 || itemJ.hasCore) group.add(pj);
                }
            }
            if (group.size() >= 2) {
                Collections.sort(group);
                itemI.harmonizedFontSize = group.get(group.size() / 2);
            }
        }
    }

    private static void prepareVerticalColumn(int[] argb, int width, int height, Item item, Input s,
                                              boolean hasSurface, int surfaceColor) {
        item.verticalTokens = VerticalTypography.tokenize(s.translated);
        List<String> glyphList = new ArrayList<>(item.verticalTokens.size());
        for (VerticalTypography.Token tok : item.verticalTokens) glyphList.add(tok.text);
        item.glyphs = glyphList.toArray(new String[0]);
        item.displayText = s.translated;
        for (int[] line : item.detectedLines) {
            if (OcrRegion.valid(line)) item.strips.add(line);
        }
        if (item.strips.isEmpty()) item.strips.add(item.sourceRect);
        // Sort strips right-to-left (top-to-bottom tiebreak) so reading order matches vertical Japanese.
        item.strips.sort((a, b) -> {
            int r = Integer.compare(b[2], a[2]);
            return r != 0 ? r : Integer.compare(a[1], b[1]);
        });
        int[] source = item.sourceRect;
        item.stripBottoms = new int[item.strips.size()];
        for (int k = 0; k < item.strips.size(); k++) {
            int[] strip = item.strips.get(k);
            int limitY = source[3];
            if (hasSurface && (strip[2] - strip[0]) >= 4) {
                int sbr = (surfaceColor >> 16) & 255, sbg = (surfaceColor >> 8) & 255, sbb = surfaceColor & 255;
                int x0 = strip[0] + (strip[2] - strip[0]) / 4;
                int x1 = Math.max(x0 + 1, strip[2] - (strip[2] - strip[0]) / 4);
                limitY = clamp(strip[3], source[1], source[3]);
                for (int y = limitY; y < source[3]; y++) {
                    int nonBg = 0, total = 0;
                    for (int x = x0; x < x1; x++) {
                        int c = argb[y * width + x];
                        int d = Math.abs(((c >> 16) & 255) - sbr) + Math.abs(((c >> 8) & 255) - sbg)
                                + Math.abs((c & 255) - sbb);
                        if (d >= 64) nonBg++;
                        total++;
                    }
                    if (total > 0 && nonBg * 100 >= total * 35) break;
                    limitY = y + 1;
                }
                limitY = Math.max(limitY, Math.min(source[3], strip[3]));
            }
            item.stripBottoms[k] = limitY;
        }
        item.preferredFontSize = Math.max(9f, item.detectedHeight * .86f);
    }

    static VerticalTypography.Layout buildVerticalLayout(Item item, float targetPreferred, boolean allowShrink,
                                                         int[] argb, int width, int height) {
        return VerticalTypography.layout(item.verticalTokens, item.areaRect, item.sourceRect,
                item.strips, item.stripBottoms, targetPreferred, allowShrink,
                argb, width, height, item.hasSurface, item.backgroundColor);
    }

    private static void prepareHorizontalText(Item item, Input s, int areaH) {
        float sourcePitch = 0f;
        if (item.detectedLines.size() >= 2) {
            float[] pitches = new float[item.detectedLines.size() - 1];
            for (int k = 1; k < item.detectedLines.size(); k++) {
                pitches[k - 1] = item.detectedLines.get(k)[1] - item.detectedLines.get(k - 1)[1];
            }
            Arrays.sort(pitches);
            if (pitches[pitches.length / 2] > 1f) sourcePitch = pitches[pitches.length / 2];
        }
        item.pitchRatio = item.detectedHeight > 0 && sourcePitch > 0
                ? clampFloat(sourcePitch / item.detectedHeight, 1.0f, 1.8f) : 1.15f;
        item.allowedRows = item.detectedLines.size();
        item.wrapText = item.allowedRows != 1;

        String text = s.translated.replace("\r\n", "\n").replace('\r', '\n');
        boolean explicitRows = rowsBreakExplicitly(item.detectedLines, item.sourceRect[2] - item.sourceRect[0]);
        boolean keepStructuredLines = explicitRows || hasSeparateTextLines(s.source) || hasKeyValueLines(s.source);
        if (s.sourceLines.size() > 1 && !keepStructuredLines) {
            text = reflowSingleLineBreaks(text);
        } else if (item.allowedRows == 1) {
            text = reflowSingleLineBreaks(text);
        }
        item.displayText = VerticalTypography.normalizeHorizontalPunctuation(text.trim());

        boolean hasProsePunct = hasAnyChar(item.displayText, PROSE_PUNCT) || hasAnyChar(s.source, PROSE_PUNCT);
        item.isProse = hasProsePunct || item.detectedLines.size() >= 2 || item.displayText.length() >= 14;
        item.isShortLabel = !hasProsePunct && item.detectedLines.size() <= 1 && item.displayText.length() <= 8;

        float detectedHeight = item.detectedHeight;
        if (detectedHeight > 0f) {
            TextTraits traits = analyzeText(s.source);
            float bandSize = (traits.cjk ? detectedHeight : inkBand(detectedHeight)) / glyphBandRatio(traits);
            float core = item.coreHeight;
            boolean validCore = core >= 4f && core < detectedHeight * .85f;
            item.hasCore = validCore;
            float emSize = traits.cjk ? bandSize
                    : (validCore ? (bandSize + core / glyphCoreRatio(traits)) * .5f : bandSize);
            float preferred = clampFloat(traits.cjk || !item.isShortLabel ? emSize : emSize * .92f, 9f, 144f);
            float rowCap = item.allowedRows >= 2 ? (float) areaH / item.allowedRows * 1.15f : (float) areaH;
            item.preferredFontSize = Math.min(preferred, Math.max(9f, rowCap));
        } else {
            item.preferredFontSize = clampFloat(areaH * .75f, 10f, 144f);
        }
    }

    private static int detectAlignmentAndBold(int[] argb, int width, int height, int i, List<Item> items,
                                              List<Input> inputs, float[] lineHeights, boolean[] hasSurface,
                                              int[] surfaceColors, int pad, float overhangAllowance) {
        Item item = items.get(i);
        Input s = inputs.get(i);
        int[] source = item.sourceRect;
        float detectedHeight = lineHeights[i];
        int symExpand = pad;

        boolean hasProsePunct = hasAnyChar(s.translated, PROSE_PUNCT) || hasAnyChar(s.source, PROSE_PUNCT);
        boolean isShortLabel = !hasProsePunct && !s.translated.isEmpty()
                && s.translated.length() <= 14 && s.source.length() <= 32;
        boolean rtlSource = OcrGrouping.hasRtlScript(s.source);
        boolean hasLeftAlignedPeer = false;
        boolean centerAlignedHeadingPair = false;

        for (int j = 0; j < items.size(); j++) {
            if (i == j) continue;
            Item otherItem = items.get(j);
            if (otherItem.suppressed || otherItem.sourceRect == null || !OcrRegion.valid(otherItem.sourceRect))
                continue;
            int[] other = otherItem.sourceRect;
            int vGap = other[1] >= source[3] ? (other[1] - source[3])
                    : (source[1] >= other[3] ? (source[1] - other[3]) : 0);
            int edgeDiff = rtlSource ? Math.abs(source[2] - other[2]) : Math.abs(source[0] - other[0]);
            if (edgeDiff <= Math.max(14, (int) (detectedHeight * .9f))
                    && vGap <= Math.max(120, (int) (detectedHeight * 6f))) {
                boolean sameSurface = !hasSurface[i] || !hasSurface[j]
                        || colorDist(surfaceColors[i], surfaceColors[j]) <= 45;
                if (sameSurface) hasLeftAlignedPeer = true;
            }
            if (other[2] <= source[0] || other[0] >= source[2]) continue;
            if (item.detectedLines.size() == 1 && !hasProsePunct && detectedHeight >= 22f
                    && other[1] >= source[3] - 2 && vGap <= Math.max(20, (int) (detectedHeight * 1.4f))
                    && lineHeights[j] > 0f && detectedHeight >= lineHeights[j] * 1.35f) {
                item.bold = true;
            }
            if (isShortLabel && item.detectedLines.size() == 1 && vGap <= Math.max(18, (int) (detectedHeight * 1.4f))) {
                int centerDiff = Math.abs((source[0] + source[2]) - (other[0] + other[2])) / 2;
                int leftShift = Math.abs(source[0] - other[0]);
                int rightShift = Math.abs(source[2] - other[2]);
                int widthDiff = Math.abs((source[2] - source[0]) - (other[2] - other[0]));
                if (centerDiff <= Math.max(6, (int) (detectedHeight * .15f))
                        && widthDiff >= Math.max(24, (int) (detectedHeight * 1.6f))
                        && leftShift >= 12 && rightShift >= 12
                        && Math.abs(leftShift - rightShift) <= Math.max(6, (int) (detectedHeight * .20f))) {
                    centerAlignedHeadingPair = true;
                }
            }
        }

        boolean multiLineCentered = false;
        if (isShortLabel && !hasLeftAlignedPeer && item.detectedLines.size() == 2) {
            float boxCenter = .5f * (source[0] + source[2]);
            float boxW = source[2] - source[0];
            int narrowCentered = 0;
            boolean allCentered = boxW > 0f;
            for (int[] line : item.detectedLines) {
                float lineCenter = .5f * (line[0] + line[2]);
                if (Math.abs(lineCenter - boxCenter) > Math.max(3f, boxW * .04f)) {
                    allCentered = false;
                    break;
                }
                if ((line[2] - line[0]) <= boxW * .78f) {
                    float leftGap = line[0] - source[0];
                    float rightGap = source[2] - line[2];
                    if (leftGap >= detectedHeight * .8f && rightGap >= detectedHeight * .8f
                            && Math.abs(leftGap - rightGap) <= Math.max(3f, detectedHeight * .20f)) {
                        narrowCentered++;
                    } else {
                        allCentered = false;
                        break;
                    }
                }
            }
            multiLineCentered = allCentered && narrowCentered >= 1;
        }

        boolean buttonCentered = false;
        if (isShortLabel && !hasLeftAlignedPeer && item.detectedLines.size() <= 1 && s.translated.length() <= 10) {
            int y0 = clamp(source[1] + (source[3] - source[1]) / 4, 0, height - 1);
            int y1 = clamp(source[3] - (source[3] - source[1]) / 4, y0 + 1, height);
            int maxScan = Math.max(24, (source[2] - source[0]) * 2);
            int bgR = (item.backgroundColor >> 16) & 255;
            int bgG = (item.backgroundColor >> 8) & 255;
            int bgB = item.backgroundColor & 255;
            int leftClear = 0;
            while (leftClear < maxScan && columnDiff(argb, width, i, items, source,
                    source[0] - 2 - leftClear, y0, y1, bgR, bgG, bgB) <= 28) leftClear++;
            boolean leftHitBorder = leftClear < maxScan && columnDiff(argb, width, i, items, source,
                    source[0] - 2 - leftClear, y0, y1, bgR, bgG, bgB) >= 48;
            int rightClear = 0;
            while (rightClear < maxScan && columnDiff(argb, width, i, items, source,
                    source[2] + 1 + rightClear, y0, y1, bgR, bgG, bgB) <= 28) rightClear++;
            boolean rightHitBorder = rightClear < maxScan && columnDiff(argb, width, i, items, source,
                    source[2] + 1 + rightClear, y0, y1, bgR, bgG, bgB) >= 48;
            if (leftHitBorder && rightHitBorder && leftClear >= 8 && rightClear >= 8
                    && Math.abs(leftClear - rightClear) <= Math.max(3, (int) (.08f * (leftClear + rightClear)))) {
                buttonCentered = true;
                symExpand = Math.max(pad, Math.min(Math.min(leftClear - 2, rightClear - 2),
                        (int) (overhangAllowance * .5f)));
            }
        }
        item.centerAligned = !hasLeftAlignedPeer && (multiLineCentered || centerAlignedHeadingPair || buttonCentered);
        return symExpand;
    }

    private static int columnDiff(int[] argb, int width, int selfIndex, List<Item> items, int[] source,
                                  int sx, int y0, int y1, int bgR, int bgG, int bgB) {
        if (sx < 0 || sx >= width) return 999;
        for (int j = 0; j < items.size(); j++) {
            if (j == selfIndex) continue;
            Item other = items.get(j);
            if (other.suppressed || other.sourceRect == null) continue;
            int[] o = other.sourceRect;
            if (sx >= o[0] && sx < o[2] && o[3] > source[1] && o[1] < source[3]) return 999;
        }
        int diffSum = 0, samples = 0;
        int stepY = Math.max(1, (y1 - y0) / 6);
        for (int sy = y0; sy < y1; sy += stepY) {
            int c = argb[sy * width + sx];
            diffSum += Math.abs(((c >> 16) & 255) - bgR)
                    + Math.abs(((c >> 8) & 255) - bgG)
                    + Math.abs((c & 255) - bgB);
            samples++;
        }
        return samples > 0 ? (diffSum / samples) : 999;
    }

    private static void harmonizeColors(int[] argb, int width, int height, List<Item> items, List<Input> inputs,
                                        float[] lineHeights, boolean[] hasSurface, int[] surfaceColors,
                                        boolean[] hasTextColor, int[] textColors) {
        int n = items.size();
        int[] resolvedBg = new int[n];
        Arrays.fill(resolvedBg, 0xffffffff);
        for (int i = 0; i < n; i++) {
            Item item = items.get(i);
            if (item.suppressed || item.sourceRect == null || !OcrRegion.valid(item.sourceRect)) continue;
            resolvedBg[i] = hasSurface[i] ? surfaceColors[i] : analyzeBackground(argb, width, height, item.sourceRect);
        }
        // Inherit missing colors from nearest same-surface peer.
        for (int i = 0; i < n; i++) {
            Item item = items.get(i);
            if (item.suppressed || item.sourceRect == null || hasTextColor[i] || item.coverOnly) continue;
            int bestDist = Integer.MAX_VALUE;
            boolean found = false;
            int inherited = 0;
            for (int j = 0; j < n; j++) {
                if (i == j || items.get(j).suppressed || items.get(j).sourceRect == null || !hasTextColor[j]) continue;
                if (colorDist(resolvedBg[i], resolvedBg[j]) > 36) continue;
                float hi = Math.max(1f, lineHeights[i]), hj = Math.max(1f, lineHeights[j]);
                if (Math.max(hi, hj) > Math.min(hi, hj) * 1.45f) continue;
                int dx = Math.abs(item.sourceRect[0] - items.get(j).sourceRect[0]);
                int dy = Math.abs(item.sourceRect[1] - items.get(j).sourceRect[1]);
                int d = dx * 2 + dy;
                if (d < bestDist) {
                    bestDist = d;
                    inherited = textColors[j];
                    found = true;
                }
            }
            hasTextColor[i] = true;
            textColors[i] = found ? inherited : (luminance(resolvedBg[i]) >= 140 ? 0xff202020 : 0xfff2f2f2);
        }
        // Harmonize peer ink colors on the same surface.
        int[] harmonized = textColors.clone();
        for (int i = 0; i < n; i++) {
            Item itemI = items.get(i);
            if (itemI.suppressed || itemI.sourceRect == null || !hasTextColor[i] || itemI.coverOnly) continue;
            int ci = textColors[i];
            int bgLumI = (int) luminance(resolvedBg[i]);
            int lumI = (int) luminance(ci);
            boolean neutralI = chroma(ci) <= 28;
            boolean darkOnLightI = lumI < bgLumI;
            List<Integer> rs = new ArrayList<>(), gs = new ArrayList<>(), bs = new ArrayList<>();
            for (int j = 0; j < n; j++) {
                Item itemJ = items.get(j);
                if (itemJ.suppressed || itemJ.sourceRect == null || !hasTextColor[j] || itemJ.coverOnly) continue;
                if (colorDist(resolvedBg[i], resolvedBg[j]) > 36) continue;
                float hi = Math.max(1f, lineHeights[i]), hj = Math.max(1f, lineHeights[j]);
                boolean sameTier = Math.max(hi, hj) <= Math.min(hi, hj) * 1.28f;
                boolean sameColPara = itemI.detectedLines.size() >= 2 && itemJ.detectedLines.size() >= 2
                        && Math.abs(itemI.sourceRect[0] - itemJ.sourceRect[0]) <= 28;
                if (!sameTier && !sameColPara) continue;
                int cj = textColors[j];
                int lumJ = (int) luminance(cj);
                boolean neutralJ = chroma(cj) <= 28;
                boolean darkOnLightJ = lumJ < (int) luminance(resolvedBg[j]);
                boolean compatible = false;
                if (neutralI && neutralJ && darkOnLightI == darkOnLightJ) {
                    int maxLumDiff = sameColPara ? 68 : 38;
                    compatible = Math.abs(lumI - lumJ) <= maxLumDiff;
                } else if (!neutralI && !neutralJ) {
                    compatible = colorDist(ci, cj) <= 64;
                }
                if (compatible) {
                    rs.add((cj >> 16) & 255);
                    gs.add((cj >> 8) & 255);
                    bs.add(cj & 255);
                }
            }
            if (rs.size() >= 2) {
                Collections.sort(rs);
                Collections.sort(gs);
                Collections.sort(bs);
                int pick = (neutralI && darkOnLightI && bgLumI >= 180) ? rs.size() / 3 : rs.size() / 2;
                harmonized[i] = 0xff000000 | (rs.get(pick) << 16) | (gs.get(pick) << 8) | bs.get(pick);
            }
        }
        System.arraycopy(harmonized, 0, textColors, 0, n);
    }

    /**
     * Pixel-level stroke-only surface repair ported from SpatialLayout.cpp:1193-1763.
     * Untouched pixels keep the exact captured artwork; only glyph strokes and their anti-aliasing
     * halo are reconstructed from local neighbor pixels and smooth block tone while protecting
     * horizontal/vertical structural borders.
     */
    static int[] buildCover(int[] argb, int imgW, int imgH, int[] region, int[] source,
                            List<int[]> detectedLines, int surfaceRef, boolean reliableSurface,
                            boolean hasGlyphColor, int glyphColor, int grow, float detectedHeight,
                            boolean isColumn) {
        return buildCover(argb, imgW, imgH, region, source, detectedLines, surfaceRef, reliableSurface,
                hasGlyphColor, glyphColor, grow, grow, detectedHeight, isColumn);
    }

    static int[] buildCover(int[] argb, int imgW, int imgH, int[] region, int[] source,
                            List<int[]> detectedLines, int surfaceRef, boolean reliableSurface,
                            boolean hasGlyphColor, int glyphColor, int grow, int rightGrow, float detectedHeight,
                            boolean isColumn) {
        int coverW = region[2] - region[0];
        int coverH = region[3] - region[1];
        if (coverW <= 0 || coverH <= 0) return new int[0];

        int sr = (surfaceRef >> 16) & 255, sg = (surfaceRef >> 8) & 255, sb = surfaceRef & 255;
        int gr = (glyphColor >> 16) & 255, gg = (glyphColor >> 8) & 255, gb = glyphColor & 255;

        int reach = Math.max(6, grow * 3);
        int tl = sampleCornerBand(argb, imgW, imgH, region[0] - reach, region[0] - 1, region[1] - reach, region[1] - 1, hasGlyphColor, gr, gg, gb, surfaceRef);
        int tr = sampleCornerBand(argb, imgW, imgH, region[2] + 1, region[2] + reach, region[1] - reach, region[1] - 1, hasGlyphColor, gr, gg, gb, surfaceRef);
        int bl = sampleCornerBand(argb, imgW, imgH, region[0] - reach, region[0] - 1, region[3] + 1, region[3] + reach, hasGlyphColor, gr, gg, gb, surfaceRef);
        int br = sampleCornerBand(argb, imgW, imgH, region[2] + 1, region[2] + reach, region[3] + 1, region[3] + reach, hasGlyphColor, gr, gg, gb, surfaceRef);
        int[][] corner = {
                {(tl >> 16) & 255, (tl >> 8) & 255, tl & 255},
                {(tr >> 16) & 255, (tr >> 8) & 255, tr & 255},
                {(bl >> 16) & 255, (bl >> 8) & 255, bl & 255},
                {(br >> 16) & 255, (br >> 8) & 255, br & 255}
        };

        final int blockSize = 24;
        int blocksX = (coverW + blockSize - 1) / blockSize;
        int blocksY = (coverH + blockSize - 1) / blockSize;
        int[] blockR = new int[blocksX * blocksY];
        int[] blockG = new int[blocksX * blocksY];
        int[] blockB = new int[blocksX * blocksY];
        int[] blockSpread = new int[blocksX * blocksY];

        int[] hist = new int[256];
        int maxBlockPixels = blockSize * blockSize;
        int[] allR = new int[maxBlockPixels], allG = new int[maxBlockPixels], allB = new int[maxBlockPixels];
        int[] closeR = new int[maxBlockPixels], closeG = new int[maxBlockPixels], closeB = new int[maxBlockPixels];
        int[] smoothR = new int[maxBlockPixels], smoothG = new int[maxBlockPixels], smoothB = new int[maxBlockPixels];

        for (int by = 0; by < blocksY; by++) {
            int y0 = by * blockSize, y1 = Math.min(coverH, y0 + blockSize);
            for (int bx = 0; bx < blocksX; bx++) {
                int x0 = bx * blockSize, x1 = Math.min(coverW, x0 + blockSize);
                int allN = 0, closeN = 0, smoothN = 0;
                for (int y = y0; y < y1; y++) {
                    int sy = clamp(region[1] + y, 0, imgH - 1);
                    int syU = Math.max(0, sy - 1), syD = Math.min(imgH - 1, sy + 1);
                    for (int x = x0; x < x1; x++) {
                        int sx = clamp(region[0] + x, 0, imgW - 1);
                        int c = argb[sy * imgW + sx];
                        int pr = (c >> 16) & 255, pg = (c >> 8) & 255, pb = c & 255;
                        allR[allN] = pr; allG[allN] = pg; allB[allN] = pb; allN++;
                        int distSurf = Math.abs(pr - sr) + Math.abs(pg - sg) + Math.abs(pb - sb);
                        int distGlyph = hasGlyphColor
                                ? Math.abs(pr - gr) + Math.abs(pg - gg) + Math.abs(pb - gb) : 999;
                        if (distSurf < 96 && distGlyph >= 42) {
                            closeR[closeN] = pr; closeG[closeN] = pg; closeB[closeN] = pb; closeN++;
                        }
                        int cL = argb[sy * imgW + Math.max(0, sx - 1)];
                        int cR = argb[sy * imgW + Math.min(imgW - 1, sx + 1)];
                        int cU = argb[syU * imgW + sx];
                        int cD = argb[syD * imgW + sx];
                        int grad = Math.max(Math.max(colorDist(c, cL), colorDist(c, cR)),
                                Math.max(colorDist(c, cU), colorDist(c, cD)));
                        if (grad <= 24 && (!reliableSurface || distSurf < 110) && distGlyph >= 54) {
                            smoothR[smoothN] = pr; smoothG[smoothN] = pg; smoothB[smoothN] = pb; smoothN++;
                        }
                    }
                }
                int[] pickR, pickG, pickB;
                int pickN;
                if (smoothN >= 6) { pickR = smoothR; pickG = smoothG; pickB = smoothB; pickN = smoothN; }
                else if (closeN >= 6) { pickR = closeR; pickG = closeG; pickB = closeB; pickN = closeN; }
                else if (reliableSurface && closeN < 4) { allR[0] = sr; allG[0] = sg; allB[0] = sb; pickR = allR; pickG = allG; pickB = allB; pickN = 1; }
                else { pickR = allR; pickG = allG; pickB = allB; pickN = allN; }
                int idx = by * blocksX + bx;
                int medR = medianOf(hist, pickR, pickN);
                int medG = medianOf(hist, pickG, pickN);
                int medB = medianOf(hist, pickB, pickN);
                blockR[idx] = medR; blockG[idx] = medG; blockB[idx] = medB;
                long spreadSum = 0;
                for (int k = 0; k < pickN; k++) {
                    spreadSum += Math.abs(pickR[k] - medR) + Math.abs(pickG[k] - medG) + Math.abs(pickB[k] - medB);
                }
                blockSpread[idx] = pickN == 0 ? 4 : clamp((int) (spreadSum / pickN), 1, 24);
            }
        }

        int totalPixels = coverW * coverH;
        byte[] glyphMask = new byte[totalPixels];
        byte[] rawInk = new byte[totalPixels];
        byte[] structMask = new byte[totalPixels];

        for (int y = 0; y < coverH; y++) {
            int sy = clamp(region[1] + y, 0, imgH - 1);
            int by = y / blockSize;
            for (int x = 0; x < coverW; x++) {
                int sx = clamp(region[0] + x, 0, imgW - 1);
                int c = argb[sy * imgW + sx];
                int pr = (c >> 16) & 255, pg = (c >> 8) & 255, pb = c & 255;
                int bIdx = by * blocksX + (x / blockSize);
                int departure = Math.abs(pr - blockR[bIdx]) + Math.abs(pg - blockG[bIdx]) + Math.abs(pb - blockB[bIdx]);
                int threshold = clamp(blockSpread[bIdx] * 3 + 16, 18, 52);
                boolean closerToGlyph = hasGlyphColor && departure > 14
                        && (Math.abs(pr - gr) + Math.abs(pg - gg) + Math.abs(pb - gb)) < departure;
                byte active = (departure > threshold || closerToGlyph) ? (byte) 1 : 0;
                int pIdx = y * coverW + x;
                rawInk[pIdx] = active;
                if (active != 0 && insideDetectedLine(sx, sy, source, detectedLines, grow, rightGrow, isColumn)) {
                    glyphMask[pIdx] = 1;
                }
            }
        }

        // Protect thin horizontal & vertical structural lines (table rules, card borders, underlines).
        markHorizontalStructures(rawInk, structMask, argb, imgW, imgH, region, source, coverW, coverH,
                sr, sg, sb, hasGlyphColor, gr, gg, gb, detectedHeight);
        markVerticalStructures(rawInk, structMask, argb, imgW, imgH, region, source, coverW, coverH,
                sr, sg, sb, hasGlyphColor, gr, gg, gb, detectedHeight);

        // Dilate glyph mask by 2px (excluding structMask) and build 2px halo ring.
        byte[] grown = new byte[totalPixels];
        byte[] halo = new byte[totalPixels];
        dilateExcluding(glyphMask, structMask, grown, coverW, coverH, 2);
        byte[] outer = new byte[totalPixels];
        dilateExcluding(grown, structMask, outer, coverW, coverH, 2);
        for (int idx = 0; idx < totalPixels; idx++) {
            if (outer[idx] != 0 && grown[idx] == 0 && structMask[idx] == 0) halo[idx] = 1;
        }

        // Copy untouched original pixels into cover.
        int[] cover = new int[totalPixels];
        for (int y = 0; y < coverH; y++) {
            int sy = clamp(region[1] + y, 0, imgH - 1);
            int dstRow = y * coverW;
            int srcRow = sy * imgW;
            for (int x = 0; x < coverW; x++) {
                int sx = clamp(region[0] + x, 0, imgW - 1);
                cover[dstRow + x] = 0xff000000 | (argb[srcRow + sx] & 0x00ffffff);
            }
        }

        // Directional IDW interpolation across erased stroke runs.
        boolean[] rowFull = new boolean[coverH];
        for (int y = 0; y < coverH; y++) {
            int rowOff = y * coverW;
            int x = 0;
            boolean anyUntouched = false;
            while (x < coverW) {
                if (grown[rowOff + x] == 0 || structMask[rowOff + x] != 0) {
                    if (structMask[rowOff + x] == 0) anyUntouched = true;
                    x++;
                    continue;
                }
                int runStart = x;
                while (x < coverW && grown[rowOff + x] != 0 && structMask[rowOff + x] == 0) x++;
                int runEnd = x;
                int leftX = runStart - 1;
                while (leftX >= 0 && (grown[rowOff + leftX] != 0 || rawInk[rowOff + leftX] != 0 || structMask[rowOff + leftX] != 0)) leftX--;
                if (leftX > 0 && grown[rowOff + leftX - 1] == 0 && rawInk[rowOff + leftX - 1] == 0 && structMask[rowOff + leftX - 1] == 0) leftX--;
                int rightX = runEnd;
                while (rightX < coverW && (grown[rowOff + rightX] != 0 || rawInk[rowOff + rightX] != 0 || structMask[rowOff + rightX] != 0)) rightX++;
                if (rightX + 1 < coverW && grown[rowOff + rightX + 1] == 0 && rawInk[rowOff + rightX + 1] == 0 && structMask[rowOff + rightX + 1] == 0) rightX++;
                boolean hasLeft = leftX >= 0 && structMask[rowOff + leftX] == 0 && grown[rowOff + leftX] == 0 && rawInk[rowOff + leftX] == 0;
                boolean hasRight = rightX < coverW && structMask[rowOff + rightX] == 0 && grown[rowOff + rightX] == 0 && rawInk[rowOff + rightX] == 0;
                float vBias = (runEnd - runStart > 8) ? 2.2f : 1.4f;
                for (int k = runStart; k < runEnd; k++) {
                    int yUp = y - 1;
                    while (yUp >= 0 && (grown[yUp * coverW + k] != 0 || rawInk[yUp * coverW + k] != 0 || structMask[yUp * coverW + k] != 0)) yUp--;
                    if (yUp > 0 && grown[(yUp - 1) * coverW + k] == 0 && rawInk[(yUp - 1) * coverW + k] == 0 && structMask[(yUp - 1) * coverW + k] == 0) yUp--;
                    int yDown = y + 1;
                    while (yDown < coverH && (grown[yDown * coverW + k] != 0 || rawInk[yDown * coverW + k] != 0 || structMask[yDown * coverW + k] != 0)) yDown++;
                    if (yDown + 1 < coverH && grown[(yDown + 1) * coverW + k] == 0 && rawInk[(yDown + 1) * coverW + k] == 0 && structMask[(yDown + 1) * coverW + k] == 0) yDown++;

                    float sumW = 0f, accR = 0f, accG = 0f, accB = 0f;
                    if (yUp >= 0) {
                        float w = vBias / Math.max(1f, y - yUp);
                        int c = cover[yUp * coverW + k];
                        accR += ((c >> 16) & 255) * w; accG += ((c >> 8) & 255) * w; accB += (c & 255) * w; sumW += w;
                    }
                    if (yDown < coverH) {
                        float w = vBias / Math.max(1f, yDown - y);
                        int c = cover[yDown * coverW + k];
                        accR += ((c >> 16) & 255) * w; accG += ((c >> 8) & 255) * w; accB += (c & 255) * w; sumW += w;
                    }
                    if (hasLeft) {
                        float w = 1f / Math.max(1f, k - leftX);
                        int c = cover[rowOff + leftX];
                        accR += ((c >> 16) & 255) * w; accG += ((c >> 8) & 255) * w; accB += (c & 255) * w; sumW += w;
                    }
                    if (hasRight) {
                        float w = 1f / Math.max(1f, rightX - k);
                        int c = cover[rowOff + rightX];
                        accR += ((c >> 16) & 255) * w; accG += ((c >> 8) & 255) * w; accB += (c & 255) * w; sumW += w;
                    }
                    if (sumW > 0f) {
                        int rr = clamp((int) (accR / sumW + .5f), 0, 255);
                        int gg2 = clamp((int) (accG / sumW + .5f), 0, 255);
                        int bb2 = clamp((int) (accB / sumW + .5f), 0, 255);
                        cover[rowOff + k] = 0xff000000 | (rr << 16) | (gg2 << 8) | bb2;
                    }
                }
            }
            if (!anyUntouched) rowFull[y] = true;
        }

        // 3-pass 3x3 smoothing inside the tight bounding box of the repaired glyph mask.
        int minGy = coverH, maxGy = -1, minGx = coverW, maxGx = -1;
        for (int y = 1; y + 1 < coverH; y++) {
            int rowOff = y * coverW;
            for (int x = 1; x + 1 < coverW; x++) {
                int idx = rowOff + x;
                if (grown[idx] != 0 && structMask[idx] == 0) {
                    if (y < minGy) minGy = y;
                    if (y > maxGy) maxGy = y;
                    if (x < minGx) minGx = x;
                    if (x > maxGx) maxGx = x;
                }
            }
        }
        if (maxGy >= minGy && maxGx >= minGx) {
            int[] scratch = new int[totalPixels];
            System.arraycopy(cover, 0, scratch, 0, totalPixels);
            for (int pass = 0; pass < 3; pass++) {
                for (int y = minGy; y <= maxGy; y++) {
                    int rowOff = y * coverW;
                    for (int x = minGx; x <= maxGx; x++) {
                        int idx = rowOff + x;
                        if (grown[idx] == 0 || structMask[idx] != 0) {
                            scratch[idx] = cover[idx];
                            continue;
                        }
                        int sumR = 0, sumG = 0, sumB = 0, cnt = 0;
                        for (int dy = -1; dy <= 1; dy++) {
                            int nRow = (y + dy) * coverW;
                            for (int dx = -1; dx <= 1; dx++) {
                                int nIdx = nRow + (x + dx);
                                if (structMask[nIdx] != 0) continue;
                                int c = cover[nIdx];
                                sumR += (c >> 16) & 255; sumG += (c >> 8) & 255; sumB += c & 255; cnt++;
                            }
                        }
                        if (cnt > 0) scratch[idx] = 0xff000000 | ((sumR / cnt) << 16) | ((sumG / cnt) << 8) | (sumB / cnt);
                    }
                }
                int[] tmp = cover; cover = scratch; scratch = tmp;
            }
        }

        // Fallback corner/surface blend for fully-covered scanlines.
        for (int y = 0; y < coverH; y++) {
            if (!rowFull[y]) continue;
            float fy = coverH > 1 ? y / (float) (coverH - 1) : .5f;
            int byClamped = clamp(y / blockSize, 0, blocksY - 1);
            int rowOff = y * coverW;
            for (int x = 0; x < coverW; x++) {
                if (structMask[rowOff + x] != 0) continue;
                float fx = coverW > 1 ? x / (float) (coverW - 1) : .5f;
                int bIdx = byClamped * blocksX + clamp(x / blockSize, 0, blocksX - 1);
                int[] rgb = new int[3];
                int[] ref = reliableSurface ? new int[]{sr, sg, sb}
                        : new int[]{blockR[bIdx], blockG[bIdx], blockB[bIdx]};
                float cornerW = reliableSurface ? .35f : .70f;
                for (int ch = 0; ch < 3; ch++) {
                    float topV = corner[0][ch] + (corner[1][ch] - corner[0][ch]) * fx;
                    float botV = corner[2][ch] + (corner[3][ch] - corner[2][ch]) * fx;
                    float val = topV + (botV - topV) * fy;
                    rgb[ch] = clamp((int) (val * cornerW + ref[ch] * (1f - cornerW) + .5f), 0, 255);
                }
                cover[rowOff + x] = 0xff000000 | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
            }
        }

        // Adaptive blending toward bilinear local smooth block tone so scanline seams disappear.
        for (int y = 0; y < coverH; y++) {
            float gy = (y + .5f) / blockSize - .5f;
            int y0 = (int) Math.floor(gy);
            float ty = gy - y0;
            int byClamped = clamp(y / blockSize, 0, blocksY - 1);
            int rowOff = y * coverW;
            for (int x = 0; x < coverW; x++) {
                int idx = rowOff + x;
                if (structMask[idx] != 0) continue;
                boolean isGrown = grown[idx] != 0;
                boolean isHalo = halo[idx] != 0;
                if (!isGrown && !isHalo) continue;
                int bxClamped = clamp(x / blockSize, 0, blocksX - 1);
                boolean smoothSurface = blockSpread[byClamped * blocksX + bxClamped] <= 8;
                float weight = isGrown ? (smoothSurface ? .86f : .58f) : (smoothSurface ? .45f : .22f);
                float gx = (x + .5f) / blockSize - .5f;
                int x0 = (int) Math.floor(gx);
                float tx = gx - x0;
                int bx0 = clamp(x0, 0, blocksX - 1), bx1 = clamp(x0 + 1, 0, blocksX - 1);
                int by0 = clamp(y0, 0, blocksY - 1), by1 = clamp(y0 + 1, 0, blocksY - 1);
                int i00 = by0 * blocksX + bx0, i10 = by0 * blocksX + bx1;
                int i01 = by1 * blocksX + bx0, i11 = by1 * blocksX + bx1;
                float tR = (blockR[i00] * (1 - tx) + blockR[i10] * tx) * (1 - ty)
                        + (blockR[i01] * (1 - tx) + blockR[i11] * tx) * ty;
                float tG = (blockG[i00] * (1 - tx) + blockG[i10] * tx) * (1 - ty)
                        + (blockG[i01] * (1 - tx) + blockG[i11] * tx) * ty;
                float tB = (blockB[i00] * (1 - tx) + blockB[i10] * tx) * (1 - ty)
                        + (blockB[i01] * (1 - tx) + blockB[i11] * tx) * ty;
                int c = cover[idx];
                float curR = (c >> 16) & 255, curG = (c >> 8) & 255, curB = c & 255;
                float dev = (Math.abs(tR - curR) + Math.abs(tG - curG) + Math.abs(tB - curB)) / 3f;
                float falloff = smoothSurface ? 36f : 24f;
                float adaptive = weight / (1f + dev / falloff);
                int rr = clamp((int) (curR + (tR - curR) * adaptive + .5f), 0, 255);
                int gg2 = clamp((int) (curG + (tG - curG) * adaptive + .5f), 0, 255);
                int bb2 = clamp((int) (curB + (tB - curB) * adaptive + .5f), 0, 255);
                cover[idx] = 0xff000000 | (rr << 16) | (gg2 << 8) | bb2;
            }
        }
        // Keep ONLY repaired strokes (grown) and feathered halo opaque; leave untouched pixels
        // and protected borders fully transparent (alpha = 0) so overlapping cover rects of
        // closely stacked list items NEVER repaint an adjacent item's original erased glyphs.
        for (int idx = 0; idx < totalPixels; idx++) {
            if (structMask[idx] != 0 || (grown[idx] == 0 && halo[idx] == 0)) {
                cover[idx] = 0x00000000;
            } else if (grown[idx] == 0) {
                cover[idx] = (0xc8 << 24) | (cover[idx] & 0x00ffffff);
            }
        }
        return cover;
    }

    private static boolean insideDetectedLine(int sx, int sy, int[] source, List<int[]> detectedLines,
                                              int grow, int rightGrow, boolean isColumn) {
        if (sx >= source[0] - grow && sx <= source[2] + rightGrow
                && sy >= source[1] - grow && sy <= source[3] + grow) return true;
        if (detectedLines.isEmpty()) return true;
        int hGrow = isColumn ? 2 : grow;
        int rGrow = isColumn ? rightGrow : grow;
        for (int[] line : detectedLines) {
            if (sx >= line[0] - hGrow && sx <= line[2] + rGrow
                    && sy >= line[1] - (grow + 2) && sy <= line[3] + (grow + 2)) return true;
        }
        return false;
    }

    private static void markHorizontalStructures(byte[] rawInk, byte[] structMask, int[] argb, int imgW, int imgH,
                                                 int[] region, int[] source, int coverW, int coverH,
                                                 int sr, int sg, int sb, boolean hasGlyph, int gr, int gg, int gb,
                                                 float detectedHeight) {
        for (int y = 0; y < coverH; y++) {
            int sy = region[1] + y;
            boolean outsideSourceY = sy < source[1] || sy >= source[3];
            int rowOff = y * coverW;
            int x = 0;
            while (x < coverW) {
                if (rawInk[rowOff + x] == 0) { x++; continue; }
                int x0 = x, gap = 0;
                while (x < coverW) {
                    if (rawInk[rowOff + x] != 0) { gap = 0; x++; }
                    else if (gap == 0 && x + 1 < coverW && rawInk[rowOff + x + 1] != 0) { gap = 1; x++; }
                    else break;
                }
                int x1 = (x > x0 && rawInk[rowOff + x - 1] == 0) ? x - 1 : x;
                int runLen = x1 - x0;
                if (runLen < 12) continue;
                int thinCols = 0;
                for (int k = x0; k < x1; k++) {
                    int up = y, down = y;
                    while (up > 0 && y - up < 5 && rawInk[(up - 1) * coverW + k] != 0) up--;
                    while (down + 1 < coverH && down - y < 5 && rawInk[(down + 1) * coverW + k] != 0) down++;
                    if (down - up + 1 <= 4) thinCols++;
                }
                if (thinCols * 10 < runLen * 7) continue;
                boolean fullSpan = runLen >= Math.max(28, (int) (coverW * .60f));
                boolean contLeft = x0 <= 1 && region[0] >= 3
                        && (outsideInk(argb, imgW, imgH, region[0] - 2, sy, sr, sg, sb, hasGlyph, gr, gg, gb)
                        || outsideInk(argb, imgW, imgH, region[0] - 3, sy, sr, sg, sb, hasGlyph, gr, gg, gb));
                boolean contRight = x1 >= coverW - 1 && region[2] + 2 < imgW
                        && (outsideInk(argb, imgW, imgH, region[2] + 1, sy, sr, sg, sb, hasGlyph, gr, gg, gb)
                        || outsideInk(argb, imgW, imgH, region[2] + 2, sy, sr, sg, sb, hasGlyph, gr, gg, gb));
                boolean outsideRule = outsideSourceY && runLen >= Math.max(16, (int) (detectedHeight * 1.1f));
                if (fullSpan || contLeft || contRight || outsideRule) {
                    for (int k = x0; k < x1; k++) {
                        int up = y, down = y;
                        while (up > 0 && y - up < 4 && rawInk[(up - 1) * coverW + k] != 0) up--;
                        while (down + 1 < coverH && down - y < 4 && rawInk[(down + 1) * coverW + k] != 0) down++;
                        if (down - up + 1 <= 4) {
                            for (int yy = up; yy <= down; yy++) structMask[yy * coverW + k] = 1;
                        } else if (rawInk[rowOff + k] != 0) {
                            structMask[rowOff + k] = 1;
                        }
                    }
                }
            }
        }
    }

    private static void markVerticalStructures(byte[] rawInk, byte[] structMask, int[] argb, int imgW, int imgH,
                                               int[] region, int[] source, int coverW, int coverH,
                                               int sr, int sg, int sb, boolean hasGlyph, int gr, int gg, int gb,
                                               float detectedHeight) {
        for (int x = 0; x < coverW; x++) {
            int sx = region[0] + x;
            boolean outsideSourceX = sx < source[0] || sx >= source[2];
            int y = 0;
            while (y < coverH) {
                if (rawInk[y * coverW + x] == 0) { y++; continue; }
                int y0 = y, gap = 0;
                while (y < coverH) {
                    if (rawInk[y * coverW + x] != 0) { gap = 0; y++; }
                    else if (gap == 0 && y + 1 < coverH && rawInk[(y + 1) * coverW + x] != 0) { gap = 1; y++; }
                    else break;
                }
                int y1 = (y > y0 && rawInk[(y - 1) * coverW + x] == 0) ? y - 1 : y;
                int runLen = y1 - y0;
                if (runLen < 12) continue;
                int thinRows = 0;
                for (int k = y0; k < y1; k++) {
                    int l = x, r = x;
                    while (l > 0 && x - l < 5 && rawInk[k * coverW + (l - 1)] != 0) l--;
                    while (r + 1 < coverW && r - x < 5 && rawInk[k * coverW + (r + 1)] != 0) r++;
                    if (r - l + 1 <= 4) thinRows++;
                }
                if (thinRows * 10 < runLen * 7) continue;
                boolean fullSpan = runLen >= Math.max(24, (int) (coverH * .60f));
                boolean contTop = y0 <= 1 && region[1] >= 3
                        && (outsideInk(argb, imgW, imgH, sx, region[1] - 2, sr, sg, sb, hasGlyph, gr, gg, gb)
                        || outsideInk(argb, imgW, imgH, sx, region[1] - 3, sr, sg, sb, hasGlyph, gr, gg, gb));
                boolean contBot = y1 >= coverH - 1 && region[3] + 2 < imgH
                        && (outsideInk(argb, imgW, imgH, sx, region[3] + 1, sr, sg, sb, hasGlyph, gr, gg, gb)
                        || outsideInk(argb, imgW, imgH, sx, region[3] + 2, sr, sg, sb, hasGlyph, gr, gg, gb));
                boolean outsideRule = outsideSourceX && runLen >= Math.max(16, (int) (detectedHeight * 1.1f));
                if (fullSpan || contTop || contBot || outsideRule) {
                    for (int k = y0; k < y1; k++) {
                        int l = x, r = x;
                        while (l > 0 && x - l < 4 && rawInk[k * coverW + (l - 1)] != 0) l--;
                        while (r + 1 < coverW && r - x < 4 && rawInk[k * coverW + (r + 1)] != 0) r++;
                        if (r - l + 1 <= 4) {
                            for (int xx = l; xx <= r; xx++) structMask[k * coverW + xx] = 1;
                        } else if (rawInk[k * coverW + x] != 0) {
                            structMask[k * coverW + x] = 1;
                        }
                    }
                }
            }
        }
    }

    private static boolean outsideInk(int[] argb, int imgW, int imgH, int sx, int sy,
                                      int sr, int sg, int sb, boolean hasGlyph, int gr, int gg, int gb) {
        if (sx < 0 || sx >= imgW || sy < 0 || sy >= imgH) return false;
        int c = argb[sy * imgW + sx];
        int pr = (c >> 16) & 255, pg = (c >> 8) & 255, pb = c & 255;
        int dSurf = Math.abs(pr - sr) + Math.abs(pg - sg) + Math.abs(pb - sb);
        int dGlyph = hasGlyph ? (Math.abs(pr - gr) + Math.abs(pg - gg) + Math.abs(pb - gb)) : 999;
        return dSurf >= 36 || dGlyph < dSurf;
    }

    private static void dilateExcluding(byte[] src, byte[] structMask, byte[] dst, int w, int h, int radius) {
        byte[] tmp = new byte[w * h];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                if (structMask[row + x] != 0) continue;
                int x0 = Math.max(0, x - radius), x1 = Math.min(w - 1, x + radius);
                for (int xx = x0; xx <= x1; xx++) {
                    if (src[row + xx] != 0 && structMask[row + xx] == 0) { tmp[row + x] = 1; break; }
                }
            }
        }
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int y0 = Math.max(0, y - radius), y1 = Math.min(h - 1, y + radius);
            for (int x = 0; x < w; x++) {
                if (structMask[row + x] != 0) continue;
                for (int yy = y0; yy <= y1; yy++) {
                    if (tmp[yy * w + x] != 0) { dst[row + x] = 1; break; }
                }
            }
        }
    }

    private static int sampleCornerBand(int[] argb, int imgW, int imgH, int x0, int x1, int y0, int y1,
                                        boolean skipGlyph, int gr, int gg, int gb, int fallback) {
        x0 = Math.max(0, x0); x1 = Math.min(imgW, x1);
        y0 = Math.max(0, y0); y1 = Math.min(imgH, y1);
        if (x1 <= x0 || y1 <= y0) return fallback;
        int stepX = Math.max(1, (x1 - x0) / 6), stepY = Math.max(1, (y1 - y0) / 8);
        long sR = 0, sG = 0, sB = 0;
        int count = 0;
        for (int y = y0; y < y1; y += stepY) {
            int row = y * imgW;
            for (int x = x0; x < x1; x += stepX) {
                int c = argb[row + x];
                int pr = (c >> 16) & 255, pg = (c >> 8) & 255, pb = c & 255;
                if (skipGlyph && Math.abs(pr - gr) + Math.abs(pg - gg) + Math.abs(pb - gb) < 90) continue;
                sR += pr; sG += pg; sB += pb; count++;
            }
        }
        if (count < 4) return fallback;
        return 0xff000000 | ((int) (sR / count) << 16) | ((int) (sG / count) << 8) | (int) (sB / count);
    }

    /** Pixel-level inspection of surface color, ink color, row geometry, and dense core height. */
    static PixelText inspectTextPixels(int[] argb, int width, int height, int[] box) {
        PixelText result = new PixelText();
        if (!OcrRegion.valid(box)) return result;
        int boxW = box[2] - box[0], boxH = box[3] - box[1];
        int[] hist = new int[4096];
        int step = Math.max(1, (int) Math.sqrt((double) boxW * boxH / 24000.0));
        int sampled = 0;
        for (int y = box[1]; y < box[3]; y += step) {
            int row = y * width;
            for (int x = box[0]; x < box[2]; x += step) {
                hist[bin12(argb[row + x])]++;
                sampled++;
            }
        }
        if (sampled == 0) return result;
        int mode = maxBin(hist);
        int br = ((mode >> 8) & 15) * 16 + 8, bg = ((mode >> 4) & 15) * 16 + 8, bb = (mode & 15) * 16 + 8;

        // 2px exterior ring fallback for tight boxes dominated by ink.
        if (hist[mode] < sampled * .45) {
            int[] ringHist = new int[4096];
            int ringSampled = 0;
            int rx0 = Math.max(0, box[0] - 2), rx1 = Math.min(width, box[2] + 2);
            int ry0 = Math.max(0, box[1] - 2), ry1 = Math.min(height, box[3] + 2);
            for (int y = ry0; y < ry1; y++) {
                int row = y * width;
                for (int x = rx0; x < rx1; x++) {
                    if (x >= box[0] && x < box[2] && y >= box[1] && y < box[3]) { x = box[2] - 1; continue; }
                    ringHist[bin12(argb[row + x])]++;
                    ringSampled++;
                }
            }
            if (ringSampled >= 8) {
                int rMode = maxBin(ringHist);
                if (ringHist[rMode] >= ringSampled * .45) {
                    int rbr = ((rMode >> 8) & 15) * 16 + 8;
                    int rbg = ((rMode >> 4) & 15) * 16 + 8;
                    int rbb = (rMode & 15) * 16 + 8;
                    if (Math.abs(br - rbr) + Math.abs(bg - rbg) + Math.abs(bb - rbb) > 72) {
                        br = rbr; bg = rbg; bb = rbb;
                    }
                }
            }
        }

        int[] bgChR = new int[256], bgChG = new int[256], bgChB = new int[256];
        int bgCount = 0;
        for (int y = box[1]; y < box[3]; y += step) {
            int row = y * width;
            for (int x = box[0]; x < box[2]; x += step) {
                int c = argb[row + x];
                int pr = (c >> 16) & 255, pg = (c >> 8) & 255, pb = c & 255;
                if (Math.abs(pr - br) + Math.abs(pg - bg) + Math.abs(pb - bb) < 48) {
                    bgChR[pr]++; bgChG[pg]++; bgChB[pb]++;
                    bgCount++;
                }
            }
        }
        boolean reliableBackdrop = bgCount >= sampled * .50;
        if (reliableBackdrop) {
            br = histMedian(bgChR, bgCount);
            bg = histMedian(bgChG, bgCount);
            bb = histMedian(bgChB, bgCount);
            result.hasBackground = true;
            result.background = 0xff000000 | (br << 16) | (bg << 8) | bb;
        } else {
            // No dominant uniform interior backdrop (e.g., glowing/outlined headings or textured
            // game banners): anchor foreground contrast against the true exterior background so
            // glowing halos are never mistaken for the background and background corners are never
            // mistaken for ink.
            int extBg = analyzeBackground(argb, width, height, box);
            br = (extBg >> 16) & 255;
            bg = (extBg >> 8) & 255;
            bb = extBg & 255;
        }

        final int tileW = 96;
        int numTiles = boxW > 128 ? Math.max(1, (boxW + tileW - 1) / tileW) : 1;
        int[] tR = new int[numTiles], tG = new int[numTiles], tB = new int[numTiles];
        Arrays.fill(tR, br); Arrays.fill(tG, bg); Arrays.fill(tB, bb);
        if (numTiles > 1) {
            int[] tileHist = new int[4096];
            int[] tcR = new int[256], tcG = new int[256], tcB = new int[256];
            for (int t = 0; t < numTiles; t++) {
                int tx0 = box[0] + t * tileW, tx1 = Math.min(box[2], tx0 + tileW);
                Arrays.fill(tileHist, 0);
                int tSampled = 0;
                for (int y = box[1]; y < box[3]; y += step) {
                    int row = y * width;
                    for (int x = tx0; x < tx1; x += step) {
                        tileHist[bin12(argb[row + x])]++;
                        tSampled++;
                    }
                }
                if (tSampled == 0) continue;
                int tm = maxBin(tileHist);
                int tbr = ((tm >> 8) & 15) * 16 + 8, tbg = ((tm >> 4) & 15) * 16 + 8, tbb = (tm & 15) * 16 + 8;
                Arrays.fill(tcR, 0); Arrays.fill(tcG, 0); Arrays.fill(tcB, 0);
                int tBgCnt = 0;
                for (int y = box[1]; y < box[3]; y += step) {
                    int row = y * width;
                    for (int x = tx0; x < tx1; x += step) {
                        int c = argb[row + x];
                        int pr = (c >> 16) & 255, pg = (c >> 8) & 255, pb = c & 255;
                        if (Math.abs(pr - tbr) + Math.abs(pg - tbg) + Math.abs(pb - tbb) < 48) {
                            tcR[pr]++; tcG[pg]++; tcB[pb]++; tBgCnt++;
                        }
                    }
                }
                if (tBgCnt >= tSampled * .45) {
                    tR[t] = histMedian(tcR, tBgCnt);
                    tG[t] = histMedian(tcG, tBgCnt);
                    tB[t] = histMedian(tcB, tBgCnt);
                }
            }
        }

        int[] counts = new int[4096];
        long[] rs = new long[4096], gs = new long[4096], bs = new long[4096];
        int foreground = 0;
        List<int[]> rows = new ArrayList<>();
        int activeTop = -1, left = box[2], right = box[0], lastRow = -1;
        boolean trackInk = (long) boxW * boxH <= 2_000_000L;
        byte[] inkMark = trackInk ? new byte[boxW * boxH] : null;
        int[] rowSampleR = (!reliableBackdrop && numTiles == 1) ? new int[68] : null;
        int[] rowSampleG = (!reliableBackdrop && numTiles == 1) ? new int[68] : null;
        int[] rowSampleB = (!reliableBackdrop && numTiles == 1) ? new int[68] : null;

        int[] rowCounts = new int[boxH];
        int[] rowLefts = new int[boxH];
        int[] rowRights = new int[boxH];
        boolean[] wideRow = new boolean[boxH];

        for (int y = box[1]; y < box[3]; y++) {
            int ry = y - box[1];
            int rowOff = y * width;
            int rowRed = br, rowGreen = bg, rowBlue = bb;
            if (rowSampleR != null) {
                int stride = Math.max(1, boxW / 64);
                int sCnt = 0;
                for (int x = box[0]; x < box[2] && sCnt < rowSampleR.length; x += stride) {
                    int c = argb[rowOff + x];
                    rowSampleR[sCnt] = (c >> 16) & 255;
                    rowSampleG[sCnt] = (c >> 8) & 255;
                    rowSampleB[sCnt] = c & 255;
                    sCnt++;
                }
                if (sCnt > 0) {
                    Arrays.sort(rowSampleR, 0, sCnt);
                    Arrays.sort(rowSampleG, 0, sCnt);
                    Arrays.sort(rowSampleB, 0, sCnt);
                    rowRed = rowSampleR[sCnt / 2];
                    rowGreen = rowSampleG[sCnt / 2];
                    rowBlue = rowSampleB[sCnt / 2];
                }
            }
            int rowCount = 0, rowLeft = box[2], rowRight = box[0], curRun = 0, maxRun = 0;
            for (int x = box[0]; x < box[2]; x++) {
                int c = argb[rowOff + x];
                int pr = (c >> 16) & 255, pg = (c >> 8) & 255, pb = c & 255;
                int refR, refG, refB;
                if (numTiles > 1) {
                    int t = (x - box[0]) / tileW;
                    refR = tR[t]; refG = tG[t]; refB = tB[t];
                } else {
                    refR = rowRed; refG = rowGreen; refB = rowBlue;
                }
                int dist = Math.abs(pr - refR) + Math.abs(pg - refG) + Math.abs(pb - refB);
                if (dist < 65) { curRun = 0; continue; }
                curRun++;
                if (curRun > maxRun) maxRun = curRun;
                rowCount++;
                if (x < rowLeft) rowLeft = x;
                rowRight = x + 1;
                if (inkMark != null) inkMark[ry * boxW + (x - box[0])] = 1;
            }
            rowCounts[ry] = rowCount;
            rowLefts[ry] = rowLeft;
            rowRights[ry] = rowRight;
            wideRow[ry] = (boxW >= 32 && maxRun > Math.max(28, (int) (boxW * .55)))
                    || rowCount >= boxW * .85;
        }

        // Only treat thin (<= 4px tall) wide bands as horizontal divider rules; tall bands belong
        // to glowing or outlined display headings whose bright stroke cores must still be sampled.
        boolean[] isRuleRow = new boolean[boxH];
        for (int ry = 0; ry < boxH; ) {
            if (!wideRow[ry]) { ry++; continue; }
            int end = ry;
            while (end < boxH && wideRow[end]) end++;
            if (end - ry <= 4) {
                for (int k = ry; k < end; k++) isRuleRow[k] = true;
            }
            ry = end;
        }

        for (int y = box[1]; y < box[3]; y++) {
            int ry = y - box[1];
            int rowOff = y * width;
            int rowCount = rowCounts[ry];
            int rowLeft = rowLefts[ry];
            int rowRight = rowRights[ry];
            if (!isRuleRow[ry] && rowCount >= 2) {
                for (int x = rowLeft; x < rowRight; x++) {
                    if (inkMark != null && inkMark[ry * boxW + (x - box[0])] == 0) continue;
                    int c = argb[rowOff + x];
                    int pr = (c >> 16) & 255, pg = (c >> 8) & 255, pb = c & 255;
                    int refR = numTiles > 1 ? tR[(x - box[0]) / tileW] : br;
                    int refG = numTiles > 1 ? tG[(x - box[0]) / tileW] : bg;
                    int refB = numTiles > 1 ? tB[(x - box[0]) / tileW] : bb;
                    if (Math.abs(pr - refR) + Math.abs(pg - refG) + Math.abs(pb - refB) < 65) continue;
                    int k = bin12(c);
                    counts[k]++; rs[k] += pr; gs[k] += pg; bs[k] += pb;
                    foreground++;
                }
                if (activeTop < 0) activeTop = y;
                lastRow = y;
                left = Math.min(left, rowLeft);
                right = Math.max(right, rowRight);
            } else if (activeTop >= 0 && y - lastRow > 1) {
                if (lastRow - activeTop >= 2) rows.add(new int[]{left, activeTop, right, lastRow + 1});
                activeTop = -1; left = box[2]; right = box[0];
            }
        }
        if (activeTop >= 0 && lastRow - activeTop >= 2) rows.add(new int[]{left, activeTop, right, lastRow + 1});

        // Dense ink core (xHeight) measurement.
        if (inkMark != null && !rows.isEmpty()) {
            List<Float> cores = new ArrayList<>();
            int[] colInk = new int[boxW];
            for (int[] line : rows) {
                int top = Math.max(0, line[1] - box[1]);
                int bottom = Math.min(boxH, line[3] - box[1]);
                if (bottom - top < 3) continue;
                Arrays.fill(colInk, 0);
                for (int y = top; y < bottom; y++) {
                    int row = y * boxW;
                    for (int x = 0; x < boxW; x++) colInk[x] += inkMark[row + x];
                }
                int bestL = 0, bestR = 0, runStart = -1, gap = 0;
                for (int x = 0; x < boxW; x++) {
                    if (colInk[x] > 0) {
                        if (runStart < 0) runStart = x;
                        gap = 0;
                    } else if (runStart >= 0 && ++gap > 2) {
                        int end = x - gap - 1;
                        if (end - runStart > bestR - bestL) { bestL = runStart; bestR = end; }
                        runStart = -1;
                    }
                }
                if (runStart >= 0 && boxW - runStart > bestR - bestL) { bestL = runStart; bestR = boxW; }
                if (bestR - bestL < (bottom - top) * 2 || bestR <= bestL) { bestL = 0; bestR = boxW; }
                int[] lineInk = new int[bottom - top];
                int peak = 0;
                for (int y = top; y < bottom; y++) {
                    int row = y * boxW, sum = 0;
                    for (int x = bestL; x < bestR; x++) sum += inkMark[row + x];
                    lineInk[y - top] = sum;
                    if (sum > peak) peak = sum;
                }
                if (peak < 3) continue;
                int dense = 0;
                for (int cnt : lineInk) if (cnt * 2 >= peak) dense++;
                if (dense >= 3) cores.add((float) dense);
            }
            if (!cores.isEmpty()) {
                Collections.sort(cores);
                result.xHeight = cores.get(cores.size() / 2);
            }
        }

        // Highest-contrast weighted foreground ink color.
        if (foreground >= 2 && foreground <= (long) boxW * boxH * .98) {
            double maxDist = 0;
            for (int i = 0; i < 4096; i++) {
                if (counts[i] < 2) continue;
                double d = Math.abs(rs[i] / (double) counts[i] - br)
                        + Math.abs(gs[i] / (double) counts[i] - bg)
                        + Math.abs(bs[i] / (double) counts[i] - bb);
                if (d > maxDist) maxDist = d;
            }
            double best = 0;
            int selected = -1;
            for (int i = 0; i < 4096; i++) {
                if (counts[i] < 2) continue;
                double d = Math.abs(rs[i] / (double) counts[i] - br)
                        + Math.abs(gs[i] / (double) counts[i] - bg)
                        + Math.abs(bs[i] / (double) counts[i] - bb);
                if (d < maxDist * .58) continue;
                double weight = counts[i] * d * d * d;
                if (weight > best) { best = weight; selected = i; }
            }
            if (selected >= 0) {
                int cr = (int) (rs[selected] / counts[selected]);
                int cg = (int) (gs[selected] / counts[selected]);
                int cb = (int) (bs[selected] / counts[selected]);
                int bgChroma = Math.max(br, Math.max(bg, bb)) - Math.min(br, Math.min(bg, bb));
                int fgChroma = Math.max(cr, Math.max(cg, cb)) - Math.min(cr, Math.min(cg, cb));
                if (bgChroma <= 20 && fgChroma <= 24) {
                    int neutral = clamp((int) (.299 * cr + .587 * cg + .114 * cb + .5), 0, 255);
                    cr = cg = cb = neutral;
                }
                result.hasColor = true;
                result.color = 0xff000000 | (cr << 16) | (cg << 8) | cb;
            }
        }

        if (rows.isEmpty() || rows.size() > 100) return result;
        int[] heights = new int[rows.size()];
        for (int i = 0; i < rows.size(); i++) heights[i] = rows.get(i)[3] - rows.get(i)[1];
        Arrays.sort(heights);
        int median = heights[heights.length / 2];
        if (median < 5 || median > 200) return result;
        int consistent = 0;
        for (int h : heights) if (h >= median * .6 && h <= median * 1.5) consistent++;
        if (consistent < (int) (heights.length * .8)) return result;
        for (int[] r : rows) {
            if (r[3] - r[1] < median * .5) continue;
            result.lines.add(new int[]{
                    Math.max(box[0], r[0] - 1),
                    Math.max(box[1], r[1] - 1),
                    Math.min(box[2], r[2] + 1),
                    Math.min(box[3], r[3] + 1)
            });
        }
        result.lineHeight = median;
        return result;
    }

    /** Exterior 6px ring median background sample (Capture.cpp:AnalyzeBackground). */
    static int analyzeBackground(int[] argb, int width, int height, int[] rect) {
        int[] local = clip(rect, width, height);
        if (!OcrRegion.valid(local)) return 0xff141414;
        int ring = 6;
        int left = Math.max(0, local[0] - ring), top = Math.max(0, local[1] - ring);
        int right = Math.min(width, local[2] + ring), bottom = Math.min(height, local[3] + ring);
        int[] hR = new int[256], hG = new int[256], hB = new int[256];
        int count = 0;
        int stride = Math.max(1, (right - left + bottom - top) / 4096);
        for (int y = top; y < bottom; y += stride) {
            int row = y * width;
            for (int x = left; x < right; x += stride) {
                if (x >= local[0] && x < local[2] && y >= local[1] && y < local[3]) {
                    x = local[2] - stride;
                    continue;
                }
                int c = argb[row + x];
                hR[(c >> 16) & 255]++;
                hG[(c >> 8) & 255]++;
                hB[c & 255]++;
                count++;
            }
        }
        if (count == 0) return 0xff141414;
        return 0xff000000 | (histMedian(hR, count) << 16) | (histMedian(hG, count) << 8) | histMedian(hB, count);
    }

    /** WCAG luminance contrast >= 3.0 while preserving original hue. */
    static int readableTextColor(int preferred, int background) {
        if (contrastRatio(preferred, background) >= 3.0) return preferred;
        double blackC = contrastRatio(0xff000000, background);
        double whiteC = contrastRatio(0xffffffff, background);
        int target = blackC >= whiteC ? 0 : 255;
        int pr = (preferred >> 16) & 255, pg = (preferred >> 8) & 255, pb = preferred & 255;
        for (int step = 1; step <= 16; step++) {
            float t = step / 16f;
            int r = clamp((int) (pr + (target - pr) * t + .5f), 0, 255);
            int g = clamp((int) (pg + (target - pg) * t + .5f), 0, 255);
            int b = clamp((int) (pb + (target - pb) * t + .5f), 0, 255);
            int cand = 0xff000000 | (r << 16) | (g << 8) | b;
            if (contrastRatio(cand, background) >= 3.0) return cand;
        }
        return target == 0 ? 0xff000000 : 0xffffffff;
    }

    private static double wcagLuminance(int color) {
        double r = linearize((color >> 16) & 255);
        double g = linearize((color >> 8) & 255);
        double b = linearize(color & 255);
        return .2126 * r + .7152 * g + .0722 * b;
    }

    private static double linearize(int channel) {
        double s = channel / 255.0;
        return s <= .04045 ? s / 12.92 : Math.pow((s + .055) / 1.055, 2.4);
    }

    private static double contrastRatio(int a, int b) {
        double la = wcagLuminance(a), lb = wcagLuminance(b);
        return (Math.max(la, lb) + .05) / (Math.min(la, lb) + .05);
    }

    static boolean shouldKeepOriginalPixels(Input s) {
        if (!s.hasSourceGeometry || s.translated.isEmpty()) return false;
        if (hasTargetScript(s.translated)) return false;
        String normTrans = normalizeAsciiAlnum(s.translated);
        String normOrig = normalizeAsciiAlnum(s.source);
        if (normTrans.isEmpty()) return true;
        if (normTrans.equals(normOrig)) return true;
        return normTrans.length() <= 2 && s.translated.length() <= 4;
    }

    static boolean isVerticalSource(List<int[]> lines, int[] box) {
        if (lines == null || lines.isEmpty()) {
            int w = box[2] - box[0], h = box[3] - box[1];
            return w > 0 && h * 5 > w * 6;
        }
        if (lines.size() == 1) {
            int[] l = lines.get(0);
            int w = l[2] - l[0], h = l[3] - l[1];
            return w > 0 && h * 5 > w * 6;
        }
        int tall = 0, maxH = 0;
        for (int[] l : lines) {
            int w = l[2] - l[0], h = l[3] - l[1];
            if (w > 0 && h * 5 > w * 6) tall++;
            if (h > maxH) maxH = h;
        }
        return tall >= 1 && tall * 2 >= lines.size() && maxH * 10 >= (box[3] - box[1]) * 6;
    }

    static float inkBand(float band) {
        float share = .70f + .15f * clampFloat((band - 14f) / 26f, 0f, 1f);
        return band * share;
    }

    static TextTraits analyzeText(String text) {
        TextTraits traits = new TextTraits();
        if (text == null) return traits;
        for (int i = 0; i < text.length(); ) {
            int code = text.codePointAt(i);
            i += Character.charCount(code);
            if ((code >= 0x2E80 && code <= 0x9FFF) || (code >= 0xAC00 && code <= 0xD7AF)
                    || (code >= 0xF900 && code <= 0xFAFF) || (code >= 0xFF00 && code <= 0xFF60)) {
                traits.cjk = true;
            } else if (code >= 'A' && code <= 'Z') {
                traits.upper = true;
            } else if (code >= '0' && code <= '9') {
                traits.digit = true;
            } else if (code >= 'a' && code <= 'z') {
                traits.lower = true;
                if ("bdfhklt".indexOf(code) >= 0) traits.ascender = true;
                if ("gjpqy".indexOf(code) >= 0) traits.descender = true;
            } else if ((code >= 0x00C0 && code <= 0x00D6) || (code >= 0x00D8 && code <= 0x00DE)
                    || (code >= 0x0391 && code <= 0x03AB) || (code >= 0x0400 && code <= 0x042F)) {
                traits.upper = true;
            } else if ((code >= 0x00DF && code <= 0x024F) || (code >= 0x1E00 && code <= 0x1EFF)
                    || (code >= 0x03AC && code <= 0x03CE) || (code >= 0x0430 && code <= 0x04FF)) {
                traits.lower = true;
                if (code >= 0x00E0 && code <= 0x00FE) traits.ascender = true;
            } else if ((code >= 0x0600 && code <= 0x06FF) || (code >= 0x0900 && code <= 0x097F)
                    || (code >= 0x0E00 && code <= 0x0E7F)) {
                traits.complexScript = true;
                int type = Character.getType(code);
                if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK) traits.stackedMarks = true;
            }
        }
        return traits;
    }

    static float glyphBandRatio(TextTraits t) {
        if (t.cjk) return 1f;
        if (t.complexScript) return t.stackedMarks ? .92f : .80f;
        if (t.descender) return .95f;
        if (t.upper || t.ascender || t.digit) return .74f;
        if (t.lower) return .54f;
        return .74f;
    }

    static float glyphCoreRatio(TextTraits t) {
        if (t.cjk) return .78f;
        if (t.complexScript) return .60f;
        if (!t.lower) return .70f;
        return .56f;
    }

    private static boolean rowsBreakExplicitly(List<int[]> lines, int boxWidth) {
        if (lines.size() < 2 || boxWidth <= 0) return false;
        int shortRows = 0;
        for (int[] line : lines) if ((line[2] - line[0]) < boxWidth * .85f) shortRows++;
        if (shortRows * 2 > lines.size()) return true;
        int wideGaps = 0;
        for (int i = 1; i < lines.size(); i++) {
            int gap = lines.get(i)[1] - lines.get(i - 1)[3];
            int band = Math.min(lines.get(i - 1)[3] - lines.get(i - 1)[1], lines.get(i)[3] - lines.get(i)[1]);
            if (band > 0 && gap > band * .6f) wideGaps++;
        }
        return wideGaps * 2 > lines.size() - 1;
    }

    private static boolean hasKeyValueLines(String original) {
        if (original == null) return false;
        String[] parts = original.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        if (parts.length < 2 || parts.length > 4) return false;
        for (String p : parts) {
            String t = p.trim();
            if (t.contains("：") || t.contains(":")) return true;
        }
        return false;
    }

    private static boolean hasSeparateTextLines(String original) {
        if (original == null) return false;
        int start = 0, complete = 0;
        for (int end = original.indexOf('\n'); end >= 0; end = original.indexOf('\n', start)) {
            if (end == start) return false;
            int last = end - 1;
            while (last >= start && (original.charAt(last) == ' ' || original.charAt(last) == '\t'
                    || original.charAt(last) == '\r')) last--;
            if (last < start || SEPARATE_LINE_CLOSERS.indexOf(original.charAt(last)) < 0) return false;
            complete++;
            start = end + 1;
        }
        return complete > 0;
    }

    private static String reflowSingleLineBreaks(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\n' && i > 0 && i + 1 < text.length()
                    && text.charAt(i - 1) != '\n' && text.charAt(i + 1) != '\n') {
                char prev = out.length() == 0 ? ' ' : out.charAt(out.length() - 1);
                char next = text.charAt(i + 1);
                boolean noSpacePrev = prev >= 0x2E80 || (prev >= 0x0E00 && prev <= 0x0EFF);
                boolean noSpaceNext = next >= 0x2E80 || (next >= 0x0E00 && next <= 0x0EFF);
                if (!noSpacePrev && !noSpaceNext && prev != ' ' && next != ' ') out.append(' ');
                continue;
            }
            out.append(ch);
        }
        return out.toString();
    }

    private static boolean isHBorderRow(int[] argb, int width, int height, int y, int x0, int x1, int bgColor) {
        if (y < 0 || y >= height || x1 - x0 < 20) return false;
        int br = (bgColor >> 16) & 255, bg = (bgColor >> 8) & 255, bb = bgColor & 255;
        int hits = 0, total = 0;
        int step = Math.max(1, (x1 - x0) / 80);
        int row = y * width;
        for (int x = x0; x < x1; x += step) {
            int c = argb[row + x];
            if (Math.abs(((c >> 16) & 255) - br) + Math.abs(((c >> 8) & 255) - bg)
                    + Math.abs((c & 255) - bb) >= 48) hits++;
            total++;
        }
        return total > 0 && hits * 10 >= total * 7;
    }

    private static boolean isVBorderCol(int[] argb, int width, int height, int x, int y0, int y1, int bgColor) {
        if (x < 0 || x >= width || y1 - y0 < 10) return false;
        int br = (bgColor >> 16) & 255, bg = (bgColor >> 8) & 255, bb = bgColor & 255;
        int hits = 0, total = 0;
        int step = Math.max(1, (y1 - y0) / 40);
        for (int y = y0; y < y1; y += step) {
            int c = argb[y * width + x];
            if (Math.abs(((c >> 16) & 255) - br) + Math.abs(((c >> 8) & 255) - bg)
                    + Math.abs((c & 255) - bb) >= 48) hits++;
            total++;
        }
        return total > 0 && hits * 10 >= total * 7;
    }

    private static boolean hasTargetScript(String text) {
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if ((ch >= 0x4E00 && ch <= 0x9FFF) || (ch >= 0x3400 && ch <= 0x4DBF)
                    || (ch >= 0xF900 && ch <= 0xFAFF) || (ch >= 0x3040 && ch <= 0x30FF)
                    || (ch >= 0xAC00 && ch <= 0xD7AF)) return true;
        }
        return false;
    }

    private static String normalizeAsciiAlnum(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= 'A' && ch <= 'Z') out.append((char) (ch - 'A' + 'a'));
            else if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) out.append(ch);
        }
        return out.toString();
    }

    private static boolean hasAnyChar(String text, String chars) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) if (chars.indexOf(text.charAt(i)) >= 0) return true;
        return false;
    }

    private static boolean conflicts(int[] a, int[] b) {
        int[] r = OcrRegion.intersect(a, b);
        if (!OcrRegion.valid(r)) return false;
        long shared = area(r);
        long smallest = Math.min(area(a), area(b));
        return smallest > 0 && shared * 2 > smallest;
    }

    private static long area(int[] r) {
        return (long) Math.max(0, r[2] - r[0]) * Math.max(0, r[3] - r[1]);
    }

    private static int rectGap(int[] a, int[] b) {
        int dx = Math.max(0, Math.max(a[0], b[0]) - Math.min(a[2], b[2]));
        int dy = Math.max(0, Math.max(a[1], b[1]) - Math.min(a[3], b[3]));
        return Math.max(dx, dy);
    }

    private static int colorDist(int a, int b) {
        return Math.abs(((a >> 16) & 255) - ((b >> 16) & 255))
                + Math.abs(((a >> 8) & 255) - ((b >> 8) & 255))
                + Math.abs((a & 255) - (b & 255));
    }

    private static int chroma(int c) {
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
    }

    static float luminance(int c) {
        return .299f * ((c >> 16) & 255) + .587f * ((c >> 8) & 255) + .114f * (c & 255);
    }

    private static int bin12(int c) {
        return (((c >> 20) & 15) << 8) | (((c >> 12) & 15) << 4) | ((c >> 4) & 15);
    }

    private static int maxBin(int[] hist) {
        int best = 0;
        for (int i = 1; i < hist.length; i++) if (hist[i] > hist[best]) best = i;
        return best;
    }

    private static int histMedian(int[] hist, int total) {
        int target = (total + 1) / 2, acc = 0;
        for (int v = 0; v < 256; v++) {
            acc += hist[v];
            if (acc >= target) return v;
        }
        return 128;
    }

    private static int medianOf(int[] hist, int[] values, int count) {
        if (count <= 0) return 128;
        Arrays.fill(hist, 0);
        for (int i = 0; i < count; i++) hist[clamp(values[i], 0, 255)]++;
        int target = count / 2 + 1, seen = 0;
        for (int v = 0; v < 256; v++) {
            seen += hist[v];
            if (seen >= target) return v;
        }
        return 255;
    }

    private static int[] clip(int[] r, int width, int height) {
        int[] out = {clamp(r[0], 0, width), clamp(r[1], 0, height),
                clamp(r[2], 0, width), clamp(r[3], 0, height)};
        return OcrRegion.valid(out) ? out : new int[]{0, 0, 0, 0};
    }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    private static float clampFloat(float v, float min, float max) { return Math.max(min, Math.min(max, v)); }
}
