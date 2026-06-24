package com.serhat.autosub;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Runtime-tested encoding choices shared by every burned-subtitle export. */
public final class HardSubtitleExportSettings {
    private static final String TAG = "AutoSubEncoders";
    private static final String PREFS = "autosub_settings";
    private static final String KEY_RESOLUTION = "hard_subtitle_resolution";
    private static final String KEY_FPS = "hard_subtitle_fps";
    private static final String KEY_TARGET_WIDTH = "hard_subtitle_target_width";
    private static final String KEY_TARGET_HEIGHT = "hard_subtitle_target_height";
    private static final String KEY_TARGET_FPS = "hard_subtitle_target_fps";
    private static final String KEY_SOURCE_VIDEO_BITRATE = "hard_subtitle_source_video_bitrate_kbps";
    private static final String KEY_QUALITY = "hard_subtitle_quality";
    private static final String KEY_ENCODER = "hard_subtitle_encoder";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    public interface Callback { void onChosen(); }

    private static final class SourceInfo {
        int width;
        int height;
        int minimumShortEdge = Integer.MAX_VALUE;
        float frameRate;
        float minimumFrameRate = Float.MAX_VALUE;
        int videoBitrateKbps;
        int minimumVideoBitrateKbps = Integer.MAX_VALUE;
        int readableVideos;
    }

    private static final class ResolutionOption {
        final String value;
        final String label;
        final int width;
        final int height;
        final int sourceVideoBitrateKbps;
        ResolutionOption(String value, String label, int width, int height, int sourceVideoBitrateKbps) {
            this.value = value;
            this.label = label;
            this.width = width;
            this.height = height;
            this.sourceVideoBitrateKbps = sourceVideoBitrateKbps;
        }
    }

    private static final class FpsOption {
        final String value;
        final String label;
        final int fps;
        FpsOption(String value, String label, int fps) {
            this.value = value;
            this.label = label;
            this.fps = fps;
        }
    }

    private HardSubtitleExportSettings() { }

    public static void show(Fragment fragment, Uri videoUri, Callback callback) {
        show(fragment, videoUri == null ? Collections.emptyList() : Collections.singletonList(videoUri), callback);
    }

