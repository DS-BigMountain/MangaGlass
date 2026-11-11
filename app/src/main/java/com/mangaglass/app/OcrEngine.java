package com.mangaglass.app;

import android.content.Context;
import android.graphics.Bitmap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Bundled recognizer: one detector pass, page-shape routing to the manga engine, orientation
 * classification, multi-language recognition with per-line quality selection, and the grouping pass
 * that turns recognized lines into translation passages. Every layer above the graph call runs on
 * the capture worker and checks cancellation between lines.
 */
final class OcrEngine {
    interface Progress { void update(String message); }
    static final String AUTO = "auto", JAPANESE = "ja", ENGLISH = "en", KOREAN = "ko";
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

    /** Recognize every readable passage of a captured frame and return it in reading order. */
    static List<OcrRegion> recognize(Context context, Bitmap bitmap, String language, Progress progress) throws Exception {
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        if (width < 8 || height < 8) throw new IllegalArgumentException("画面尺寸无效，请重新截取");
        OcrRuntime runtime = runtime(context, progress);
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        Progress report = progress == null ? message -> { } : progress;

        report.update("本地识别 · 检测文字");
        List<int[]> boxes = detect(runtime, pixels, width, height);
        boolean manga = false;
        if (!boxes.isEmpty() && runtime.mangaAvailable() && (AUTO.equals(language) || JAPANESE.equals(language))) {
            int vertical = 0;
            for (int[] box : boxes) if ((box[3] - box[1]) * 3 > (box[2] - box[0]) * 2) vertical++;
            manga = vertical / (double) boxes.size() >= .3;
        }
        List<OcrLine> lines;
        if (manga) {
            report.update("本地识别 · 漫画引擎");
            lines = recognizeManga(runtime, pixels, width, height);
            if (lines.isEmpty()) {
                report.update("本地识别 · 通用引擎");
                lines = recognizeGeneral(runtime, pixels, width, height, boxes, language, report);
            }
        } else {
            report.update("本地识别 · 通用引擎");
            lines = recognizeGeneral(runtime, pixels, width, height, boxes, language, report);
        }
        if (lines.isEmpty()) return Collections.emptyList();

        report.update("本地识别 · 合并段落");
        OcrGrouping.Pixels source = (x, y) -> pixels[y * width + x];
        for (int i = 0; i < lines.size(); i++) {
            if ((i & 7) == 0 && Thread.currentThread().isInterrupted()) throw new InterruptedException();
            OcrLine line = lines.get(i);
            int[] ink = OcrGrouping.tighten(line.rect, source, width, height);
            if (OcrRegion.valid(ink)) {
                ink[0] = Math.max(0, ink[0] - 1); ink[1] = Math.max(0, ink[1] - 1);
                ink[2] = Math.min(width, ink[2] + 1); ink[3] = Math.min(height, ink[3] + 1);
            }
            line.rect[0] = ink[0]; line.rect[1] = ink[1]; line.rect[2] = ink[2]; line.rect[3] = ink[3];
        }
        List<OcrRegion> regions = OcrGrouping.group(lines, source, width, height);
        if (regions.size() > 256) throw new IllegalArgumentException("本页文字区域过多，请放大画面后重试");
        return regions;
    }

    private static List<int[]> detect(OcrRuntime runtime, int[] pixels, int width, int height) throws Exception {
        OcrRuntime.Graph detector = runtime.detector();
        float scale = Math.min(1f, 1280f / Math.max(width, height));
        int outWidth = Math.max(32, Math.round(width * scale / 32f) * 32);
        int outHeight = Math.max(32, Math.round(height * scale / 32f) * 32);
        float[] input = OcrTensor.detection(pixels, width, height, outWidth, outHeight);
        long[][] shape = new long[1][];
        float[] map = runtime.run(detector.session, detector.inputName, input,
                new long[]{1, 3, outHeight, outWidth}, shape);
        int mapHeight = (int) shape[0][2], mapWidth = (int) shape[0][3];
        return DbPostProcessor.boxes(map, mapWidth, mapHeight, width, height, DbPostProcessor.Params.GENERAL);
    }

