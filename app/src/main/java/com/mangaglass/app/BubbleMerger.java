package com.mangaglass.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 竖排列合并：把同一气泡内的竖排列归并为段落。
 * 两列同时满足三项条件即进入同一组：水平间隙 ≤ 1.9 倍列宽、列高重叠 > 45%、
 * 垂直中心距 ≤ 较高一列高度的一半。相邻列沿列链传递合并。
 * 合并结果给出外接框、原始索引、估计字高（列宽中位数）以及自右向左的阅读顺序。
 * 文件只引用标准库类型，合并方法为纯函数，阈值可通过 Rules 替换。
 */
final class BubbleMerger {
    /** 合并允许的水平间隙上限与列宽之比。 */
    static final float GAP_RATIO=1.9f;
    /** 合并要求的列高重叠比例下限，比较时取严格大于。 */
    static final float OVERLAP_RATIO=.45f;
    /** 合并允许的垂直中心距与较高一列高度之比。 */
    static final float ALIGN_RATIO=.5f;

    /** 一组可替换的合并阈值。 */
    static final class Rules {
        final float gapRatio,overlapRatio,alignRatio;
        Rules() { this(GAP_RATIO,OVERLAP_RATIO,ALIGN_RATIO); }
        Rules(float gapRatio,float overlapRatio,float alignRatio) {
            this.gapRatio=gapRatio; this.overlapRatio=overlapRatio; this.alignRatio=alignRatio;
        }
    }

    /** 合并后的气泡段落。 */
    static final class Paragraph {
        /** 外接框：左、上、右、下。 */
        final int[] bounds;
        /** 段内原始框索引，按自右向左的阅读顺序排列。 */
        final int[] indices;
        /** 估计字高：段内列宽的中位数。 */
        final float fontSize;
        Paragraph(int[] bounds,int[] indices,float fontSize) {
            this.bounds=bounds; this.indices=indices; this.fontSize=fontSize;
        }
    }

    private static final Rules DEFAULTS=new Rules();

    private BubbleMerger() {}

    /** 按默认阈值合并。 */
    static List<Paragraph> merge(int[][] boxes) {
        return merge(boxes,DEFAULTS);
    }

    /** 按给定阈值合并；段落按外接框右边界自右向左排序，再按上边界自上向下。 */
    static List<Paragraph> merge(int[][] boxes,Rules rules) {
        List<Integer> valid=new ArrayList<>();
        for(int i=0;i<boxes.length;i++) if(valid(boxes[i])) valid.add(i);
        int[] parent=new int[boxes.length];
        for(int index:valid) parent[index]=index;
        // 并查集把满足条件的列串成同一段，列链上的相邻关系沿链传递。
        for(int i=0;i<valid.size();i++) for(int j=i+1;j<valid.size();j++) {
            int a=valid.get(i),b=valid.get(j);
            if(sameBubble(boxes[a],boxes[b],rules)) {
                int rootA=root(parent,a),rootB=root(parent,b);
                if(rootA!=rootB) parent[Math.max(rootA,rootB)]=Math.min(rootA,rootB);
            }
        }
        Map<Integer,List<Integer>> groups=new LinkedHashMap<>();
        for(int index:valid) groups.computeIfAbsent(root(parent,index),key -> new ArrayList<>()).add(index);
        List<Paragraph> paragraphs=new ArrayList<>();
        for(List<Integer> group:groups.values()) paragraphs.add(paragraph(boxes,group));
        paragraphs.sort((a,b) -> {
            int result=Integer.compare(b.bounds[2],a.bounds[2]);
            if(result!=0) return result;
            result=Integer.compare(a.bounds[1],b.bounds[1]);
            return result!=0 ? result : Integer.compare(a.indices[0],b.indices[0]);
        });
        return paragraphs;
    }

    /** 三项合并条件的判定：水平间隙、列高重叠与垂直对齐。 */
    static boolean sameBubble(int[] a,int[] b,Rules rules) {
        int gap=Math.max(0,Math.max(a[0],b[0])-Math.min(a[2],b[2]));
        int columnWidth=Math.min(width(a),width(b));
        if(gap>columnWidth*rules.gapRatio) return false;
        int overlap=Math.min(a[3],b[3])-Math.max(a[1],b[1]);
        int shorter=Math.min(height(a),height(b));
        if(overlap<=0 || overlap<=shorter*rules.overlapRatio) return false;
        float centerA=(a[1]+a[3])/2f,centerB=(b[1]+b[3])/2f;
        return Math.abs(centerA-centerB)<=Math.max(height(a),height(b))*rules.alignRatio;
    }

    private static Paragraph paragraph(int[][] boxes,List<Integer> group) {
        int left=Integer.MAX_VALUE,top=Integer.MAX_VALUE,right=Integer.MIN_VALUE,bottom=Integer.MIN_VALUE;
        int[] widths=new int[group.size()];
        for(int i=0;i<group.size();i++) {
            int[] box=boxes[group.get(i)];
            left=Math.min(left,box[0]); top=Math.min(top,box[1]);
            right=Math.max(right,box[2]); bottom=Math.max(bottom,box[3]);
            widths[i]=width(box);
        }
        Arrays.sort(widths);
        group.sort((a,b) -> {
            int result=Integer.compare(centerX(boxes[b]),centerX(boxes[a]));
            if(result!=0) return result;
            result=Integer.compare(boxes[a][1],boxes[b][1]);
            return result!=0 ? result : Integer.compare(a,b);
        });
        int[] indices=new int[group.size()];
        for(int i=0;i<indices.length;i++) indices[i]=group.get(i);
        // 中位数取排序后的中间列宽，列数为偶数时取较大一侧。
        return new Paragraph(new int[]{left,top,right,bottom},indices,widths[widths.length/2]);
    }

    private static int root(int[] parent,int index) {
        while(parent[index]!=index) {
            parent[index]=parent[parent[index]];
            index=parent[index];
        }
        return index;
    }

    private static boolean valid(int[] box) {
        return box!=null && box.length>=4 && box[2]>box[0] && box[3]>box[1];
    }

    private static int width(int[] box) { return box[2]-box[0]; }
    private static int height(int[] box) { return box[3]-box[1]; }
    private static int centerX(int[] box) { return (box[0]+box[2])/2; }
}
