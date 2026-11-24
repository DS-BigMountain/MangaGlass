package com.mangaglass.app;

import ai.onnxruntime.OrtException;
import android.content.Context;
import android.graphics.Bitmap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Bundled recognizer: one detector pass, true-vertical page-shape routing to the manga engine
 * with confidence fallback, fast batched orientation probing, width-bucketed mini-batch
 * recognition, selective multi-language refinement, and appearance-aware grouping.
 */
final class OcrEngine {
    interface Progress { void update(String message); }
    static final String AUTO = "auto", JAPANESE = "ja", ENGLISH = "en", KOREAN = "ko";
    private static final int MAX_REC_BATCH = 8;
    private static final int MAX_CLS_BATCH = 16;
    private static OcrRuntime shared;

    private OcrEngine() {}

    static synchronized OcrRuntime runtime(Context context, Progress progress) throws Exception {
        if (shared == null) shared = new OcrRuntime(context, progress == null ? null : progress::update);
        return shared;
    }

    static synchronized void release() {
        if (shared != null) { shared.release(); shared = null; }
    }

    static boolean available(Context context) { return OcrModels.available(context); }

    /** Background preload invoked when the floating service starts to remove first-tap cold start. */
    static void prewarm(Context context, String language) {
        if (context == null) return;
        try {
            runtime(context.getApplicationContext(), null).warmUp(language);
        } catch (Exception ignored) { }
    }

    /**
     * Routing follows the measured page shape: true vertical columns (height > 1.2 * width)
     * decide the engine without a second detection budget. Explicit Japanese accepts a 30%
     * vertical share; auto requires stronger vertical evidence so square UI badges or short
     * horizontal tokens never misroute into the Japanese-only manga recognizer.
     */
    static boolean shouldUseMangaEngine(List<int[]> boxes, String language, boolean mangaAvailable) {
        if (boxes == null || boxes.isEmpty() || !mangaAvailable) return false;
        if (!AUTO.equals(language) && !JAPANESE.equals(language)) return false;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        int vertical = 0;
        for (int[] box : boxes) {
            int w = box[2] - box[0], h = box[3] - box[1];
            if (box[0] < minX) minX = box[0];
            if (box[1] < minY) minY = box[1];
            if (box[2] > maxX) maxX = box[2];
            if (box[3] > maxY) maxY = box[3];
            // Genuine vertical columns have meaningful height (>= 24px) rather than short horizontal tokens.
            if (w > 0 && h >= 24 && h * 5 > w * 6) vertical++;
        }
        // A single ultra-wide horizontal strip (e.g. one-line code comment screenshot) is never vertical manga.
        int spanW = maxX - minX, spanH = maxY - minY;
        if (boxes.size() >= 2 && spanH < 64 && spanW > spanH * 4) return false;
        double share = vertical / (double) boxes.size();
        if (JAPANESE.equals(language)) return share >= .30;
        if (vertical >= 2 && share >= .40) return true;
        if (boxes.size() == 1 && vertical == 1) {
            int[] only = boxes.get(0);
            return (only[3] - only[1]) > (only[2] - only[0]) * 2;
        }
        return false;
    }

    /** Falls back to the general engine when manga recognition is empty or doubtful in auto mode. */
    static boolean shouldFallbackFromManga(List<OcrLine> lines, String language) {
        if (lines == null || lines.isEmpty()) return true;
        double sum = 0;
        int count = 0;
        for (OcrLine line : lines) {
            if (line.text != null && !line.text.trim().isEmpty()) {
                sum += line.confidence;
                count++;
            }
        }
        if (count == 0) return true;
        return AUTO.equals(language) && (sum / count) < .75;
    }

