package com.mangaglass.app;

import android.content.Context;
import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxModelMetadata;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import java.io.File;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Lazy, persistent ONNX Runtime sessions for the bundled detector, orientation classifier,
 * language recognizers and the manga engine. Keeping loaded language recognizers resident avoids
 * repeated 200-500ms disk reload thrashing during AUTO-mode probing.
 */
final class OcrRuntime {
    static final class Graph {
        final OrtSession session;
        final String inputName;
        final long[] inputShape;
        final String[] alphabet;

        Graph(OrtSession session, String inputName, long[] inputShape, String[] alphabet) {
            this.session = session;
            this.inputName = inputName;
            this.inputShape = inputShape;
            this.alphabet = alphabet;
        }

        int height(int fallback) { return inputShape.length > 2 && inputShape[2] > 0 ? (int) inputShape[2] : fallback; }
        int width() { return inputShape.length > 3 && inputShape[3] > 0 ? (int) inputShape[3] : 0; }
        boolean supportsBatch() { return inputShape.length >= 4 && inputShape[0] <= 0; }
    }

    private final OrtEnvironment environment;
    private final OrtSession.SessionOptions options;
    private final File directory;
    private Graph detector, classifier, mangaDetector, mangaRecognizer;
    private final Map<String, Graph> recognizers = new HashMap<>();
    private String mangaDictionary;

    OcrRuntime(Context context, OcrModels.Progress progress) throws Exception {
        environment = OrtEnvironment.getEnvironment();
        options = new OrtSession.SessionOptions();
        int cores = Math.max(2, Runtime.getRuntime().availableProcessors());
        int threads = Math.min(4, Math.max(2, cores / 2));
        options.setIntraOpNumThreads(threads);
        options.setInterOpNumThreads(1);
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        try {
            // Avoid busy-spinning on mobile big.LITTLE SoCs so UI and network I/O stay responsive.
            options.addConfigEntry("session.intra_op.allow_spinning", "0");
        } catch (OrtException ignored) { }
        directory = OcrModels.prepare(context, progress);
    }

    synchronized Graph detector() throws Exception {
        if (detector == null) detector = load("det.onnx", false);
        return detector;
    }

    synchronized Graph classifier() throws Exception {
        if (classifier == null) {
            try { classifier = load("cls.onnx", false); }
            catch (Exception missing) { classifier = null; }
        }
        return classifier;
    }

    synchronized Graph recognizer(String language) throws Exception {
        String key = "en".equals(language) ? "en" : "ko".equals(language) ? "ko" : "ja";
        Graph cached = recognizers.get(key);
        if (cached != null) return cached;
        String file = "ja".equals(key) ? "rec-ja.onnx" : "en".equals(key) ? "rec-en.onnx" : "rec-ko.onnx";
        Graph loaded = load(file, true);
        recognizers.put(key, loaded);
        return loaded;
    }

    synchronized boolean mangaAvailable() {
        return new File(directory, "manga_det.onnx").isFile() && new File(directory, "manga_rec.onnx").isFile();
    }

    synchronized Graph mangaDetector() throws Exception {
        if (mangaDetector == null) mangaDetector = load("manga_det.onnx", false);
        return mangaDetector;
    }

    synchronized Graph mangaRecognizer() throws Exception {
        if (mangaRecognizer == null) {
            if (mangaDictionary == null) mangaDictionary = OcrModels.dictionary(directory);
            mangaRecognizer = load("manga_rec.onnx", true, CtcDecoder.alphabet(mangaDictionary));
        }
        return mangaRecognizer;
    }

    /** Preload the detector and primary recognizer so the first capture incurs no cold-start delay. */
    void warmUp(String language) {
        try {
            detector();
            String primary = OcrEngine.ENGLISH.equals(language) ? OcrEngine.ENGLISH
                    : OcrEngine.KOREAN.equals(language) ? OcrEngine.KOREAN : OcrEngine.JAPANESE;
            recognizer(primary);
        } catch (Exception ignored) { }
    }

    /** Run one graph (batch size 1 or N) and return flattened output tensor values with declared shape. */
    float[] run(OrtSession session, String inputName, float[] data, long[] shape, long[][] outputShape)
            throws OrtException {
        try (OnnxTensor input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(data), shape);
             OrtSession.Result result = session.run(Collections.singletonMap(inputName, input))) {
            OnnxTensor output = (OnnxTensor) result.get(0);
            outputShape[0] = ((TensorInfo) output.getInfo()).getShape();
            FloatBuffer buffer = output.getFloatBuffer();
            float[] values = new float[buffer.remaining()];
            buffer.get(values);
            return values;
        }
    }

    private Graph load(String name, boolean recognition) throws Exception {
        return load(name, recognition, null);
    }

    private Graph load(String name, boolean recognition, String[] externalAlphabet) throws Exception {
        OrtSession session = environment.createSession(new File(directory, name).getAbsolutePath(), options);
        String inputName = session.getInputNames().iterator().next();
        long[] inputShape = inputShape(session, inputName);
        String[] alphabet = externalAlphabet;
        if (recognition && alphabet == null) alphabet = embeddedAlphabet(session);
        return new Graph(session, inputName, inputShape, alphabet);
    }

    private static long[] inputShape(OrtSession session, String inputName) throws OrtException {
        Map<String, NodeInfo> info = session.getInputInfo();
        NodeInfo node = info.get(inputName);
        if (node == null) return new long[]{1, 3, 0, 0};
        TensorInfo tensor = (TensorInfo) node.getInfo();
        return tensor.getShape();
    }

    /** Paddle exports carry their dictionary as the "character" custom metadata entry. */
    private static String[] embeddedAlphabet(OrtSession session) throws OrtException {
        OnnxModelMetadata metadata = session.getMetadata();
        String character = metadata.getCustomMetadataValue("character").orElse(null);
        return character == null ? null : CtcDecoder.alphabet(character);
    }

    synchronized void release() {
        for (Graph graph : recognizers.values()) close(graph);
        recognizers.clear();
        close(detector);
        close(classifier);
        close(mangaDetector);
        close(mangaRecognizer);
        detector = null;
        classifier = null;
        mangaDetector = null;
        mangaRecognizer = null;
    }

    private static void close(Graph graph) {
        if (graph == null) return;
        try { graph.session.close(); } catch (OrtException ignored) { }
    }
}
