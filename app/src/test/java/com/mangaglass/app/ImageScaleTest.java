package com.mangaglass.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class ImageScaleTest {
    @Test public void defaultLimitScalesTheLongSideTo1920() {
        ImageScale.Fit fit=ImageScale.fit(3000,2000);
        assertEquals(1920,fit.width);
        assertEquals(1280,fit.height);
        assertEquals(.64f,fit.scale,.0001f);
    }

    @Test public void portraitImageScalesByItsHeight() {
        ImageScale.Fit fit=ImageScale.fit(1080,2400);
        assertEquals(864,fit.width);
        assertEquals(1920,fit.height);
        assertEquals(.8f,fit.scale,.0001f);
    }

    @Test public void smallImageKeepsItsOriginalSize() {
        ImageScale.Fit fit=ImageScale.fit(800,600);
        assertEquals(800,fit.width);
        assertEquals(600,fit.height);
        assertEquals(1f,fit.scale,0f);
    }

    @Test public void imageAtTheLimitKeepsItsOriginalSize() {
        ImageScale.Fit fit=ImageScale.fit(1920,1080);
        assertEquals(1920,fit.width);
        assertEquals(1080,fit.height);
        assertEquals(1f,fit.scale,0f);
    }

    @Test public void sixteenByNineImageScalesExactly() {
        ImageScale.Fit fit=ImageScale.fit(3840,2160,ImageScale.LONG_SIDE_DEFAULT);
        assertEquals(1920,fit.width);
        assertEquals(1080,fit.height);
        assertEquals(.5f,fit.scale,.0001f);
    }

    @Test public void smallLimitScalesTheLongSideTo1600() {
        ImageScale.Fit fit=ImageScale.fit(4000,2000,ImageScale.LONG_SIDE_SMALL);
        assertEquals(1600,fit.width);
        assertEquals(800,fit.height);
        assertEquals(.4f,fit.scale,.0001f);
    }

    @Test public void largeLimitScalesTheLongSideTo2048() {
        ImageScale.Fit fit=ImageScale.fit(4000,2000,ImageScale.LONG_SIDE_LARGE);
        assertEquals(2048,fit.width);
        assertEquals(1024,fit.height);
        assertEquals(.512f,fit.scale,.0001f);
    }

    @Test public void oddSourceDimensionsRoundToEvenTargets() {
        // 缩放比例 .768：高 1301 像素乘比例得 999.168，取偶数为 1000。
        ImageScale.Fit fit=ImageScale.fit(2500,1301);
        assertEquals(1920,fit.width);
        assertEquals(1000,fit.height);
        assertEquals(0,fit.width%2);
        assertEquals(0,fit.height%2);
    }

    @Test public void squareImageScalesWithoutDistortion() {
        ImageScale.Fit fit=ImageScale.fit(2000,2000);
        assertEquals(1920,fit.width);
        assertEquals(1920,fit.height);
    }

    @Test public void mappingConvertsScaledCoordinatesBackToTheSource() {
        ImageScale.Mapping mapping=ImageScale.mapping(3000,2000);
        assertEquals(1500f,mapping.xToSource(960),.01f);
        assertEquals(1000f,mapping.yToSource(640),.01f);
        assertEquals(960f,mapping.xToScaled(1500),.01f);
        assertEquals(640f,mapping.yToScaled(1000),.01f);
    }

    @Test public void mappingRoundTripsABox() {
        ImageScale.Mapping mapping=ImageScale.mapping(3000,2000);
        assertArrayEquals(new int[]{192,128,384,256},mapping.boxToScaled(new int[]{300,200,600,400}));
        assertArrayEquals(new int[]{300,200,600,400},mapping.boxToSource(new int[]{192,128,384,256}));
    }

    @Test public void mappingKeepsIdentityForImagesInsideTheLimit() {
        ImageScale.Mapping mapping=ImageScale.mapping(800,600);
        assertArrayEquals(new int[]{10,20,110,220},mapping.boxToScaled(new int[]{10,20,110,220}));
        assertArrayEquals(new int[]{10,20,110,220},mapping.boxToSource(new int[]{10,20,110,220}));
    }

    @Test public void supportedLongSideOffersTheThreeLimits() {
        assertTrue(ImageScale.supportedLongSide(1600));
        assertTrue(ImageScale.supportedLongSide(1920));
        assertTrue(ImageScale.supportedLongSide(2048));
        assertFalse(ImageScale.supportedLongSide(1024));
    }
}
