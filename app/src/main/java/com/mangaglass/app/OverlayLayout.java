package com.mangaglass.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Spatial layout & surface-repair engine for Android Canvas, backed by {@link SpatialLayoutPlanner}.
 * Pass 1 rebuilds only the source glyph strokes (leaving artwork, gradients and UI borders intact);
 * Pass 2 paints every translated passage in its original font size tier, alignment and color.
 */
final class OverlayLayout {
    final List<Label> labels = new ArrayList<>();
    /** Coordinates unavailable; the overlay keeps a compact bottom reader panel for these. */
    final List<TextRegion> unplaced = new ArrayList<>();

    static final class ColumnStrip {
        final Rect rect;
        final String[] glyphs;

        ColumnStrip(Rect rect, String[] glyphs) {
            this.rect = rect;
            this.glyphs = glyphs;
        }
    }

    static final class Label {
        final Rect bounds;
        final Rect source;
        final Rect coverRect;
        final Bitmap coverBitmap;
        final String fullText;
        final float fontSize;
        final float linePitch;
        final float drawX;
        final float drawY;
        final RectF clipRect;
        final int textColor;
        final int rimColor;
        final boolean drawRim;
        final boolean bold;
        final boolean coverOnly;
        final StaticLayout textLayout;
        final VerticalTypography.Layout verticalLayout;

        Label(Rect bounds, Rect source, Rect coverRect, Bitmap coverBitmap, String fullText,
              float fontSize, float linePitch, float drawX, float drawY, RectF clipRect,
              int textColor, int rimColor, boolean drawRim, boolean bold, boolean coverOnly,
              StaticLayout textLayout, VerticalTypography.Layout verticalLayout) {
            this.bounds = new Rect(bounds);
            this.source = new Rect(source);
            this.coverRect = coverRect == null ? null : new Rect(coverRect);
            this.coverBitmap = coverBitmap;
            this.fullText = fullText == null ? "" : fullText;
            this.fontSize = fontSize;
            this.linePitch = linePitch;
            this.drawX = drawX;
            this.drawY = drawY;
            this.clipRect = clipRect;
            this.textColor = textColor;
            this.rimColor = rimColor;
            this.drawRim = drawRim;
            this.bold = bold;
            this.coverOnly = coverOnly;
            this.textLayout = textLayout;
            this.verticalLayout = verticalLayout;
        }

        String fullText() { return fullText; }

        float fontSize() { return fontSize; }

        int height() { return bounds.height(); }

        void drawText(Canvas canvas) {
            if (coverOnly) return;
            if (verticalLayout != null && !verticalLayout.columns.isEmpty()) {
                drawVerticalLayout(canvas);
                return;
            }
            if (textLayout == null) return;
            canvas.save();
            if (clipRect != null) canvas.clipRect(clipRect);
            canvas.translate(drawX, drawY);
            TextPaint tp = textLayout.getPaint();
            if (drawRim) {
                float rimW = Math.max(1.4f, fontSize * .135f);
                tp.setStyle(Paint.Style.STROKE);
                tp.setStrokeJoin(Paint.Join.ROUND);
                tp.setStrokeCap(Paint.Cap.ROUND);
                tp.setStrokeWidth(rimW);
                tp.setColor(rimColor);
                textLayout.draw(canvas);
            }
            float weightW = Math.max(.45f, fontSize * .032f);
            tp.setStyle(Paint.Style.STROKE);
            tp.setStrokeJoin(Paint.Join.ROUND);
            tp.setStrokeCap(Paint.Cap.ROUND);
            tp.setStrokeWidth(weightW);
            tp.setColor(textColor);
            textLayout.draw(canvas);
            tp.setStyle(Paint.Style.FILL);
            tp.setColor(textColor);
            textLayout.draw(canvas);
            canvas.restore();
        }

