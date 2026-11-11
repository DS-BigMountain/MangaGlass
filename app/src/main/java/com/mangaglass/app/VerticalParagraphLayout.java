package com.mangaglass.app;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.TextPaint;
import java.util.Locale;

/**
 * 竖排段落布局。每字占一行，逐列自右向左回填，字高 = 列宽 × 0.90，行步 = 字号 × 1.12。
 * 用于高瘦且原文含 CJK 字符的区域，与横排 {@link ParagraphLayout.Flow} 共用
 * height()/draw(canvas, bounds)/fullText 契约，因此 PageFlow 的左右避让与整页上移原样协同。
 */
final class VerticalParagraphLayout {
    /** 字高相对列宽的比例。 */
    static final float GLYPH_SHARE = .90f;
    /** 行步相对字号的倍数。 */
    static final float LINE_STEP = 1.12f;
    /** 竖排判据：区域高相对宽的最低倍数。 */
    static final float TALL_RATIO = 1.8f;

    private final String fullText;
    private final String[] cells;
    private final Metrics metrics;
    private final boolean centered;
    private final TextPaint paint;

    VerticalParagraphLayout(String fullText, String[] cells, Metrics metrics, boolean centered, int color) {
        this.fullText = fullText;
        this.cells = cells;
        this.metrics = metrics;
        this.centered = centered;
        this.paint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG | TextPaint.SUBPIXEL_TEXT_FLAG);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        paint.setTextSize(metrics.fontSize);
        paint.setColor(color);
        paint.setTextLocale(Locale.SIMPLIFIED_CHINESE);
    }

    /** 竖排几何参数，保持纯 Java 以便 JVM 单测直接核对。 */
    static final class Metrics {
        final int padding, columns, rows, usedColumns;
        final float fontSize, step, cellHeight, contentWidth, available;

        Metrics(int padding, int columns, int rows, int usedColumns,
                float fontSize, float step, float cellHeight, float contentWidth, float available) {
            this.padding = padding;
            this.columns = columns;
            this.rows = rows;
            this.usedColumns = usedColumns;
            this.fontSize = fontSize;
            this.step = step;
            this.cellHeight = cellHeight;
            this.contentWidth = contentWidth;
            this.available = available;
        }

        /** 段落高度：每列行数 × 字高。 */
        int height() { return Math.max(1, Math.round(rows * cellHeight)); }
    }

    /** 供 ParagraphLayout 组装 Flow 时读取。 */
    String fullText() { return fullText; }
    float fontSize() { return metrics.fontSize; }
    int padding() { return metrics.padding; }
    int columns() { return metrics.usedColumns; }
    int height() { return metrics.height(); }

    static VerticalParagraphLayout create(String value, int width, float preferredSize,
                                          boolean centered, int color, int strokeColor) {
        String fullText = ParagraphLayout.normalize(value);
        // 竖排逐字成列，空白字符在每字一格的网格里由列间留白承担；按码点重建字符格。
        String packed = fullText.replaceAll("\\s+", "");
        String[] cells = codePoints(packed);
        Metrics metrics = metrics(cells.length, width, preferredSize);
        return new VerticalParagraphLayout(fullText, cells, metrics, centered, color);
    }

    /** 计算竖排几何：字号取建议值与可用宽度的较小者，列数由行步整除宽度得出。 */
    static Metrics metrics(int charCount, int width, float preferredSize) {
        int outer = Math.max(1, width);
        float preferred = Math.max(1, preferredSize);
        int padding = Math.max(0, Math.min(Math.round(preferred * .24f), Math.round(outer * .08f)));
        padding = Math.min(padding, (outer - 1) / 2);
        int available = Math.max(1, outer - 2 * padding);
        float fontSize = Math.max(1f, Math.min(preferred, available / LINE_STEP));
        float step = fontSize * LINE_STEP;
        int columns = Math.max(1, (int) Math.floor(available / step + .0001f));
        int count = Math.max(1, charCount);
        int rows = (count + columns - 1) / columns;
        int usedColumns = Math.min(columns, (count + rows - 1) / rows);
        float cellHeight = step * GLYPH_SHARE;
        return new Metrics(padding, columns, rows, usedColumns, fontSize, step, cellHeight,
                usedColumns * step, available);
    }

    /** 竖排选用条件：区域高 > 宽 × 1.8，且原文含 CJK 字符。 */
    static boolean applicable(TextRegion region) {
        if (region == null || region.bounds == null) return false;
        return verticalSource(region.bounds.width(), region.bounds.height(), hasCjk(region.source));
    }

    /** 纯参数版本的竖排判据，便于 JVM 单测。 */
    static boolean verticalSource(int width, int height, boolean cjk) {
        return cjk && width > 0 && height > 0 && height > width * TALL_RATIO;
    }

    static boolean hasCjk(String text) {
        if (text == null) return false;
        return text.codePoints().anyMatch(c -> {
            Character.UnicodeScript script = Character.UnicodeScript.of(c);
            return script == Character.UnicodeScript.HAN
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA;
        });
    }

    /** 按码点切分字符格，代理对与组合字符保持完整。 */
    private static String[] codePoints(String text) {
        int count = text.codePointCount(0, text.length());
        String[] cells = new String[count];
        int index = 0;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            int size = Character.charCount(codePoint);
            cells[index++] = text.substring(offset, offset + size);
            offset += size;
        }
        return cells;
    }

    /** 绘制竖排段落；先描边后填充，与横排段落的文字样式保持一致。 */
    void draw(Canvas canvas, Rect bounds, float strokeWidth, int strokeColor, int fillColor) {
        canvas.save();
        canvas.clipRect(bounds);
        float offsetX = centered
                ? Math.max(0, (metrics.available - metrics.contentWidth) / 2f)
                : Math.max(0, metrics.available - metrics.contentWidth);
        canvas.translate(bounds.left + metrics.padding + offsetX, bounds.top + metrics.padding);
        float cellTop = 0;
        float columnLeft = 0;
        for (int column = 0; column < metrics.usedColumns; column++) {
            columnLeft = metrics.contentWidth - (column + 1) * metrics.step;
            for (int row = 0; row < metrics.rows; row++) {
                int index = column * metrics.rows + row;
                if (index >= cells.length) break;
                cellTop = row * metrics.cellHeight;
                drawGlyph(canvas, cells[index], columnLeft, cellTop, Paint.Style.STROKE, strokeColor, strokeWidth);
            }
        }
        for (int column = 0; column < metrics.usedColumns; column++) {
            columnLeft = metrics.contentWidth - (column + 1) * metrics.step;
            for (int row = 0; row < metrics.rows; row++) {
                int index = column * metrics.rows + row;
                if (index >= cells.length) break;
                cellTop = row * metrics.cellHeight;
                drawGlyph(canvas, cells[index], columnLeft, cellTop, Paint.Style.FILL, fillColor, strokeWidth);
            }
        }
        canvas.restore();
    }

    private void drawGlyph(Canvas canvas, String cell, float columnLeft, float cellTop,
                           Paint.Style style, int glyphColor, float strokeWidth) {
        paint.setStyle(style);
        paint.setColor(glyphColor);
        if (style == Paint.Style.STROKE) {
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(strokeWidth);
        }
        float glyphWidth = paint.measureText(cell);
        float x = columnLeft + (metrics.step - glyphWidth) / 2f;
        Paint.FontMetrics font = paint.getFontMetrics();
        float baseline = cellTop + metrics.cellHeight / 2f - (font.ascent + font.descent) / 2f;
        canvas.drawText(cell, x, baseline, paint);
    }
}