    /** Fast detector-only geometry pass used to snap Vision-mode translations onto exact screen rows. */
    static List<OcrRegion> detectGeometry(Context context, Bitmap bitmap) throws Exception {
        if (context == null || bitmap == null || !available(context)) return Collections.emptyList();
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        if (width < 8 || height < 8) return Collections.emptyList();
        OcrRuntime runtime = runtime(context, null);
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        OcrGrouping.Pixels source = (x, y) -> pixels[y * width + x];
        List<int[]> boxes = detect(runtime, pixels, width, height, source);
        if (boxes.isEmpty()) return Collections.emptyList();
        List<OcrLine> lines = new ArrayList<>(boxes.size());
        for (int i = 0; i < boxes.size(); i++) {
            if ((i & 7) == 0 && Thread.currentThread().isInterrupted()) throw new InterruptedException();
            int[] ink = tightenPadded(boxes.get(i), source, width, height);
            lines.add(new OcrLine(ink, " ", 1f));
        }
        return OcrGrouping.group(lines, source, width, height);
    }

    /** Recognize every readable passage of a captured frame and return it in reading order. */
    static List<OcrRegion> recognize(Context context, Bitmap bitmap, String language, Progress progress) throws Exception {
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        if (width < 8 || height < 8) throw new IllegalArgumentException("画面尺寸无效，请重新截取");
        OcrRuntime runtime = runtime(context, progress);
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        OcrGrouping.Pixels source = (x, y) -> pixels[y * width + x];
        Progress report = progress == null ? message -> { } : progress;

        report.update("识别中");
        List<int[]> boxes = detect(runtime, pixels, width, height, source);
        boolean manga = shouldUseMangaEngine(boxes, language, runtime.mangaAvailable());
        List<OcrLine> lines;
        if (manga) {
            report.update("识别中");
            lines = recognizeManga(runtime, pixels, width, height, source);
            if (shouldFallbackFromManga(lines, language)) {
                report.update("识别中");
                lines = recognizeGeneral(runtime, pixels, width, height, boxes, language, report);
            }
        } else {
            report.update("识别中");
            lines = recognizeGeneral(runtime, pixels, width, height, boxes, language, report);
        }
        if (lines.isEmpty()) return Collections.emptyList();

        report.update("识别中");
        for (int i = 0; i < lines.size(); i++) {
            if ((i & 7) == 0 && Thread.currentThread().isInterrupted()) throw new InterruptedException();
            OcrLine line = lines.get(i);
            int[] ink = tightenPadded(line.rect, source, width, height);
            line.rect[0] = ink[0]; line.rect[1] = ink[1]; line.rect[2] = ink[2]; line.rect[3] = ink[3];
        }
        List<OcrRegion> regions = OcrGrouping.group(lines, source, width, height);
        if (regions.size() > 256) throw new IllegalArgumentException("本页文字区域过多，请放大画面后重试");
        return regions;
    }

    private static int[] tightenPadded(int[] rect, OcrGrouping.Pixels source, int width, int height) {
        int[] ink = OcrGrouping.tighten(rect, source, width, height);
        if (OcrRegion.valid(ink)) {
            ink[0] = Math.max(0, ink[0] - 1);
            ink[1] = Math.max(0, ink[1] - 1);
            ink[2] = Math.min(width, ink[2] + 1);
            ink[3] = Math.min(height, ink[3] + 1);
        }
        return ink;
    }

    private static List<int[]> detect(OcrRuntime runtime, int[] pixels, int width, int height,
                                      OcrGrouping.Pixels source) throws Exception {
        OcrRuntime.Graph detector = runtime.detector();
        float scale = Math.min(1f, 1600f / Math.max(width, height));
        int outWidth = Math.max(32, Math.round(width * scale / 32f) * 32);
        int outHeight = Math.max(32, Math.round(height * scale / 32f) * 32);
        float[] input = OcrTensor.detection(pixels, width, height, outWidth, outHeight);
        long[][] shape = new long[1][];
        float[] map = runtime.run(detector.session, detector.inputName, input,
                new long[]{1, 3, outHeight, outWidth}, shape);
        int mapHeight = (int) shape[0][2], mapWidth = (int) shape[0][3];
        return DbPostProcessor.boxes(map, mapWidth, mapHeight, width, height,
                DbPostProcessor.Params.GENERAL, source);
    }

