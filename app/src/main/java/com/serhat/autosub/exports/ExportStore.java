package com.serhat.autosub.exports;


import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.serhat.autosub.models.VoskModelInfo;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ExportStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "autosub_exports.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "exports";

    public ExportStore(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "path TEXT NOT NULL UNIQUE," +
                "parent_path TEXT NOT NULL," +
                "type TEXT NOT NULL," +
                "model_id TEXT," +
                "model_language TEXT," +
                "source_video_uri TEXT," +
                "source_video_name TEXT," +
                "export_kind TEXT," +
                "format TEXT," +
                "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_exports_parent ON " + TABLE + " (parent_path)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE);
        onCreate(db);
    }

    public void addFolder(File folder, File rootDir) {
        if (folder == null) {
            return;
        }
        File parent = folder.equals(rootDir) ? rootDir : folder.getParentFile();
        ContentValues values = baseValues(folder, parent == null ? rootDir : parent, ExportRecord.TYPE_FOLDER);
        values.put("created_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public void addFile(File file, File rootDir, String type, VoskModelInfo modelInfo, String sourceVideoUri,
                        String sourceVideoName, String exportKind, String format) {
        if (file == null) {
            return;
        }
        File parent = file.getParentFile();
        addFolder(rootDir, rootDir);
        if (parent != null) {
            addFolder(parent, rootDir);
        }
        ContentValues values = baseValues(file, parent == null ? rootDir : parent, type);
        values.put("model_id", modelInfo == null ? "" : modelInfo.getId());
        values.put("model_language", modelInfo == null ? "" : modelInfo.getLanguage());
        values.put("source_video_uri", sourceVideoUri == null ? "" : sourceVideoUri);
        values.put("source_video_name", sourceVideoName == null ? "" : sourceVideoName);
        values.put("export_kind", exportKind == null ? "" : exportKind);
        values.put("format", format == null ? "" : format);
        values.put("created_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public List<ExportRecord> getChildren(File folder) {
        List<ExportRecord> records = new ArrayList<>();
        List<String> missingPaths = new ArrayList<>();
        String parentPath = safePath(folder);
        try (Cursor cursor = getReadableDatabase().query(TABLE, null, "parent_path = ? AND path != ?",
                new String[]{parentPath, parentPath}, null, null,
                "CASE WHEN type = 'folder' THEN 0 ELSE 1 END, created_at DESC")) {
            while (cursor.moveToNext()) {
                ExportRecord record = recordFromCursor(cursor);
                if (recordExists(record)) {
                    records.add(record);
                } else {
                    missingPaths.add(record.getPath());
                }
            }
        }
        for (String path : missingPaths) {
            deletePath(path);
        }
        return records;
    }

    public List<File> getFolders(File rootDir) {
        List<File> folders = new ArrayList<>();
        List<String> missingPaths = new ArrayList<>();
        addFolder(rootDir, rootDir);
        try (Cursor cursor = getReadableDatabase().query(TABLE, null, "type = ?",
                new String[]{ExportRecord.TYPE_FOLDER}, null, null, "path ASC")) {
            while (cursor.moveToNext()) {
                ExportRecord record = recordFromCursor(cursor);
                File folder = new File(record.getPath());
                if (folder.isDirectory() && isInsideRoot(folder, rootDir)) {
                    folders.add(folder);
                } else {
                    missingPaths.add(record.getPath());
                }
            }
        }
        for (String path : missingPaths) {
            deletePath(path);
        }
        return folders;
    }

    public void updatePath(String oldPath, File newFile, File rootDir) {
        if (oldPath == null || newFile == null) {
            return;
        }
        File parent = newFile.getParentFile();
        if (parent != null) {
            addFolder(parent, rootDir);
        }
        ContentValues values = new ContentValues();
        values.put("path", safePath(newFile));
        values.put("parent_path", safePath(parent == null ? rootDir : parent));
        getWritableDatabase().update(TABLE, values, "path = ?", new String[]{oldPath});
    }

    public void deletePath(String path) {
        getWritableDatabase().delete(TABLE, "path = ?", new String[]{path});
    }

    private ContentValues baseValues(File file, File parent, String type) {
        ContentValues values = new ContentValues();
        values.put("path", safePath(file));
        values.put("parent_path", safePath(parent));
        values.put("type", type);
        return values;
    }

    private ExportRecord recordFromCursor(Cursor cursor) {
        return new ExportRecord(
                cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                cursor.getString(cursor.getColumnIndexOrThrow("path")),
                cursor.getString(cursor.getColumnIndexOrThrow("parent_path")),
                cursor.getString(cursor.getColumnIndexOrThrow("type")),
                cursor.getString(cursor.getColumnIndexOrThrow("model_id")),
                cursor.getString(cursor.getColumnIndexOrThrow("model_language")),
                cursor.getString(cursor.getColumnIndexOrThrow("source_video_uri")),
                cursor.getString(cursor.getColumnIndexOrThrow("source_video_name")),
                cursor.getString(cursor.getColumnIndexOrThrow("export_kind")),
                cursor.getString(cursor.getColumnIndexOrThrow("format")),
                cursor.getLong(cursor.getColumnIndexOrThrow("created_at")));
    }

    private boolean recordExists(ExportRecord record) {
        File file = new File(record.getPath());
        return ExportRecord.TYPE_FOLDER.equals(record.getType()) ? file.isDirectory() : file.isFile();
    }

    private boolean isInsideRoot(File file, File rootDir) {
        try {
            String rootPath = rootDir.getCanonicalPath();
            String filePath = file.getCanonicalPath();
            return filePath.equals(rootPath) || filePath.startsWith(rootPath + File.separator);
        } catch (Exception e) {
            return false;
        }
    }

    private String safePath(File file) {
        try {
            return file.getCanonicalPath();
        } catch (Exception e) {
            return file.getAbsolutePath();
        }
    }

    public List<File> getExistingExportsForVideo(String videoUriString) {
        List<File> files = new ArrayList<>();
        if (videoUriString == null || videoUriString.trim().isEmpty()) {
            return files;
        }
        try (Cursor cursor = getReadableDatabase().query(TABLE, new String[]{"path"}, "source_video_uri = ?",
                new String[]{videoUriString}, null, null, null)) {
            while (cursor.moveToNext()) {
                String path = cursor.getString(cursor.getColumnIndexOrThrow("path"));
                File file = new File(path);
                if (file.isFile()) {
                    files.add(file);
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return files;
    }

    public void deleteExportsForVideo(String videoUriString) {
        if (videoUriString == null || videoUriString.trim().isEmpty()) {
            return;
        }
        List<File> files = getExistingExportsForVideo(videoUriString);
        for (File file : files) {
            if (file.isFile()) {
                file.delete();
            }
            deletePath(file.getAbsolutePath());
        }
    }
}
