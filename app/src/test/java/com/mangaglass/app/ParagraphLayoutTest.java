package com.mangaglass.app;

import org.junit.Test;
import static org.junit.Assert.*;

/** 段落排版的纯函数单测：文本归一化、描边宽度与描边反色规则。 */
public class ParagraphLayoutTest {
    @Test public void normalizedTextJoinsChineseHardBreaksAndKeepsParagraphGaps() {
        assertEquals("这是译文\n\n下一段",ParagraphLayout.normalize("这是\n译文\n\n下一段"));
        assertEquals("An English sentence",ParagraphLayout.normalize("An English\nsentence"));
    }

    @Test public void strokeWidthStaysInsideTheSixToTenPercentBand() {
        for(float size:new float[]{12,18,24,36,48,72}) {
            float width=ParagraphLayout.strokeWidth(size);
            assertTrue(width>=size*.06f);
            assertTrue(width<=size*.10f);
        }
        assertEquals(ParagraphLayout.MIN_STROKE,ParagraphLayout.strokeWidth(1),0f);
    }

    @Test public void outlineColourReversesBackgroundLightness() {
        assertEquals(0xff000000,ParagraphLayout.strokeColor(0xffffffff));
        assertEquals(0xff000000,ParagraphLayout.strokeColor(0xff919191));
        assertEquals(0xffffffff,ParagraphLayout.strokeColor(0xff909090));
        assertEquals(0xffffffff,ParagraphLayout.strokeColor(0xff202124));
    }

    @Test public void outlineColourDerivesFromTheFillColour() {
        assertEquals(0xff000000,ParagraphLayout.strokeColorForFill(0xff202124));
        assertEquals(0xffffffff,ParagraphLayout.strokeColorForFill(0xfffafafa));
    }

    @Test public void defaultSpacingKeepsThePreviousLayoutValue() {
        assertEquals(1.08f,ParagraphLayout.DEFAULT_SPACING,0f);
    }
}
