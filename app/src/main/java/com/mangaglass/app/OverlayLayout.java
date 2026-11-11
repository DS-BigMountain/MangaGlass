package com.mangaglass.app;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Shader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 在截图工作线程完成排版准备，视图只负责绘制现成的路径与段落。
 * 每段译文从气泡顶边开始向下生长，左右相邻译文在画面内互相让位，最低块越过底边时整页上移。
 * 背景由分块中值重建给出，墨迹掩码只覆盖原文笔画及其抗锯齿边缘。
 */
final class OverlayLayout {
    final List<Label> labels = new ArrayList<>();
    /** 坐标缺位的区域集合；覆盖层为它们保留一个紧凑的底部面板。 */
    final List<TextRegion> unplaced = new ArrayList<>();

    static final class Label {
        final ParagraphLayout.Flow flow;
        final Rect bounds;
        /** 与文字块同生共死的同色矩形；文字始终绘制在它内部。 */
        final Rect backdrop;
        final Path mask;
        final Rect source;
        final Shader background;

        Label(ParagraphLayout.Flow flow, Rect bounds, Path mask, Rect source, Shader background) {
            this.flow = flow;
            this.bounds = new Rect(bounds);
            this.mask = mask;
            this.source = new Rect(source);
            this.background = background;
            // 两像素余量让抗锯齿字形边缘落在自身背景框内。
            this.backdrop = new Rect(bounds.left - 2, bounds.top - 2, bounds.right + 2, bounds.bottom + 2);
        }

        String fullText() { return flow.fullText; }

        float fontSize() { return flow.fontSize; }

        int height() { return bounds.height(); }
    }

    /** 排版中的段落块，邻居分离期间水平边界可变。 */
    private static final class Block implements PageFlow.Block {
        final TextRegion region;
        final float spacing;
        final int color, strokeColor;
        final boolean centered;
        final int balloonTop, balloonHeight, maxHeight;
        /** 源文字号估计，供竖排降级时恢复横排使用。 */
        final float sourceSize;
        float preferred;
        boolean vertical;
        int left, right, top;
        ParagraphLayout.Flow flow;
        Rect bounds;
        Path mask;
        Shader background;

        Block(TextRegion region, int left, int right, int top, int balloonHeight, int maxHeight, float sourceSize,
              int color, boolean centered, float spacing, int strokeColor, boolean vertical) {
            this.region = region;
            this.left = left;
            this.right = right;
            this.top = top;
            this.balloonTop = top;
            this.balloonHeight = Math.max(2, balloonHeight);
            this.maxHeight = Math.max(2, maxHeight);
            this.sourceSize = Math.max(1, sourceSize);
            this.preferred = this.sourceSize;
            this.color = color;
            this.centered = centered;
            this.spacing = spacing;
            this.strokeColor = strokeColor;
            this.vertical = vertical;
        }

        void place() {
            int width = Math.max(2, right - left);
            ParagraphLayout.Flow candidate = layout(width, preferred, vertical);
            // 译文超出气泡可用高度时逐步缩小字号；单个超长译文留在自己的气泡内，不再推高整页。
            for (int attempt = 0; attempt < 10 && !fits(candidate); attempt++) {
                preferred = Math.max(8, preferred * .82f);
                candidate = layout(width, preferred, vertical);
            }
            // 高瘦误判会让竖排字号缩到难以阅读，此时改用横排并重新拟合。
            if (vertical && candidate.fontSize < Math.max(8, sourceSize * .55f)) {
                vertical = false;
                preferred = sourceSize;
                candidate = layout(width, preferred, false);
                for (int attempt = 0; attempt < 10 && !fits(candidate); attempt++) {
                    preferred = Math.max(8, preferred * .82f);
                    candidate = layout(width, preferred, false);
                }
            }
            flow = candidate;
            bounds = new Rect(left, top, right, top + 2 * flow.padding + flow.height());
        }

        private boolean fits(ParagraphLayout.Flow candidate) {
            return 2 * candidate.padding + candidate.height() <= maxHeight;
        }

        private ParagraphLayout.Flow layout(int width, float size, boolean verticalLayout) {
            return verticalLayout
                    ? ParagraphLayout.verticalFlow(region.translated, width, size, color, centered, strokeColor)
                    : ParagraphLayout.flow(region.translated, width, size, color, centered, spacing, strokeColor);
        }

        @Override public int left() { return left; }
        @Override public int right() { return right; }
        @Override public int top() { return bounds.top; }
        @Override public int bottom() { return bounds.bottom; }
        @Override public int balloonTop() { return balloonTop; }
        @Override public int balloonHeight() { return balloonHeight; }

        @Override public boolean move(int dx) {
            if (dx == 0) return false;
            left += dx;
            right += dx;
            bounds.offset(dx, 0);
            return true;
        }

