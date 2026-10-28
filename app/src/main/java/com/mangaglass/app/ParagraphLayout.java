package com.mangaglass.app;

import android.graphics.Rect;
import android.graphics.Canvas;
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
        public final float fontSize, textLeft, textTop, scale;
        public final String fullText;
        public final StaticLayout text;
        Placement(Rect bounds, int padding, StaticLayout text, String fullText, boolean centered) {
            this.bounds = new Rect(bounds); this.padding = padding; this.text = text; this.fullText = fullText;
            float widest=text.getWidth();
            for(int line=0;line<text.getLineCount();line++) widest=Math.max(widest,text.getLineMax(line));
            // Integer line metrics can still exceed a one-pixel region. Scale the
            // complete measured paragraph in that case; never discard its content.
            scale=Math.min(1,Math.min((bounds.width()-2*padding)/Math.max(1f,widest),
                    (bounds.height()-2*padding)/(float)Math.max(1,text.getHeight())));
            fontSize = text.getPaint().getTextSize()*scale;
            textLeft = bounds.left + padding + (centered ? Math.max(0,(bounds.width()-2*padding-text.getWidth()*scale)/2f) : 0);
            textTop = bounds.top + padding + (centered ? Math.max(0, (bounds.height() - 2 * padding - text.getHeight()*scale) / 2f) : 0);
        }
        void draw(Canvas canvas) {
            canvas.save(); canvas.clipRect(bounds); canvas.translate(textLeft,textTop);
            canvas.scale(scale,scale); text.draw(canvas); canvas.restore();
        }
    }
    private ParagraphLayout() {}
    public static String normalize(String value) {
        return value.replace("\r\n","\n").replace('\r','\n').replace('\u2028','\n').replace('\u2029','\n')
                .replaceAll("[\\t ]+"," ").replaceAll(" *\\n *","\n")
                .replaceAll("\\n{2,}","\n\n")
                .replaceAll("(?<=[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}])\\n(?=[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}])","")
                .replaceAll("(?<!\\n)\\n(?!\\n)"," ").trim();
    }
    public static Placement fit(String value, Rect bounds, float preferredSize, int color) {
        return fit(value,bounds,preferredSize,color,
                bounds.height()>bounds.width()*.85f || value.codePointCount(0,value.length())<24);
    }
    static Placement fit(String value, Rect bounds, float preferredSize, int color, boolean centered) {
        if (bounds.isEmpty()) throw new IllegalArgumentException("Empty translation bounds");
        String fullText = normalize(value);
        preferredSize=Math.max(1,preferredSize);
        int padding = Math.max(0, Math.min(Math.round(preferredSize * .24f), Math.round(Math.min(bounds.width(), bounds.height()) * .08f)));
        padding = Math.min(padding, (Math.min(bounds.width(), bounds.height()) - 1) / 2);
        int width = Math.max(1, bounds.width() - 2 * padding), height = Math.max(1, bounds.height() - 2 * padding);
        Layout.Alignment alignment = centered ? Layout.Alignment.ALIGN_CENTER : Layout.Alignment.ALIGN_NORMAL;
        StaticLayout best = create(fullText, width, preferredSize, color, alignment);
        if (!fits(best, width, height)) {
            // Compact line spacing can save a line before making the glyphs smaller.
            best = create(fullText,width,preferredSize,color,alignment,1f);
        }
        if (!fits(best, width, height)) {
            // Test actual line heights, font padding and advances, including fallback fonts.
            float low = 1, high = preferredSize;
            best = create(fullText, width, low, color, alignment,1f);
            for (int i = 0; i < 14 && fits(best,width,height); i++) {
                float size = (low + high) / 2;
                StaticLayout candidate = create(fullText, width, size, color, alignment,1f);
                if (fits(candidate, width, height)) { low = size; best = candidate; }
                else high = size;
            }
        }
        return new Placement(bounds, padding, best, fullText, centered);
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
                .setBreakStrategy(alignment==Layout.Alignment.ALIGN_CENTER
                        ? android.graphics.text.LineBreaker.BREAK_STRATEGY_BALANCED
                        : android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        if (Build.VERSION.SDK_INT >= 28) builder.setUseLineSpacingFromFallbacks(true);
        return builder.build();
    }
}
