package com.mangaglass.app;

import android.graphics.Rect;

public final class TextRegion {
    public final Rect bounds;
    public final String source;
    public final String translated;
    public TextRegion(Rect bounds, String source, String translated) {
        this.bounds = new Rect(bounds);
        this.source = source;
        this.translated = translated;
    }
}
