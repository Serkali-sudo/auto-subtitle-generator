package com.serhat.autosub;

import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.widget.EditText;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.Collections;
import java.util.List;

public final class ExportFolderDialog {
    public interface Callback {
        void onSelected(File folder);
    }

    private static final String PREFS = "autosub_export_folders";
    private static final String KEY_LAST_FOLDER = "last_folder";

    private ExportFolderDialog() {
    }

    public static void show(Fragment fragment, String title, Callback callback) {
        if (fragment == null || !fragment.isAdded()) {
            return;
        }
        ExportSettings.ensureLocationChosen(fragment, () -> showFolderPicker(fragment, title, callback));
    }

    private static void showFolderPicker(Fragment fragment, String title, Callback callback) {
        Context context = fragment.requireContext();
        File rootDir = new File(ApplicationPath.applicationPath(context));
        if (!ensureDirectory(context, rootDir)) {
            return;
        }

        ExportStore exportStore = new ExportStore(context);
        exportStore.addFolder(rootDir, rootDir);
        List<File> folders = collectFolders(context);
        if (folders.size() <= 1) {
            saveLastFolder(context, rootDir);
            callback.onSelected(rootDir);
            return;
        }
        int checkedIndex = findLastFolderIndex(context, folders);
        String[] labels = new String[folders.size()];
        for (int i = 0; i < folders.size(); i++) {
            labels[i] = labelFor(rootDir, folders.get(i));
        }

        final int[] selected = {checkedIndex};
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setSingleChoiceItems(labels, checkedIndex, (d, which) -> selected[0] = which)
                .setPositiveButton("Use", (d, which) -> {
                    File folder = folders.get(selected[0]);
                    saveLastFolder(context, folder);
                    callback.onSelected(folder);
                })
                .setNeutralButton("New folder", null)
                .setNegativeButton("Cancel", null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(DialogInterface.BUTTON_NEUTRAL)
                .setOnClickListener(v -> showCreateFolderDialog(fragment, folders.get(selected[0]), callback, dialog)));
        dialog.show();
    }

    public static List<File> collectFolders(Context context) {
        File rootDir = new File(ApplicationPath.applicationPath(context));
        if (!ensureDirectory(context, rootDir)) {
            return Collections.emptyList();
        }
        ExportStore exportStore = new ExportStore(context);
        exportStore.addFolder(rootDir, rootDir);
        List<File> folders = exportStore.getFolders(rootDir);
        if (folders.isEmpty()) {
            folders.add(rootDir);
        }
        Collections.sort(folders, (a, b) -> labelFor(rootDir, a).compareToIgnoreCase(labelFor(rootDir, b)));
        return folders;
    }

    private static void showCreateFolderDialog(Fragment fragment, File parent, Callback callback,
                                               androidx.appcompat.app.AlertDialog folderDialog) {
        Context context = fragment.requireContext();
        EditText input = new EditText(context);
        input.setSingleLine(true);
        input.setHint("Folder name");
        int padding = Math.round(20 * context.getResources().getDisplayMetrics().density);
        input.setPadding(padding, padding / 2, padding, padding / 2);
        new MaterialAlertDialogBuilder(context)
                .setTitle("New export folder")
                .setView(input)
                .setPositiveButton("Create", (dialog, which) -> {
                    File folder = createFolder(context, parent, input.getText().toString());
                    if (folder != null) {
                        folderDialog.dismiss();
                        saveLastFolder(context, folder);
                        callback.onSelected(folder);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    public static File createFolder(Context context, File parent, String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) {
            Toast.makeText(context, "Enter a folder name", Toast.LENGTH_SHORT).show();
            return null;
        }
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            Toast.makeText(context, "Use a simple folder name", Toast.LENGTH_SHORT).show();
            return null;
        }
        File folder = new File(parent, name);
        if (folder.isDirectory()) {
            Toast.makeText(context, "Folder already exists", Toast.LENGTH_SHORT).show();
            return null;
        }
        if (!folder.mkdirs()) {
            Toast.makeText(context, "Could not create folder", Toast.LENGTH_SHORT).show();
            return null;
        }
        File rootDir = new File(ApplicationPath.applicationPath(context));
        new ExportStore(context).addFolder(folder, rootDir);
        return folder;
    }

    private static int findLastFolderIndex(Context context, List<File> folders) {
        String lastPath = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LAST_FOLDER, "");
        if (lastPath == null || lastPath.isEmpty()) {
            return 0;
        }
        for (int i = 0; i < folders.size(); i++) {
            if (lastPath.equals(folders.get(i).getAbsolutePath())) {
                return i;
            }
        }
        return 0;
    }

    private static void saveLastFolder(Context context, File folder) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_LAST_FOLDER, folder.getAbsolutePath()).apply();
    }

    private static boolean ensureDirectory(Context context, File directory) {
        if (directory.isDirectory() || directory.mkdirs()) {
            return true;
        }
        Toast.makeText(context, "Export folder is not available", Toast.LENGTH_SHORT).show();
        return false;
    }

    private static String labelFor(File rootDir, File folder) {
        if (rootDir.equals(folder)) {
            return "AutoSub";
        }
        try {
            String rootPath = rootDir.getCanonicalPath();
            String folderPath = folder.getCanonicalPath();
            if (folderPath.startsWith(rootPath)) {
                String relative = folderPath.substring(rootPath.length());
                while (relative.startsWith(File.separator)) {
                    relative = relative.substring(1);
                }
                return relative.replace(File.separatorChar, '/');
            }
        } catch (Exception ignored) {
        }
        return folder.getName();
    }
}