    private static List<OcrLine> recognizeGeneral(OcrRuntime runtime, int[] pixels, int width, int height,
                                                  List<int[]> boxes, String language, Progress progress) throws Exception {
        if (boxes.isEmpty()) return Collections.emptyList();
        boolean[] upsideDown = classifyOrientation(runtime, pixels, width, height, boxes);
        List<String> languages = AUTO.equals(language)
                ? Arrays.asList(JAPANESE, ENGLISH, KOREAN) : Collections.singletonList(language);
        List<OcrLine> lines = null;
        for (String candidateLanguage : languages) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            progress.update("本地识别 · " + languageLabel(candidateLanguage));
            OcrRuntime.Graph model = runtime.recognizer(candidateLanguage);
            if (model == null || model.alphabet == null) continue;
            List<OcrLine> candidate = new ArrayList<>(boxes.size());
            for (int i = 0; i < boxes.size(); i++) {
                if ((i & 3) == 0 && Thread.currentThread().isInterrupted()) throw new InterruptedException();
                candidate.add(recognizeLine(runtime, model, pixels, width, height, boxes.get(i), upsideDown[i]));
            }
            if (lines == null) {
                lines = candidate;
            } else {
                // Compare the same detected line across recognizers; a small label keeps its own reading.
                for (int i = 0; i < lines.size(); i++)
                    if (quality(candidate.get(i)) > quality(lines.get(i))) lines.set(i, candidate.get(i));
            }
            if (!AUTO.equals(language)) break;
            double score = 0, weight = 0;
            float minimum = 1f;
            for (OcrLine line : lines) {
                double importance = Math.sqrt(Math.max(1, line.width()));
                float confidence = quality(line);
                score += confidence * importance;
                weight += importance;
                minimum = Math.min(minimum, confidence);
            }
            if (score / Math.max(1, weight) >= .90 && minimum >= .80f) break;
        }
        if (lines == null) return Collections.emptyList();
        List<OcrLine> kept = new ArrayList<>(lines.size());
        for (OcrLine line : lines) {
            // A short, low-confidence reading describes artwork rather than a passage.
            if (line.text.trim().isEmpty() || line.confidence < .65f) continue;
            kept.add(line);
        }
        return kept;
    }

    private static boolean[] classifyOrientation(OcrRuntime runtime, int[] pixels, int width, int height,
                                                 List<int[]> boxes) throws Exception {
        boolean[] upsideDown = new boolean[boxes.size()];
        OcrRuntime.Graph classifier = runtime.classifier();
        if (classifier == null) return upsideDown;
        int classifierHeight = classifier.height(80);
        int classifierWidth = classifier.width() > 0 ? classifier.width() : 160;
        for (int i = 0; i < boxes.size(); i++) {
            if ((i & 7) == 0 && Thread.currentThread().isInterrupted()) throw new InterruptedException();
            float[] input = OcrTensor.recognition(pixels, width, height, boxes.get(i), classifierWidth,
                    classifierHeight, classifierWidth, false, false, false, 0, false);
            long[][] shape = new long[1][];
            float[] output = runtime.run(classifier.session, classifier.inputName, input,
                    new long[]{1, 3, classifierHeight, classifierWidth}, shape);
            if (output.length < 2) continue;
            float margin = output[0] - output[1];
            float score = margin > 30f ? 0f : 1f / (1f + (float) Math.exp(margin));
            upsideDown[i] = score >= .9f;
        }
        return upsideDown;
    }

    private static OcrLine recognizeLine(OcrRuntime runtime, OcrRuntime.Graph model, int[] pixels,
                                         int width, int height, int[] box, boolean upsideDown) throws Exception {
        int inputHeight = model.height(48);
        int fixedWidth = model.width();
        boolean tall = (box[3] - box[1]) > (box[2] - box[0]) * 3 / 2;
        if (!tall) return attempt(runtime, model, pixels, width, height, box, upsideDown, false, false, 1);
        // The column reading needs a block count; a few candidates cover the usual character sizes.
        int ratio = Math.max(1, (int) Math.round((double) (box[3] - box[1]) / Math.max(1, box[2] - box[0])));
        int[] candidates = {ratio, ratio + 1, ratio + 2,
                Math.max(1, (int) Math.round((box[3] - box[1]) / Math.max(1.0, (box[2] - box[0]) * 1.4)))};
        OcrLine best = new OcrLine(box, "", 0);
        for (int blocks : candidates) {
            OcrLine line = attempt(runtime, model, pixels, width, height, box, upsideDown, true, false, blocks);
            if (line.confidence > best.confidence) best = line;
        }
        if (best.confidence >= .5f) return best;
        // A line turned a quarter reads differently from a column of upright characters.
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

    private static List<OcrLine> recognizeManga(OcrRuntime runtime, int[] pixels, int width, int height) throws Exception {
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
                DbPostProcessor.Params.MANGA);
        if (boxes.isEmpty()) return Collections.emptyList();
        OcrRuntime.Graph model = runtime.mangaRecognizer();
        if (model == null || model.alphabet == null) return Collections.emptyList();
        int inputHeight = model.height(48);
        List<OcrLine> lines = new ArrayList<>(boxes.size());
        for (int i = 0; i < boxes.size(); i++) {
            if ((i & 3) == 0 && Thread.currentThread().isInterrupted()) throw new InterruptedException();
            int[] box = boxes.get(i);
            boolean vertical = (box[3] - box[1]) > (box[2] - box[0]);
            int spanWidth = vertical ? box[3] - box[1] : box[2] - box[0];
            int spanHeight = vertical ? box[2] - box[0] : box[3] - box[1];
            int contentWidth = Math.max(16, (int) Math.round(48.0 * spanWidth / Math.max(1, spanHeight)));
            if (contentWidth > 4096) continue;
            float[] tensor = OcrTensor.recognition(pixels, width, height, box, contentWidth, inputHeight,
                    contentWidth, false, false, false, 0, vertical);
            long[][] outputShape = new long[1][];
            float[] logits = runtime.run(model.session, model.inputName, tensor,
                    new long[]{1, 3, inputHeight, contentWidth}, outputShape);
            CtcDecoder.Result result = CtcDecoder.decode(logits, (int) outputShape[0][1],
                    (int) outputShape[0][2], model.alphabet);
            lines.add(new OcrLine(box, result.text, result.confidence));
        }
        // Manga pages read columns right to left, each column top to bottom.
        int vertical = 0;
        for (OcrLine line : lines) if (line.height() > line.width()) vertical++;
        if (vertical * 2 > lines.size()) lines.sort(OcrGrouping.COLUMN_ORDER);
        return lines;
    }

    private static float quality(OcrLine line) {
        if (line.confidence <= 0) return 0;
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