        private void drawVerticalLayout(Canvas canvas) {
            float sz = verticalLayout.fontSize;
            float pitch = verticalLayout.rowPitch;
            TextPaint tp = createPaint(sz, textColor, bold);
            Paint.FontMetrics fm = tp.getFontMetrics();
            Rect glyphBounds = new Rect();
            float rimW = Math.max(1.4f, sz * .135f);
            float weightW = Math.max(.45f, sz * .032f);
            canvas.save();
            if (clipRect != null) canvas.clipRect(clipRect);
            for (VerticalTypography.Column col : verticalLayout.columns) {
                float cx = col.centerX;
                for (int r = 0; r < col.tokens.size(); r++) {
                    VerticalTypography.Token tok = col.tokens.get(r);
                    float cellTop = col.topY + r * pitch;
                    float cellCy = cellTop + pitch * .5f;
                    switch (tok.mode) {
                        case ROTATE_90: {
                            // Center the glyph's actual ink bounding box on (cx, cellCy) before 90-deg CW rotation
                            // so ellipses ("…") and dashes ("—") align dead-center along the column axis.
                            tp.getTextBounds(tok.text, 0, tok.text.length(), glyphBounds);
                            float gx, baseline;
                            if (!glyphBounds.isEmpty()) {
                                gx = cx - glyphBounds.exactCenterX();
                                baseline = cellCy - glyphBounds.exactCenterY();
                            } else {
                                float gw = tp.measureText(tok.text);
                                gx = cx - gw * .5f;
                                baseline = cellCy - (fm.ascent + fm.descent) * .5f;
                            }
                            canvas.save();
                            canvas.rotate(90f, cx, cellCy);
                            drawGlyphPasses(canvas, tok.text, gx, baseline, tp, rimW, weightW);
                            canvas.restore();
                            break;
                        }
                        case CORNER_PUNCT: {
                            float gw = tp.measureText(tok.text);
                            // Optical upper-right placement matching vertical CJK typography for 。、，．
                            float gx = cx - gw * .5f + sz * .26f;
                            float baseline = cellCy - (fm.ascent + fm.descent) * .5f - sz * .26f;
                            drawGlyphPasses(canvas, tok.text, gx, baseline, tp, rimW, weightW);
                            break;
                        }
                        case TATE_CHU_YOKO: {
                            float rawW = tp.measureText(tok.text);
                            float targetW = sz * .98f;
                            float scaleX = rawW > targetW ? (targetW / rawW) : 1f;
                            float subSize = tok.text.length() >= 3 ? sz * .74f : sz * .86f;
                            tp.setTextSize(subSize);
                            tp.setTextScaleX(scaleX);
                            Paint.FontMetrics sfm = tp.getFontMetrics();
                            float gw = tp.measureText(tok.text);
                            float gx = cx - gw * .5f;
                            float baseline = cellCy - (sfm.ascent + sfm.descent) * .5f;
                            drawGlyphPasses(canvas, tok.text, gx, baseline, tp,
                                    Math.max(1.2f, subSize * .135f), Math.max(.4f, subSize * .032f));
                            tp.setTextScaleX(1f);
                            tp.setTextSize(sz);
                            break;
                        }
                        default: {
                            float gw = tp.measureText(tok.text);
                            float gx = cx - gw * .5f;
                            float baseline = cellCy - (fm.ascent + fm.descent) * .5f;
                            drawGlyphPasses(canvas, tok.text, gx, baseline, tp, rimW, weightW);
                            break;
                        }
                    }
                }
            }
            canvas.restore();
        }

