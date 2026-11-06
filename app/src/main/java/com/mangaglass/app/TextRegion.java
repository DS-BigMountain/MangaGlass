package com.mangaglass.app;

import android.graphics.Rect;

public final class TextRegion {
    /** Original text ink box in screenshot pixels. */
    public final Rect bounds;
    /** Enclosing balloon or caption container in screenshot pixels; empty when the model reported no container. */
    public final Rect bubble;
    public final String source;
    public final String translated;
    public TextRegion(Rect bounds, String source, String translated) {
        this(bounds, new Rect(), source, translated);
    }
    public TextRegion(Rect bounds, Rect bubble, String source, String translated) {
        this.bounds = new Rect(bounds);
        this.bubble = bubble == null ? new Rect() : new Rect(bubble);
        this.source = source;
        this.translated = translated;
    }
}
