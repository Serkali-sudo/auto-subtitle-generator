package com.serhat.autosub;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;

import androidx.core.app.ActivityCompat;

import java.io.File;

public class ApplicationPath {

    public static String applicationPath(Context context) {
        return getQuantumPath("AutoSub", Environment.DIRECTORY_MOVIES, context);
    }

    public static String getQuantumPath(String folderName, String dir, Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            File result = new File(Environment.getExternalStoragePublicDirectory(dir),
                    folderName);
            if (result.isDirectory() || result.mkdirs()) {
                return result.getPath();
            } else {
                File orig_file = Environment.getExternalStoragePublicDirectory(dir);
                if (orig_file.isDirectory() || orig_file.mkdirs()) {
                    return orig_file.getPath();
                } else {
                    return lastResort(folderName, context);
                }
            }
        } else {
            if (isStoragePermissionGranted(context)) {
                File result = new File(Environment.getExternalStoragePublicDirectory(dir),
                        folderName);
                if (result.isDirectory() || result.mkdirs()) {
                    return result.getPath();
                } else {
                    File orig_file = Environment.getExternalStoragePublicDirectory(dir);
                    if (orig_file.isDirectory() || orig_file.mkdirs()) {
                        return orig_file.getPath();
                    } else {
                        return lastResort(folderName, context);
                    }
                }
            } else {
                return lastResort(folderName, context);
            }
        }
    }

    public static boolean isStoragePermissionGranted(Context context) {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    public static String lastResort(String filePath, Context context) {
        return context.getExternalFilesDir(filePath).getPath();
    }


}
