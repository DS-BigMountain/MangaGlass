package com.mangaglass.app;

import java.util.*;

/** Horizontal Chinese paragraphs. All labels share one font size; original OCR widths never constrain wrapping. */
public final class ParagraphLayout {
    public interface Measure { float width(String text); }
    public static final class Paragraph {
        public final int[] anchor;
        public final String text;
        public Paragraph(int[] anchor,String text) { this.anchor=anchor.clone(); this.text=normalize(text); }
    }
    public static final class Placement {
        public final int left,top,right,bottom,padding;
        public final float fontSize,lineHeight;
        public final List<String> lines;
        Placement(int left,int top,int width,int height,int padding,float fontSize,float lineHeight,List<String> lines) {
            this.left=left; this.top=top; this.right=left+width; this.bottom=top+height;
            this.padding=padding; this.fontSize=fontSize; this.lineHeight=lineHeight; this.lines=lines;
        }
    }
    private ParagraphLayout() {}
    public static String normalize(String value) {
        return value.replaceAll("[\\r\\n\\u2028\\u2029]+"," ").replaceAll("[\\t ]+"," ").trim();
    }
    public static List<Placement> arrange(List<Paragraph> paragraphs,int screenWidth,int screenHeight,float fontSize,float lineHeight,Measure measure) {
        int padding=Math.max(4,Math.round(fontSize*.25f)), margin=Math.max(4,Math.round(fontSize*.2f));
        int maximum=Math.max(1,screenWidth-2*(margin+padding));
        int standard=Math.max(1,Math.min(maximum,Math.round(fontSize*8)));
        List<Placement> placed=new ArrayList<>();
        for(Paragraph paragraph:paragraphs) {
            // Short names stay on a horizontal line; long dialogue grows in rows, never shrinks the type.
            int contentWidth=Math.max(1,Math.min(standard,(int)Math.ceil(measure.width(paragraph.text))));
            List<String> lines=wrap(paragraph.text,contentWidth,measure);
            while(lines.size()*lineHeight+padding*2>screenHeight-margin*2 && contentWidth<maximum) {
                contentWidth=Math.min(maximum,contentWidth+Math.max(1,Math.round(fontSize*2)));
                lines=wrap(paragraph.text,contentWidth,measure);
            }
            int width=contentWidth+padding*2, height=(int)Math.ceil(lines.size()*lineHeight)+padding*2;
            int[] a=paragraph.anchor;
            int idealX=(a[0]+a[2]-width)/2, idealY=(a[1]+a[3]-height)/2;
            int x=clamp(idealX,margin,screenWidth-margin-width), y=clamp(idealY,margin,screenHeight-margin-height);
            // Find the nearest free slot without making adjacent expanded paragraphs cover one another.
            List<Integer> xs=new ArrayList<>(Collections.singletonList(x)), ys=new ArrayList<>(Collections.singletonList(y));
            for(Placement other:placed) {
                xs.add(clamp(other.left-width-margin,margin,screenWidth-margin-width)); xs.add(clamp(other.right+margin,margin,screenWidth-margin-width));
                ys.add(clamp(other.top-height-margin,margin,screenHeight-margin-height)); ys.add(clamp(other.bottom+margin,margin,screenHeight-margin-height));
            }
            double best=Double.POSITIVE_INFINITY; int chosenX=x,chosenY=y;
            for(int candidateX:xs) for(int candidateY:ys) {
                long overlap=0;
                for(Placement other:placed) overlap+=(long)Math.max(0,Math.min(candidateX+width,other.right)-Math.max(candidateX,other.left))
                        *Math.max(0,Math.min(candidateY+height,other.bottom)-Math.max(candidateY,other.top));
                double score=overlap*1_000_000.0+Math.pow(candidateX-x,2)+Math.pow(candidateY-y,2);
                if(score<best) { best=score; chosenX=candidateX; chosenY=candidateY; }
            }
            placed.add(new Placement(chosenX,chosenY,width,height,padding,fontSize,lineHeight,lines));
        }
        return placed;
    }
    private static int clamp(int value,int min,int max) { return Math.max(min,Math.min(Math.max(min,max),value)); }
    public static List<String> wrap(String input,int width,Measure measure) {
        String text=normalize(input); List<String> lines=new ArrayList<>(); int start=0,end=0,lastSpace=-1;
        while(end<text.length()) {
            int next=end+Character.charCount(text.codePointAt(end));
            if(end>start && measure.width(text.substring(start,next))>width) {
                int split=lastSpace>start ? lastSpace : end;
                // Keep Chinese closing punctuation with the preceding character, within the same width.
                if(split==end && closing(text.codePointAt(end))) {
                    int candidate=text.offsetByCodePoints(end,-1);
                    while(candidate>start && closing(text.codePointAt(candidate))) candidate=text.offsetByCodePoints(candidate,-1);
                    if(candidate>start) split=candidate;
                }
                if(split>start && "（【《「『“‘(".indexOf(text.codePointBefore(split))>=0) {
                    int candidate=text.offsetByCodePoints(split,-1);
                    if(candidate>start) split=candidate;
                }
                lines.add(text.substring(start,split).trim()); start=split;
                while(start<text.length() && text.charAt(start)==' ') start++;
                end=start; lastSpace=-1; continue;
            }
            if(text.charAt(end)==' ') lastSpace=end;
            end=next;
        }
        if(start<text.length()) lines.add(text.substring(start).trim());
        if(lines.isEmpty()) lines.add("");
        return lines;
    }
    private static boolean closing(int c) { return "，。！？；：、）】》」』”’…,.!?;:)".indexOf(c)>=0; }
}
