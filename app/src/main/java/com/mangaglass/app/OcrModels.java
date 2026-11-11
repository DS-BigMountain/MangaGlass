package com.mangaglass.app;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Prepares the bundled OCR weights for memory-mapped loading. The assets ship uncompressed and
 * every file is copied once into private storage; later starts reuse a file whose size matches.
 */
final class OcrModels {
    interface Progress { void update(String message); }
    static final String DIRECTORY = "ocr-models";
    private static final String ASSET_ROOT = "models/";
    static final String[] FILES = {
            "det.onnx", "cls.onnx", "rec-ja.onnx", "rec-en.onnx", "rec-ko.onnx",
            "manga_det.onnx", "manga_rec.onnx", "ppocrv6_dict.txt"};

    private OcrModels() {}

    static synchronized File prepare(Context context, Progress progress) throws IOException {
        File directory = new File(context.getFilesDir(), DIRECTORY);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建本地模型目录");
        AssetManager assets = context.getAssets();
        for (String name : FILES) {
            File target = new File(directory, name);
            long expected = assetLength(assets, ASSET_ROOT + name);
            if (target.isFile() && expected >= 0 && target.length() == expected) continue;
            if (target.isFile() && expected < 0 && target.length() > 0) continue;
            if (progress != null) progress.update("准备本地识别模型 · " + name);
            copy(assets, ASSET_ROOT + name, target);
        }
        return directory;
    }

    static boolean available(Context context) {
        File directory = new File(context.getFilesDir(), DIRECTORY);
        for (String name : FILES) {
            File target = new File(directory, name);
            if (!target.isFile() || target.length() == 0) return false;
        }
        return true;
    }

    static File file(File directory, String name) { return new File(directory, name); }

    static String dictionary(File directory) throws IOException {
        return new String(java.nio.file.Files.readAllBytes(file(directory, "ppocrv6_dict.txt").toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    private static long assetLength(AssetManager assets, String path) {
        try (AssetFileDescriptor descriptor = assets.openFd(path)) {
            return descriptor.getLength();
        } catch (IOException compressed) {
            return -1;
        }
    }

    private static void copy(AssetManager assets, String path, File target) throws IOException {
        File temporary = new File(target.getParentFile(), target.getName() + ".part");
        try (InputStream input = assets.open(path); FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.getFD().sync();
        }
        if (!temporary.renameTo(target)) {
            // A failed rename leaves the finished copy usable for the next attempt.
            if (!temporary.delete()) temporary.deleteOnExit();
            throw new IOException("本地模型写入失败");
        }
    }
}
