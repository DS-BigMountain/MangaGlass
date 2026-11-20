package com.mangaglass.app;

import android.graphics.Rect;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class TextRegion {
    /** Original text ink box in screenshot pixels. */
    public final Rect bounds;
    /** Enclosing balloon or caption container in screenshot pixels; empty when the model reported no container. */
    public final Rect bubble;
    public final String source;
    public final String translated;
    /** Explicit vertical layout flag when known from OCR/vision; null lets layout infer from pixels. */
    public final Boolean vertical;
    /** Measured individual source lines or vertical strips in screenshot pixels. */
    public final List<Rect> sourceLines;
    /** Measured source line height (or median strip width for vertical columns) in pixels; 0 when unknown. */
    public final float sourceLineHeight;
    /** True when bounds and line geometry came directly from local detection. */
    public final boolean hasSourceGeometry;
    /** True when translation left a foreign passage untranslated and original pixels should survive. */
    public final boolean untranslated;
    /** True when this entry exists only to rebuild an uncovered source row without drawing text. */
    public final boolean coverOnly;

    public TextRegion(Rect bounds, String source, String translated) {
        this(bounds, new Rect(), source, translated, null, Collections.emptyList(), 0f, false, false, false);
    }

    public TextRegion(Rect bounds, Rect bubble, String source, String translated) {
        this(bounds, bubble, source, translated, null, Collections.emptyList(), 0f, false, false, false);
    }

    public TextRegion(Rect bounds, Rect bubble, String source, String translated, Boolean vertical) {
        this(bounds, bubble, source, translated, vertical, Collections.emptyList(), 0f, false, false, false);
    }

    public TextRegion(Rect bounds, Rect bubble, String source, String translated, Boolean vertical,
                      List<Rect> sourceLines, float sourceLineHeight, boolean hasSourceGeometry,
                      boolean untranslated, boolean coverOnly) {
        this.bounds = bounds == null ? new Rect() : new Rect(bounds);
        this.bubble = bubble == null ? new Rect() : new Rect(bubble);
        this.source = source == null ? "" : source;
        this.translated = translated == null ? "" : translated;
        this.vertical = vertical;
        if (sourceLines == null || sourceLines.isEmpty()) {
            this.sourceLines = Collections.emptyList();
        } else {
            List<Rect> copied = new ArrayList<>(sourceLines.size());
            for (Rect line : sourceLines) {
                if (line != null && !line.isEmpty()) copied.add(new Rect(line));
            }
            this.sourceLines = Collections.unmodifiableList(copied);
        }
        this.sourceLineHeight = sourceLineHeight;
        this.hasSourceGeometry = hasSourceGeometry;
        this.untranslated = untranslated;
        this.coverOnly = coverOnly;
    }

    /** Creates a cover-only entry that repairs an uncovered source row without drawing text. */
    static TextRegion cover(Rect line) {
        return new TextRegion(line, new Rect(), "", "", false,
                Collections.singletonList(line), line.height(), true, false, true);
    }
}
