package com.mangaglass.app;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java vertical CJK typesetting and punctuation engine for manga speech bubbles.
 * Converts translated text into typographic tokens (vertical ellipses/dashes/brackets via 90-deg
 * rotation, optical upper-right corner placement for fullwidth stops/commas, tate-chu-yoko for
 * paired exclamation/question marks and short alphanumeric tags), applies kinsoku line-breaking,
 * and computes a unified, evenly-spaced right-to-left vertical paragraph grid centered inside the
 * speech bubble's safe interior.
 */
final class VerticalTypography {
    enum DrawMode {
        NORMAL,
        ROTATE_90,
        CORNER_PUNCT,
        TATE_CHU_YOKO
    }

    static final class Token {
        final String text;
        final DrawMode mode;
        final boolean noColStart;
        final boolean noColEnd;
        final boolean gluePrev;

        Token(String text, DrawMode mode, boolean noColStart, boolean noColEnd, boolean gluePrev) {
            this.text = text;
            this.mode = mode;
            this.noColStart = noColStart;
            this.noColEnd = noColEnd;
            this.gluePrev = gluePrev;
        }
    }

    static final class Column {
        final float centerX;
        final float topY;
        final float bottomY;
        final List<Token> tokens;

        Column(float centerX, float topY, float bottomY, List<Token> tokens) {
            this.centerX = centerX;
            this.topY = topY;
            this.bottomY = bottomY;
            this.tokens = tokens;
        }
    }

    static final class Layout {
        final float fontSize;
        final float rowPitch;
        final float colPitch;
        final float contentLeft;
        final float contentTop;
        final float contentRight;
        final float contentBottom;
        final List<Column> columns;

        Layout(float fontSize, float rowPitch, float colPitch,
               float contentLeft, float contentTop, float contentRight, float contentBottom,
               List<Column> columns) {
            this.fontSize = fontSize;
            this.rowPitch = rowPitch;
            this.colPitch = colPitch;
            this.contentLeft = contentLeft;
            this.contentTop = contentTop;
            this.contentRight = contentRight;
            this.contentBottom = contentBottom;
            this.columns = columns;
        }
    }

    static final float ROW_PITCH_RATIO = 1.08f;
    static final float COL_PITCH_RATIO = 1.22f;

    private static final String OPEN_BRACKETS = "「『（【《〈〔([";
    private static final String CLOSE_BRACKETS = "」』）】》〉〕)]";
    private static final String ROTATE_SYMBOLS = "…—─ー－-~～「」『』（）()【】[]《》〈〉〔〕";
    private static final String CORNER_PUNCTS = "。、，．";
    private static final String NO_COL_START = "。、，．！？!?:：；;」』）】》〉〕)]…—─ー－-~～・·";

    private VerticalTypography() {}

