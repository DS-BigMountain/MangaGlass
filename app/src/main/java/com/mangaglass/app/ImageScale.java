package com.mangaglass.app;

/**
 * 上传图片缩放：按长边上限等比缩小原图，目标宽高取偶数，原图尺寸在长边上限以内时保持原尺寸。
 * 计算结果附带缩放比例，并提供原图与缩放图之间的坐标换算。
 * 长边上限提供三档：1600、1920、2048，默认 1920。
 * 文件只引用标准库类型，可在 JVM 单测中直接运行。
 */
final class ImageScale {
    /** 小档长边上限。 */
    static final int LONG_SIDE_SMALL=1600;
    /** 默认长边上限。 */
    static final int LONG_SIDE_DEFAULT=1920;
    /** 大档长边上限。 */
    static final int LONG_SIDE_LARGE=2048;

    /** 目标尺寸与缩放比例。 */
    static final class Fit {
        /** 目标宽度（像素）。 */
        final int width;
        /** 目标高度（像素）。 */
        final int height;
        /** 目标相对原图的缩放比例，取值区间为 (0,1]。 */
        final float scale;
        Fit(int width,int height,float scale) {
            this.width=width; this.height=height; this.scale=scale;
        }
    }

    /** 原图与缩放图之间的坐标换算，逐轴换算以保持像素对齐。 */
    static final class Mapping {
        final int sourceWidth,sourceHeight,targetWidth,targetHeight;
        Mapping(int sourceWidth,int sourceHeight,int targetWidth,int targetHeight) {
            this.sourceWidth=sourceWidth; this.sourceHeight=sourceHeight;
            this.targetWidth=targetWidth; this.targetHeight=targetHeight;
        }
        /** 原图横坐标换算到缩放图横坐标。 */
        float xToScaled(float x) { return x*targetWidth/sourceWidth; }
        /** 原图纵坐标换算到缩放图纵坐标。 */
        float yToScaled(float y) { return y*targetHeight/sourceHeight; }
        /** 缩放图横坐标换算回原图横坐标。 */
        float xToSource(float x) { return x*sourceWidth/targetWidth; }
        /** 缩放图纵坐标换算回原图纵坐标。 */
        float yToSource(float y) { return y*sourceHeight/targetHeight; }
        /** 原图框换算到缩放图框。 */
        int[] boxToScaled(int[] box) {
            return new int[]{Math.round(xToScaled(box[0])),Math.round(yToScaled(box[1])),
                    Math.round(xToScaled(box[2])),Math.round(yToScaled(box[3]))};
        }
        /** 缩放图框换算回原图框。 */
        int[] boxToSource(int[] box) {
            return new int[]{Math.round(xToSource(box[0])),Math.round(yToSource(box[1])),
                    Math.round(xToSource(box[2])),Math.round(yToSource(box[3]))};
        }
    }

    private ImageScale() {}

    /** 按默认长边上限 1920 计算目标尺寸。 */
    static Fit fit(int width,int height) {
        return fit(width,height,LONG_SIDE_DEFAULT);
    }

    /** 按长边上限计算目标尺寸：等比缩放、目标宽高取偶数、原图尺寸在长边上限以内时保持原尺寸。 */
    static Fit fit(int width,int height,int maxLongSide) {
        int sourceWidth=Math.max(1,width),sourceHeight=Math.max(1,height);
        // 上限按偶数档参与计算，目标长边落在上限以内。
        int limit=Math.max(2,maxLongSide/2*2);
        int longSide=Math.max(sourceWidth,sourceHeight);
        if(longSide<=limit) return new Fit(sourceWidth,sourceHeight,1f);
        float scale=limit/(float)longSide;
        return new Fit(even(sourceWidth*scale),even(sourceHeight*scale),scale);
    }

    /** 判断长边上限是否属于提供的小、默认、大三档。 */
    static boolean supportedLongSide(int longSide) {
        return longSide==LONG_SIDE_SMALL || longSide==LONG_SIDE_DEFAULT || longSide==LONG_SIDE_LARGE;
    }

    /** 按默认长边上限建立原图与缩放图的坐标换算。 */
    static Mapping mapping(int width,int height) {
        return mapping(width,height,LONG_SIDE_DEFAULT);
    }

    /** 按长边上限建立原图与缩放图的坐标换算。 */
    static Mapping mapping(int width,int height,int maxLongSide) {
        Fit fit=fit(width,height,maxLongSide);
        return new Mapping(Math.max(1,width),Math.max(1,height),fit.width,fit.height);
    }

    /** 偶数取整：半像素格数先四舍五入，再乘回 2，最小值为 2。 */
    private static int even(float value) {
        return Math.max(2,Math.round(value/2f)*2);
    }
}
