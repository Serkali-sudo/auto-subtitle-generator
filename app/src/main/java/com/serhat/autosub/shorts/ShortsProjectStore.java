package com.serhat.autosub.shorts;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.serhat.autosub.subtitles.SubtitleGenerator;

import java.util.ArrayList;
import java.util.List;

public class ShortsProjectStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "autosub_shorts.db";
    private static final int DB_VERSION = 4;
    private static final Gson GSON = new Gson();

    public ShortsProjectStore(Context context) { super(context, DB_NAME, null, DB_VERSION); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE shorts_projects (id INTEGER PRIMARY KEY AUTOINCREMENT, queue_item_id INTEGER NOT NULL UNIQUE, focus_prompt TEXT, desired_count INTEGER, min_duration INTEGER, max_duration INTEGER, updated_at INTEGER NOT NULL, project_mode TEXT NOT NULL DEFAULT 'AI_HIGHLIGHTS', phrase TEXT, keep_whole_subtitle INTEGER NOT NULL DEFAULT 0, remove_silence INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE shorts_candidates (id INTEGER PRIMARY KEY AUTOINCREMENT, project_id INTEGER NOT NULL, start_subtitle_id INTEGER, end_subtitle_id INTEGER, start_ms INTEGER, end_ms INTEGER, title TEXT, hook TEXT, reason TEXT, score INTEGER, selected INTEGER, crop_position REAL, crop_keyframes TEXT, burn_captions INTEGER, caption_layer TEXT, render_state TEXT, output_path TEXT, error_message TEXT, FOREIGN KEY(project_id) REFERENCES shorts_projects(id) ON DELETE CASCADE)");
        db.execSQL("CREATE INDEX idx_shorts_candidates_project ON shorts_candidates(project_id)");
    }

    @Override public void onConfigure(SQLiteDatabase db) { super.onConfigure(db); db.setForeignKeyConstraintsEnabled(true); }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE shorts_candidates ADD COLUMN crop_keyframes TEXT");
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE shorts_projects ADD COLUMN project_mode TEXT NOT NULL DEFAULT 'AI_HIGHLIGHTS'");
            db.execSQL("ALTER TABLE shorts_projects ADD COLUMN phrase TEXT");
            db.execSQL("ALTER TABLE shorts_projects ADD COLUMN keep_whole_subtitle INTEGER NOT NULL DEFAULT 0");
        }
        if (oldVersion < 4) db.execSQL("ALTER TABLE shorts_projects ADD COLUMN remove_silence INTEGER NOT NULL DEFAULT 0");
    }

    public synchronized long save(ShortsProject project) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put("queue_item_id", project.getQueueItemId());
            values.put("focus_prompt", project.getFocusPrompt());
            values.put("desired_count", project.getDesiredCount());
            values.put("min_duration", project.getMinDurationSeconds());
            values.put("max_duration", project.getMaxDurationSeconds());
            values.put("project_mode", project.getMode().name());
            values.put("phrase", project.getPhrase());
            values.put("keep_whole_subtitle", project.isKeepWholeSubtitle() ? 1 : 0);
            values.put("remove_silence", project.isRemoveSilence() ? 1 : 0);
            values.put("updated_at", System.currentTimeMillis());
            long id = db.insertWithOnConflict("shorts_projects", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            project.setId(id);
            db.delete("shorts_candidates", "project_id = ?", new String[]{String.valueOf(id)});
            for (ShortsCandidate candidate : project.getCandidates()) {
                candidate.setProjectId(id);
                ContentValues cv = candidateValues(candidate);
                long candidateId = db.insertOrThrow("shorts_candidates", null, cv);
                candidate.setId(candidateId);
            }
            db.setTransactionSuccessful();
            return id;
        } finally { db.endTransaction(); }
    }

    public synchronized ShortsProject loadForQueueItem(long queueItemId) {
        try (Cursor c = getReadableDatabase().query("shorts_projects", null, "queue_item_id = ?",
                new String[]{String.valueOf(queueItemId)}, null, null, null)) {
            if (!c.moveToFirst()) return null;
            ShortsProject project = new ShortsProject(queueItemId,
                    c.getString(c.getColumnIndexOrThrow("focus_prompt")),
                    c.getInt(c.getColumnIndexOrThrow("desired_count")),
                    c.getInt(c.getColumnIndexOrThrow("min_duration")),
                    c.getInt(c.getColumnIndexOrThrow("max_duration")));
            project.setId(c.getLong(c.getColumnIndexOrThrow("id")));
            project.setUpdatedAt(c.getLong(c.getColumnIndexOrThrow("updated_at")));
            try {
                project.setMode(ShortsProject.Mode.valueOf(c.getString(c.getColumnIndexOrThrow("project_mode"))));
            } catch (Exception ignored) { }
            int phraseColumn = c.getColumnIndex("phrase");
            if (phraseColumn >= 0) project.setPhrase(c.getString(phraseColumn));
            int wholeColumn = c.getColumnIndex("keep_whole_subtitle");
            if (wholeColumn >= 0) project.setKeepWholeSubtitle(c.getInt(wholeColumn) == 1);
            int removeSilenceColumn = c.getColumnIndex("remove_silence");
            if (removeSilenceColumn >= 0) project.setRemoveSilence(c.getInt(removeSilenceColumn) == 1);
            project.setCandidates(loadCandidates(project.getId()));
            return project;
        }
    }

    public synchronized void deleteForQueueItem(long queueItemId) {
        getWritableDatabase().delete("shorts_projects", "queue_item_id = ?", new String[]{String.valueOf(queueItemId)});
    }

    private List<ShortsCandidate> loadCandidates(long projectId) {
        List<ShortsCandidate> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("shorts_candidates", null, "project_id = ?",
                new String[]{String.valueOf(projectId)}, null, null, "score DESC, start_ms ASC")) {
            while (c.moveToNext()) {
                ShortsCandidate item = new ShortsCandidate(
                        c.getInt(c.getColumnIndexOrThrow("start_subtitle_id")),
                        c.getInt(c.getColumnIndexOrThrow("end_subtitle_id")),
                        c.getLong(c.getColumnIndexOrThrow("start_ms")),
                        c.getLong(c.getColumnIndexOrThrow("end_ms")),
                        c.getString(c.getColumnIndexOrThrow("title")),
                        c.getString(c.getColumnIndexOrThrow("hook")),
                        c.getString(c.getColumnIndexOrThrow("reason")),
                        c.getInt(c.getColumnIndexOrThrow("score")));
                item.setId(c.getLong(c.getColumnIndexOrThrow("id")));
                item.setProjectId(projectId);
                item.setSelected(c.getInt(c.getColumnIndexOrThrow("selected")) == 1);
                item.setCropPosition(c.getFloat(c.getColumnIndexOrThrow("crop_position")));
                int keyframesColumn = c.getColumnIndex("crop_keyframes");
                if (keyframesColumn >= 0) {
                    String json = c.getString(keyframesColumn);
                    if (json != null && !json.isEmpty()) {
                        try {
                            List<ShortsCropKeyframe> keyframes = GSON.fromJson(json,
                                    new TypeToken<List<ShortsCropKeyframe>>() { }.getType());
                            item.setCropKeyframes(keyframes);
                        } catch (Exception ignored) { }
                    }
                }
                item.setBurnCaptions(c.getInt(c.getColumnIndexOrThrow("burn_captions")) == 1);
                try { item.setCaptionLayer(SubtitleGenerator.SubtitleLayerMode.valueOf(c.getString(c.getColumnIndexOrThrow("caption_layer")))); } catch (Exception ignored) {}
                try { item.setRenderState(ShortsCandidate.RenderState.valueOf(c.getString(c.getColumnIndexOrThrow("render_state")))); } catch (Exception ignored) {}
                item.setOutputPath(c.getString(c.getColumnIndexOrThrow("output_path")));
                item.setErrorMessage(c.getString(c.getColumnIndexOrThrow("error_message")));
                result.add(item);
            }
        }
        return result;
    }

    private ContentValues candidateValues(ShortsCandidate c) {
        ContentValues v = new ContentValues();
        v.put("project_id", c.getProjectId()); v.put("start_subtitle_id", c.getStartSubtitleId());
        v.put("end_subtitle_id", c.getEndSubtitleId()); v.put("start_ms", c.getStartMs());
        v.put("end_ms", c.getEndMs()); v.put("title", c.getTitle()); v.put("hook", c.getHook());
        v.put("reason", c.getReason()); v.put("score", c.getScore()); v.put("selected", c.isSelected() ? 1 : 0);
        v.put("crop_position", c.getCropPosition());
        v.put("crop_keyframes", GSON.toJson(c.getCropKeyframes()));
        v.put("burn_captions", c.isBurnCaptions() ? 1 : 0);
        v.put("caption_layer", c.getCaptionLayer().name()); v.put("render_state", c.getRenderState().name());
        v.put("output_path", c.getOutputPath()); v.put("error_message", c.getErrorMessage());
        return v;
    }
}
