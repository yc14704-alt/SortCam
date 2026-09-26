package com.sortcam.app.ocr;

import android.content.Context;
import android.net.Uri;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public final class OcrEngine {
    public interface Callback {
        void onSuccess(String text);
        void onError(Exception e);
    }

    private OcrEngine() {}

    public static void recognize(Context context, Uri uri, Callback callback) {
        final InputImage image;
        try {
            image = InputImage.fromFilePath(context, uri);
        } catch (IOException e) {
            callback.onError(e);
            return;
        }

        TextRecognizer korean = TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());
        TextRecognizer latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        AtomicInteger pending = new AtomicInteger(2);
        String[] results = new String[]{"", ""};
        Exception[] failure = new Exception[]{null};

        Runnable finish = () -> {
            if (pending.decrementAndGet() != 0) return;
            korean.close();
            latin.close();
            String merged = mergeDistinct(results[0], results[1]);
            if (!merged.isEmpty()) {
                callback.onSuccess(merged);
            } else if (failure[0] != null) {
                callback.onError(failure[0]);
            } else {
                callback.onSuccess("");
            }
        };

        korean.process(image)
                .addOnSuccessListener(result -> {
                    results[0] = result.getText() == null ? "" : result.getText();
                    finish.run();
                })
                .addOnFailureListener(e -> {
                    failure[0] = e;
                    finish.run();
                });

        latin.process(image)
                .addOnSuccessListener(result -> {
                    results[1] = result.getText() == null ? "" : result.getText();
                    finish.run();
                })
                .addOnFailureListener(e -> {
                    failure[0] = e;
                    finish.run();
                });
    }

    private static String mergeDistinct(String a, String b) {
        Set<String> lines = new LinkedHashSet<>();
        addLines(lines, a);
        addLines(lines, b);
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            if (out.length() > 0) out.append('\n');
            out.append(line);
        }
        return out.toString();
    }

    private static void addLines(Set<String> lines, String text) {
        if (text == null) return;
        for (String line : text.split("\\R")) {
            String cleaned = line.trim();
            if (!cleaned.isEmpty()) lines.add(cleaned);
        }
    }
}
