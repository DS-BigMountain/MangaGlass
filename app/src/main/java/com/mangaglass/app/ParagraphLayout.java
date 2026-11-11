package com.mangaglass.app;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Build;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;

/**
 * 译文段落排版。横排段落按可用宽度测量并绘制；竖排段落委托 {@link VerticalParagraphLayout}。
 * 两种段落共用 height()/draw(canvas, bounds)/fullText 契约，供 PageFlow 的避让与上移直接使用。
 * 绘制顺序统一为先描边（STROKE）后填充（FILL）：描边宽度取字号的 0.08 倍，描边色按背景亮度反色。
 */
public final class ParagraphLayout {
    /** 默认行距比例，保持 V1.2.1 现状。 */
    static final float DEFAULT_SPACING = LineSpacing.FALLBACK;
    /** 描边宽度相对字号的比例，落在 0.06~0.10 倍区间。 */
    static final float STROKE_SHARE = .08f;
    /** 描边宽度的下限（像素）。 */
    static final float MIN_STROKE = .5f;

    /** 定宽段落：在给定矩形内完成测量、缩放与绘制。 */
    public static final class Placement {
        public final Rect bounds;
        public final int padding;
        public final float fontSize, textLeft, textTop, scale;
        public final String fullText;
        public final StaticLayout text;
        private final float strokeWidth;
        private final int fillColor, strokeColor;

        Placement(Rect bounds, int padding, StaticLayout text, String fullText, boolean centered) {
            this(bounds, padding, text, fullText, centered, text.getPaint().getColor());
        }

        Placement(Rect bounds, int padding, StaticLayout text, String fullText, boolean centered, int fillColor) {
            this.bounds = new Rect(bounds);
            this.padding = padding;
            this.text = text;
            this.fullText = fullText;
            this.fillColor = fillColor;
            this.strokeColor = strokeColorForFill(fillColor);
            this.strokeWidth = strokeWidth(text.getPaint().getTextSize());
            float widest = text.getWidth();
            for (int line = 0; line < text.getLineCount(); line++) widest = Math.max(widest, text.getLineMax(line));
            // 整数行高可能与区域差一个像素；此时整体缩放已量好的段落，保留全文。
            scale = Math.min(1, Math.min((bounds.width() - 2 * padding) / Math.max(1f, widest),
                    (bounds.height() - 2 * padding) / (float) Math.max(1, text.getHeight())));
            fontSize = text.getPaint().getTextSize() * scale;
            textLeft = bounds.left + padding + (centered ? Math.max(0, (bounds.width() - 2 * padding - text.getWidth() * scale) / 2f) : 0);
            textTop = bounds.top + padding + (centered ? Math.max(0, (bounds.height() - 2 * padding - text.getHeight() * scale) / 2f) : 0);
        }

        void draw(Canvas canvas) {
            canvas.save();
            canvas.clipRect(bounds);
            canvas.translate(textLeft, textTop);
            canvas.scale(scale, scale);
            drawOutlined(canvas, text, fillColor, strokeColor, strokeWidth);
            canvas.restore();
        }
    }

    /** 流式段落：锚定在区域顶边，宽度固定，高度随内容增长。 */
    public static final class Flow {
        public final StaticLayout text;
        public final float fontSize;
        public final int padding;
        public final String fullText;
        private final float spacing;
        private final float strokeWidth;
        private final int fillColor, strokeColor;
        private final VerticalParagraphLayout vertical;

        Flow(StaticLayout text, float fontSize, int padding, String fullText,
             float spacing, int fillColor, int strokeColor, VerticalParagraphLayout vertical) {
            this.text = text;
            this.fontSize = fontSize;
            this.padding = padding;
            this.fullText = fullText;
            this.spacing = spacing;
            this.fillColor = fillColor;
            this.strokeColor = strokeColor;
            this.strokeWidth = strokeWidth(fontSize);
            this.vertical = vertical;
        }

        public int height() { return vertical != null ? vertical.height() : text.getHeight(); }

        public int lineCount() { return vertical != null ? vertical.columns() : text.getLineCount(); }

        public String textValue() { return vertical != null ? vertical.fullText() : fullText; }

        /** 横排行距比例，竖排段落按每字一行的固定行步绘制。 */
        public float spacing() { return spacing; }

