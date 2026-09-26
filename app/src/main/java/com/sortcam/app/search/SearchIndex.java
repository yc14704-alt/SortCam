package com.sortcam.app.search;

import com.sortcam.app.model.Category;
import com.sortcam.app.model.PhotoRecord;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SearchIndex {
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(01[016789])[- .]?(\\d{3,4})[- .]?(\\d{4})(?!\\d)");
    private static final Pattern LONG_NUMBER = Pattern.compile("(?<!\\d)\\d{6,}(?!\\d)");

    private SearchIndex() {}

    public static String build(String mediaType, String tags, String memo, String ocrText) {
        String mediaWords = PhotoRecord.TYPE_VIDEO.equals(mediaType)
                ? "동영상 영상 비디오 video movie"
                : "사진 이미지 photo image";
        String source = mediaWords + " " + safe(tags) + " " + safe(memo) + " " + safe(ocrText);
        StringBuilder extra = new StringBuilder();
        Matcher phones = PHONE.matcher(source);
        while (phones.find()) {
            extra.append(' ').append(phones.group(1)).append(phones.group(2)).append(phones.group(3));
        }
        Matcher nums = LONG_NUMBER.matcher(source);
        while (nums.find()) {
            extra.append(' ').append(nums.group().replaceAll("\\D", ""));
        }
        return source + extra;
    }

    public static String build(String tags, String memo, String ocrText) {
        return build(PhotoRecord.TYPE_PHOTO, tags, memo, ocrText);
    }

    public static boolean matches(PhotoRecord media, Category category, String query) {
        String q = normalize(query);
        if (q.isEmpty()) return true;
        String mediaWords = media.isVideo() ? "동영상 영상 비디오 video movie" : "사진 이미지 photo image";
        String haystack = mediaWords + " " + safe(category == null ? "" : category.name) + " " +
                safe(media.tags) + " " + safe(media.memo) + " " + safe(media.ocrText) + " " + safe(media.aiIndex);
        return normalize(haystack).contains(q);
    }

    public static String preview(String mediaType, String ocrText) {
        StringBuilder out = new StringBuilder("자동 검색어: ");
        out.append(PhotoRecord.TYPE_VIDEO.equals(mediaType) ? "동영상 · " : "사진 · ");
        Matcher phone = PHONE.matcher(safe(ocrText));
        if (phone.find()) {
            out.append(phone.group(1)).append(phone.group(2)).append(phone.group(3)).append(" · ");
        }
        Matcher number = LONG_NUMBER.matcher(safe(ocrText));
        if (number.find()) {
            out.append(number.group()).append(" · ");
        }
        if (safe(ocrText).trim().isEmpty()) {
            out.append("태그와 메모가 검색에 반영됩니다.");
        } else {
            out.append("인식된 글자 전체");
        }
        return out.toString();
    }

    public static String preview(String ocrText) {
        return preview(PhotoRecord.TYPE_PHOTO, ocrText);
    }

    public static String normalize(String text) {
        String n = Normalizer.normalize(safe(text), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return n.replaceAll("[^0-9a-z가-힣]", "");
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