    /** Tokenizes translated Chinese text for vertical CJK rendering with authentic punctuation forms. */
    static List<Token> tokenize(String raw) {
        List<Token> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        int len = text.length();
        int i = 0;
        while (i < len) {
            int cp = text.codePointAt(i);
            int step = Character.charCount(cp);
            if (cp == '\n' || cp == '\t' || cp == ' ' || cp == '\u3000') {
                i += step;
                continue;
            }
            // Normalize ASCII "..." or ".." into vertical ellipsis tokens.
            if (cp == '.' && i + 1 < len && text.charAt(i + 1) == '.') {
                int end = i;
                while (end < len && text.charAt(end) == '.') end++;
                int dots = end - i;
                int count = dots >= 5 ? 2 : 1;
                for (int k = 0; k < count; k++) {
                    out.add(new Token("…", DrawMode.ROTATE_90, true, false, k > 0));
                }
                i = end;
                continue;
            }
            // Map horizontal curly double/single quotes to vertical corner quotes (rotated 90 CW -> ﹁﹂ / ﹃﹄).
            if (cp == '“') {
                out.add(new Token("「", DrawMode.ROTATE_90, false, true, false));
                i += step;
                continue;
            }
            if (cp == '”') {
                out.add(new Token("」", DrawMode.ROTATE_90, true, false, false));
                i += step;
                continue;
            }
            if (cp == '‘') {
                out.add(new Token("『", DrawMode.ROTATE_90, false, true, false));
                i += step;
                continue;
            }
            if (cp == '’') {
                out.add(new Token("』", DrawMode.ROTATE_90, true, false, false));
                i += step;
                continue;
            }
            // Normalize ASCII ! and ? in CJK vertical text, and pair double marks (！？, ？！, ！！, ？？) as tate-chu-yoko.
            if (isExclOrQuest(cp)) {
                char first = toFullwidthExclQuest(cp);
                if (i + step < len) {
                    int nextCp = text.codePointAt(i + step);
                    if (isExclOrQuest(nextCp)) {
                        char second = toFullwidthExclQuest(nextCp);
                        String pair = "" + toHalfwidthExclQuest(first) + toHalfwidthExclQuest(second);
                        out.add(new Token(pair, DrawMode.TATE_CHU_YOKO, true, false, false));
                        i += step + Character.charCount(nextCp);
                        continue;
                    }
                }
                out.add(new Token(String.valueOf(first), DrawMode.NORMAL, true, false, false));
                i += step;
                continue;
            }
            // Normalize ASCII comma/colon/semicolon to fullwidth equivalents in vertical Chinese.
            if (cp == ',') {
                out.add(new Token("，", DrawMode.CORNER_PUNCT, true, false, false));
                i += step;
                continue;
            }
            if (cp == ':') {
                out.add(new Token("：", DrawMode.NORMAL, true, false, false));
                i += step;
                continue;
            }
            if (cp == ';') {
                out.add(new Token("；", DrawMode.NORMAL, true, false, false));
                i += step;
                continue;
            }
            // Group short ASCII alphanumeric runs (e.g. "22", "LV1", "SSR") into compact tate-chu-yoko cells.
            if (isAsciiAlnum(cp)) {
                int end = i;
                while (end < len && isAsciiAlnum(text.charAt(end))) end++;
                String run = text.substring(i, end);
                if (run.length() == 1) {
                    out.add(new Token(run, DrawMode.NORMAL, false, false, false));
                } else if (run.length() <= 3) {
                    out.add(new Token(run, DrawMode.TATE_CHU_YOKO, false, false, false));
                } else {
                    // Split longer ASCII runs into 2-char tate-chu-yoko pairs or rotated segment.
                    for (int k = 0; k < run.length(); k += 2) {
                        String chunk = run.substring(k, Math.min(run.length(), k + 2));
                        out.add(new Token(chunk, chunk.length() > 1 ? DrawMode.TATE_CHU_YOKO : DrawMode.NORMAL,
                                false, false, false));
                    }
                }
                i = end;
                continue;
            }

            String ch = text.substring(i, i + step);
            char c = step == 1 ? (char) cp : 0;
            if (c != 0 && CORNER_PUNCTS.indexOf(c) >= 0) {
                out.add(new Token(ch, DrawMode.CORNER_PUNCT, true, false, false));
            } else if (c != 0 && ROTATE_SYMBOLS.indexOf(c) >= 0) {
                boolean noStart = NO_COL_START.indexOf(c) >= 0;
                boolean noEnd = OPEN_BRACKETS.indexOf(c) >= 0;
                boolean glue = (c == '…' || c == '—' || c == '─' || c == 'ー')
                        && !out.isEmpty() && out.get(out.size() - 1).text.equals(ch);
                out.add(new Token(ch, DrawMode.ROTATE_90, noStart, noEnd, glue));
            } else {
                boolean noStart = c != 0 && NO_COL_START.indexOf(c) >= 0;
                boolean noEnd = c != 0 && OPEN_BRACKETS.indexOf(c) >= 0;
                out.add(new Token(ch, DrawMode.NORMAL, noStart, noEnd, false));
            }
            i += step;
        }
        return out;
    }