    private static List<OcrLine> recognizeGeneral(OcrRuntime runtime, int[] pixels, int width, int height,
                                                  List<int[]> boxes, String language, Progress progress) throws Exception {
        if (boxes.isEmpty()) return Collections.emptyList();
        boolean[] upsideDown = classifyOrientation(runtime, pixels, width, height, boxes);
        List<Integer> allIndices = new ArrayList<>(boxes.size());
        for (int i = 0; i < boxes.size(); i++) allIndices.add(i);

        if (!AUTO.equals(language)) {
            progress.update("识别中");
            OcrRuntime.Graph model = runtime.recognizer(language);
            if (model == null || model.alphabet == null) return Collections.emptyList();
            OcrLine[] out = new OcrLine[boxes.size()];
            recognizeSubset(runtime, model, pixels, width, height, boxes, upsideDown, allIndices, out);
            return filterGeneralLines(Arrays.asList(out));
        }

        // Auto cascade: run the unified Japanese/CJK+ASCII model first in width-bucketed mini-batches,
        // then selectively refine only uncertain lines using fast language guards and 3-line probes.
        progress.update("识别中");
        OcrRuntime.Graph primary = runtime.recognizer(JAPANESE);
        if (primary == null || primary.alphabet == null) return Collections.emptyList();
        OcrLine[] lineArr = new OcrLine[boxes.size()];
        recognizeSubset(runtime, primary, pixels, width, height, boxes, upsideDown, allIndices, lineArr);
        List<OcrLine> lines = new ArrayList<>(Arrays.asList(lineArr));

        long startedAt = System.nanoTime();
        long budgetNanos = 5_000_000_000L;
        if (!isSatisfied(lines)) {
            int kanaLines = countKanaLines(lines);
            for (String candidateLanguage : Arrays.asList(ENGLISH, KOREAN)) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (System.nanoTime() - startedAt >= budgetNanos) break;
                List<Integer> uncertain = new ArrayList<>();
                boolean anyLatin = false;
                for (int i = 0; i < lines.size(); i++) {
                    if (quality(lines.get(i)) < .90f) {
                        uncertain.add(i);
                        if (!anyLatin && hasLatinLetters(lines.get(i).text)) anyLatin = true;
                    }
                }
                if (uncertain.isEmpty()) break;
                double avgQ = averageQuality(lines);
                if (ENGLISH.equals(candidateLanguage) && !anyLatin && avgQ >= .70) continue;
                // A screen with multiple clear Japanese Kana lines does not need a Korean model probe.
                if (KOREAN.equals(candidateLanguage) && kanaLines >= 2 && avgQ >= .72) continue;

                OcrRuntime.Graph model = runtime.recognizer(candidateLanguage);
                if (model == null || model.alphabet == null) continue;
                if (uncertain.size() <= 4) {
                    OcrLine[] candArr = new OcrLine[boxes.size()];
                    recognizeSubset(runtime, model, pixels, width, height, boxes, upsideDown, uncertain, candArr);
                    for (int idx : uncertain) {
                        if (candArr[idx] != null && quality(candArr[idx]) > quality(lines.get(idx))) {
                            lines.set(idx, candArr[idx]);
                        }
                    }
                } else {
                    List<Integer> ranked = new ArrayList<>(uncertain);
                    ranked.sort((a, b) -> {
                        float qa = quality(lines.get(a)), qb = quality(lines.get(b));
                        boolean lowA = qa < .80f, lowB = qb < .80f;
                        if (lowA != lowB) return lowA ? -1 : 1;
                        long areaA = (long) lines.get(a).width() * lines.get(a).height();
                        long areaB = (long) lines.get(b).width() * lines.get(b).height();
                        return Long.compare(areaB, areaA);
                    });
                    List<Integer> probes = new ArrayList<>(3);
                    for (int idx : ranked) {
                        if (lines.get(idx).width() >= 20 && lines.get(idx).height() >= 8) probes.add(idx);
                        if (probes.size() == 3) break;
                    }
                    if (probes.isEmpty()) {
                        for (int k = 0; k < Math.min(3, ranked.size()); k++) probes.add(ranked.get(k));
                    }
                    OcrLine[] probeOut = new OcrLine[boxes.size()];
                    recognizeSubset(runtime, model, pixels, width, height, boxes, upsideDown, probes, probeOut);
                    boolean promoted = false;
                    for (int idx : probes) {
                        OcrLine cand = probeOut[idx];
                        if (cand == null) continue;
                        float oldQ = quality(lines.get(idx));
                        float newQ = quality(cand);
                        if ((newQ >= .75f && newQ > oldQ + .05f) || (oldQ < .60f && newQ >= .68f)) promoted = true;
                        if (newQ > oldQ) lines.set(idx, cand);
                    }
                    if (promoted) {
                        List<Integer> rest = new ArrayList<>(uncertain.size());
                        for (int idx : uncertain) if (!probes.contains(idx)) rest.add(idx);
                        if (!rest.isEmpty()) {
                            OcrLine[] restOut = new OcrLine[boxes.size()];
                            recognizeSubset(runtime, model, pixels, width, height, boxes, upsideDown, rest, restOut);
                            for (int idx : rest) {
                                if (restOut[idx] != null && quality(restOut[idx]) > quality(lines.get(idx))) {
                                    lines.set(idx, restOut[idx]);
                                }
                            }
                        }
                    }
                }
                if (isSatisfied(lines)) break;
            }
        }
        return filterGeneralLines(lines);
    }

    /**
     * Recognizes a subset of boxes using width-bucketed mini-batches for horizontal lines (up to
     * 8 lines per ONNX call) and early-exit single-line recognition for tall vertical columns.
     */
    private static void recognizeSubset(OcrRuntime runtime, OcrRuntime.Graph model, int[] pixels,
                                        int width, int height, List<int[]> boxes, boolean[] upsideDown,
                                        List<Integer> indices, OcrLine[] out) throws Exception {
        int inputHeight = model.height(48);
        int fixedWidth = model.width();
        boolean canBatch = model.supportsBatch() && indices.size() >= 2;
        List<int[]> horizItems = new ArrayList<>(indices.size()); // {boxIdx, contentWidth, tensorWidth}
        List<Integer> tallItems = new ArrayList<>();

        for (int idx : indices) {
            int[] box = boxes.get(idx);
            int bw = box[2] - box[0], bh = box[3] - box[1];
            boolean tall = bh > bw * 3 / 2;
            if (tall || !canBatch) {
                tallItems.add(idx);
            } else {
                int cw = fixedWidth > 0 ? fixedWidth
                        : Math.max(8, (int) Math.ceil((float) inputHeight * bw / Math.max(1, bh)));
                if (cw > 4096) {
                    out[idx] = new OcrLine(box, " ", 0);
                } else {
                    int tw = fixedWidth > 0 ? fixedWidth : Math.max(320, ((cw + 15) / 16) * 16);
                    horizItems.add(new int[]{idx, cw, tw});
                }
            }
        }

        // Width-bucketed mini-batch execution for horizontal lines.
        if (!horizItems.isEmpty()) {
            horizItems.sort((a, b) -> Integer.compare(a[2], b[2]));
            int pos = 0;
            while (pos < horizItems.size()) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                int startW = horizItems.get(pos)[2];
                int end = pos + 1;
                int maxW = startW;
                while (end < horizItems.size() && end - pos < MAX_REC_BATCH) {
                    int nextW = horizItems.get(end)[2];
                    if (nextW > Math.max(startW + 96, (int) (startW * 1.25f)) || nextW > 1600) break;
                    maxW = nextW;
                    end++;
                }
                int batchSize = end - pos;
                if (batchSize == 1) {
                    int[] item = horizItems.get(pos);
                    out[item[0]] = recognizeLine(runtime, model, pixels, width, height,
                            boxes.get(item[0]), upsideDown[item[0]]);
                } else {
                    int plane = 3 * inputHeight * maxW;
                    float[] batchInput = new float[batchSize * plane];
                    for (int b = 0; b < batchSize; b++) {
                        int[] item = horizItems.get(pos + b);
                        OcrTensor.recognitionInto(batchInput, b * plane, pixels, width, height,
                                boxes.get(item[0]), maxW, inputHeight, item[1],
                                upsideDown[item[0]], false, false, 1, false);
                    }
                    try {
                        long[][] shape = new long[1][];
                        float[] logits = runtime.run(model.session, model.inputName, batchInput,
                                new long[]{batchSize, 3, inputHeight, maxW}, shape);
                        int steps = (int) shape[0][1], classes = (int) shape[0][2];
                        int sliceSize = steps * classes;
                        for (int b = 0; b < batchSize; b++) {
                            int idx = horizItems.get(pos + b)[0];
                            CtcDecoder.Result decoded = CtcDecoder.decode(logits, b * sliceSize,
                                    steps, classes, model.alphabet);
                            out[idx] = new OcrLine(boxes.get(idx), decoded.text, decoded.confidence);
                        }
                    } catch (OrtException batchUnsupported) {
                        for (int b = 0; b < batchSize; b++) {
                            int idx = horizItems.get(pos + b)[0];
                            out[idx] = recognizeLine(runtime, model, pixels, width, height,
                                    boxes.get(idx), upsideDown[idx]);
                        }
                    }
                }
                pos = end;
            }
        }

        for (int i = 0; i < tallItems.size(); i++) {
            if ((i & 3) == 0 && Thread.currentThread().isInterrupted()) throw new InterruptedException();
            int idx = tallItems.get(i);
            out[idx] = recognizeLine(runtime, model, pixels, width, height, boxes.get(idx), upsideDown[idx]);
        }
    }

    private static List<OcrLine> filterGeneralLines(List<OcrLine> lines) {
        List<OcrLine> kept = new ArrayList<>(lines.size());
        for (OcrLine line : lines) {
            if (line == null || line.text == null || line.text.trim().isEmpty() || line.confidence < .65f) continue;
            kept.add(line);
        }
        return kept;
    }

    private static boolean isSatisfied(List<OcrLine> lines) {
        double score = 0, weight = 0, lowWeight = 0;
        for (OcrLine line : lines) {
            if (line == null) continue;
            double importance = Math.sqrt(Math.max(1, line.width()));
            float confidence = quality(line);
            score += confidence * importance;
            weight += importance;
            if (confidence < .72f) lowWeight += importance;
        }
        return score / Math.max(1.0, weight) >= .88 && lowWeight <= weight * .10;
    }

    private static double averageQuality(List<OcrLine> lines) {
        if (lines.isEmpty()) return 0;
        double sum = 0;
        for (OcrLine line : lines) if (line != null) sum += quality(line);
        return sum / lines.size();
    }

    private static int countKanaLines(List<OcrLine> lines) {
        int count = 0;
        for (OcrLine line : lines) {
            if (line == null || line.text == null || line.confidence < .75f) continue;
            for (int i = 0; i < line.text.length(); i++) {
                char c = line.text.charAt(i);
                if (c >= 0x3040 && c <= 0x30FF) { count++; break; }
            }
        }
        return count;
    }

    private static boolean hasLatinLetters(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= 0x00C0 && c <= 0x024F))
                return true;
        }
        return false;
    }

    /**
     * Fast orientation probe: checks up to 4 widest horizontal boxes in a single batched ONNX call.
     * On upright mobile screens (>99.9% of captures) where all probes read cleanly upright, skips
     * classifying the remaining lines. If any probe suggests inverted text, classifies all remaining
     * boxes in mini-batches of 16.
     */
    private static boolean[] classifyOrientation(OcrRuntime runtime, int[] pixels, int width, int height,
                                                 List<int[]> boxes) throws Exception {
        boolean[] upsideDown = new boolean[boxes.size()];
        OcrRuntime.Graph classifier = runtime.classifier();
        if (classifier == null || boxes.isEmpty()) return upsideDown;
        int clsH = classifier.height(80);
        int clsW = classifier.width() > 0 ? classifier.width() : 160;

        List<Integer> candidates = new ArrayList<>(boxes.size());
        for (int i = 0; i < boxes.size(); i++) {
            int[] b = boxes.get(i);
            if ((b[2] - b[0]) >= (b[3] - b[1])) candidates.add(i);
        }
        if (candidates.isEmpty()) return upsideDown;
        candidates.sort((a, b) -> Integer.compare(
                boxes.get(b)[2] - boxes.get(b)[0], boxes.get(a)[2] - boxes.get(a)[0]));
        int probeCount = Math.min(4, candidates.size());
        List<Integer> probes = candidates.subList(0, probeCount);

        float[] probeScores = runOrientationBatch(runtime, classifier, pixels, width, height, boxes, probes, clsW, clsH);
        boolean anyInvertedHint = false;
        for (int k = 0; k < probeCount; k++) {
            if (probeScores[k] >= .9f) upsideDown[probes.get(k)] = true;
            if (probeScores[k] >= .40f) anyInvertedHint = true;
        }
        if (!anyInvertedHint || candidates.size() <= probeCount) return upsideDown;

        List<Integer> remaining = new ArrayList<>(boxes.size());
        boolean[] probed = new boolean[boxes.size()];
        for (int idx : probes) probed[idx] = true;
        for (int i = 0; i < boxes.size(); i++) if (!probed[i]) remaining.add(i);

        for (int start = 0; start < remaining.size(); start += MAX_CLS_BATCH) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            int end = Math.min(remaining.size(), start + MAX_CLS_BATCH);
            List<Integer> chunk = remaining.subList(start, end);
            float[] scores = runOrientationBatch(runtime, classifier, pixels, width, height, boxes, chunk, clsW, clsH);
            for (int k = 0; k < chunk.size(); k++) {
                if (scores[k] >= .9f) upsideDown[chunk.get(k)] = true;
            }
        }
        return upsideDown;
    }

    private static float[] runOrientationBatch(OcrRuntime runtime, OcrRuntime.Graph classifier,
                                               int[] pixels, int width, int height, List<int[]> boxes,
                                               List<Integer> indices, int clsW, int clsH) throws Exception {
        int n = indices.size();
        float[] scores = new float[n];
        int plane = 3 * clsH * clsW;
        if (n >= 2 && classifier.supportsBatch()) {
            float[] batch = new float[n * plane];
            for (int i = 0; i < n; i++) {
                OcrTensor.recognitionInto(batch, i * plane, pixels, width, height, boxes.get(indices.get(i)),
                        clsW, clsH, clsW, false, false, false, 0, false);
            }
            try {
                long[][] shape = new long[1][];
                float[] out = runtime.run(classifier.session, classifier.inputName, batch,
                        new long[]{n, 3, clsH, clsW}, shape);
                int stride = out.length / n;
                if (stride >= 2) {
                    for (int i = 0; i < n; i++) {
                        float margin = out[i * stride] - out[i * stride + 1];
                        scores[i] = margin > 30f ? 0f : 1f / (1f + (float) Math.exp(margin));
                    }
                    return scores;
                }
            } catch (OrtException ignored) { }
        }
        for (int i = 0; i < n; i++) {
            float[] input = OcrTensor.recognition(pixels, width, height, boxes.get(indices.get(i)),
                    clsW, clsH, clsW, false, false, false, 0, false);
            long[][] shape = new long[1][];
            float[] out = runtime.run(classifier.session, classifier.inputName, input,
                    new long[]{1, 3, clsH, clsW}, shape);
            if (out.length >= 2) {
                float margin = out[0] - out[1];
                scores[i] = margin > 30f ? 0f : 1f / (1f + (float) Math.exp(margin));
            }
        }
        return scores;
    }

    private static OcrLine recognizeLine(OcrRuntime runtime, OcrRuntime.Graph model, int[] pixels,
                                         int width, int height, int[] box, boolean upsideDown) throws Exception {
        boolean tall = (box[3] - box[1]) > (box[2] - box[0]) * 3 / 2;
        if (!tall) return attempt(runtime, model, pixels, width, height, box, upsideDown, false, false, 1);
        int ratio = Math.max(1, (int) Math.round((double) (box[3] - box[1]) / Math.max(1, box[2] - box[0])));
        int alt = Math.max(1, (int) Math.round((box[3] - box[1]) / Math.max(1.0, (box[2] - box[0]) * 1.4)));
        int[] candidates = {ratio, ratio + 1, ratio + 2, alt};
        OcrLine best = new OcrLine(box, "", 0);
        for (int i = 0; i < candidates.length; i++) {
            int blocks = candidates[i];
            boolean dup = false;
            for (int j = 0; j < i; j++) if (candidates[j] == blocks) { dup = true; break; }
            if (dup) continue;
            OcrLine line = attempt(runtime, model, pixels, width, height, box, upsideDown, true, false, blocks);
            if (line.confidence > best.confidence) best = line;
            if (best.confidence >= .88f) return best;
        }
        if (best.confidence >= .5f) return best;
        OcrLine turned = attempt(runtime, model, pixels, width, height, box, upsideDown, false, true, 1);
        return turned.confidence > best.confidence ? turned : best;
    }

    private static OcrLine attempt(OcrRuntime runtime, OcrRuntime.Graph model, int[] pixels, int width, int height,
                                   int[] box, boolean upsideDown, boolean vertical, boolean quarter, int blocks) throws Exception {
        int inputHeight = model.height(48);
        int fixedWidth = model.width();
        int contentWidth;
        if (fixedWidth > 0) {
            contentWidth = fixedWidth;
        } else if (vertical) {
            contentWidth = Math.max(8, inputHeight * blocks);
        } else if (quarter) {
            contentWidth = Math.max(8, (int) Math.ceil((float) inputHeight * (box[3] - box[1]) / (box[2] - box[0])));
        } else {
            contentWidth = Math.max(8, (int) Math.ceil((float) inputHeight * (box[2] - box[0]) / (box[3] - box[1])));
        }
        if (contentWidth > 4096) return new OcrLine(box, " ", 0);
        int tensorWidth = fixedWidth > 0 ? fixedWidth : Math.max(320, contentWidth);
        float[] input = OcrTensor.recognition(pixels, width, height, box, tensorWidth, inputHeight, contentWidth,
                upsideDown && !vertical && !quarter, vertical, quarter, blocks, false);
        long[][] shape = new long[1][];
        float[] logits = runtime.run(model.session, model.inputName, input,
                new long[]{1, 3, inputHeight, tensorWidth}, shape);
        int steps = (int) shape[0][1], classes = (int) shape[0][2];
        CtcDecoder.Result result = CtcDecoder.decode(logits, steps, classes, model.alphabet);
        return new OcrLine(box, result.text, result.confidence);
    }

    private static List<OcrLine> recognizeManga(OcrRuntime runtime, int[] pixels, int width, int height,
                                                OcrGrouping.Pixels source) throws Exception {
        int longSide = Math.max(width, height), shortSide = Math.max(1, Math.min(width, height));
        double ratio = 960.0 / longSide;
        if (longSide / (double) shortSide > 2.0)
            ratio = Math.min(1.0, Math.sqrt(0.75 * 960.0 * 960.0 / ((double) width * height)));
        int outWidth = Math.max(32, (int) Math.round(width * ratio / 32) * 32);
        int outHeight = Math.max(32, (int) Math.round(height * ratio / 32) * 32);
        OcrRuntime.Graph detector = runtime.mangaDetector();
        float[] input = OcrTensor.detection(pixels, width, height, outWidth, outHeight);
        long[][] shape = new long[1][];
        float[] map = runtime.run(detector.session, detector.inputName, input,
                new long[]{1, 3, outHeight, outWidth}, shape);
        List<int[]> boxes = DbPostProcessor.boxes(map, (int) shape[0][3], (int) shape[0][2], width, height,
                DbPostProcessor.Params.MANGA, source);
        if (boxes.isEmpty()) return Collections.emptyList();
        OcrRuntime.Graph model = runtime.mangaRecognizer();
        if (model == null || model.alphabet == null) return Collections.emptyList();
        int inputHeight = model.height(48);
        int fixedWidth = model.width();

        List<int[]> items = new ArrayList<>(boxes.size()); // {boxIdx, contentWidth, tensorWidth, vertical(0/1)}
        for (int i = 0; i < boxes.size(); i++) {
            int[] box = boxes.get(i);
            boolean vertical = (box[3] - box[1]) > (box[2] - box[0]);
            int spanWidth = vertical ? box[3] - box[1] : box[2] - box[0];
            int spanHeight = vertical ? box[2] - box[0] : box[3] - box[1];
            int contentWidth = fixedWidth > 0 ? fixedWidth
                    : Math.max(16, (int) Math.round(48.0 * spanWidth / Math.max(1, spanHeight)));
            if (contentWidth > 4096) continue;
            int tensorWidth = fixedWidth > 0 ? fixedWidth : ((contentWidth + 15) / 16) * 16;
            items.add(new int[]{i, contentWidth, tensorWidth, vertical ? 1 : 0});
        }
        items.sort((a, b) -> Integer.compare(a[2], b[2]));

        List<OcrLine> lines = new ArrayList<>(items.size());
        boolean canBatch = model.supportsBatch();
        int pos = 0;
        while (pos < items.size()) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            int startW = items.get(pos)[2];
            int end = pos + 1;
            int maxW = startW;
            if (canBatch) {
                while (end < items.size() && end - pos < MAX_REC_BATCH) {
                    int nextW = items.get(end)[2];
                    if (nextW > Math.max(startW + 64, (int) (startW * 1.25f)) || nextW > 1600) break;
                    maxW = nextW;
                    end++;
                }
            }
            int batchSize = end - pos;
            boolean batchDone = false;
            if (batchSize >= 2) {
                int plane = 3 * inputHeight * maxW;
                float[] batchInput = new float[batchSize * plane];
                for (int b = 0; b < batchSize; b++) {
                    int[] item = items.get(pos + b);
                    OcrTensor.recognitionInto(batchInput, b * plane, pixels, width, height,
                            boxes.get(item[0]), maxW, inputHeight, item[1],
                            false, false, false, 0, item[3] != 0);
                }
                try {
                    long[][] outputShape = new long[1][];
                    float[] logits = runtime.run(model.session, model.inputName, batchInput,
                            new long[]{batchSize, 3, inputHeight, maxW}, outputShape);
                    int steps = (int) outputShape[0][1], classes = (int) outputShape[0][2];
                    int sliceSize = steps * classes;
                    for (int b = 0; b < batchSize; b++) {
                        int[] box = boxes.get(items.get(pos + b)[0]);
                        CtcDecoder.Result result = CtcDecoder.decode(logits, b * sliceSize,
                                steps, classes, model.alphabet);
                        if (keepMangaLine(result.text, result.confidence)) {
                            lines.add(new OcrLine(box, result.text, result.confidence));
                        }
                    }
                    batchDone = true;
                } catch (OrtException ignored) { }
            }
            if (!batchDone) {
                for (int b = 0; b < batchSize; b++) {
                    int[] item = items.get(pos + b);
                    int[] box = boxes.get(item[0]);
                    float[] tensor = OcrTensor.recognition(pixels, width, height, box, item[1], inputHeight,
                            item[1], false, false, false, 0, item[3] != 0);
                    long[][] outputShape = new long[1][];
                    float[] logits = runtime.run(model.session, model.inputName, tensor,
                            new long[]{1, 3, inputHeight, item[1]}, outputShape);
                    CtcDecoder.Result result = CtcDecoder.decode(logits, (int) outputShape[0][1],
                            (int) outputShape[0][2], model.alphabet);
                    if (keepMangaLine(result.text, result.confidence)) {
                        lines.add(new OcrLine(box, result.text, result.confidence));
                    }
                }
            }
            pos = end;
        }
        int vertical = 0;
        for (OcrLine line : lines) if (line.height() > line.width()) vertical++;
        if (vertical * 2 > lines.size()) lines.sort(OcrGrouping.COLUMN_ORDER);
        return lines;
    }

    static boolean keepMangaLine(String text, float confidence) {
        if (text == null) return false;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return false;
        if (confidence < .60f) return false;
        // Single-glyph artwork/speedline/furigana detections need stronger confidence to survive.
        if (trimmed.codePointCount(0, trimmed.length()) == 1 && confidence < .78f) return false;
        return true;
    }

    private static float quality(OcrLine line) {
        if (line == null || line.confidence <= 0) return 0;
        return line.confidence * (.6f + .4f * OcrGrouping.coverage(line.text, line.width(), line.height()));
    }

    private static String languageLabel(String language) {
        if (JAPANESE.equals(language)) return "日语";
        if (ENGLISH.equals(language)) return "英语";
        return "韩语";
    }

    static String label(String language) {
        if (AUTO.equals(language)) return "自动";
        return languageLabel(language);
    }
}
