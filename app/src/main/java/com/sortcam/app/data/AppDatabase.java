package com.sortcam.app.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.sortcam.app.model.Category;
import com.sortcam.app.model.PhotoRecord;

import java.util.ArrayList;
import java.util.List;

public class AppDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "sortcam.db";
    private static final int DB_VERSION = 2;

    public AppDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE categories (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL UNIQUE," +
                "emoji TEXT NOT NULL," +
                "is_default INTEGER NOT NULL DEFAULT 0," +
                "sort_order INTEGER NOT NULL DEFAULT 0)");

        db.execSQL("CREATE TABLE photos (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "uri TEXT NOT NULL UNIQUE," +
                "category_id INTEGER NOT NULL," +
                "captured_at INTEGER NOT NULL," +
                "tags TEXT NOT NULL DEFAULT ''," +
                "memo TEXT NOT NULL DEFAULT ''," +
                "ocr_text TEXT NOT NULL DEFAULT ''," +
                "ai_index TEXT NOT NULL DEFAULT ''," +
                "media_type TEXT NOT NULL DEFAULT 'photo'," +
                "duration_ms INTEGER NOT NULL DEFAULT 0," +
                "FOREIGN KEY(category_id) REFERENCES categories(id) ON DELETE RESTRICT)");

        seedDefaults(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE photos ADD COLUMN media_type TEXT NOT NULL DEFAULT 'photo'");
            db.execSQL("ALTER TABLE photos ADD COLUMN duration_ms INTEGER NOT NULL DEFAULT 0");
        }
    }

    public void ensureDefaults() {
        SQLiteDatabase db = getWritableDatabase();
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM categories", null)) {
            if (c.moveToFirst() && c.getInt(0) == 0) seedDefaults(db);
        }
    }

    private void seedDefaults(SQLiteDatabase db) {
        insertCategoryInternal(db, "업무", "💼", true, 0);
        insertCategoryInternal(db, "개인", "🏠", true, 1);
        insertCategoryInternal(db, "영수증", "🧾", true, 2);
        insertCategoryInternal(db, "문서", "📄", true, 3);
    }

    private long insertCategoryInternal(SQLiteDatabase db, String name, String emoji, boolean isDefault, int sortOrder) {
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("emoji", emoji);
        cv.put("is_default", isDefault ? 1 : 0);
        cv.put("sort_order", sortOrder);
        return db.insertOrThrow("categories", null, cv);
    }

    public long addCategory(String name, String emoji) {
        int nextOrder = 0;
        SQLiteDatabase db = getWritableDatabase();
        try (Cursor c = db.rawQuery("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM categories", null)) {
            if (c.moveToFirst()) nextOrder = c.getInt(0);
        }
        return insertCategoryInternal(db, name, emoji, false, nextOrder);
    }

    public boolean categoryNameExists(String name, long excludeId) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM categories WHERE lower(name)=lower(?) AND id<>? LIMIT 1",
                new String[]{name, String.valueOf(excludeId)})) {
            return c.moveToFirst();
        }
    }

    public void updateCategory(long id, String name, String emoji) {
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("emoji", emoji);
        getWritableDatabase().update("categories", cv, "id=?", new String[]{String.valueOf(id)});
    }

    public void deleteCategory(long id, long fallbackCategoryId) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues cv = new ContentValues();
            cv.put("category_id", fallbackCategoryId);
            db.update("photos", cv, "category_id=?", new String[]{String.valueOf(id)});
            db.delete("categories", "id=? AND is_default=0", new String[]{String.valueOf(id)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public List<Category> getCategories() {
        List<Category> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(
                "categories",
                new String[]{"id", "name", "emoji", "is_default", "sort_order"},
                null, null, null, null, "sort_order ASC, id ASC")) {
            while (c.moveToNext()) {
                out.add(new Category(
                        c.getLong(0), c.getString(1), c.getString(2), c.getInt(3) == 1, c.getInt(4)
                ));
            }
        }
        return out;
    }

    public Category getCategory(long id) {
        try (Cursor c = getReadableDatabase().query(
                "categories",
                new String[]{"id", "name", "emoji", "is_default", "sort_order"},
                "id=?", new String[]{String.valueOf(id)}, null, null, null, "1")) {
            if (c.moveToFirst()) {
                return new Category(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3) == 1, c.getInt(4));
            }
        }
        return null;
    }

    public long getFirstDefaultCategoryId() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id FROM categories WHERE is_default=1 ORDER BY sort_order ASC LIMIT 1", null)) {
            if (c.moveToFirst()) return c.getLong(0);
        }
        List<Category> categories = getCategories();
        return categories.isEmpty() ? -1L : categories.get(0).id;
    }

    public long insertPhoto(PhotoRecord p) {
        ContentValues cv = new ContentValues();
        cv.put("uri", p.uri);
        cv.put("category_id", p.categoryId);
        cv.put("captured_at", p.capturedAt);
        cv.put("tags", p.tags == null ? "" : p.tags);
        cv.put("memo", p.memo == null ? "" : p.memo);
        cv.put("ocr_text", p.ocrText == null ? "" : p.ocrText);
        cv.put("ai_index", p.aiIndex == null ? "" : p.aiIndex);
        cv.put("media_type", p.mediaType == null ? PhotoRecord.TYPE_PHOTO : p.mediaType);
        cv.put("duration_ms", p.durationMs);
        return getWritableDatabase().insertOrThrow("photos", null, cv);
    }

    public void updatePhotoMetadata(long photoId, String tags, String memo, String ocrText, String aiIndex) {
        ContentValues cv = new ContentValues();
        cv.put("tags", tags == null ? "" : tags);
        cv.put("memo", memo == null ? "" : memo);
        cv.put("ocr_text", ocrText == null ? "" : ocrText);
        cv.put("ai_index", aiIndex == null ? "" : aiIndex);
        getWritableDatabase().update("photos", cv, "id=?", new String[]{String.valueOf(photoId)});
    }

    public void updatePhotoCategory(long photoId, long categoryId) {
        ContentValues cv = new ContentValues();
        cv.put("category_id", categoryId);
        getWritableDatabase().update("photos", cv, "id=?", new String[]{String.valueOf(photoId)});
    }

    public void deletePhoto(long photoId) {
        getWritableDatabase().delete("photos", "id=?", new String[]{String.valueOf(photoId)});
    }

    public PhotoRecord getPhoto(long id) {
        try (Cursor c = getReadableDatabase().query(
                "photos", photoColumns(), "id=?", new String[]{String.valueOf(id)}, null, null, null, "1")) {
            if (c.moveToFirst()) return readPhoto(c);
        }
        return null;
    }

    public List<PhotoRecord> getPhotos() {
        List<PhotoRecord> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(
                "photos", photoColumns(), null, null, null, null, "captured_at DESC, id DESC")) {
            while (c.moveToNext()) out.add(readPhoto(c));
        }
        return out;
    }

    public List<PhotoRecord> getPhotosByCategory(long categoryId) {
        List<PhotoRecord> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(
                "photos", photoColumns(), "category_id=?", new String[]{String.valueOf(categoryId)},
                null, null, "captured_at DESC")) {
            while (c.moveToNext()) out.add(readPhoto(c));
        }
        return out;
    }

    private String[] photoColumns() {
        return new String[]{"id", "uri", "category_id", "captured_at", "tags", "memo", "ocr_text", "ai_index", "media_type", "duration_ms"};
    }

    private PhotoRecord readPhoto(Cursor c) {
        return new PhotoRecord(
                c.getLong(0), c.getString(1), c.getLong(2), c.getLong(3),
                c.getString(4), c.getString(5), c.getString(6), c.getString(7),
                c.getString(8), c.getLong(9)
        );
    }
}