        /** 先描边后填充；竖排段落按列自右向左绘制。 */
        void draw(Canvas canvas, Rect bounds) {
            if (vertical != null) {
                vertical.draw(canvas, bounds, strokeWidth, strokeColor, fillColor);
                return;
            }
            canvas.save();
            canvas.clipRect(bounds);
            canvas.translate(bounds.left + padding, bounds.top + padding);
            drawOutlined(canvas, text, fillColor, strokeColor, strokeWidth);
            canvas.restore();
        }
    }

    private ParagraphLayout() {}

    /** 描边宽度随字号取 0.08 倍，落在 0.06~0.10 倍区间。 */
    static float strokeWidth(float fontSize) {
        return Math.max(MIN_STROKE, fontSize * STROKE_SHARE);
    }

    /** 描边色取背景反色：背景亮时用深色描边，背景暗时用浅色描边。 */
    static int strokeColor(int background) {
        return luminance(background) < 145 ? 0xffffffff : 0xff000000;
    }

    /** 按填充色反推描边色：填充色已按背景亮度取反，因此深色文字配深描边、浅色文字配浅描边。 */
    static int strokeColorForFill(int fill) {
        return luminance(fill) < 145 ? 0xff000000 : 0xffffffff;
    }

    static int luminance(int color) {
        return (((color >> 16) & 255) * 3 + ((color >> 8) & 255) * 6 + (color & 255)) / 10;
    }

    private static void drawOutlined(Canvas canvas, StaticLayout layout, int fillColor, int strokeColor, float strokeWidth) {
        TextPaint paint = layout.getPaint();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(strokeWidth);
        paint.setColor(strokeColor);
        layout.draw(canvas);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(fillColor);
        layout.draw(canvas);
    }

    public static String normalize(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n').replace('\u2028', '\n').replace('\u2029', '\n')
                .replaceAll("[\\t ]+", " ").replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{2,}", "\n\n")
                .replaceAll("(?<=[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}])\\n(?=[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}])", "")
                .replaceAll("(?<!\\n)\\n(?!\\n)", " ").trim();
    }

    public static Placement fit(String value, Rect bounds, float preferredSize, int color) {
        return fit(value, bounds, preferredSize, color,
                bounds.height() > bounds.width() * .85f || value.codePointCount(0, value.length()) < 24);
    }

    static Placement fit(String value, Rect bounds, float preferredSize, int color, boolean centered) {
        if (bounds.isEmpty()) throw new IllegalArgumentException("Empty translation bounds");
        String fullText = normalize(value);
        preferredSize = Math.max(1, preferredSize);
        int padding = Math.max(0, Math.min(Math.round(preferredSize * .24f), Math.round(Math.min(bounds.width(), bounds.height()) * .08f)));
        padding = Math.min(padding, (Math.min(bounds.width(), bounds.height()) - 1) / 2);
        int width = Math.max(1, bounds.width() - 2 * padding), height = Math.max(1, bounds.height() - 2 * padding);
        Layout.Alignment alignment = centered ? Layout.Alignment.ALIGN_CENTER : Layout.Alignment.ALIGN_NORMAL;
        StaticLayout best = create(fullText, width, preferredSize, color, alignment);
        if (!fits(best, width, height)) {
            // 紧凑行距可以在缩字之前先省下一行。
            best = create(fullText, width, preferredSize, color, alignment, 1f);
        }
        if (!fits(best, width, height)) {
            // 以实际行高、字体留白与字形步进为准做二分，涵盖回退字体。
            float low = 1, high = preferredSize;
            best = create(fullText, width, low, color, alignment, 1f);
            for (int i = 0; i < 14 && fits(best, width, height); i++) {
                float size = (low + high) / 2;
                StaticLayout candidate = create(fullText, width, size, color, alignment, 1f);
                if (fits(candidate, width, height)) { low = size; best = candidate; }
                else high = size;
            }
        }
        return new Placement(bounds, padding, best, fullText, centered, color);
    }

    /** 横排流式段落，行距取默认值。 */
    public static Flow flow(String value, int width, float preferredSize, int color, boolean centered) {
        return flow(value, width, preferredSize, color, centered, DEFAULT_SPACING, strokeColorForFill(color));
    }

