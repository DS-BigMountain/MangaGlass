package com.mangaglass.app;

import java.util.ArrayList;
import java.util.List;

/**
 * 阅读顺序：按页面朝向把检测框排成漫画阅读流。
 * 竖排页的列自右向左排列，同列自上向下；横排页的行自上向下排列，同行自左向右。
 * 列判定容差取列宽的一半，行判定容差取行高的一半，两者下限均为 8 像素。
 * 位置相同的框按原始索引兜底，排序结果保持稳定。
 * 文件只引用标准库类型，排序方法为纯函数。
 */
final class ReadingOrder {
    /** 竖排框的高宽比下限：高大于宽的 1.8 倍。 */
    static final float VERTICAL_ASPECT=1.8f;
    /** 竖排页所需的竖排框数量占比：达到 30%。 */
    static final float VERTICAL_SHARE=.3f;
    /** 列、行判定容差的最小像素值。 */
    static final int MIN_TOLERANCE=8;

    private ReadingOrder() {}

    /** 页面朝向：竖排框（高大于宽的 1.8 倍，且文本含 CJK 或假名）占比达到 30% 即为竖排页。 */
    static boolean verticalPage(int[][] boxes,String[] texts) {
        int total=0,vertical=0;
        for(int i=0;i<boxes.length;i++) {
            if(!valid(boxes[i])) continue;
            total++;
            if(tall(boxes[i]) && eastAsian(text(texts,i))) vertical++;
        }
        return total>0 && vertical>=Math.ceil(total*VERTICAL_SHARE);
    }

    /** 阅读顺序下的原始索引；位置相同的框按原始索引兜底，尺寸退化的框排在末尾。 */
    static int[] order(int[][] boxes,String[] texts) {
        List<Integer> valid=new ArrayList<>(),invalid=new ArrayList<>();
        for(int i=0;i<boxes.length;i++) {
            if(valid(boxes[i])) valid.add(i); else invalid.add(i);
        }
        List<List<Integer>> groups=verticalPage(boxes,texts)
                ? verticalGroups(boxes,valid) : horizontalGroups(boxes,valid);
        int[] order=new int[boxes.length];
        int at=0;
        for(List<Integer> group:groups) for(int index:group) order[at++]=index;
        for(int index:invalid) order[at++]=index;
        return order;
    }

    /** 竖排页：按水平中心自右向左成列，同列按上边界自上向下。 */
    private static List<List<Integer>> verticalGroups(int[][] boxes,List<Integer> indices) {
        indices.sort((a,b) -> {
            int result=Integer.compare(centerX(boxes[b]),centerX(boxes[a]));
            if(result!=0) return result;
            result=Integer.compare(boxes[a][1],boxes[b][1]);
            return result!=0 ? result : Integer.compare(a,b);
        });
        List<List<Integer>> columns=new ArrayList<>();
        for(int index:indices) {
            List<Integer> column=null;
            for(List<Integer> candidate:columns) {
                int[] first=boxes[candidate.get(0)];
                // 列宽取两框宽度的较小值，列判定容差为列宽的一半。
                int tolerance=Math.max(MIN_TOLERANCE,Math.round(Math.min(width(boxes[index]),width(first))*.5f));
                if(Math.abs(centerX(boxes[index])-centerX(first))<=tolerance) { column=candidate; break; }
            }
            if(column==null) { column=new ArrayList<>(); columns.add(column); }
            column.add(index);
        }
        for(List<Integer> column:columns) column.sort((a,b) -> {
            int result=Integer.compare(boxes[a][1],boxes[b][1]);
            return result!=0 ? result : Integer.compare(a,b);
        });
        return columns;
    }

    /** 横排页：按垂直中心自上向下成行，同行按左边界自左向右。 */
    private static List<List<Integer>> horizontalGroups(int[][] boxes,List<Integer> indices) {
        indices.sort((a,b) -> {
            int result=Integer.compare(centerY(boxes[a]),centerY(boxes[b]));
            if(result!=0) return result;
            result=Integer.compare(boxes[a][0],boxes[b][0]);
            return result!=0 ? result : Integer.compare(a,b);
        });
        List<List<Integer>> rows=new ArrayList<>();
        for(int index:indices) {
            List<Integer> row=null;
            for(List<Integer> candidate:rows) {
                int[] first=boxes[candidate.get(0)];
                // 行高取两框高度的较小值，行判定容差为行高的一半。
                int tolerance=Math.max(MIN_TOLERANCE,Math.round(Math.min(height(boxes[index]),height(first))*.5f));
                if(Math.abs(centerY(boxes[index])-centerY(first))<=tolerance) { row=candidate; break; }
            }
            if(row==null) { row=new ArrayList<>(); rows.add(row); }
            row.add(index);
        }
        for(List<Integer> row:rows) row.sort((a,b) -> {
            int result=Integer.compare(boxes[a][0],boxes[b][0]);
            return result!=0 ? result : Integer.compare(a,b);
        });
        return rows;
    }

    private static boolean valid(int[] box) {
        return box!=null && box.length>=4 && box[2]>box[0] && box[3]>box[1];
    }

    private static boolean tall(int[] box) {
        return height(box)>width(box)*VERTICAL_ASPECT;
    }

    private static boolean eastAsian(String text) {
        return text!=null && text.codePoints().anyMatch(c -> Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN
                || Character.UnicodeScript.of(c)==Character.UnicodeScript.HIRAGANA
                || Character.UnicodeScript.of(c)==Character.UnicodeScript.KATAKANA);
    }

    private static String text(String[] texts,int index) {
        return texts==null || index>=texts.length ? "" : texts[index];
    }

    private static int width(int[] box) { return box[2]-box[0]; }
    private static int height(int[] box) { return box[3]-box[1]; }
    private static int centerX(int[] box) { return (box[0]+box[2])/2; }
    private static int centerY(int[] box) { return (box[1]+box[3])/2; }
}
