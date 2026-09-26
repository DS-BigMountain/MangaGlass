package com.mangaglass.app;

import java.util.*;

/** Groups OCR columns by a shared enclosed light background, independently of font or text-box size. */
public final class BubbleGroups {
    public static final class Group {
        public final List<Integer> members;
        public final int[] bounds;
        Group(List<Integer> members, int[][] boxes) {
            this.members = members;
            bounds = boxes[members.get(0)].clone();
            for (int id : members) {
                bounds[0] = Math.min(bounds[0],boxes[id][0]); bounds[1] = Math.min(bounds[1],boxes[id][1]);
                bounds[2] = Math.max(bounds[2],boxes[id][2]); bounds[3] = Math.max(bounds[3],boxes[id][3]);
            }
        }
    }
    private BubbleGroups() {}
    public static List<Group> find(int[][] boxes, int width, int height, BubbleGeometry.Pixels pixels, boolean rtl) {
        if (boxes.length == 0) return Collections.emptyList();
        int step = Math.max(2,(Math.max(width,height)+999)/1000);
        int cols=(width+step-1)/step, rows=(height+step-1)/step;
        int[] labels=new int[cols*rows], queue=new int[labels.length];
        // A cell is traversable only if its pixels are light. Thin outlines cannot be skipped by downsampling.
        for(int gy=0;gy<rows;gy++) for(int gx=0;gx<cols;gx++) {
            boolean light=true;
            for(int y=gy*step;y<Math.min(height,(gy+1)*step)&&light;y++) for(int x=gx*step;x<Math.min(width,(gx+1)*step);x++) {
                int color=pixels.at(x,y);
                if(Math.min((color>>16)&255,Math.min((color>>8)&255,color&255))<225) { light=false; break; }
            }
            labels[gy*cols+gx]=light ? 0 : -1;
        }
        List<Boolean> enclosed=new ArrayList<>(); enclosed.add(false);
        int component=0;
        for(int seed=0;seed<labels.length;seed++) {
            if(labels[seed]!=0) continue;
            int id=++component, read=0, write=1; queue[0]=seed; labels[seed]=id;
            int left=cols, top=rows, right=0, bottom=0;
            while(read<write) {
                int p=queue[read++], x=p%cols, y=p/cols;
                left=Math.min(left,x); top=Math.min(top,y); right=Math.max(right,x); bottom=Math.max(bottom,y);
                if(x>0 && labels[p-1]==0) { labels[p-1]=id; queue[write++]=p-1; }
                if(x+1<cols && labels[p+1]==0) { labels[p+1]=id; queue[write++]=p+1; }
                if(y>0 && labels[p-cols]==0) { labels[p-cols]=id; queue[write++]=p-cols; }
                if(y+1<rows && labels[p+cols]==0) { labels[p+cols]=id; queue[write++]=p+cols; }
            }
            enclosed.add(left>0 && top>0 && right<cols-1 && bottom<rows-1
                    && right-left<cols*.65 && bottom-top<rows*.45 && write>12);
        }
        Map<Integer,List<Integer>> groups=new LinkedHashMap<>();
        for(int i=0;i<boxes.length;i++) {
            int[] b=boxes[i]; Map<Integer,Integer> votes=new HashMap<>();
            int pad=step*2;
            for(int y=Math.max(0,b[1]-pad);y<Math.min(height,b[3]+pad);y+=step) {
                for(int x=Math.max(0,b[0]-pad);x<Math.min(width,b[2]+pad);x+=step) {
                    int id=labels[(y/step)*cols+x/step];
                    if(id>0 && enclosed.get(id)) votes.put(id,votes.getOrDefault(id,0)+1);
                }
            }
            int winner=-i-1, most=0;
            for(Map.Entry<Integer,Integer> vote:votes.entrySet()) if(vote.getValue()>most) { most=vote.getValue(); winner=vote.getKey(); }
            groups.computeIfAbsent(winner,ignored -> new ArrayList<>()).add(i);
        }
        List<Group> result=new ArrayList<>();
        for(List<Integer> members:groups.values()) result.add(new Group(order(members,boxes,rtl),boxes));
        result.sort(Comparator.comparingInt(g -> g.bounds[1]));
        return result;
    }
    private static List<Integer> order(List<Integer> members,int[][] boxes,boolean rtl) {
        // Form rows/bands first, then sort the columns in each band; avoids a non-transitive comparator.
        List<Integer> pending=new ArrayList<>(members);
        pending.sort(Comparator.comparingInt(i -> boxes[i][1]));
        List<List<Integer>> bands=new ArrayList<>();
        for(int id:pending) {
            List<Integer> match=null;
            for(List<Integer> band:bands) {
                int[] first=boxes[band.get(0)], b=boxes[id];
                int overlap=Math.min(first[3],b[3])-Math.max(first[1],b[1]);
                if(overlap>Math.min(first[3]-first[1],b[3]-b[1])*.3) { match=band; break; }
            }
            if(match==null) { match=new ArrayList<>(); bands.add(match); }
            match.add(id);
        }
        List<Integer> ordered=new ArrayList<>();
        for(List<Integer> band:bands) {
            band.sort(Comparator.comparingInt(i -> rtl ? -boxes[i][2] : boxes[i][0])); ordered.addAll(band);
        }
        return ordered;
    }
}