    public static void show(Fragment fragment, List<Uri> videoUris, Callback callback) {
        if (fragment == null || !fragment.isAdded()) return;
        Context appContext = fragment.requireContext().getApplicationContext();
        AlertDialog loadingDialog = showLoadingDialog(fragment.requireContext());
        EXECUTOR.execute(() -> {
            SourceInfo source = new SourceInfo();
            try {
                source = inspectSources(appContext, videoUris);
            } catch (Exception error) {
                Log.e(TAG, "Could not inspect hard-subtitle export capabilities", error);
            }
            SourceInfo finalSource = source;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (loadingDialog.isShowing()) loadingDialog.dismiss();
                if (fragment.isAdded()) showCombinedDialog(fragment, finalSource, callback);
            });
        });
    }

    private static AlertDialog showLoadingDialog(Context context) {
        int padding = Math.round(24 * context.getResources().getDisplayMetrics().density);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        content.setPadding(padding, padding / 2, padding, padding / 4);

        CircularProgressIndicator progress = new CircularProgressIndicator(context);
        progress.setIndeterminate(true);
        int progressSize = Math.round(48 * context.getResources().getDisplayMetrics().density);
        content.addView(progress, new LinearLayout.LayoutParams(progressSize, progressSize));

        TextView message = new TextView(context);
        message.setText("Reading the source resolution and checking device codec capabilities…");
        message.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        messageParams.topMargin = padding / 2;
        content.addView(message, messageParams);

        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle("Preparing export settings")
                .setView(content)
                .setCancelable(false)
                .create();
        dialog.show();
        return dialog;
    }

    private static void showCombinedDialog(Fragment fragment, SourceInfo source, Callback callback) {
        Context context = fragment.requireContext();
        View view = fragment.getLayoutInflater().inflate(R.layout.dialog_hard_subtitle_export, null);
        TextView sourceText = view.findViewById(R.id.sourceResolutionTV);
        AutoCompleteTextView resolutionDropdown = view.findViewById(R.id.resolutionDropdown);
        AutoCompleteTextView fpsDropdown = view.findViewById(R.id.fpsDropdown);
        AutoCompleteTextView qualityDropdown = view.findViewById(R.id.qualityDropdown);
        AutoCompleteTextView codecDropdown = view.findViewById(R.id.codecDropdown);

        List<ResolutionOption> resolutions = buildResolutionOptions(source);
        List<String> resolutionLabels = new ArrayList<>();
        for (ResolutionOption option : resolutions) resolutionLabels.add(option.label);
        List<FpsOption> frameRates = buildFpsOptions(source);
        List<String> fpsLabels = new ArrayList<>();
        for (FpsOption option : frameRates) fpsLabels.add(option.label);
        String[] qualityLabels = {"Original bitrate", "High", "Balanced", "Compact"};
        String[] qualityValues = {"original", "high", "balanced", "compact"};

        setDropdown(context, resolutionDropdown, resolutionLabels);
        setDropdown(context, fpsDropdown, fpsLabels);
        setDropdown(context, qualityDropdown, Arrays.asList(qualityLabels));
        SharedPreferences saved = prefs(context);
        int resolutionIndex = indexOfResolution(resolutions, saved.getString(KEY_RESOLUTION, "source"));
        int fpsIndex = indexOfFps(frameRates, saved.getString(KEY_FPS, "source"));
        int qualityIndex = indexOf(qualityValues, saved.getString(KEY_QUALITY, "balanced"));
        resolutionDropdown.setText(resolutionLabels.get(resolutionIndex), false);
        fpsDropdown.setText(fpsLabels.get(fpsIndex), false);
        qualityDropdown.setText(qualityLabels[qualityIndex], false);
        refreshCodecDropdown(context, resolutions.get(resolutionIndex), frameRates.get(fpsIndex), qualityValues[qualityIndex],
                saved.getString(KEY_ENCODER, "mpeg4"), codecDropdown);
        resolutionDropdown.setOnItemClickListener((parent, selectedView, position, id) -> {
            int quality = Math.max(0, Arrays.asList(qualityLabels)
                    .indexOf(qualityDropdown.getText().toString()));
            int fps = Math.max(0, fpsLabels.indexOf(fpsDropdown.getText().toString()));
            refreshCodecDropdown(context, resolutions.get(position), frameRates.get(fps), qualityValues[quality],
                    saved.getString(KEY_ENCODER, "mpeg4"), codecDropdown);
        });
        fpsDropdown.setOnItemClickListener((parent, selectedView, position, id) -> {
            int resolution = Math.max(0, resolutionLabels.indexOf(resolutionDropdown.getText().toString()));
            int quality = Math.max(0, Arrays.asList(qualityLabels).indexOf(qualityDropdown.getText().toString()));
            refreshCodecDropdown(context, resolutions.get(resolution), frameRates.get(position),
                    qualityValues[quality], saved.getString(KEY_ENCODER, "mpeg4"), codecDropdown);
        });
        qualityDropdown.setOnItemClickListener((parent, selectedView, position, id) -> {
            int resolution = Math.max(0, resolutionLabels
                    .indexOf(resolutionDropdown.getText().toString()));
            int fps = Math.max(0, fpsLabels.indexOf(fpsDropdown.getText().toString()));
            refreshCodecDropdown(context, resolutions.get(resolution), frameRates.get(fps), qualityValues[position],
                    saved.getString(KEY_ENCODER, "mpeg4"), codecDropdown);
        });
        sourceText.setText(sourceDescription(source));

        new MaterialAlertDialogBuilder(context)
                .setTitle("Hard subtitle video")
                .setView(view)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Continue", (dialog, which) -> {
                    int selectedResolution = Math.max(0,
                            resolutionLabels.indexOf(resolutionDropdown.getText().toString()));
                    int selectedQuality = Math.max(0,
                            Arrays.asList(qualityLabels).indexOf(qualityDropdown.getText().toString()));
                    int selectedFps = Math.max(0, fpsLabels.indexOf(fpsDropdown.getText().toString()));
                    List<String> encoders = deviceEncodersFor(resolutions.get(selectedResolution),
                            frameRates.get(selectedFps), qualityValues[selectedQuality]);
                    List<String> codecLabels = encoderLabels(encoders);
                    int selectedCodec = Math.max(0, codecLabels.indexOf(codecDropdown.getText().toString()));
                    prefs(context).edit()
                            .putString(KEY_RESOLUTION, resolutions.get(selectedResolution).value)
                            .putString(KEY_FPS, frameRates.get(selectedFps).value)
                            .putInt(KEY_TARGET_WIDTH, resolutions.get(selectedResolution).width)
                            .putInt(KEY_TARGET_HEIGHT, resolutions.get(selectedResolution).height)
                            .putInt(KEY_TARGET_FPS, frameRates.get(selectedFps).fps)
                            .putInt(KEY_SOURCE_VIDEO_BITRATE,
                                    resolutions.get(selectedResolution).sourceVideoBitrateKbps)
                            .putString(KEY_QUALITY, qualityValues[selectedQuality])
                            .putString(KEY_ENCODER, encoders.get(selectedCodec)).apply();
                    callback.onChosen();
                }).show();
    }

    private static void refreshCodecDropdown(Context context, ResolutionOption resolution, FpsOption fps,
                                             String quality, String preferredEncoder,
                                             AutoCompleteTextView codecDropdown) {
        List<String> encoders = deviceEncodersFor(resolution, fps, quality);
        List<String> labels = encoderLabels(encoders);
        setDropdown(context, codecDropdown, labels);
        int selected = encoders.indexOf(preferredEncoder);
        codecDropdown.setText(labels.get(selected >= 0 ? selected : 0), false);
    }

    private static List<String> encoderLabels(List<String> encoders) {
        List<String> labels = new ArrayList<>();
        for (String encoder : encoders) labels.add(encoderLabel(encoder));
        return labels;
    }

    private static List<String> deviceEncodersFor(ResolutionOption resolution, FpsOption fps, String quality) {
        List<String> result = new ArrayList<>();
        int width = Math.max(2, resolution.width);
        int height = Math.max(2, resolution.height);
        int h264Bitrate = targetBitrateKbps(width, height, fps.fps, quality, false,
                resolution.sourceVideoBitrateKbps) * 1000;
        int hevcBitrate = targetBitrateKbps(width, height, fps.fps, quality, true,
                resolution.sourceVideoBitrateKbps) * 1000;
        if (deviceSupportsEncoding(MediaFormat.MIMETYPE_VIDEO_AVC, width, height, h264Bitrate, fps.fps)) {
            result.add("h264_mediacodec");
        }
        if (deviceSupportsEncoding(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height, hevcBitrate, fps.fps)) {
            result.add("hevc_mediacodec");
        }
        result.add("mpeg4");
        Log.i(TAG, "ANDROID_ENCODERS_FOR_" + width + "x" + height + "=" + String.join(",", result));
        return result;
    }

    private static boolean deviceSupportsEncoding(String mime, int width, int height, int bitrate, int fps) {
        MediaFormat format = MediaFormat.createVideoFormat(mime, width, height);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, Math.max(1, fps));
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        try {
            for (MediaCodecInfo codec : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (!codec.isEncoder()) continue;
                for (String type : codec.getSupportedTypes()) {
                    if (!mime.equalsIgnoreCase(type)) continue;
                    try {
                        if (codec.getCapabilitiesForType(type).isFormatSupported(format)) return true;
                    } catch (Exception ignored) { }
                }
            }
        } catch (Exception error) {
            Log.w(TAG, "Could not query Android encoder support for " + mime, error);
        }
        return false;
    }

    private static void setDropdown(Context context, AutoCompleteTextView view, List<String> values) {
        view.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_list_item_1, values));
        view.setOnClickListener(v -> view.showDropDown());
    }

    private static SourceInfo inspectSources(Context context, List<Uri> uris) {
        SourceInfo result = new SourceInfo();
        if (uris == null) return result;
        for (Uri uri : uris) {
            if (uri == null) continue;
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(context, uri);
                int width = Integer.parseInt(retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                int height = Integer.parseInt(retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                String rotationValue = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                int rotation = rotationValue == null ? 0 : Integer.parseInt(rotationValue);
                if (rotation == 90 || rotation == 270) {
                    int swap = width; width = height; height = swap;
                }
                if (width <= 0 || height <= 0) continue;
                int[] trackInfo = readVideoTrackInfo(context, uri);
                float frameRate = trackInfo[0];
                int videoBitrateKbps = trackInfo[1];
                String totalBitrateValue = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_BITRATE);
                if (totalBitrateValue != null) {
                    try {
                        int totalBitrateKbps = Integer.parseInt(totalBitrateValue) / 1000;
                        int estimatedVideoBitrate = totalBitrateKbps - trackInfo[2];
                        if (estimatedVideoBitrate > 0) videoBitrateKbps = estimatedVideoBitrate;
                    } catch (NumberFormatException ignored) { }
                }
                if (result.readableVideos == 0) {
                    result.width = width;
                    result.height = height;
                    result.frameRate = frameRate;
                    result.videoBitrateKbps = videoBitrateKbps;
                }
                result.minimumShortEdge = Math.min(result.minimumShortEdge, Math.min(width, height));
                if (frameRate > 0) result.minimumFrameRate = Math.min(result.minimumFrameRate, frameRate);
                if (videoBitrateKbps > 0) {
                    result.minimumVideoBitrateKbps = Math.min(result.minimumVideoBitrateKbps,
                            videoBitrateKbps);
                }
                result.readableVideos++;
            } catch (Exception ignored) {
            } finally {
                try { retriever.release(); } catch (Exception ignored) { }
            }
        }
        return result;
    }

    private static int[] readVideoTrackInfo(Context context, Uri uri) {
        MediaExtractor extractor = new MediaExtractor();
        int fps = 0;
        int videoBitrateKbps = 0;
        int audioBitrateKbps = 0;
        try {
            extractor.setDataSource(context, uri, null);
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        fps = Math.max(fps, format.getInteger(MediaFormat.KEY_FRAME_RATE));
                    }
                    if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                        videoBitrateKbps = Math.max(videoBitrateKbps,
                                Math.max(0, format.getInteger(MediaFormat.KEY_BIT_RATE) / 1000));
                    }
                } else if (mime != null && mime.startsWith("audio/")) {
                    int trackAudioBitrate = 0;
                    if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                        trackAudioBitrate = format.getInteger(MediaFormat.KEY_BIT_RATE) / 1000;
                    } else {
                        // Fall back to a standard estimate for common audio tracks on Android (e.g. AAC)
                        int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                                ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
                        trackAudioBitrate = channels == 1 ? 64 : (channels == 2 ? 128 : 320);
                    }
                    audioBitrateKbps += Math.max(0, trackAudioBitrate);
                }
            }
        } catch (Exception ignored) {
        } finally {
            try { extractor.release(); } catch (Exception ignored) { }
        }
        return new int[]{fps, videoBitrateKbps, audioBitrateKbps};
    }

    private static List<ResolutionOption> buildResolutionOptions(SourceInfo source) {
        List<ResolutionOption> result = new ArrayList<>();
        String original = source.readableVideos == 1
                ? "Original (" + source.width + "×" + source.height + ")" : "Original resolution";
        int originalWidth = source.width > 0 ? source.width : 1280;
        int originalHeight = source.height > 0 ? source.height : 720;
        int sourceBitrate = source.minimumVideoBitrateKbps == Integer.MAX_VALUE
                ? source.videoBitrateKbps : source.minimumVideoBitrateKbps;
        result.add(new ResolutionOption("source", original, originalWidth, originalHeight, sourceBitrate));
        int[] edges = {2160, 1080, 720, 480};
        String[] names = {"4K", "1080p", "720p", "480p"};
        for (int i = 0; i < edges.length; i++) {
            if (source.minimumShortEdge == Integer.MAX_VALUE || edges[i] >= source.minimumShortEdge) continue;
            String label = names[i];
            int width = originalWidth;
            int height = originalHeight;
            if (source.readableVideos == 1) {
                float scale = edges[i] / (float) Math.min(source.width, source.height);
                width = even(Math.round(source.width * scale));
                height = even(Math.round(source.height * scale));
                label += " (" + width + "×" + height + ")";
            } else if (source.readableVideos > 1) {
                float scale = edges[i] / (float) Math.min(originalWidth, originalHeight);
                width = even(Math.round(originalWidth * scale));
                height = even(Math.round(originalHeight * scale));
                label += " (adapts to each aspect ratio)";
            }
            result.add(new ResolutionOption(String.valueOf(edges[i]), label, width, height, sourceBitrate));
        }
        return result;
    }

    private static List<FpsOption> buildFpsOptions(SourceInfo source) {
        List<FpsOption> result = new ArrayList<>();
        int original = source.frameRate > 0 ? Math.max(1, Math.round(source.frameRate)) : 30;
        String label = source.frameRate > 0 ? "Original (" + formatFps(source.frameRate) + " fps)" : "Original frame rate";
        result.add(new FpsOption("source", label, original));
        int[] choices = {60, 50, 30, 25, 24};
        for (int fps : choices) {
            if (source.minimumFrameRate == Float.MAX_VALUE || fps >= source.minimumFrameRate - 0.01f) continue;
            result.add(new FpsOption(String.valueOf(fps), fps + " fps", fps));
        }
        return result;
    }

    private static String formatFps(float fps) {
        return Math.abs(fps - Math.round(fps)) < 0.01f
                ? String.valueOf(Math.round(fps)) : String.format(Locale.US, "%.2f", fps);
    }

    private static int even(int value) { return Math.max(2, value - Math.abs(value % 2)); }

    private static String sourceDescription(SourceInfo source) {
        if (source.readableVideos == 1) return "Source: " + source.width + "×" + source.height
                + (source.videoBitrateKbps > 0 ? " • " + source.videoBitrateKbps + " kbps video" : "")
                + ". Larger resolutions are hidden.";
        if (source.readableVideos > 1) return source.readableVideos
                + " videos selected. Choices are limited by the smallest source.";
        return "Source dimensions unavailable. Original resolution is the safe choice.";
    }

    private static int indexOfResolution(List<ResolutionOption> values, String wanted) {
        for (int i = 0; i < values.size(); i++) if (values.get(i).value.equals(wanted)) return i;
        return 0;
    }

    private static int indexOfFps(List<FpsOption> values, String wanted) {
        for (int i = 0; i < values.size(); i++) if (values.get(i).value.equals(wanted)) return i;
        return 0;
    }

    private static int indexOf(String[] values, String wanted) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(wanted)) return i;
        return 0;
    }

    private static String encoderLabel(String encoder) {
        if ("libx264".equals(encoder)) return "H.264 (x264) — libx264";
        if ("libx265".equals(encoder)) return "H.265 (x265) — libx265";
        if ("h264_mediacodec".equals(encoder)) return "H.264 (hardware) — h264_mediacodec";
        if ("hevc_mediacodec".equals(encoder)) return "H.265 (hardware) — hevc_mediacodec";
        if ("mpeg4".equals(encoder)) return "MPEG-4 — mpeg4";
        return encoder;
    }

    public static String videoFilterSuffix(Context context) {
        String resolution = prefs(context).getString(KEY_RESOLUTION, "source");
        if ("source".equals(resolution)) return "";
        int shortEdge;
        switch (resolution) {
            case "2160": shortEdge = 2160; break;
            case "720": shortEdge = 720; break;
            case "480": shortEdge = 480; break;
            default: shortEdge = 1080; break;
        }
        return String.format(Locale.US,
                ",scale=w='if(gt(iw,ih),-2,%d)':h='if(gt(iw,ih),%d,-2)'",
                shortEdge, shortEdge);
    }

    public static String videoEncodingArguments(Context context) {
        SharedPreferences prefs = prefs(context);
        String encoder = prefs.getString(KEY_ENCODER, "mpeg4");
        String quality = prefs.getString(KEY_QUALITY, "balanced");
        if ("libx264".equals(encoder) || "libx265".equals(encoder)) {
            int crf = "high".equals(quality) ? ("libx265".equals(encoder) ? 20 : 18)
                    : "compact".equals(quality) ? ("libx265".equals(encoder) ? 30 : 28)
                    : ("libx265".equals(encoder) ? 26 : 23);
            return withFrameRate(context, "-c:v " + encoder + " -preset medium -crf " + crf + " -pix_fmt yuv420p");
        }
        if (encoder.endsWith("_mediacodec")) {
            int width = prefs.getInt(KEY_TARGET_WIDTH, defaultTargetWidth(prefs.getString(KEY_RESOLUTION, "source")));
            int height = prefs.getInt(KEY_TARGET_HEIGHT, defaultTargetHeight(prefs.getString(KEY_RESOLUTION, "source")));
            int fps = prefs.getInt(KEY_TARGET_FPS, 30);
            int sourceBitrate = prefs.getInt(KEY_SOURCE_VIDEO_BITRATE, 0);
            int bitrate = targetBitrateKbps(width, height, fps, quality,
                    "hevc_mediacodec".equals(encoder), sourceBitrate);
            int gop = Math.max(1, fps * 5);
            String mime = "hevc_mediacodec".equals(encoder)
                    ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
            String bitrateMode = deviceSupportsCbr(mime) ? " -bitrate_mode 2" : "";
            return withFrameRate(context, "-c:v " + encoder + " -b:v " + bitrate
                    + "k -g " + gop + bitrateMode);
        }
        if (encoder.contains("vp9") || encoder.contains("av1") || encoder.contains("aom")) {
            int crf = "high".equals(quality) ? 20 : "compact".equals(quality) ? 38 : 30;
            return withFrameRate(context, "-c:v " + encoder + " -crf " + crf + " -b:v 0");
        }
        int sourceBitrate = prefs.getInt(KEY_SOURCE_VIDEO_BITRATE, 0);
        if (sourceBitrate > 0) {
            int width = prefs.getInt(KEY_TARGET_WIDTH, 1280);
            int height = prefs.getInt(KEY_TARGET_HEIGHT, 720);
            int fps = prefs.getInt(KEY_TARGET_FPS, 30);
            int bitrate = targetBitrateKbps(width, height, fps, quality, false, sourceBitrate);
            return withFrameRate(context, "-c:v " + encoder + " -b:v " + bitrate + "k");
        }
        int q = "original".equals(quality) || "high".equals(quality) ? 2
                : "compact".equals(quality) ? 7 : 4;
        return withFrameRate(context, "-c:v " + encoder + " -q:v " + q);
    }

    private static String withFrameRate(Context context, String arguments) {
        String fps = prefs(context).getString(KEY_FPS, "source");
        return "source".equals(fps) ? arguments : arguments + " -r " + fps;
    }

    private static int targetBitrateKbps(int width, int height, int fps, String quality,
                                         boolean hevc, int sourceBitrateKbps) {
        if (sourceBitrateKbps > 0) {
            if ("original".equals(quality)) return sourceBitrateKbps;
            double factor = "original".equals(quality) ? 1.0
                    : "high".equals(quality) ? 0.85
                    : "compact".equals(quality) ? 0.40 : 0.65;
            if (hevc && !"original".equals(quality)) factor *= 0.75;
            return Math.max(hevc ? 220 : 300,
                    (int) Math.round(sourceBitrateKbps * factor));
        }
        double bitsPerPixel;
        if (hevc) {
            bitsPerPixel = "original".equals(quality) ? 0.140
                    : "high".equals(quality) ? 0.105
                    : "compact".equals(quality) ? 0.045 : 0.070;
        } else {
            bitsPerPixel = "original".equals(quality) ? 0.210
                    : "high".equals(quality) ? 0.160
                    : "compact".equals(quality) ? 0.065 : 0.105;
        }
        int calculated = (int) Math.round(Math.max(2, width) * (double) Math.max(2, height)
                * Math.max(1, fps) * bitsPerPixel / 1000d);
        return Math.max(hevc ? 220 : 300, Math.min(hevc ? 35000 : 50000, calculated));
    }

    private static boolean deviceSupportsCbr(String mime) {
        try {
            for (MediaCodecInfo codec : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (!codec.isEncoder()) continue;
                for (String type : codec.getSupportedTypes()) {
                    if (!mime.equalsIgnoreCase(type)) continue;
                    try {
                        MediaCodecInfo.EncoderCapabilities encoderCapabilities = codec
                                .getCapabilitiesForType(type).getEncoderCapabilities();
                        if (encoderCapabilities != null && encoderCapabilities.isBitrateModeSupported(
                                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)) return true;
                    } catch (Exception ignored) { }
                }
            }
        } catch (Exception error) {
            Log.w(TAG, "Could not query CBR support for " + mime, error);
        }
        return false;
    }

    private static int defaultTargetWidth(String resolution) {
        if ("2160".equals(resolution)) return 3840;
        if ("720".equals(resolution)) return 1280;
        if ("480".equals(resolution)) return 854;
        return "1080".equals(resolution) ? 1920 : 1280;
    }

    private static int defaultTargetHeight(String resolution) {
        if ("2160".equals(resolution)) return 2160;
        if ("720".equals(resolution)) return 720;
        if ("480".equals(resolution)) return 480;
        return "1080".equals(resolution) ? 1080 : 720;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