        private void drawGlyphPasses(Canvas canvas, String g, float x, float baseline,
                                     TextPaint tp, float rimW, float weightW) {
            if (drawRim) {
                tp.setStyle(Paint.Style.STROKE);
                tp.setStrokeJoin(Paint.Join.ROUND);
                tp.setStrokeCap(Paint.Cap.ROUND);
                tp.setStrokeWidth(rimW);
                tp.setColor(rimColor);
                canvas.drawText(g, x, baseline, tp);
            }
            tp.setStyle(Paint.Style.STROKE);
            tp.setStrokeJoin(Paint.Join.ROUND);
            tp.setStrokeCap(Paint.Cap.ROUND);
            tp.setStrokeWidth(weightW);
            tp.setColor(textColor);
            canvas.drawText(g, x, baseline, tp);
            tp.setStyle(Paint.Style.FILL);
            tp.setColor(textColor);
            canvas.drawText(g, x, baseline, tp);
        }
    }

    private static final class Fitted {
        float fontSize;
        float linePitch;
        StaticLayout layout;
        VerticalTypography.Layout verticalLayout;
        boolean centerAligned;
        boolean wrap;
    }

    static OverlayLayout prepare(Bitmap bitmap, List<TextRegion> regions, float preferred) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        OverlayLayout result = new OverlayLayout();
        if (bitmap == null || bitmap.isRecycled() || regions == null || regions.isEmpty()) return result;
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        int[] data = new int[width * height];
        bitmap.getPixels(data, 0, width, 0, 0, width, height);

        List<SpatialLayoutPlanner.Input> inputs = new ArrayList<>(regions.size());
        for (TextRegion region : regions) {
            if (!region.coverOnly && region.translated.trim().isEmpty()) continue;
            Rect r = new Rect(region.bounds);
            if (r.isEmpty() || !r.intersect(0, 0, width, height)) {
                if (!region.coverOnly) result.unplaced.add(region);
                continue;
            }
            List<int[]> lines = new ArrayList<>(region.sourceLines.size());
            for (Rect line : region.sourceLines) {
                lines.add(new int[]{line.left, line.top, line.right, line.bottom});
            }
            inputs.add(new SpatialLayoutPlanner.Input(
                    new int[]{r.left, r.top, r.right, r.bottom},
                    lines,
                    region.sourceLineHeight,
                    region.source,
                    region.translated,
                    region.vertical,
                    region.hasSourceGeometry,
                    region.untranslated,
                    region.coverOnly));
        }

        float scale = Math.max(1f, Math.min(width, height) / 720f);
        List<SpatialLayoutPlanner.Item> items = SpatialLayoutPlanner.plan(data, width, height, inputs, scale);
        int n = items.size();
        Fitted[] fitted = new Fitted[n];
        float[] fittedSizes = new float[n];

        for (int i = 0; i < n; i++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            SpatialLayoutPlanner.Item item = items.get(i);
            if (item.suppressed || item.coverOnly) continue;
            fitted[i] = fitItem(item, item.harmonizedFontSize, true, scale, data, width, height);
            fittedSizes[i] = fitted[i].fontSize;
        }

        // Pass B: bring closely coupled peer segments down to a matching face when one shrinks.
        float[] finalSizes = SpatialLayoutPlanner.harmonizeFinalSizes(items, fittedSizes, scale);
        for (int i = 0; i < n; i++) {
            SpatialLayoutPlanner.Item item = items.get(i);
            if (item.suppressed || item.coverOnly || fitted[i] == null) continue;
            if (finalSizes[i] < fittedSizes[i] - .25f) {
                fitted[i] = fitItem(item, finalSizes[i], false, scale, data, width, height);
            }
        }

        // Pass C: resolve any remaining drawn overhang collisions between adjacent labels.
        resolveDrawnOverlaps(items, fitted, scale, data, width, height);