        @Override public boolean range(int newLeft, int newRight) {
            if (newLeft == left && newRight == right) return false;
            left = newLeft;
            right = newRight;
            place();
            return true;
        }

        @Override public boolean lift(int dy) {
            if (dy == 0) return false;
            top += dy;
            bounds.offset(0, dy);
            return true;
        }
    }

    static OverlayLayout prepare(Bitmap bitmap, List<TextRegion> regions, float preferred) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        OverlayLayout result = new OverlayLayout();
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        int[] data = new int[width * height];
        bitmap.getPixels(data, 0, width, 0, 0, width, height);
        BubbleGeometry.Pixels pixels = (x, y) -> data[y * width + x];
        List<TextRegion> valid = new ArrayList<>();
        List<int[]> occupied = new ArrayList<>();
        for (TextRegion region : regions) {
            if (region.translated.trim().isEmpty()) continue;
            Rect r = new Rect(region.bounds);
            if (r.isEmpty() || !r.intersect(0, 0, width, height)) { result.unplaced.add(region); continue; }
            valid.add(new TextRegion(r, region.bubble, region.source, region.translated));
            occupied.add(new int[]{r.left, r.top, r.right, r.bottom});
        }
        int[][] sources = occupied.toArray(new int[0][]);
        List<Block> blocks = new ArrayList<>();
        for (int i = 0; i < valid.size(); i++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            TextRegion region = valid.get(i);
            int[] source = sources[i];
            int topColor = BubbleInterior.background(source, width, height, pixels, false);
            int bottomColor = BubbleInterior.background(source, width, height, pixels, true);
            BubbleInterior interior = similar(topColor, bottomColor)
                    ? BubbleInterior.detect(source, width, height, pixels, sources, topColor) : null;
            if (interior != null) bottomColor = topColor;
            float sourceSize = SourceTextSize.estimate(region, pixels, topColor, bottomColor, preferred);
            Rect balloon = balloon(region, interior, source, width, height, pixels, sources, topColor, bottomColor);
            // 气泡高度按原文墨迹的两倍封顶：面板级大框只在上方与下方留有限空间。
            int balloonHeight = Math.max(2, Math.min(balloon.height(), (source[3] - source[1]) * 2 + 16));
            boolean centered = balloonHeight > balloon.width() * .85f
                    || region.translated.codePointCount(0, region.translated.length()) < 24;
            // 分块中值重建替换单一主色采样；背景色与文字明暗都取自重建结果。
            Backdrop backdrop = Backdrop.of(source, width, height, pixels);
            int background = backdrop.medianColor();
            int foreground = luminance(background) < 145 ? 0xfffafafa : 0xff202124;
            int strokeColor = ParagraphLayout.strokeColor(background);
            boolean vertical = VerticalParagraphLayout.applicable(region);
            // 横排段落的行距按源墨迹带间隙换算；竖排段落按每字一行的固定行步绘制。
            float spacing = vertical ? ParagraphLayout.DEFAULT_SPACING
                    : LineSpacing.ratio(source, pixels, topColor, bottomColor, false);
            Block block = new Block(region, balloon.left, balloon.right, balloon.top, balloonHeight, balloonHeight,
                    sourceSize, foreground, centered, spacing, strokeColor, vertical);
            block.place();
            block.mask = inkMask(backdrop, interior, source, width, height, pixels);
            block.background = backdropShader(backdrop);
            blocks.add(block);
        }
        PageFlow.separate(blocks, width);
        PageFlow.shiftUp(blocks, height);
        for (Block block : blocks)
            result.labels.add(new Label(block.flow, block.bounds, block.mask, block.region.bounds, block.background));
        return result;
    }

    /** 容器锚点顺序：模型气泡框、像素推导的气泡内部、向空白邻域的扩展。 */
    private static Rect balloon(TextRegion region, BubbleInterior interior, int[] source, int width, int height,
                                BubbleGeometry.Pixels pixels, int[][] others, int topColor, int bottomColor) {
        int left, right, top, bottom;
        Rect bubble = region.bubble;
        if (container(bubble, source, width, height)) {
            left = Math.max(0, bubble.left);
            right = Math.min(width, bubble.right);
            top = Math.min(bubble.top, source[1]);
            bottom = Math.max(bubble.bottom, source[3]);
            // 译文至少需要原文墨迹的宽度来保持阅读顺序。
            left = Math.min(left, source[0]);
            right = Math.max(right, source[2]);
            // 紧贴墨迹的容器留给横排改写的余量有限，此时认领已验证的空白邻居。
            if (right - left < (source[2] - source[0]) * 1.4f) {
                int[] expanded = BubbleGeometry.expand(source, width, height, pixels, others, 1f, topColor, bottomColor);
                left = Math.min(left, expanded[0]);
                right = Math.max(right, expanded[2]);
            }
        } else if (interior != null) {
            left = width;
            right = 0;
            for (int row = 0; row < interior.left.length; row++) {
                left = Math.min(left, interior.left[row]);
                right = Math.max(right, interior.right[row]);
            }
            top = interior.top;
            bottom = interior.bottom;
        } else {
            int[] expanded = BubbleGeometry.expand(source, width, height, pixels, others, 1f, topColor, bottomColor);
            left = expanded[0];
            right = expanded[2];
            top = source[1];
            bottom = source[3];
        }
        left = Math.max(0, Math.min(left, width - 2));
        right = Math.min(width, Math.max(left + 2, right));
        // 锚点只允许超出墨迹有限距离；面板级大框不会把译文抬到画面顶部或压到画面底部。
        int verticalMargin = Math.max(24, (source[3] - source[1]) * 2);
        top = Math.max(top, source[1] - verticalMargin);
        bottom = Math.min(bottom, source[3] + verticalMargin);
        top = Math.max(0, Math.min(top, height - 1));
        bottom = Math.max(top + 2, Math.min(height, bottom));
        return new Rect(left, top, right, bottom);
    }

    private static boolean container(Rect bubble, int[] source, int width, int height) {
        if (bubble == null || bubble.isEmpty() || bubble.width() < 8 || bubble.height() < 8) return false;
        long area = (long) (source[2] - source[0]) * (source[3] - source[1]);
        long overlap = overlapArea(bubble, source);
        // 上报容器需要完整包住墨迹框；分离或偏小的框会把译文放到错误的落点，此时以像素结果为准。
        return area > 0 && overlap * 2 >= area && bubble.width() >= (source[2] - source[0]) * .8f;
    }

    private static long overlapArea(Rect a, int[] b) {
        int l = Math.max(a.left, b[0]), t = Math.max(a.top, b[1]);
        int r = Math.min(a.right, b[2]), bottom = Math.min(a.bottom, b[3]);
        return r > l && bottom > t ? (long) (r - l) * (bottom - t) : 0;
    }

    /** 墨迹掩码：笔画检测 + ±2px 膨胀；可识别笔画为零时沿用整段擦除的兜底语义。 */
    private static Path inkMask(Backdrop backdrop, BubbleInterior interior, int[] source,
                                int width, int height, BubbleGeometry.Pixels pixels) throws InterruptedException {
        Path mask = new Path();
        int top, bottom;
        int[] left, right;
        if (interior != null) {
            top = interior.top;
            bottom = interior.bottom;
            left = interior.left;
            right = interior.right;
        } else {
            top = source[1];
            bottom = source[3];
            int depth = Math.max(1, bottom - top);
            left = new int[depth];
            right = new int[depth];
            Arrays.fill(left, source[0]);
            Arrays.fill(right, source[2]);
        }
        if (bottom > top) {
            int[][] rects = InkMask.rects(pixels, width, height, backdrop, top, bottom, left, right);
            if (rects.length > 0) {
                for (int[] rect : rects)
                    mask.addRect(rect[0], rect[1], rect[2], rect[3], Path.Direction.CW);
                return mask;
            }
        }
        // 笔画识别结果为空时擦除整段：空白区域铺本地背景色，画面外观保持一致。
        if (interior != null) {
            for (int y = interior.top; y < interior.bottom; y++) {
                int row = y - interior.top;
                if (interior.right[row] > interior.left[row])
                    mask.addRect(interior.left[row], y, interior.right[row], y + 1, Path.Direction.CW);
            }
        } else {
            mask.addRect(source[0], source[1], source[2], source[3], Path.Direction.CW);
        }
        return mask;
    }

    /** 把分块色表铺成位图着色器：24px 块经双线性插值形成本地背景。 */
    private static Shader backdropShader(Backdrop backdrop) {
        Bitmap bitmap = Bitmap.createBitmap(backdrop.columns, backdrop.rows, Bitmap.Config.ARGB_8888);
        bitmap.setDensity(Bitmap.DENSITY_NONE);
        bitmap.setPixels(backdrop.colors, 0, backdrop.columns, 0, 0, backdrop.columns, backdrop.rows);
        BitmapShader shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Matrix matrix = new Matrix();
        matrix.setScale(backdrop.block, backdrop.block);
        matrix.postTranslate(backdrop.left, backdrop.top);
        shader.setLocalMatrix(matrix);
        return shader;
    }

    private static int luminance(int c) {
        return (((c >> 16) & 255) * 3 + ((c >> 8) & 255) * 6 + (c & 255)) / 10;
    }

    private static boolean similar(int a, int b) {
        for (int shift = 0; shift <= 16; shift += 8)
            if (Math.abs(((a >>> shift) & 255) - ((b >>> shift) & 255)) > 16) return false;
        return true;
    }
}
