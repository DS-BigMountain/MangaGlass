package com.mangaglass.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class CtcDecoderTest {
    private static final String[] ALPHABET = {"", "a", "b", "c", " "};

    private static float[] steps(float[]... rows) {
        float[] out = new float[rows.length * ALPHABET.length];
        for (int t = 0; t < rows.length; t++) System.arraycopy(rows[t], 0, out, t * ALPHABET.length, rows[t].length);
        return out;
    }

    private static float[] row(int best) {
        float[] row = new float[ALPHABET.length];
        row[best] = .9f;
        return row;
    }

    @Test public void dictionaryPlacesBlankFirstAndSpaceLast() {
        String[] alphabet = CtcDecoder.alphabet("a\nb\nc\n");
        assertEquals(5, alphabet.length);
        assertEquals("", alphabet[0]);
        assertEquals("a", alphabet[1]);
        assertEquals("c", alphabet[3]);
        assertEquals(" ", alphabet[4]);
    }

    @Test public void dictionaryKeepsEveryLineWithoutTrailingNewline() {
        String[] alphabet = CtcDecoder.alphabet("甲\n乙");
        assertEquals(4, alphabet.length);
        assertEquals("乙", alphabet[2]);
    }

    @Test public void repeatedSymbolsCollapseAndBlankSeparatesRepeats() {
        CtcDecoder.Result result = CtcDecoder.decode(steps(row(1), row(1), row(0), row(1), row(2)), 5, ALPHABET.length, ALPHABET);
        assertEquals("aab", result.text);
        assertTrue(result.confidence > .8f);
    }

    @Test public void trailingBlankAddsNothing() {
        CtcDecoder.Result result = CtcDecoder.decode(steps(row(1), row(0), row(0)), 3, ALPHABET.length, ALPHABET);
        assertEquals("a", result.text);
    }

    @Test public void emptyReadingCarriesZeroConfidence() {
        CtcDecoder.Result result = CtcDecoder.decode(steps(row(0), row(0)), 2, ALPHABET.length, ALPHABET);
        assertEquals("", result.text);
        assertEquals(0f, result.confidence, .0001f);
    }

    @Test(expected = IllegalArgumentException.class)
    public void dictionaryClassMismatchIsRejected() {
        CtcDecoder.decode(steps(row(1)), 1, ALPHABET.length + 1, ALPHABET);
    }
}