        for (int i = 0; i < n; i++) {
            SpatialLayoutPlanner.Item item = items.get(i);
            if (item.suppressed) continue;
            Rect coverRect = null;
            Bitmap coverBitmap = null;
            if (item.coverRect != null && item.coverArgb != null && item.coverArgb.length > 0) {
                int cw = item.coverRect[2] - item.coverRect[0];
                int ch = item.coverRect[3] - item.coverRect[1];
                if (cw > 0 && ch > 0 && item.coverArgb.length == cw * ch) {
                    coverRect = new Rect(item.coverRect[0], item.coverRect[1], item.coverRect[2], item.coverRect[3]);
                    coverBitmap = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
                    coverBitmap.setDensity(Bitmap.DENSITY_NONE);
                    coverBitmap.setPixels(item.coverArgb, 0, cw, 0, 0, cw, ch);
                }
            }
            Rect area = new Rect(item.areaRect[0], item.areaRect[1], item.areaRect[2], item.areaRect[3]);
            Rect source = new Rect(item.sourceRect[0], item.sourceRect[1], item.sourceRect[2], item.sourceRect[3]);
            if (item.coverOnly) {
                result.labels.add(new Label(area, source, coverRect, coverBitmap, "",
                        0f, 0f, 0f, 0f, null, 0, 0, false, false, true, null, null));
                continue;
            }
            Fitted f = fitted[i];
            if (f == null || (f.layout == null && (f.verticalLayout == null || f.verticalLayout.columns.isEmpty()))) {
                if (coverBitmap != null) coverBitmap.recycle();
                continue;
            }
            float pad = item.padding;
            RectF clipRect;
            if (f.verticalLayout != null) {
                float rimPad = Math.max(2f, f.fontSize * .20f);
                clipRect = new RectF(
                        Math.max(0f, Math.min(area.left, f.verticalLayout.contentLeft) - rimPad),
                        Math.max(0f, Math.min(area.top, f.verticalLayout.contentTop) - rimPad),
                        Math.min(width, Math.max(area.right, f.verticalLayout.contentRight) + rimPad),
                        Math.min(height, Math.max(area.bottom, f.verticalLayout.contentBottom) + rimPad));
            } else {
                float clipLeft = f.centerAligned
                        ? area.left + pad - item.maxOverhang * .5f
                        : area.left + pad;
                float clipRight = f.centerAligned
                        ? area.right - pad + item.maxOverhang * .5f
                        : area.right - pad + item.maxOverhang;
                clipRect = new RectF(
                        Math.max(0f, clipLeft - 2f),
                        Math.max(0f, area.top - 2f),
                        Math.min(width, clipRight + 2f),
                        Math.min(height, area.bottom + 2f));
            }

            float drawX = area.left + pad;
            float drawY = area.top + pad;
            if (f.layout != null) {
                if (f.centerAligned) {
                    drawX = area.left + pad - (f.layout.getWidth() - item.availableWidth) * .5f;
                }
                drawY = verticalAlignY(item, area, pad, f.layout, f.fontSize, f.linePitch);
            }
            result.labels.add(new Label(area, source, coverRect, coverBitmap, item.displayText,
                    f.fontSize, f.linePitch, drawX, drawY, clipRect,
                    item.textColor, item.rimColor, item.drawRim, item.bold, false,
                    f.layout, f.verticalLayout));
        }
        return result;
    }

    private static void resolveDrawnOverlaps(List<SpatialLayoutPlanner.Item> items, Fitted[] fitted,
                                             float scale, int[] argb, int width, int height) {
        int n = items.size();
        for (int pass = 0; pass < 2; pass++) {
            boolean changed = false;
            float[][] drawn = new float[n][];
            for (int i = 0; i < n; i++) {
                SpatialLayoutPlanner.Item it = items.get(i);
                Fitted f = fitted[i];
                if (it.suppressed || it.coverOnly || f == null) continue;
                drawn[i] = computeDrawnRect(it, f);
            }
            for (int i = 0; i < n; i++) {
                if (drawn[i] == null) continue;
                for (int j = i + 1; j < n; j++) {
                    if (drawn[j] == null) continue;
                    float ix0 = Math.max(drawn[i][0], drawn[j][0]);
                    float iy0 = Math.max(drawn[i][1], drawn[j][1]);
                    float ix1 = Math.min(drawn[i][2], drawn[j][2]);
                    float iy1 = Math.min(drawn[i][3], drawn[j][3]);
                    if (ix1 - ix0 <= 2f || iy1 - iy0 <= 2f) continue;
                    // Separate along the shallower penetration axis at midpoint of source boxes.
                    SpatialLayoutPlanner.Item a = items.get(i), b = items.get(j);
                    if ((ix1 - ix0) <= (iy1 - iy0)) {
                        SpatialLayoutPlanner.Item left = a.sourceRect[0] <= b.sourceRect[0] ? a : b;
                        SpatialLayoutPlanner.Item right = left == a ? b : a;
                        int li = left.index, ri = right.index;
                        int mid = (left.sourceRect[2] + right.sourceRect[0]) / 2;
                        if (mid > left.areaRect[0] + 4 && mid < right.areaRect[2] - 4) {
                            left.areaRect[2] = Math.min(left.areaRect[2], mid - 1);
                            left.maxOverhang = 0f;
                            left.availableWidth = Math.max(4f, left.areaRect[2] - left.areaRect[0] - 2 * left.padding);
                            right.areaRect[0] = Math.max(right.areaRect[0], mid + 1);
                            if (right.centerAligned) right.maxOverhang = 0f;
                            right.availableWidth = Math.max(4f, right.areaRect[2] - right.areaRect[0] - 2 * right.padding);
                            fitted[li] = fitItem(left, fitted[li].fontSize, true, scale, argb, width, height);
                            fitted[ri] = fitItem(right, fitted[ri].fontSize, true, scale, argb, width, height);
                            changed = true;
                        }
                    } else {
                        SpatialLayoutPlanner.Item top = a.sourceRect[1] <= b.sourceRect[1] ? a : b;
                        SpatialLayoutPlanner.Item bot = top == a ? b : a;
                        int ti = top.index, bi = bot.index;
                        int mid = (top.sourceRect[3] + bot.sourceRect[1]) / 2;
                        if (mid > top.areaRect[1] + 4 && mid < bot.areaRect[3] - 4) {
                            top.areaRect[3] = Math.min(top.areaRect[3], mid - 1);
                            top.availableHeight = Math.max(4f, top.areaRect[3] - top.areaRect[1] - 2 * top.padding);
                            bot.areaRect[1] = Math.max(bot.areaRect[1], mid + 1);
                            bot.availableHeight = Math.max(4f, bot.areaRect[3] - bot.areaRect[1] - 2 * bot.padding);
                            fitted[ti] = fitItem(top, fitted[ti].fontSize, true, scale, argb, width, height);
                            fitted[bi] = fitItem(bot, fitted[bi].fontSize, true, scale, argb, width, height);
                            changed = true;
                        }
                    }
                }
            }
            if (!changed) break;
        }
    }

    private static float[] computeDrawnRect(SpatialLayoutPlanner.Item it, Fitted f) {
        if (f.verticalLayout != null) {
            return new float[]{
                    f.verticalLayout.contentLeft, f.verticalLayout.contentTop,
                    f.verticalLayout.contentRight, f.verticalLayout.contentBottom
            };
        }
        if (f.layout == null) return null;
        Rect area = new Rect(it.areaRect[0], it.areaRect[1], it.areaRect[2], it.areaRect[3]);
        float pad = it.padding;
        float widest = 0f;
        for (int l = 0; l < f.layout.getLineCount(); l++) widest = Math.max(widest, f.layout.getLineMax(l));
        float x0 = f.centerAligned ? (area.left + pad + (it.availableWidth - widest) * .5f) : (area.left + pad);
        float y0 = verticalAlignY(it, area, pad, f.layout, f.fontSize, f.linePitch);
        return new float[]{x0, y0, x0 + widest, y0 + f.layout.getHeight()};
    }

    private static Fitted fitItem(SpatialLayoutPlanner.Item item, float targetPreferred,
                                  boolean allowShrink, float scale, int[] argb, int imgW, int imgH) {
        Fitted out = new Fitted();
        out.centerAligned = item.centerAligned;
        if (item.column) {
            VerticalTypography.Layout vl = SpatialLayoutPlanner.buildVerticalLayout(
                    item, targetPreferred, allowShrink, argb, imgW, imgH);
            out.fontSize = vl.fontSize;
            out.linePitch = vl.rowPitch;
            out.verticalLayout = vl;
            return out;
        }

        boolean wrap = item.wrapText;
        int maxRows = item.allowedRows;
        float size = targetPreferred;
        StaticLayout layout = buildHorizontalLayout(item, size, wrap);
        if (allowShrink && !fitsHorizontal(layout, item, size, maxRows)) {
            // Single-line prose with generous vertical space can wrap to 2 lines instead of shrinking heavily.
            if (!wrap && item.isProse && !item.isShortLabel && item.availableHeight >= targetPreferred * 2.15f) {
                StaticLayout wrappedTrial = buildHorizontalLayout(item, targetPreferred, true);
                if (fitsHorizontal(wrappedTrial, item, targetPreferred, 2) && wrappedTrial.getLineCount() <= 2) {
                    wrap = true;
                    maxRows = 2;
                    layout = wrappedTrial;
                }
            }
            if (!fitsHorizontal(layout, item, size, maxRows)) {
                // Allow shrinking cleanly down to a safe readable floor so text is never clipped.
                float low = Math.min(targetPreferred, Math.max(7f, targetPreferred * .35f));
                float high = targetPreferred;
                StaticLayout best = buildHorizontalLayout(item, low, wrap);
                for (int step = 0; step < 14; step++) {
                    float mid = (low + high) * .5f;
                    StaticLayout trial = buildHorizontalLayout(item, mid, wrap);
                    if (fitsHorizontal(trial, item, mid, maxRows)) {
                        low = mid;
                        best = trial;
                    } else {
                        high = mid;
                    }
                }
                // If wrapping with one extra row allows a larger readable size while still fitting height, prefer it.
                if (wrap && maxRows > 0 && !fitsHorizontal(best, item, low, maxRows)) {
                    int relaxedRows = maxRows + 2;
                    float rLow = low, rHigh = targetPreferred;
                    StaticLayout rBest = buildHorizontalLayout(item, rLow, true);
                    for (int step = 0; step < 12; step++) {
                        float mid = (rLow + rHigh) * .5f;
                        StaticLayout trial = buildHorizontalLayout(item, mid, true);
                        if (fitsHorizontal(trial, item, mid, relaxedRows)) {
                            rLow = mid;
                            rBest = trial;
                        } else {
                            rHigh = mid;
                        }
                    }
                    if (fitsHorizontal(rBest, item, rLow, relaxedRows)) {
                        low = rLow;
                        best = rBest;
                    }
                }
                size = low;
                layout = best;
            }
        }
        out.fontSize = size;
        out.linePitch = size * item.pitchRatio;
        out.layout = layout;
        out.wrap = wrap;
        return out;
    }

    @android.annotation.SuppressLint("InlinedApi")
    private static StaticLayout buildHorizontalLayout(SpatialLayoutPlanner.Item item, float fontSize, boolean wrap) {
        TextPaint paint = createPaint(fontSize, item.textColor, item.bold);
        Layout.Alignment alignment = item.centerAligned ? Layout.Alignment.ALIGN_CENTER : Layout.Alignment.ALIGN_NORMAL;
        float maxW = wrap ? item.availableWidth : Math.max(item.availableWidth + item.maxOverhang,
                paint.measureText(item.displayText) + 4f);
        int layoutWidth = Math.max(1, (int) Math.ceil(wrap ? item.availableWidth : maxW));
        Paint.FontMetrics fm = paint.getFontMetrics();
        float naturalHeight = Math.max(1f, fm.descent - fm.ascent);
        float mult = Math.max(.92f, Math.min(1.6f, (fontSize * item.pitchRatio) / naturalHeight));
        StaticLayout.Builder builder = StaticLayout.Builder.obtain(
                        item.displayText, 0, item.displayText.length(), paint, layoutWidth)
                .setAlignment(alignment)
                .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                .setIncludePad(false)
                .setLineSpacing(0f, mult)
                .setBreakStrategy(item.centerAligned
                        ? android.graphics.text.LineBreaker.BREAK_STRATEGY_BALANCED
                        : android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        if (Build.VERSION.SDK_INT >= 28) builder.setUseLineSpacingFromFallbacks(true);
        return builder.build();
    }

    private static boolean fitsHorizontal(StaticLayout layout, SpatialLayoutPlanner.Item item,
                                          float fontSize, int maxRows) {
        int lines = layout.getLineCount();
        if (maxRows > 0 && lines > maxRows) return false;
        float widest = 0f;
        for (int l = 0; l < lines; l++) widest = Math.max(widest, layout.getLineMax(l));
        if (widest > item.availableWidth + item.maxOverhang + .5f) return false;
        // Compare against the true rendered StaticLayout height so the last line is never clipped.
        return layout.getHeight() <= item.availableHeight + 2f;
    }

    private static float verticalAlignY(SpatialLayoutPlanner.Item item, Rect area, float pad,
                                        StaticLayout layout, float fontSize, float pitch) {
        float innerTop = area.top + pad;
        float innerBottom = area.bottom - pad;
        int lineCount = Math.max(1, layout.getLineCount());
        float contentH = layout.getHeight();
        float visualCenter = contentH * .5f;
        float targetCenter = (innerTop + innerBottom) * .5f;
        if (!item.detectedLines.isEmpty()) {
            int minTop = Integer.MAX_VALUE, maxBottom = Integer.MIN_VALUE;
            for (int[] line : item.detectedLines) {
                if (OcrRegion.valid(line)) {
                    minTop = Math.min(minTop, line[1]);
                    maxBottom = Math.max(maxBottom, line[3]);
                }
            }
            if (maxBottom > minTop) targetCenter = (minTop + maxBottom) * .5f;
        } else if (item.sourceRect != null && OcrRegion.valid(item.sourceRect)) {
            targetCenter = (item.sourceRect[1] + item.sourceRect[3]) * .5f;
        }
        float drawY = targetCenter - visualCenter;
        float minDrawY = area.top;
        float maxDrawY = Math.max(minDrawY, area.bottom - contentH);
        return Math.max(minDrawY, Math.min(maxDrawY, drawY));
    }

    private static TextPaint createPaint(float fontSize, int color, boolean bold) {
        TextPaint font = new TextPaint(TextPaint.ANTI_ALIAS_FLAG | TextPaint.SUBPIXEL_TEXT_FLAG);
        font.setTypeface(Ui.typeface(bold ? 750 : 550));
        font.setTextSize(fontSize);
        font.setColor(color);
        font.setTextLocale(Locale.SIMPLIFIED_CHINESE);
        return font;
    }

    /** Preserved for existing unit test compatibility. */
    static float comfortableSize(float sourceSize, float preferred, int balloonWidth, int balloonHeight) {
        if (preferred <= 0) return sourceSize;
        boolean spacious = balloonHeight >= preferred * 1.35f
                || (balloonWidth >= preferred * 2.0f && balloonHeight >= preferred * 1.1f);
        if (!spacious) return sourceSize;
        float comfortFloor = preferred * .65f;
        if (sourceSize >= comfortFloor) return sourceSize;
        float comfortCeiling = preferred * .80f;
        return Math.min(comfortCeiling, Math.max(sourceSize * 1.25f, comfortFloor));
    }

    void release() {
        for (Label label : labels) {
            if (label.coverBitmap != null && !label.coverBitmap.isRecycled()) {
                label.coverBitmap.recycle();
            }
        }
        labels.clear();
    }
}
