package com.mangaglass.app;

import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Build;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;

/** Measures and draws the very same layout, inside a region anchored to the source text. */
public final class ParagraphLayout {
    public static final class Placement {
        public final Rect bounds;
        public final int padding;
        public final float fontSize, textLeft, textTop;
        public final String fullText;
        public final StaticLayout text;
        public final boolean needsReader;
        Placement(Rect bounds, int padding, StaticLayout text, String fullText, boolean centered, boolean needsReader) {
            this.bounds = new Rect(bounds); this.padding = padding; this.text = text; this.fullText = fullText;
            this.fontSize = text.getPaint().getTextSize(); this.needsReader = needsReader;
            textLeft = bounds.left + padding;
            textTop = bounds.top + padding + (centered ? Math.max(0, (bounds.height() - 2 * padding - text.getHeight()) / 2f) : 0);
        }
    }
    private ParagraphLayout() {}
    public static String normalize(String value) {
        return value.replaceAll("[\\r\\n\\u2028\\u2029]+"," ").replaceAll("[\\t ]+"," ").trim();
    }
    public static Placement fit(String value, Rect bounds, float preferredSize, float readableSize, int color) {
        if (bounds.isEmpty()) throw new IllegalArgumentException("Empty translation bounds");
        String fullText = normalize(value);
        int padding = Math.max(0, Math.min(Math.round(preferredSize * .12f), Math.round(Math.min(bounds.width(), bounds.height()) * .06f)));
        padding = Math.min(padding, (Math.min(bounds.width(), bounds.height()) - 1) / 2);
        int width = Math.max(1, bounds.width() - 2 * padding), height = Math.max(1, bounds.height() - 2 * padding);
        boolean centered = bounds.height() > bounds.width() * .85f || fullText.codePointCount(0, fullText.length()) < 24;
        Layout.Alignment alignment = centered ? Layout.Alignment.ALIGN_CENTER : Layout.Alignment.ALIGN_NORMAL;
        StaticLayout best = create(fullText, width, preferredSize, color, alignment);
        if (!fits(best, width, height)) {
            // Compact line spacing can save a line before making the glyphs smaller.
            best = create(fullText,width,preferredSize,color,alignment,1f);
        }
        if (!fits(best, width, height)) {
            // Test actual line heights, font padding and advances, including fallback fonts.
            float low = .1f, high = preferredSize;
            best = create(fullText, width, low, color, alignment,1f);
            for (int i = 0; i < 14; i++) {
                float size = (low + high) / 2;
                StaticLayout candidate = create(fullText, width, size, color, alignment,1f);
                if (fits(candidate, width, height)) { low = size; best = candidate; }
                else high = size;
            }
        }
        boolean needsReader = best.getPaint().getTextSize() < readableSize;
        if (!fits(best, width, height)) {
            // An implausibly small AI box cannot contain thousands of glyphs. An explicit marker
            // opens the complete text; never silently ellipsize or paint outside the region.
            needsReader = true;
            best = create("…", width, .1f, color, Layout.Alignment.ALIGN_CENTER);
        }
        return new Placement(bounds, padding, best, fullText, centered, needsReader);
    }
    static boolean fits(StaticLayout layout, int width, int height) {
        if (layout.getHeight() > height) return false;
        for (int line = 0; line < layout.getLineCount(); line++) {
            // Trailing spaces are not painted. Counting them makes mixed Chinese/Latin
            // paragraphs appear too wide and can unnecessarily halve the chosen font size.
            if (layout.getLineMax(line) > width + .01f) return false;
        }
        return true;
    }
    @android.annotation.SuppressLint("InlinedApi") // Compile-time constant 1, supported by StaticLayout since API 23.
    static StaticLayout create(String value, int width, float size, int color, Layout.Alignment alignment) {
        return create(value,width,size,color,alignment,1.08f);
    }
    @android.annotation.SuppressLint("InlinedApi")
    private static StaticLayout create(String value, int width, float size, int color, Layout.Alignment alignment, float spacing) {
        TextPaint font = new TextPaint(TextPaint.ANTI_ALIAS_FLAG | TextPaint.SUBPIXEL_TEXT_FLAG);
        font.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL)); font.setTextSize(size); font.setColor(color);
        font.setTextLocale(java.util.Locale.SIMPLIFIED_CHINESE);
        StaticLayout.Builder builder = StaticLayout.Builder.obtain(value, 0, value.length(), font, Math.max(1, width))
                .setAlignment(alignment).setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                .setIncludePad(true).setLineSpacing(0, spacing)
                .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY).setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        if (Build.VERSION.SDK_INT >= 28) builder.setUseLineSpacingFromFallbacks(true);
        return builder.build();
    }
}
