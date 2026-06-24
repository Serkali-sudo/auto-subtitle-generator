package com.serhat.autosub.exports;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.StrictMode;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import com.serhat.autosub.R;
import com.serhat.autosub.ui.common.AppOptionDialog;
import com.serhat.autosub.ui.main.MainViewModel;

import java.io.File;
import java.util.Locale;

public final class ExportFileActions {
    private static final String PREFS = "autosub_export_actions";
    private static final String KEY_HIDE_VIDEO_EXPORTED_DIALOG = "hide_video_exported_dialog";
    private static final String KEY_HIDE_SUBTITLE_EXPORTED_DIALOG = "hide_subtitle_exported_dialog";

    private ExportFileActions() {
    }

    public static boolean isVideo(File file) {
        String name = file == null ? "" : file.getName().toLowerCase(Locale.US);
        return name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".webm") || name.endsWith(".mov");
    }

    public static boolean isSubtitle(File file) {
        String name = file == null ? "" : file.getName().toLowerCase(Locale.US);
        return name.endsWith(".srt") || name.endsWith(".vtt") || name.endsWith(".ass");
    }

    public static void showExportCompleteDialog(Fragment fragment, MainViewModel viewModel,
                                                String filePath, boolean video) {
        if (fragment == null || !fragment.isAdded()) {
            return;
        }
        Context context = fragment.requireContext();
        File file = new File(filePath == null ? "" : filePath);
        if (video && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_HIDE_VIDEO_EXPORTED_DIALOG, false)) {
            Toast.makeText(context, "Video exported", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!video && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_HIDE_SUBTITLE_EXPORTED_DIALOG, false)) {
            Toast.makeText(context, "Subtitles saved", Toast.LENGTH_SHORT).show();
            return;
        }
        AppOptionDialog.Option[] options = video
                ? new AppOptionDialog.Option[]{
                        new AppOptionDialog.Option(R.drawable.ri_folder_open_line,
                                "Exports", "Open the Exports screen and browse all saved files."),
                        new AppOptionDialog.Option(R.drawable.ri_folder_settings_line,
                                "Files", "Open this export's folder in the device file manager."),
                        new AppOptionDialog.Option(R.drawable.ri_share_line,
                                "Share", "Send this file to another app."),
                        new AppOptionDialog.Option(R.drawable.ri_play_circle_line,
                                "Play", "Open the exported video in a video player.")
                }
                : new AppOptionDialog.Option[]{
                        new AppOptionDialog.Option(R.drawable.ri_folder_open_line,
                                "Exports", "Open the Exports screen and browse all saved files."),
                        new AppOptionDialog.Option(R.drawable.ri_folder_settings_line,
                                "Files", "Open this export's folder in the device file manager."),
                        new AppOptionDialog.Option(R.drawable.ri_share_line,
                                "Share", "Send this file to another app.")
                };

        if (video) {
            AppOptionDialog.showWithCheckbox(context,
                    "Video exported",
                    file.getName(),
                    options,
                    "Don't show this again",
                    false,
                    (which, checked) -> {
                        if (checked) {
                            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                                    .putBoolean(KEY_HIDE_VIDEO_EXPORTED_DIALOG, true)
                                    .apply();
                        }
                        handleExportAction(context, viewModel, file, true, which);
                    });
        } else {
            AppOptionDialog.showWithCheckbox(context,
                    "Subtitles saved",
                    file.getName(),
                    options,
                    "Don't show this again",
                    false,
                    (which, checked) -> {
                        if (checked) {
                            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                                    .putBoolean(KEY_HIDE_SUBTITLE_EXPORTED_DIALOG, true)
                                    .apply();
                        }
                        handleExportAction(context, viewModel, file, false, which);
                    });
        }
    }

    private static void handleExportAction(Context context, MainViewModel viewModel, File file,
                                           boolean video, int which) {
        if (which == 0) {
            viewModel.setActiveNavigationTab(R.id.nav_exports);
        } else if (which == 1) {
            openInFileManager(context, file);
        } else if (which == 2) {
            shareFile(context, file);
        } else if (video && which == 3) {
            playVideo(context, file);
        }
    }

    public static void openInFileManager(Context context, File file) {
        File target = file != null && file.isDirectory() ? file : file == null ? null : file.getParentFile();
        if (target == null || !target.exists()) {
            Toast.makeText(context, "Folder is not available", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(Uri.fromFile(target), "resource/folder");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (!startWithFileUri(context, intent) && file != null && file.isFile()) {
            openFile(context, file);
        }
    }

    public static void openFile(Context context, File file) {
        if (file == null || !file.exists()) {
            Toast.makeText(context, "File is not available", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW);
        Uri fileUri = androidx.core.content.FileProvider.getUriForFile(context, context.getPackageName() + ".provider", file);
        intent.setDataAndType(fileUri, getMimeType(file));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            context.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show();
        }
    }

    public static void playVideo(Context context, File file) {
        if (file == null || !isVideo(file)) {
            Toast.makeText(context, "This export is not a video", Toast.LENGTH_SHORT).show();
            return;
        }
        openFile(context, file);
    }

    public static void shareFile(Context context, File file) {
        if (file == null || !file.isFile()) {
            Toast.makeText(context, "File is not available", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType(getMimeType(file));
        Uri fileUri = androidx.core.content.FileProvider.getUriForFile(context, context.getPackageName() + ".provider", file);
        shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            context.startActivity(Intent.createChooser(shareIntent, "Share export"));
        } catch (Exception e) {
            Toast.makeText(context, "No app can share this file", Toast.LENGTH_SHORT).show();
        }
    }

    private static boolean startWithFileUri(Context context, Intent intent) {
        StrictMode.VmPolicy oldPolicy = StrictMode.getVmPolicy();
        StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder().build());
        try {
            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            StrictMode.setVmPolicy(oldPolicy);
        }
    }

    private static String getMimeType(File file) {
        if (file == null) {
            return "*/*";
        }
        String name = file.getName().toLowerCase(Locale.US);
        if (name.endsWith(".srt")) return "application/x-subrip";
        if (name.endsWith(".vtt")) return "text/vtt";
        if (name.endsWith(".ass")) return "text/plain";
        if (name.endsWith(".mkv")) return "video/x-matroska";
        String extension = MimeTypeMap.getFileExtensionFromUrl(file.getName());
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        if (mime != null) {
            return mime;
        }
        if (isVideo(file)) {
            return "video/*";
        }
        return "*/*";
    }
}
