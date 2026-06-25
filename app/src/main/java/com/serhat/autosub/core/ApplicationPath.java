package com.serhat.autosub.core;

import android.content.Context;

import com.serhat.autosub.exports.ExportSettings;

import java.io.File;

public class ApplicationPath {

    public static String applicationPath(Context context) {
        File exportRoot = ExportSettings.getExportRoot(context);
        if (exportRoot.isDirectory() || exportRoot.mkdirs()) {
            return exportRoot.getPath();
        }
        return privateExportRoot(context).getPath();
    }

    private static File privateExportRoot(Context context) {
        return new File(context.getFilesDir(), "exports");
    }
}
