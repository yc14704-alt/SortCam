package com.sortcam.app.importer;

import android.app.Application;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.provider.MediaStore;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;
import com.sortcam.app.data.AppDatabase;
import com.sortcam.app.model.PhotoRecord;
import com.sortcam.app.search.SearchIndex;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Retains a sequential import across rotation; uses no Activity or Activity database. */
public class PhotoImportModel extends AndroidViewModel {
    public static class Progress {
        public final boolean running;
        public final String message;
        Progress(boolean running, String message) { this.running = running; this.message = message; }
    }
    private final MutableLiveData<Progress> progress = new MutableLiveData<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean running;
    public PhotoImportModel(@NonNull Application app) { super(app); }
    public LiveData<Progress> progress() { return progress; }
    public boolean isRunning() { return running; }

    public void start(List<Uri> selection, long categoryId, String folder) {
        if (running || selection.isEmpty()) return;
        running = true;
        List<Uri> uris = new ArrayList<>(new LinkedHashSet<>(selection));
        progress.setValue(new Progress(true, "사진 준비 중…"));
        executor.execute(() -> {
            int saved = 0, skipped = 0, failed = 0, ocrFailed = 0;
            ContentResolver resolver = getApplication().getContentResolver();
            AppDatabase db = new AppDatabase(getApplication());
            TextRecognizer recognizer = TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());
            try {
                for (int index = 0; index < uris.size(); index++) {
                    Uri source = uris.get(index);
                    Uri copy = null;
                    boolean registered = false;
                    progress.postValue(new Progress(true, (index + 1) + "/" + uris.size() + " · 사진 저장 및 글자·숫자 인식 중…"));
                    try {
                        if (db.hasImportedSource(source.toString()) || db.hasPhotoUri(source.toString())) {
                            skipped++;
                            continue;
                        }
                        String mime = resolver.getType(source);
                        if (mime == null || !mime.startsWith("image/")) throw new IOException("사진 파일이 아닙니다.");
                        String ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
                        if (ext == null) ext = "img";
                        long now = System.currentTimeMillis();
                        ContentValues values = new ContentValues();
                        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "SortCam_import_" + UUID.randomUUID() + "." + ext);
                        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                        values.put(MediaStore.MediaColumns.RELATIVE_PATH, folder);
                        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                        copy = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                        if (copy == null) throw new IOException("저장 공간을 만들 수 없습니다.");
                        try (InputStream in = resolver.openInputStream(source); OutputStream out = resolver.openOutputStream(copy)) {
                            if (in == null || out == null) throw new IOException("사진을 열 수 없습니다.");
                            byte[] buffer = new byte[64 * 1024];
                            int count;
                            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                        }
                        values.clear();
                        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                        if (resolver.update(copy, values, null, null) != 1) throw new IOException("저장 완료 실패");
                        long destination = db.getCategory(categoryId) == null ? db.getFirstDefaultCategoryId() : categoryId;
                        PhotoRecord record = new PhotoRecord(0, copy.toString(), destination, now,
                                "#사진 #가져옴", "", "", SearchIndex.build("photo", "#사진 #가져옴", "", ""), "photo", 0);
                        record.id = db.insertImportedPhoto(record, source.toString());
                        registered = true;
                        saved++;
                        try {
                            // Korean recognizer also recognizes Latin letters and digits.
                            String text = Tasks.await(recognizer.process(InputImage.fromFilePath(getApplication(), copy))).getText();
                            PhotoRecord current = db.getPhoto(record.id);
                            if (current != null) db.updatePhotoMetadata(current.id, current.tags, current.memo, text,
                                    SearchIndex.build(current.mediaType, current.tags, current.memo, text));
                        } catch (Exception ocrError) { ocrFailed++; }
                    } catch (Exception importError) {
                        failed++;
                    } finally {
                        if (copy != null && !registered) {
                            try { resolver.delete(copy, null, null); } catch (Exception ignored) { }
                        }
                    }
                }
            } finally {
                recognizer.close();
                db.close();
                running = false;
                progress.postValue(new Progress(false, "가져오기 완료 · " + saved + "장 저장 · 중복 " + skipped
                        + "장 · 실패 " + failed + "장" + (ocrFailed > 0 ? "\n글자 인식 실패 " + ocrFailed + "장: 사진 메뉴에서 OCR 다시 읽기를 눌러주세요." : "")));
            }
        });
    }
    @Override protected void onCleared() {
        // Finish the accepted batch without retaining an Activity after it closes.
        executor.shutdown();
    }
}
