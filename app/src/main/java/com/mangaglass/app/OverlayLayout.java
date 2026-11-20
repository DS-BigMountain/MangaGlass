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
        final List<ColumnStrip> columnStrips;

        Label(Rect bounds, Rect source, Rect coverRect, Bitmap coverBitmap, String fullText,
              float fontSize, float linePitch, float drawX, float drawY, RectF clipRect,
              int textColor, int rimColor, boolean drawRim, boolean bold, boolean coverOnly,
              StaticLayout textLayout, List<ColumnStrip> columnStrips) {
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
            this.columnStrips = columnStrips;
        }

        String fullText() { return fullText; }

        float fontSize() { return fontSize; }

        int height() { return bounds.height(); }

        void drawText(Canvas canvas) {
            if (coverOnly) return;
            if (columnStrips != null && !columnStrips.isEmpty()) {
                drawVerticalStrips(canvas);
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

        private void drawVerticalStrips(Canvas canvas) {
            TextPaint tp = createPaint(fontSize, textColor, bold);
            Paint.FontMetrics fm = tp.getFontMetrics();
            float rimW = Math.max(1.4f, fontSize * .135f);
            float weightW = Math.max(.45f, fontSize * .032f);
            canvas.save();
            if (clipRect != null) canvas.clipRect(clipRect);
            for (ColumnStrip strip : columnStrips) {
                float centerX = (strip.rect.left + strip.rect.right) * .5f;
                for (int r = 0; r < strip.glyphs.length; r++) {
                    String g = strip.glyphs[r];
                    float gw = tp.measureText(g);
                    float x = centerX - gw * .5f;
                    float cellTop = strip.rect.top + r * linePitch;
                    float baseline = cellTop + linePitch * .5f - (fm.ascent + fm.descent) * .5f;
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
            canvas.restore();
        }
    }

    private static final class Fitted {
        float fontSize;
        float linePitch;
        StaticLayout layout;
        List<ColumnStrip> strips;
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
            fitted[i] = fitItem(item, item.harmonizedFontSize, true, scale);
            fittedSizes[i] = fitted[i].fontSize;
        }

        // Pass B: bring closely coupled peer segments down to a matching face when one shrinks.
        float[] finalSizes = SpatialLayoutPlanner.harmonizeFinalSizes(items, fittedSizes, scale);
        for (int i = 0; i < n; i++) {
            SpatialLayoutPlanner.Item item = items.get(i);
            if (item.suppressed || item.coverOnly || fitted[i] == null) continue;
            if (finalSizes[i] < fittedSizes[i] - .25f) {
                fitted[i] = fitItem(item, finalSizes[i], false, scale);
            }
        }

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
            if (f == null || (f.layout == null && (f.strips == null || f.strips.isEmpty()))) {
                if (coverBitmap != null) coverBitmap.recycle();
                continue;
            }
            float pad = item.padding;
            float clipLeft = f.centerAligned
                    ? area.left + pad - item.maxOverhang * .5f
                    : area.left + pad;
            float clipRight = f.centerAligned
                    ? area.right - pad + item.maxOverhang * .5f
                    : area.right - pad + item.maxOverhang;
            RectF clipRect = new RectF(
                    Math.max(0f, clipLeft),
                    Math.max(0f, area.top - 2f),
                    Math.min(width, clipRight),
                    Math.min(height, area.bottom + 2f));

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
                    f.layout, f.strips));
        }
        return result;
    }

    private static Fitted fitItem(SpatialLayoutPlanner.Item item, float targetPreferred,
                                  boolean allowShrink, float scale) {
        Fitted out = new Fitted();
        out.centerAligned = item.centerAligned;
        if (item.column) {
            float size = targetPreferred;
            if (allowShrink && item.glyphs.length > 0 && verticalCapacity(item, size) < item.glyphs.length) {
                float low = 8f, high = size;
                for (int step = 0; step < 14; step++) {
                    float mid = (low + high) * .5f;
                    if (verticalCapacity(item, mid) >= item.glyphs.length) low = mid;
                    else high = mid;
                }
                size = low;
            }
            out.fontSize = size;
            out.linePitch = Math.max(1f, size * 1.12f);
            List<ColumnStrip> strips = new ArrayList<>(item.strips.size());
            int at = 0;
            for (int k = 0; k < item.strips.size() && at < item.glyphs.length; k++) {
                int[] st = item.strips.get(k);
                int count = stripRoom(item, out.linePitch, k);
                // Put any remaining glyphs into the final strip rather than dropping text.
                int take = (k + 1 == item.strips.size())
                        ? (item.glyphs.length - at)
                        : Math.min(count, item.glyphs.length - at);
                if (take <= 0) continue;
                String[] colGlyphs = new String[take];
                System.arraycopy(item.glyphs, at, colGlyphs, 0, take);
                at += take;
                strips.add(new ColumnStrip(new Rect(st[0], st[1], st[2], item.stripBottoms[k]), colGlyphs));
            }
            out.strips = strips;
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
                float low = Math.min(targetPreferred, Math.max(10f * Math.max(1f, scale * .5f), targetPreferred * .58f));
                float high = targetPreferred;
                StaticLayout best = buildHorizontalLayout(item, low, wrap);
                for (int step = 0; step < 12; step++) {
                    float mid = (low + high) * .5f;
                    StaticLayout trial = buildHorizontalLayout(item, mid, wrap);
                    if (fitsHorizontal(trial, item, mid, maxRows)) {
                        low = mid;
                        best = trial;
                    } else {
                        high = mid;
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

    private static int stripRoom(SpatialLayoutPlanner.Item item, float pitch, int k) {
        int h = item.stripBottoms[k] - item.strips.get(k)[1];
        return Math.max(1, (int) Math.floor(h / Math.max(1f, pitch)));
    }

    private static int verticalCapacity(SpatialLayoutPlanner.Item item, float size) {
        float pitch = Math.max(1f, size * 1.12f);
        int total = 0;
        for (int k = 0; k < item.strips.size(); k++) total += stripRoom(item, pitch, k);
        return total;
    }

    @android.annotation.SuppressLint("InlinedApi")
    private static StaticLayout buildHorizontalLayout(SpatialLayoutPlanner.Item item, float fontSize, boolean wrap) {
        TextPaint paint = createPaint(fontSize, item.textColor, item.bold);
        Layout.Alignment alignment = item.centerAligned ? Layout.Alignment.ALIGN_CENTER : Layout.Alignment.ALIGN_NORMAL;
        float maxW = wrap ? item.availableWidth : Math.max(item.availableWidth + item.maxOverhang,
                paint.measureText(item.displayText) + 4f);
        int layoutWidth = Math.max(1, (int) Math.ceil(wrap ? item.availableWidth : maxW));
        // Convert target pitchRatio relative to font metrics line height.
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
        float pitch = fontSize * item.pitchRatio;
        float visualH = lines == 0 ? 0f : Math.min(layout.getHeight(), (lines - 1) * pitch + fontSize * 1.05f);
        return visualH <= item.availableHeight + 1.5f;
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