    /** 横排流式段落，行距与描边色由源图估计结果给出。 */
    public static Flow flow(String value, int width, float preferredSize, int color, boolean centered,
                            float spacing, int strokeColor) {
        int outer = Math.max(1, width);
        String fullText = normalize(value);
        float preferred = Math.max(1, preferredSize);
        float lineSpacing = Math.max(LineSpacing.MIN, Math.min(LineSpacing.MAX, spacing));
        int padding = Math.max(0, Math.min(Math.round(preferred * .24f), Math.round(outer * .08f)));
        padding = Math.min(padding, (outer - 1) / 2);
        int available = Math.max(1, outer - 2 * padding);
        Layout.Alignment alignment = centered ? Layout.Alignment.ALIGN_CENTER : Layout.Alignment.ALIGN_NORMAL;
        StaticLayout best = create(fullText, available, preferred, color, alignment, lineSpacing);
        if (!horizontalFits(best, available)) {
            // 该布局只受水平空间约束；缩到最长行放入区域宽度后，段落按该字号向下生长。
            float low = 1, high = preferred;
            StaticLayout smallest = create(fullText, available, low, color, alignment, 1f);
            best = smallest;
            if (horizontalFits(smallest, available)) {
                for (int i = 0; i < 14; i++) {
                    float size = (low + high) / 2;
                    StaticLayout candidate = create(fullText, available, size, color, alignment, 1f);
                    if (horizontalFits(candidate, available)) { low = size; best = candidate; }
                    else high = size;
                }
            }
        }
        return new Flow(best, best.getPaint().getTextSize(), padding, fullText,
                lineSpacing, color, strokeColor, null);
    }

    /** 竖排流式段落：每字一格，逐列自右向左回填。 */
    public static Flow verticalFlow(String value, int width, float preferredSize, int color, boolean centered,
                                    int strokeColor) {
        int outer = Math.max(1, width);
        VerticalParagraphLayout vertical = VerticalParagraphLayout.create(value, outer, preferredSize, centered, color, strokeColor);
        Layout.Alignment alignment = centered ? Layout.Alignment.ALIGN_CENTER : Layout.Alignment.ALIGN_NORMAL;
        // 兼容视图：既有测量接口读取 text 字段，这里保留一份横向测量结果承载完整文本。
        StaticLayout measured = create(vertical.fullText(), Math.max(1, outer - 2 * vertical.padding()),
                vertical.fontSize(), color, alignment, DEFAULT_SPACING);
        return new Flow(measured, vertical.fontSize(), vertical.padding(), vertical.fullText(),
                DEFAULT_SPACING, color, strokeColor, vertical);
    }

    static boolean horizontalFits(StaticLayout layout, int width) {
        for (int line = 0; line < layout.getLineCount(); line++)
            if (layout.getLineMax(line) > width + .01f) return false;
        return true;
    }

    static boolean fits(StaticLayout layout, int width, int height) {
        if (layout.getHeight() > height) return false;
        for (int line = 0; line < layout.getLineCount(); line++) {
            // 行尾空格在绘制阶段留白；把它们计入测量会让中英混排行显得过宽，并可能无谓缩小字号。
            if (layout.getLineMax(line) > width + .01f) return false;
        }
        return true;
    }

    @android.annotation.SuppressLint("InlinedApi") // 编译期常量 1，StaticLayout 自 API 23 起支持。
    static StaticLayout create(String value, int width, float size, int color, Layout.Alignment alignment) {
        return create(value, width, size, color, alignment, DEFAULT_SPACING);
    }

    @android.annotation.SuppressLint("InlinedApi")
    private static StaticLayout create(String value, int width, float size, int color, Layout.Alignment alignment, float spacing) {
        TextPaint font = new TextPaint(TextPaint.ANTI_ALIAS_FLAG | TextPaint.SUBPIXEL_TEXT_FLAG);
        font.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        font.setTextSize(size);
        font.setColor(color);
        font.setTextLocale(java.util.Locale.SIMPLIFIED_CHINESE);
        StaticLayout.Builder builder = StaticLayout.Builder.obtain(value, 0, value.length(), font, Math.max(1, width))
                .setAlignment(alignment).setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                .setIncludePad(true).setLineSpacing(0, spacing)
                .setBreakStrategy(alignment == Layout.Alignment.ALIGN_CENTER
                        ? android.graphics.text.LineBreaker.BREAK_STRATEGY_BALANCED
                        : android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        if (Build.VERSION.SDK_INT >= 28) builder.setUseLineSpacingFromFallbacks(true);
        return builder.build();
    }
}
