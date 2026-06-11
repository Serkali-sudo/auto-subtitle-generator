package com.serhat.autosub;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class QueueStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "autosub_queue.db";
    private static final int DB_VERSION = 7;
    private static final String TABLE = "queue_items";

    public QueueStore(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "video_uri TEXT NOT NULL," +
                "display_name TEXT NOT NULL," +
                "status TEXT NOT NULL," +
                "progress INTEGER DEFAULT 0," +
                "output_path TEXT," +
                "srt_path TEXT," +
                "vtt_path TEXT," +
                "soft_video_path TEXT," +
                "hard_video_path TEXT," +
                "message TEXT," +
                "preview_text TEXT," +
                "subtitles_json TEXT," +
                "translation_source_language TEXT," +
                "translation_target_language TEXT," +
                "translation_status TEXT," +
                "shorts_video INTEGER DEFAULT 0," +
                "use_vad INTEGER DEFAULT 0," +
                "shorts_caption_x REAL DEFAULT 0.5," +
                "shorts_caption_y REAL DEFAULT 0.5," +
                "shorts_caption_scale REAL DEFAULT 1.0," +
                "subtitle_caption_x REAL DEFAULT 0.5," +
                "subtitle_caption_y REAL DEFAULT 0.88," +
                "subtitle_caption_scale REAL DEFAULT 1.0," +
                "subtitle_caption_adjusted INTEGER DEFAULT 0," +
                "thumbnail_path TEXT," +
                "audio_path TEXT," +
                "created_at INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("DROP TABLE IF EXISTS " + TABLE);
            onCreate(db);
        } else {
            if (oldVersion < 3) {
                db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN shorts_caption_x REAL DEFAULT 0.5");
                db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN shorts_caption_y REAL DEFAULT 0.5");
            }
            if (oldVersion < 4) {
                db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN subtitle_caption_x REAL DEFAULT 0.5");
                db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN subtitle_caption_y REAL DEFAULT 0.88");
                db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN subtitle_caption_adjusted INTEGER DEFAULT 0");
            }
            if (oldVersion < 5) {
                db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN shorts_caption_scale REAL DEFAULT 1.0");
                db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN subtitle_caption_scale REAL DEFAULT 1.0");
            }
            if (oldVersion < 6) {
                db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN translation_source_language TEXT");
                db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN translation_target_language TEXT");
                db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN translation_status TEXT");
            }
            if (oldVersion < 7) {
                db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN use_vad INTEGER DEFAULT 0");
            }
        }
    }

    public long addItem(QueueItem item) {
        ContentValues values = new ContentValues();
        values.put("video_uri", item.getVideoUri().toString());
        values.put("display_name", item.getDisplayName());
        values.put("status", item.getStatus().name());
        values.put("progress", item.getProgress());
        values.put("output_path", item.getOutputPath());
        values.put("srt_path", item.getSrtPath());
        values.put("vtt_path", item.getVttPath());
        values.put("soft_video_path", item.getSoftVideoPath());
        values.put("hard_video_path", item.getHardVideoPath());
        values.put("message", item.getMessage());
        values.put("preview_text", item.getPreviewText());
        values.put("subtitles_json", subtitlesToJson(item.getSubtitles()));
        values.put("translation_source_language", item.getTranslationSourceLanguage());
        values.put("translation_target_language", item.getTranslationTargetLanguage());
        values.put("translation_status", item.getTranslationStatus());
        values.put("shorts_video", item.isShortsVideo() ? 1 : 0);
        values.put("use_vad", item.isUseVad() ? 1 : 0);
        values.put("shorts_caption_x", item.getShortsCaptionX());
        values.put("shorts_caption_y", item.getShortsCaptionY());
        values.put("shorts_caption_scale", item.getShortsCaptionScale());
        values.put("subtitle_caption_x", item.getSubtitleCaptionX());
        values.put("subtitle_caption_y", item.getSubtitleCaptionY());
        values.put("subtitle_caption_scale", item.getSubtitleCaptionScale());
        values.put("subtitle_caption_adjusted", item.isSubtitleCaptionPositionAdjusted() ? 1 : 0);
        values.put("thumbnail_path", item.getThumbnailPath());
        values.put("audio_path", item.getAudioPath());
        values.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insert(TABLE, null, values);
    }

    public void updateItem(QueueItem item) {
        ContentValues values = new ContentValues();
        values.put("video_uri", item.getVideoUri().toString());
        values.put("status", item.getStatus().name());
        values.put("progress", item.getProgress());
        values.put("output_path", item.getOutputPath());
        values.put("srt_path", item.getSrtPath());
        values.put("vtt_path", item.getVttPath());
        values.put("soft_video_path", item.getSoftVideoPath());
        values.put("hard_video_path", item.getHardVideoPath());
        values.put("message", item.getMessage());
        values.put("preview_text", item.getPreviewText());
        values.put("subtitles_json", subtitlesToJson(item.getSubtitles()));
        values.put("translation_source_language", item.getTranslationSourceLanguage());
        values.put("translation_target_language", item.getTranslationTargetLanguage());
        values.put("translation_status", item.getTranslationStatus());
        values.put("shorts_video", item.isShortsVideo() ? 1 : 0);
        values.put("use_vad", item.isUseVad() ? 1 : 0);
        values.put("shorts_caption_x", item.getShortsCaptionX());
        values.put("shorts_caption_y", item.getShortsCaptionY());
        values.put("shorts_caption_scale", item.getShortsCaptionScale());
        values.put("subtitle_caption_x", item.getSubtitleCaptionX());
        values.put("subtitle_caption_y", item.getSubtitleCaptionY());
        values.put("subtitle_caption_scale", item.getSubtitleCaptionScale());
        values.put("subtitle_caption_adjusted", item.isSubtitleCaptionPositionAdjusted() ? 1 : 0);
        values.put("thumbnail_path", item.getThumbnailPath());
        values.put("audio_path", item.getAudioPath());
        getWritableDatabase().update(TABLE, values, "id = ?", new String[]{String.valueOf(item.getId())});
    }

    public void deleteItem(long id) {
        getWritableDatabase().delete(TABLE, "id = ?", new String[]{String.valueOf(id)});
    }

    public List<QueueItem> getItems() {
        List<QueueItem> items = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(TABLE, null, null, null, null, null, "created_at DESC")) {
            while (cursor.moveToNext()) {
                long id = cursor.getLong(cursor.getColumnIndexOrThrow("id"));
                Uri videoUri = Uri.parse(cursor.getString(cursor.getColumnIndexOrThrow("video_uri")));
                String displayName = cursor.getString(cursor.getColumnIndexOrThrow("display_name"));
                
                QueueItem item = new QueueItem(videoUri, displayName);
                item.setId(id);
                item.setStatus(QueueItem.Status.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("status"))));
                item.setProgress(cursor.getInt(cursor.getColumnIndexOrThrow("progress")));
                item.setOutputPath(cursor.getString(cursor.getColumnIndexOrThrow("output_path")));
                item.setSrtPath(cursor.getString(cursor.getColumnIndexOrThrow("srt_path")));
                item.setVttPath(cursor.getString(cursor.getColumnIndexOrThrow("vtt_path")));
                item.setSoftVideoPath(cursor.getString(cursor.getColumnIndexOrThrow("soft_video_path")));
                item.setHardVideoPath(cursor.getString(cursor.getColumnIndexOrThrow("hard_video_path")));
                item.setMessage(cursor.getString(cursor.getColumnIndexOrThrow("message")));
                item.setPreviewText(cursor.getString(cursor.getColumnIndexOrThrow("preview_text")));
                item.setSubtitles(subtitlesFromJson(cursor.getString(cursor.getColumnIndexOrThrow("subtitles_json"))));
                item.setTranslationSourceLanguage(getOptionalString(cursor, "translation_source_language", ""));
                item.setTranslationTargetLanguage(getOptionalString(cursor, "translation_target_language", ""));
                item.setTranslationStatus(getOptionalString(cursor, "translation_status", ""));
                item.setShortsVideo(cursor.getInt(cursor.getColumnIndexOrThrow("shorts_video")) == 1);
                item.setUseVad(getOptionalInt(cursor, "use_vad", 0) == 1);
                item.setShortsCaptionX(getOptionalFloat(cursor, "shorts_caption_x", 0.5f));
                item.setShortsCaptionY(getOptionalFloat(cursor, "shorts_caption_y", 0.5f));
                item.setShortsCaptionScale(getOptionalFloat(cursor, "shorts_caption_scale", 1f));
                item.setSubtitleCaptionX(getOptionalFloat(cursor, "subtitle_caption_x", 0.5f));
                item.setSubtitleCaptionY(getOptionalFloat(cursor, "subtitle_caption_y", 0.88f));
                item.setSubtitleCaptionScale(getOptionalFloat(cursor, "subtitle_caption_scale", 1f));
                item.setSubtitleCaptionPositionAdjusted(getOptionalInt(cursor, "subtitle_caption_adjusted", 0) == 1);
                item.setThumbnailPath(cursor.getString(cursor.getColumnIndexOrThrow("thumbnail_path")));
                item.setAudioPath(cursor.getString(cursor.getColumnIndexOrThrow("audio_path")));
                
                items.add(item);
            }
        }
        return items;
    }

    private float getOptionalFloat(Cursor cursor, String columnName, float defaultValue) {
        int index = cursor.getColumnIndex(columnName);
        if (index < 0 || cursor.isNull(index)) {
            return defaultValue;
        }
        return cursor.getFloat(index);
    }

    private int getOptionalInt(Cursor cursor, String columnName, int defaultValue) {
        int index = cursor.getColumnIndex(columnName);
        if (index < 0 || cursor.isNull(index)) {
            return defaultValue;
        }
        return cursor.getInt(index);
    }

    private String getOptionalString(Cursor cursor, String columnName, String defaultValue) {
        int index = cursor.getColumnIndex(columnName);
        if (index < 0 || cursor.isNull(index)) {
            return defaultValue;
        }
        String value = cursor.getString(index);
        return value == null ? defaultValue : value;
    }

    private String subtitlesToJson(List<SubtitleGenerator.SubtitleEntry> subtitles) {
        JSONArray array = new JSONArray();
        if (subtitles == null) return array.toString();
        try {
            for (SubtitleGenerator.SubtitleEntry entry : subtitles) {
                JSONObject object = new JSONObject();
                object.put("number", entry.getNumber());
                object.put("start", entry.getStartTime());
                object.put("end", entry.getEndTime());
                object.put("text", entry.getText());
                object.put("translationText", entry.getTranslationText());
                JSONArray words = new JSONArray();
                for (WordTiming word : entry.getWords()) {
                    JSONObject wordObject = new JSONObject();
                    wordObject.put("word", word.getWord());
                    wordObject.put("startMs", word.getStartMs());
                    wordObject.put("endMs", word.getEndMs());
                    wordObject.put("confidence", word.getConfidence());
                    words.put(wordObject);
                }
                object.put("words", words);
                array.put(object);
            }
        } catch (Exception ignored) {
        }
        return array.toString();
    }

    private List<SubtitleGenerator.SubtitleEntry> subtitlesFromJson(String json) {
        List<SubtitleGenerator.SubtitleEntry> subtitles = new ArrayList<>();
        if (json == null || json.isEmpty()) return subtitles;
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                JSONArray wordsArray = object.optJSONArray("words");
                List<WordTiming> words = new ArrayList<>();
                if (wordsArray != null) {
                    for (int j = 0; j < wordsArray.length(); j++) {
                        JSONObject wordObject = wordsArray.getJSONObject(j);
                        words.add(new WordTiming(
                                wordObject.optString("word"),
                                wordObject.optLong("startMs"),
                                wordObject.optLong("endMs"),
                                wordObject.optDouble("confidence")));
                    }
                }
                subtitles.add(new SubtitleGenerator.SubtitleEntry(
                        object.optInt("number", i + 1),
                        object.optString("start"),
                        object.optString("end"),
                        object.optString("text"),
                        words));
                subtitles.get(subtitles.size() - 1).setTranslationText(object.optString("translationText", ""));
            }
        } catch (Exception ignored) {
        }
        return subtitles;
    }
}