    /**
     * Builds a unified, balanced right-to-left vertical paragraph layout centered inside the bubble.
     * Eliminates jagged raw-strip stitching while staying strictly inside verified bubble bounds.
     */
    static Layout layout(List<Token> tokens, int[] areaRect, int[] sourceRect,
                         List<int[]> origStrips, int[] stripBottoms,
                         float preferredSize, boolean allowShrink,
                         int[] argb, int imgW, int imgH,
                         boolean hasSurface, int surfaceColor) {
        if (tokens == null || tokens.isEmpty() || areaRect == null) {
            return new Layout(preferredSize, preferredSize * ROW_PITCH_RATIO, preferredSize * COL_PITCH_RATIO,
                    0, 0, 0, 0, new ArrayList<>());
        }
        int pad = Math.max(2, Math.round(preferredSize * .08f));
        int boxL = areaRect[0] + pad;
        int boxT = areaRect[1] + pad;
        int boxR = Math.max(boxL + 4, areaRect[2] - pad);
        int boxB = Math.max(boxT + 4, areaRect[3] - pad);

        // Compute the true safe vertical span from the bubble's original strips & stripBottoms.
        if (origStrips != null && !origStrips.isEmpty()) {
            int minStripT = Integer.MAX_VALUE;
            int maxStripB = Integer.MIN_VALUE;
            for (int k = 0; k < origStrips.size(); k++) {
                int[] st = origStrips.get(k);
                minStripT = Math.min(minStripT, st[1]);
                int sb = (stripBottoms != null && k < stripBottoms.length) ? stripBottoms[k] : st[3];
                maxStripB = Math.max(maxStripB, Math.max(st[3], sb));
            }
            boxT = Math.min(boxT, Math.max(areaRect[1], minStripT));
            boxB = Math.max(boxB, Math.min(areaRect[3], maxStripB));
        }

        int availW = Math.max(8, boxR - boxL);
        int availH = Math.max(8, boxB - boxT);
        int count = tokens.size();

        // Cap font size to fit at least 1 column horizontally.
        float maxSize = Math.min(preferredSize, Math.max(8f, availW / 1.02f));
        // Also avoid giant oversized single-char text exceeding source column width.
        float size = Math.max(8f, maxSize);

        if (allowShrink && !fitsGrid(tokens, size, availW, availH)) {
            float low = 7.5f;
            float high = size;
            for (int step = 0; step < 14; step++) {
                float mid = (low + high) * .5f;
                if (fitsGrid(tokens, mid, availW, availH)) low = mid;
                else high = mid;
            }
            size = low;
        }

        float rowPitch = Math.max(1f, size * ROW_PITCH_RATIO);
        float colPitch = Math.max(1f, size * COL_PITCH_RATIO);
        int maxRows = Math.max(1, (int) Math.floor((availH + rowPitch * .10f) / rowPitch));
        // C columns occupy (C - 1) * colPitch + size horizontally (not C * colPitch).
        int maxCols = availW <= size
                ? 1
                : Math.max(1, 1 + (int) Math.floor((availW - size + colPitch * .12f) / colPitch));
        if (maxCols == 1 && availW < colPitch) colPitch = Math.max(size, availW);

        // Choose balanced column count: minimum columns needed, distributed evenly to avoid 1-glyph orphan tail.
        int neededCols = Math.max(1, Math.min(maxCols, (count + maxRows - 1) / maxRows));
        List<List<Token>> colBuckets = distributeBalanced(tokens, neededCols, maxRows);
        // If kinsoku pushed an extra column and we still have room, accept it; otherwise keep within maxCols.
        if (colBuckets.size() > maxCols && neededCols < maxCols) {
            colBuckets = distributeBalanced(tokens, maxCols, maxRows);
        }
        int usedCols = colBuckets.size();
        int tallestCol = 1;
        for (List<Token> b : colBuckets) tallestCol = Math.max(tallestCol, b.size());

        // If any column still overflows availH (e.g., kinsoku added 1 glyph at min size), shrink cleanly to guarantee zero overflow.
        float blockH = tallestCol * rowPitch;
        float blockW = (usedCols - 1) * colPitch + size;
        if (allowShrink && (blockH > availH + 1f || blockW > availW + 1f)) {
            float scaleH = blockH > availH ? availH / blockH : 1f;
            float scaleW = blockW > availW ? availW / blockW : 1f;
            float k = Math.max(.45f, Math.min(scaleH, scaleW));
            size = Math.max(7f, size * k);
            rowPitch = Math.max(1f, size * ROW_PITCH_RATIO);
            colPitch = Math.max(1f, size * COL_PITCH_RATIO);
            blockH = tallestCol * rowPitch;
            blockW = (usedCols - 1) * colPitch + size;
        }

        // Uniform column pitch & centered block placement inside the speech bubble!
        float actualColPitch = usedCols > 1
                ? Math.min(colPitch, Math.max(size * 1.06f, (availW - size) / (usedCols - 1)))
                : size;
        float actualBlockW = (usedCols - 1) * actualColPitch + size;
        float blockLeft = boxL + Math.max(0f, (availW - actualBlockW) * .5f);
        float blockRight = blockLeft + actualBlockW;
        float blockTop = boxT + Math.max(0f, (availH - blockH) * .5f);

        // Build columns strictly right-to-left with uniform top alignment (and surface safety check for stepped boxes).
        List<Column> columns = new ArrayList<>(usedCols);
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int c = 0; c < usedCols; c++) {
            List<Token> bucket = colBuckets.get(c);
            float cx = blockRight - size * .5f - c * actualColPitch;
            float colH = bucket.size() * rowPitch;
            float colTop = blockTop;
            // For irregularly stepped/L-shaped caption boxes (e.g. a narrow upper-left corner cut out by artwork),
            // verify the column top against actual bubble surface clearance if the original strips had a real step.
            if (hasSurface && argb != null && origStrips != null && origStrips.size() >= 2) {
                int safeTop = findSafeColumnTop(argb, imgW, imgH, Math.round(cx),
                        Math.round(colTop), Math.round(boxB - colH), Math.round(size * .4f), surfaceColor);
                if (safeTop > colTop + size * .6f && safeTop + colH <= boxB + 2f) {
                    colTop = safeTop;
                }
            }
            float colBottom = colTop + colH;
            columns.add(new Column(cx, colTop, colBottom, bucket));
            minX = Math.min(minX, cx - size * .5f);
            maxX = Math.max(maxX, cx + size * .5f);
            minY = Math.min(minY, colTop);
            maxY = Math.max(maxY, colBottom);
        }
        return new Layout(size, rowPitch, actualColPitch,
                minX < maxX ? minX : boxL, minY < maxY ? minY : boxT,
                maxX > minX ? maxX : boxR, maxY > minY ? maxY : boxB,
                columns);
    }

    private static boolean fitsGrid(List<Token> tokens, float size, int availW, int availH) {
        float rowPitch = Math.max(1f, size * ROW_PITCH_RATIO);
        float colPitch = Math.max(1f, size * COL_PITCH_RATIO);
        int maxRows = (int) Math.floor((availH + rowPitch * .10f) / rowPitch);
        if (maxRows < 1 || availW < size * .96f) return false;
        int maxCols = 1 + Math.max(0, (int) Math.floor((availW - size + colPitch * .12f) / colPitch));
        int neededCols = (tokens.size() + maxRows - 1) / maxRows;
        if (neededCols > maxCols) return false;
        List<List<Token>> buckets = distributeBalanced(tokens, neededCols, maxRows);
        if (buckets.size() > maxCols) return false;
        for (List<Token> b : buckets) {
            if (b.size() > maxRows) return false;
        }
        return true;
    }

    /**
     * Distributes tokens across {@code colCount} vertical columns cleanly:
     * balances column heights (avoiding a 1-glyph orphan final column) and enforces kinsoku rules
     * so closing punctuation never starts a column and opening brackets never end a column.
     */
    static List<List<Token>> distributeBalanced(List<Token> tokens, int colCount, int maxRows) {
        int n = tokens.size();
        int cols = Math.max(1, colCount);
        if (cols == 1 || n <= 1) {
            List<List<Token>> single = new ArrayList<>(1);
            single.add(new ArrayList<>(tokens));
            return single;
        }
        // Balanced target per column: slightly top-heavier right columns (standard Japanese/Chinese manga look)
        // while preventing a tiny 1-2 glyph orphan tail column.
        int baseRows = Math.min(maxRows, (n + cols - 1) / cols);
        // If baseRows leaves a tiny last column of 1-2 glyphs (e.g., 10 glyphs across 2 cols with maxRows=8 -> 5+5),
        // balanced division already gives ceil(n / cols)!
        int[] breaks = new int[cols + 1];
        breaks[0] = 0;
        int rem = n;
        int remCols = cols;
        int cursor = 0;
        for (int c = 0; c < cols; c++) {
            int take = (rem + remCols - 1) / remCols;
            // Keep right columns slightly fuller if room permits (natural manga rhythm), e.g., 5+4 or 6+5.
            take = Math.min(maxRows, Math.min(rem, take));
            cursor += take;
            breaks[c + 1] = cursor;
            rem -= take;
            remCols--;
        }
        breaks[cols] = n;

        // Enforce kinsoku & paired-symbol glue across every column boundary.
        for (int b = 1; b < cols; b++) {
            int idx = breaks[b];
            if (idx <= breaks[b - 1] || idx >= n) continue;
            // Never split glued pairs (…… or ——) across columns.
            if (tokens.get(idx).gluePrev) {
                if (idx + 1 <= breaks[b + 1] && (idx + 1 - breaks[b - 1]) <= maxRows + 1) {
                    breaks[b] = idx + 1;
                } else if (idx - 1 > breaks[b - 1] && (breaks[b + 1] - (idx - 1)) <= maxRows + 1) {
                    breaks[b] = idx - 1;
                }
            } else if (tokens.get(idx).noColStart) {
                // Pull closing punctuation into end of previous column (allowing +1 soft tuck if needed)
                // or push preceding character to the new column so punctuation never sits alone at column top.
                if (idx + 1 <= breaks[b + 1] && (idx + 1 - breaks[b - 1]) <= maxRows + 1) {
                    breaks[b] = idx + 1;
                } else if (idx - 1 > breaks[b - 1] && (breaks[b + 1] - (idx - 1)) <= maxRows + 1) {
                    breaks[b] = idx - 1;
                }
            } else if (idx > 0 && tokens.get(idx - 1).noColEnd) {
                // Never leave an opening bracket orphaned at the bottom of a column.
                if (idx - 1 > breaks[b - 1] && (breaks[b + 1] - (idx - 1)) <= maxRows + 1) {
                    breaks[b] = idx - 1;
                }
            }
        }

        List<List<Token>> result = new ArrayList<>(cols);
        for (int c = 0; c < cols; c++) {
            int start = breaks[c], end = breaks[c + 1];
            if (end > start) {
                result.add(new ArrayList<>(tokens.subList(start, end)));
            }
        }
        // Final safety pass: never leave a column containing solely punctuation (e.g., lone "…" or "！").
        for (int c = result.size() - 1; c >= 1; c--) {
            List<Token> cur = result.get(c);
            List<Token> prev = result.get(c - 1);
            boolean allPunct = !cur.isEmpty();
            for (Token t : cur) if (!t.noColStart) { allPunct = false; break; }
            if (allPunct && !prev.isEmpty()) {
                if (prev.size() + cur.size() <= maxRows + 1) {
                    prev.addAll(cur);
                    result.remove(c);
                } else if (prev.size() >= 2) {
                    cur.add(0, prev.remove(prev.size() - 1));
                }
            }
        }
        return result;
    }

    /** Checks if a column's top edge would sit on non-surface artwork (stepped/L-shaped caption box). */
    private static int findSafeColumnTop(int[] argb, int imgW, int imgH, int cx,
                                         int startY, int maxStartY, int halfW, int surfaceColor) {
        if (maxStartY <= startY) return startY;
        int sr = (surfaceColor >> 16) & 255, sg = (surfaceColor >> 8) & 255, sb = surfaceColor & 255;
        int x0 = Math.max(0, cx - Math.max(2, halfW));
        int x1 = Math.min(imgW - 1, cx + Math.max(2, halfW));
        int scanY = Math.max(0, startY);
        int limitY = Math.min(imgH - 1, maxStartY);
        // Only shift down if the starting band is genuinely blocked (e.g. dark panel border/artwork outside the stepped box).
        if (isSurfaceRow(argb, imgW, scanY, x0, x1, sr, sg, sb)) return startY;
        for (int y = scanY + 1; y <= limitY; y++) {
            if (isSurfaceRow(argb, imgW, y, x0, x1, sr, sg, sb)) return y + 2;
        }
        return startY;
    }

    private static boolean isSurfaceRow(int[] argb, int imgW, int y, int x0, int x1, int sr, int sg, int sb) {
        int row = y * imgW;
        int clear = 0, total = 0;
        for (int x = x0; x <= x1; x++) {
            int c = argb[row + x];
            int d = Math.abs(((c >> 16) & 255) - sr) + Math.abs(((c >> 8) & 255) - sg) + Math.abs((c & 255) - sb);
            if (d < 72) clear++;
            total++;
        }
        return total > 0 && clear * 10 >= total * 6;
    }

    /** Normalizes horizontal Chinese prose punctuation (e.g., ASCII "..." -> "……" inside CJK prose). */
    static String normalizeHorizontalPunctuation(String text) {
        if (text == null || text.isEmpty()) return "";
        if (!hasCjk(text)) return text;
        StringBuilder sb = new StringBuilder(text.length());
        int n = text.length();
        for (int i = 0; i < n; ) {
            char c = text.charAt(i);
            if (c == '.' && i + 2 < n && text.charAt(i + 1) == '.' && text.charAt(i + 2) == '.') {
                // Preserve code/CLI patterns like ".../..." or "model: ..."
                boolean prevAscii = i > 0 && isAsciiAlnum(text.charAt(i - 1));
                boolean nextAscii = i + 3 < n && isAsciiAlnum(text.charAt(i + 3));
                int end = i;
                while (end < n && text.charAt(end) == '.') end++;
                if (!prevAscii && !nextAscii) {
                    sb.append(end - i >= 5 ? "……" : "…");
                    i = end;
                    continue;
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    static boolean hasCjk(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if ((cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF)
                    || (cp >= 0x3040 && cp <= 0x30FF) || (cp >= 0xF900 && cp <= 0xFAFF)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isExclOrQuest(int cp) {
        return cp == '!' || cp == '?' || cp == '！' || cp == '？';
    }

    private static char toFullwidthExclQuest(int cp) {
        return (cp == '!' || cp == '！') ? '！' : '？';
    }

    private static char toHalfwidthExclQuest(char c) {
        return (c == '!' || c == '！') ? '!' : '?';
    }

    private static boolean isAsciiAlnum(int cp) {
        return (cp >= '0' && cp <= '9') || (cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z');
    }
}
