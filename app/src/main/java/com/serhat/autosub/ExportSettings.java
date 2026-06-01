package com.serhat.autosub;

import android.content.Context;
import android.os.Environment;

import androidx.fragment.app.Fragment;

import java.io.File;

public final class ExportSettings {
    public static final String MODE_PRIVATE = "private";
    public static final String MODE_PUBLIC = "public";

    private static final String PREFS = "autosub_export_settings";
    private static final String KEY_MODE = "export_mode";
    private static final String KEY_CHOSEN = "export_location_chosen";

    public interface ReadyCallback {
        void onReady();
    }

    private ExportSettings() {
    }

    public static void ensureLocationChosen(Fragment fragment, ReadyCallback callback) {
        if (fragment == null || !fragment.isAdded()) {
            return;
        }
        Context context = fragment.requireContext();
        if (isLocationChosen(context)) {
            callback.onReady();
            return;
        }

        AppOptionDialog.show(context,
                "Export location",
                "Choose where AutoSub should save exported videos and subtitles.",
                new AppOptionDialog.Option[]{
                        new AppOptionDialog.Option(R.drawable.ri_folder_settings_line,
                                "App folder", "Keeps exports out of gallery apps. Files are deleted if AutoSub is uninstalled."),
                        new AppOptionDialog.Option(R.drawable.ri_folder_open_line,
                                "Movies folder", "Saves to Movies/AutoSub so files are easy to find outside the app.")
                }, which -> {
                    setMode(context, which == 0 ? MODE_PRIVATE : MODE_PUBLIC);
                    callback.onReady();
                });
    }

    public static boolean isLocationChosen(Context context) {
        return prefs(context).getBoolean(KEY_CHOSEN, false);
    }

    public static String getMode(Context context) {
        return prefs(context).getString(KEY_MODE, MODE_PRIVATE);
    }

    public static void setMode(Context context, String mode) {
        prefs(context).edit()
                .putString(KEY_MODE, MODE_PUBLIC.equals(mode) ? MODE_PUBLIC : MODE_PRIVATE)
                .putBoolean(KEY_CHOSEN, true)
                .apply();
    }

    public static File getExportRoot(Context context) {
        if (MODE_PUBLIC.equals(getMode(context))) {
            return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "AutoSub");
        }
        File dir = context.getExternalFilesDir("exports");
        return dir == null ? new File(context.getFilesDir(), "exports") : dir;
    }

    private static android.content.SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
