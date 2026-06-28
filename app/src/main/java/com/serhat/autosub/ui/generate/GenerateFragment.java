package com.serhat.autosub.ui.generate;


import android.content.Intent;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.serhat.autosub.R;
import com.serhat.autosub.core.ApplicationPath;
import com.serhat.autosub.core.DebugLog;
import com.serhat.autosub.databinding.DialogShortsAnalysisBinding;
import com.serhat.autosub.databinding.FragmentGenerateBinding;
import com.serhat.autosub.exports.ExportFileActions;
import com.serhat.autosub.exports.ExportFolderDialog;
import com.serhat.autosub.exports.HardSubtitleExportSettings;
import com.serhat.autosub.queue.QueueItem;
import com.serhat.autosub.subtitles.SubtitleGenerator;
import com.serhat.autosub.ui.common.AppOptionDialog;
import com.serhat.autosub.ui.main.MainViewModel;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class GenerateFragment extends Fragment implements ActionMode.Callback {

    private static final int VAD_PROMPT_THRESHOLD_MINUTES = 10;

    private FragmentGenerateBinding binding;
    private MainViewModel viewModel;
    private QueueAdapter queueAdapter;
    private ActionMode actionMode;

    private interface SubtitleLayerChoiceCallback {
        void onChosen(SubtitleGenerator.SubtitleLayerMode layerMode);
    }

    private final ActivityResultLauncher<String[]> pickMultipleMedia =
            registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
                if (uris != null && !uris.isEmpty()) {
                    for (Uri uri : uris) {
                        try {
                            requireContext().getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (Exception e) {
                            DebugLog.e("GenerateFragment", "Failed to take persistable URI permission", e);
                        }
                    }
                    addVideosToQueue(uris);
                }
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentGenerateBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);

        setupAdapter();
        setupActions();
        observeViewModel();
    }

    private void setupAdapter() {
        queueAdapter = new QueueAdapter();
        queueAdapter.setListener(new QueueAdapter.QueueActionListener() {
            @Override
            public void onRetry(QueueItem item) {
                viewModel.retryQueueItem(item);
            }

            @Override
            public void onRemove(QueueItem item) {
                viewModel.removeQueueItem(item);
            }

            @Override
            public void onCancel(QueueItem item) {
                viewModel.cancelQueueItem(item);
            }

            @Override
            public void onExportVideo(QueueItem item) {
                quickExportVideo(item);
            }

            @Override
            public void onExportSubtitle(QueueItem item) {
                quickExportSubtitle(item);
            }

            @Override
            public void onShare(QueueItem item) {
                shareQueueItemFile(item);
            }

            @Override
            public void onPlay(QueueItem item) {
                playExportedVideo(item);
            }

            @Override
            public void onPreview(QueueItem item) {
                viewModel.openQueueItemPreview(item);
            }

            @Override
            public void onTranslate(QueueItem item) {
                translateQueueItem(item);
            }

            @Override
            public void onCreateShorts(QueueItem item) {
                beginCreateShorts(item);
            }

            @Override
            public void onTalkOnly(QueueItem item) {
                exportCondensedVideo(item, false);
            }

            @Override
            public void onRemoveSilence(QueueItem item) {
                exportCondensedVideo(item, true);
            }

            @Override
            public void onSelectionChanged() {
                updateActionModeTitle();
            }

            @Override
            public void onLongPress(QueueItem item) {
                startSelectionMode(item);
            }
        });
        binding.queueRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.queueRecyclerView.setItemAnimator(null);
        binding.queueRecyclerView.setAdapter(queueAdapter);
    }

    private void beginCreateShorts(QueueItem item) {
        AppOptionDialog.show(requireContext(), "Create Shorts",
                "Choose how AutoSub should find the moments for this video.",
                new AppOptionDialog.Option[]{
                        new AppOptionDialog.Option("AI highlights",
                                "Use Gemma to find complete, compelling clip ranges."),
                        new AppOptionDialog.Option("Phrase montage",
                                "Join every occurrence of a word or phrase. No AI model required.")
                }, which -> {
                    if (which == 0) beginAiCreateShorts(item);
                    else showPhraseMontageDialog(item);
                });
    }

    private void beginAiCreateShorts(QueueItem item) {
        if (!viewModel.isShortsAiSupported()) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Android 12 required")
                    .setMessage("Local Gemma Shorts extraction requires Android 12 or newer. Subtitle generation remains available on this device.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        if (!Boolean.TRUE.equals(viewModel.getGemmaInstalled().getValue())) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Download Gemma 4 E2B")
                    .setMessage("The local Shorts editor is about 2.6 GB. Download it from Models before analyzing this transcript.")
                    .setPositiveButton("Open Models", (dialog, which) -> viewModel.openModelsForGemma())
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        Runnable continueAction = () -> viewModel.loadShortsProject(item, existing -> {
            if (existing == null || existing.isPhraseMontage() || existing.getCandidates().isEmpty()) {
                showShortsAnalysisDialog(item);
            }
            else AppOptionDialog.show(requireContext(), "Shorts project found",
                    "Review the saved candidates or run Gemma again with new instructions.",
                    new AppOptionDialog.Option[]{
                            new AppOptionDialog.Option("Review candidates", "Continue editing the saved Shorts project."),
                            new AppOptionDialog.Option("Analyze again", "Replace it with a fresh set of candidates.")
                    }, which -> {
                        if (which == 0) {
                            viewModel.prepareShorts(item);
                        } else showShortsAnalysisDialog(item);
                    });
        });
        if (viewModel.isGemmaLowMemoryDevice()) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Limited device memory")
                    .setMessage("Gemma 4 E2B is designed for devices with at least 8 GB RAM. Loading it here may be slow or the system may close AutoSub.")
                    .setPositiveButton("Try anyway", (dialog, which) -> continueAction.run())
                    .setNegativeButton("Cancel", null)
                    .show();
        } else continueAction.run();
    }

    private void showPhraseMontageDialog(QueueItem item) {
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        android.widget.LinearLayout layout = new android.widget.LinearLayout(requireContext());
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(padding, 0, padding, 0);

        android.widget.EditText phraseInput = new android.widget.EditText(requireContext());
        phraseInput.setHint("Word or phrase");
        phraseInput.setSingleLine(true);
        layout.addView(phraseInput);

        android.widget.CheckBox keepWholeSubtitle = new android.widget.CheckBox(requireContext());
        keepWholeSubtitle.setText("Keep the whole subtitle cue for each match");
        layout.addView(keepWholeSubtitle);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Create phrase montage")
                .setMessage("AutoSub will find every matching word sequence, extract those moments in timeline order, and join them into one video.")
                .setView(layout)
                .setPositiveButton("Create", (dialog, which) -> {
                    String phrase = phraseInput.getText().toString().trim();
                    if (phrase.isEmpty()) {
                        Toast.makeText(requireContext(), "Enter a word or phrase", Toast.LENGTH_LONG).show();
                        return;
                    }
                    viewModel.preparePhraseMontage(item, phrase, keepWholeSubtitle.isChecked());
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showShortsAnalysisDialog(QueueItem item) {
        DialogShortsAnalysisBinding dialogBinding = DialogShortsAnalysisBinding.inflate(getLayoutInflater());
        android.content.SharedPreferences settingsPrefs = requireContext().getSharedPreferences("autosub_settings", android.content.Context.MODE_PRIVATE);
        boolean isWordByWord = settingsPrefs.getBoolean("shorts_mode_word_by_word", false);
        int savedWords = settingsPrefs.getInt("shorts_max_words_per_subtitle", 10);
        // Default to GPU; the choice persists so it sticks once the user adjusts it.
        dialogBinding.cpuSwitch.setChecked(settingsPrefs.getBoolean("shorts_prefer_cpu", false));
        dialogBinding.wordByWordSwitch.setChecked(isWordByWord);
        dialogBinding.wordsPerSubtitleSlider.setValue((float) savedWords);
        dialogBinding.wordsPerSubtitleLabel.setText("Maximum words per subtitle: " + savedWords);
        dialogBinding.wordsPerSubtitleSlider.addOnChangeListener((slider, value, fromUser) -> {
            dialogBinding.wordsPerSubtitleLabel.setText("Maximum words per subtitle: " + (int) value);
        });
        dialogBinding.wordByWordSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            dialogBinding.wordsPerSubtitleSlider.setEnabled(!isChecked);
            dialogBinding.wordsPerSubtitleLabel.setEnabled(!isChecked);
        });
        dialogBinding.wordsPerSubtitleSlider.setEnabled(!isWordByWord);
        dialogBinding.wordsPerSubtitleLabel.setEnabled(!isWordByWord);

        AlertDialog analysisDialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Find the best Shorts")
                .setMessage("Gemma analyzes the saved transcript entirely on this device.")
                .setView(dialogBinding.getRoot())
                .create();
        dialogBinding.cancelButton.setOnClickListener(ignored -> analysisDialog.dismiss());
        dialogBinding.startAnalysisButton.setOnClickListener(ignored -> {
            settingsPrefs.edit()
                    .putBoolean("shorts_mode_word_by_word", dialogBinding.wordByWordSwitch.isChecked())
                    .putBoolean("shorts_prefer_cpu", dialogBinding.cpuSwitch.isChecked())
                    .putInt("shorts_max_words_per_subtitle", (int) dialogBinding.wordsPerSubtitleSlider.getValue())
                    .apply();
            analysisDialog.dismiss();
            viewModel.analyzeShorts(item,
                    parseInt(dialogBinding.clipCountInput, 5),
                    parseInt(dialogBinding.minDurationInput, 20),
                    parseInt(dialogBinding.maxDurationInput, 60),
                    textOf(dialogBinding.focusInput),
                    !dialogBinding.cpuSwitch.isChecked(),
                    dialogBinding.thinkingSwitch.isChecked());
        });
        analysisDialog.setOnShowListener(ignored -> {
            android.graphics.Rect visibleArea = new android.graphics.Rect();
            analysisDialog.getWindow().getDecorView().getWindowVisibleDisplayFrame(visibleArea);
            int densityPadding = Math.round(260 * getResources().getDisplayMetrics().density);
            int minimumFormHeight = Math.round(140 * getResources().getDisplayMetrics().density);
            int maximumFormHeight = Math.round(520 * getResources().getDisplayMetrics().density);
            int formHeight = Math.min(maximumFormHeight,
                    Math.max(minimumFormHeight, visibleArea.height() - densityPadding));
            android.view.ViewGroup.LayoutParams params = dialogBinding.getRoot().getLayoutParams();
            params.height = formHeight;
            dialogBinding.getRoot().setLayoutParams(params);
        });
        analysisDialog.show();
    }

    private int parseInt(android.widget.EditText field, int fallback) {
        try { return Integer.parseInt(field.getText().toString().trim()); }
        catch (Exception ignored) { return fallback; }
    }

    private String textOf(android.widget.EditText field) {
        return field.getText() == null ? "" : field.getText().toString();
    }

    private void startSelectionMode(QueueItem item) {
        if (actionMode == null) {
            actionMode = ((AppCompatActivity) requireActivity()).startSupportActionMode(this);
        }
        queueAdapter.setSelectionMode(true);
        item.setSelected(true);
        queueAdapter.notifyDataSetChanged();
        updateActionModeTitle();
    }

    private void updateActionModeTitle() {
        if (actionMode != null) {
            int count = queueAdapter.getSelectedCount();
            actionMode.setTitle(count + " selected");
            if (count == 0) {
                actionMode.finish();
            }
        }
    }

    private void playExportedVideo(QueueItem item) {
        String soft = item.getSoftVideoPath();
        String hard = item.getHardVideoPath();
        
        if (!soft.isEmpty() && !hard.isEmpty()) {
            AppOptionDialog.show(requireContext(),
                    "Play exported video",
                    "This queue item has two exported video files.",
                    new AppOptionDialog.Option[]{
                            new AppOptionDialog.Option(
                                    "Soft subtitles",
                                    "Open the video with selectable subtitle tracks."),
                            new AppOptionDialog.Option(
                                    "Hard subtitles",
                                    "Open the video with subtitles burned into the image.")
                    }, which -> openVideoFile(which == 0 ? soft : hard));
        } else if (!soft.isEmpty()) {
            openVideoFile(soft);
        } else if (!hard.isEmpty()) {
            openVideoFile(hard);
        } else {
            Toast.makeText(requireContext(), "No exported videos to play", Toast.LENGTH_SHORT).show();
        }
    }

    private void openVideoFile(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            Toast.makeText(requireContext(), "Video path is empty", Toast.LENGTH_SHORT).show();
            return;
        }
        
        java.io.File file = new java.io.File(filePath);
        if (!file.exists()) {
            Toast.makeText(requireContext(), "File does not exist: " + filePath, Toast.LENGTH_SHORT).show();
            return;
        }
        
        Uri fileUri = androidx.core.content.FileProvider.getUriForFile(requireContext(), requireContext().getPackageName() + ".provider", file);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(fileUri, "video/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Error opening video: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void quickExportVideo(QueueItem item) {
        AppOptionDialog.show(requireContext(),
                "Export video",
                "Choose how subtitles should be included in the exported video.",
                new AppOptionDialog.Option[]{
                        new AppOptionDialog.Option(
                                "Soft subtitles",
                                "Add a subtitle track that can be turned on or off in compatible players."),
                        new AppOptionDialog.Option(
                                "Hard subtitles",
                                "Burn subtitles into the video image so they show everywhere.")
                }, which -> {
                    boolean burnSubtitles = (which == 1);
                    String fontName = burnSubtitles ? "RobotoRegular" : null;
                    if (!burnSubtitles && shouldAskSoftSubtitleContainer(item)) {
                        chooseSubtitleLayerMode(item, layerMode -> chooseQueueSoftExportFormat(item, layerMode));
                    } else {
                        Runnable proceed = () -> chooseSubtitleLayerMode(item, layerMode ->
                                exportQueueVideo(item, burnSubtitles, fontName, false, layerMode));
                        if (burnSubtitles) HardSubtitleExportSettings.show(this, item.getVideoUri(), proceed::run);
                        else proceed.run();
                    }
                });
    }

    private void exportCondensedVideo(QueueItem item, boolean useVad) {
        AppOptionDialog.show(requireContext(), useVad ? "Export without silence" : "Export talk only",
                "Subtitles are retimed to match the joined video.",
                new AppOptionDialog.Option[]{
                        new AppOptionDialog.Option("Video only", "Export the joined video without subtitles."),
                        new AppOptionDialog.Option("Soft subtitles", "Add a selectable, correctly retimed subtitle track."),
                        new AppOptionDialog.Option("Hard subtitles", "Burn correctly retimed subtitles into the video."),
                        new AppOptionDialog.Option("SRT subtitles", "Export only a correctly retimed SRT file."),
                        new AppOptionDialog.Option("VTT subtitles", "Export only a correctly retimed VTT file.")
                }, which -> {
                    SubtitleGenerator.CondensedOutputMode mode = SubtitleGenerator.CondensedOutputMode.values()[which];
                    if (mode == SubtitleGenerator.CondensedOutputMode.VIDEO) {
                        executeCondensedExport(item, useVad, mode, SubtitleGenerator.SubtitleLayerMode.ORIGINAL);
                    } else {
                        Runnable proceed = () -> chooseSubtitleLayerMode(item,
                                layer -> executeCondensedExport(item, useVad, mode, layer));
                        if (mode == SubtitleGenerator.CondensedOutputMode.HARD_SUBTITLE_VIDEO) {
                            HardSubtitleExportSettings.show(this, item.getVideoUri(), proceed::run);
                        } else proceed.run();
                    }
                });
    }

    private void executeCondensedExport(QueueItem item, boolean useVad,
                                        SubtitleGenerator.CondensedOutputMode mode,
                                        SubtitleGenerator.SubtitleLayerMode layerMode) {
        ExportFolderDialog.show(this, useVad ? "Export without silence" : "Export talk only",
                outputDir -> viewModel.exportCondensedQueueItem(item, useVad, outputDir, mode, layerMode,
                        new SubtitleGenerator.VideoExportCallback() {
                            @Override public void onVideoExported(String filePath) {
                                boolean video = mode != SubtitleGenerator.CondensedOutputMode.SRT
                                        && mode != SubtitleGenerator.CondensedOutputMode.VTT;
                                ExportFileActions.showExportCompleteDialog(
                                        GenerateFragment.this, viewModel, filePath, video);
                            }

                            @Override public void onError(String errorMessage) {
                                if (isAdded()) Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_LONG).show();
                            }

                            @Override public void onProgressUpdate(int progress) { }
                        }));
    }

    private void translateQueueItem(QueueItem item) {
        if (item == null) return;
        if (item.hasTranslations()) {
            AppOptionDialog.show(requireContext(),
                    "Retranslate subtitles",
                    "This item already has translated subtitles. Use current translation settings and replace them?",
                    new AppOptionDialog.Option[]{
                            new AppOptionDialog.Option(
                                    "Retranslate",
                                    "Replace the existing translated text using current Settings."),
                            new AppOptionDialog.Option(
                                    "Cancel",
                                    "Keep the current translation unchanged.")
                    }, which -> {
                        if (which == 0) {
                            executeTranslateQueueItem(item);
                        }
                    });
        } else {
            executeTranslateQueueItem(item);
        }
    }

    private void executeTranslateQueueItem(QueueItem item) {
        viewModel.translateQueueItem(item, new SubtitleGenerator.TranslationCallback() {
            @Override
            public void onTranslated(List<SubtitleGenerator.SubtitleEntry> subtitleEntries, String sourceLanguage, String targetLanguage) {
                if (!isAdded()) return;
                Toast.makeText(requireContext(), "Subtitles translated", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String errorMessage) {
                if (!isAdded()) return;
                Toast.makeText(requireContext(), "Translation failed: " + errorMessage, Toast.LENGTH_LONG).show();
            }

            @Override
            public void onProgressUpdate(int progress) {
                // Progress is shown on the queue item card.
            }
        });
    }

    private boolean shouldAskSoftSubtitleContainer(QueueItem item) {
        return item != null && (item.isShortsVideo() || item.isSubtitleCaptionPositionAdjusted());
    }

    private void chooseQueueSoftExportFormat(QueueItem item, SubtitleGenerator.SubtitleLayerMode layerMode) {
        AppOptionDialog.show(requireContext(),
                "Soft subtitle format",
                "Adjusted subtitle positions need a container that can preserve styling.",
                new AppOptionDialog.Option[]{
                        new AppOptionDialog.Option(
                                "MKV with positioned subtitles",
                                "Keeps the adjusted subtitle placement and styling."),
                        new AppOptionDialog.Option(
                                "MP4 with standard subtitles",
                                "Best compatibility, but subtitle placement may be controlled by the player.")
                }, which -> exportQueueVideo(item, false, null, which == 1, layerMode));
    }

    private void exportQueueVideo(QueueItem item, boolean burnSubtitles, String fontName, boolean forceMp4SoftSubtitles,
                                  SubtitleGenerator.SubtitleLayerMode layerMode) {
        ExportFolderDialog.show(this, "Export to folder", outputDir ->
                executeVideoExport(item, burnSubtitles, fontName, forceMp4SoftSubtitles, outputDir, layerMode));
    }

    private void executeVideoExport(QueueItem item, boolean burnSubtitles, String fontName, boolean forceMp4SoftSubtitles,
                                    File outputDir, SubtitleGenerator.SubtitleLayerMode layerMode) {
        viewModel.exportVideoForQueueItem(item, burnSubtitles, fontName, forceMp4SoftSubtitles,
                outputDir, layerMode, new SubtitleGenerator.VideoExportCallback() {
            @Override
            public void onVideoExported(String filePath) {
                if (!isAdded()) return;
                ExportFileActions.showExportCompleteDialog(GenerateFragment.this, viewModel, filePath, true);
            }

            @Override
            public void onError(String errorMessage) {
                if (!isAdded()) return;
                if (errorMessage != null && errorMessage.startsWith("Already exported this video with this model")) {
                    showAlreadyExistsDialog(errorMessage, true, () -> {
                        String prefix = "Already exported this video with this model: ";
                        String filename = errorMessage.substring(prefix.length());
                        File targetFile = new File(outputDir, filename);
                        if (targetFile.exists()) {
                            targetFile.delete();
                        }
                        executeVideoExport(item, burnSubtitles, fontName, forceMp4SoftSubtitles, outputDir, layerMode);
                    }, () -> {
                        String prefix = "Already exported this video with this model: ";
                        String filename = errorMessage.substring(prefix.length());
                        File targetFile = new File(outputDir, filename);
                        ExportFileActions.playVideo(requireContext(), targetFile);
                    }, () -> {
                        String prefix = "Already exported this video with this model: ";
                        String filename = errorMessage.substring(prefix.length());
                        File targetFile = new File(outputDir, filename);
                        ExportFileActions.shareFile(requireContext(), targetFile);
                    });
                } else {
                    Toast.makeText(requireContext(), "Error exporting: " + errorMessage, Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onProgressUpdate(int progress) {
                // Progress is shown directly on the queue item card progressbar!
            }
        });
    }

    private void quickExportSubtitle(QueueItem item) {
        AppOptionDialog.show(requireContext(),
                "Save subtitles",
                "Choose the subtitle file format to create.",
                new AppOptionDialog.Option[]{
                        new AppOptionDialog.Option(
                                "SRT",
                                "Most common subtitle format. Works with many editors, players, and upload sites."),
                        new AppOptionDialog.Option(
                                "VTT",
                                "Web-friendly subtitle format for browsers and HTML video workflows.")
                }, which -> {
                    String format = (which == 0 ? "srt" : "vtt");
                    chooseSubtitleLayerMode(item, layerMode ->
                            ExportFolderDialog.show(this, "Save to folder", outputDir ->
                                    executeSaveSubtitles(item, format, outputDir, layerMode)));
                });
    }

    private void executeSaveSubtitles(QueueItem item, String format, File outputDir,
                                      SubtitleGenerator.SubtitleLayerMode layerMode) {
        viewModel.saveSubtitlesForQueueItem(item, format, outputDir, layerMode, new SubtitleGenerator.SubtitleSaveCallback() {
            @Override
            public void onSubtitlesSaved(String filePath) {
                if (!isAdded()) return;
                ExportFileActions.showExportCompleteDialog(GenerateFragment.this, viewModel, filePath, false);
            }

            @Override
            public void onError(String errorMessage) {
                if (!isAdded()) return;
                if (errorMessage != null && errorMessage.startsWith("Already exported subtitles")) {
                    showAlreadyExistsDialog(errorMessage, false, () -> {
                        String prefix = "Already exported subtitles for this video and model: ";
                        String filename = errorMessage.substring(prefix.length());
                        File targetFile = new File(outputDir, filename);
                        if (targetFile.exists()) {
                            targetFile.delete();
                        }
                        executeSaveSubtitles(item, format, outputDir, layerMode);
                    }, () -> {
                        String prefix = "Already exported subtitles for this video and model: ";
                        String filename = errorMessage.substring(prefix.length());
                        File targetFile = new File(outputDir, filename);
                        ExportFileActions.openFile(requireContext(), targetFile);
                    }, () -> {
                        String prefix = "Already exported subtitles for this video and model: ";
                        String filename = errorMessage.substring(prefix.length());
                        File targetFile = new File(outputDir, filename);
                        ExportFileActions.shareFile(requireContext(), targetFile);
                    });
                } else {
                    Toast.makeText(requireContext(), "Error saving: " + errorMessage, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void chooseSubtitleLayerMode(QueueItem item, SubtitleLayerChoiceCallback callback) {
        if (item == null || !item.hasTranslations()) {
            callback.onChosen(SubtitleGenerator.SubtitleLayerMode.ORIGINAL);
            return;
        }
        AppOptionDialog.show(requireContext(),
                "Subtitle language",
                "Choose which subtitle text to export.",
                new AppOptionDialog.Option[]{
                        new AppOptionDialog.Option("Original", "Export the source subtitles."),
                        new AppOptionDialog.Option("Translation", "Export the translated subtitles."),
                        new AppOptionDialog.Option("Double", "Export original first, translation second.")
                }, which -> {
                    if (which == 1) {
                        callback.onChosen(SubtitleGenerator.SubtitleLayerMode.TRANSLATION);
                    } else if (which == 2) {
                        callback.onChosen(SubtitleGenerator.SubtitleLayerMode.DOUBLE);
                    } else {
                        callback.onChosen(SubtitleGenerator.SubtitleLayerMode.ORIGINAL);
                    }
                });
    }

    private void showAlreadyExistsDialog(String errorMessage, boolean isVideo,
                                         Runnable onOverwrite, Runnable onOpen, Runnable onShare) {
        if (!isAdded()) return;

        String prefix = isVideo ? "Already exported this video with this model: " : "Already exported subtitles for this video and model: ";
        String filename = errorMessage.substring(prefix.length());

        String title = isVideo ? "Video Already Exported" : "Subtitles Already Saved";
        String message = "The following file already exists in your export library folder:\n\n" + filename + "\n\nWould you like to overwrite it, open/play it, or share it?";

        AppOptionDialog.Option[] options = new AppOptionDialog.Option[]{
                new AppOptionDialog.Option(R.drawable.ri_delete_bin_line,
                        "Overwrite File", "Replace the existing file with your new export."),
                new AppOptionDialog.Option(isVideo ? R.drawable.ri_play_circle_line : R.drawable.ri_file_text_line,
                        isVideo ? "Play Video" : "Open Subtitles", isVideo ? "Watch the previously exported video." : "View the previously saved subtitle file."),
                new AppOptionDialog.Option(R.drawable.ri_share_line,
                        "Share Existing File", "Send the existing file to another application.")
        };

        AppOptionDialog.show(requireContext(), title, message, options, which -> {
            if (which == 0) {
                onOverwrite.run();
            } else if (which == 1) {
                onOpen.run();
            } else if (which == 2) {
                onShare.run();
            }
        });
    }

    private void setupBatchExport() {
        AppOptionDialog.show(requireContext(),
                "Batch export",
                "Run one export action for every completed queue item.",
                new AppOptionDialog.Option[]{
                        new AppOptionDialog.Option(
                                "Subtitle files as SRT",
                                "Create an SRT file for each completed video."),
                        new AppOptionDialog.Option(
                                "Subtitle files as VTT",
                                "Create a web-ready VTT file for each completed video."),
                        new AppOptionDialog.Option(
                                "Videos with soft subtitles",
                                "Export videos with subtitle tracks that can be toggled in compatible players."),
                        new AppOptionDialog.Option(
                                "Videos with hard subtitles",
                                "Export videos with subtitles burned into the image.")
                }, which -> {
                    if (which == 0 || which == 1) {
                        String format = (which == 0 ? "srt" : "vtt");
                        ExportFolderDialog.show(this, "Save batch to folder", outputDir -> {
                            Toast.makeText(requireContext(), "Starting batch subtitle export...", Toast.LENGTH_SHORT).show();
                            viewModel.batchSaveSubtitles(format, outputDir, new SubtitleGenerator.SubtitleSaveCallback() {
                            @Override
                            public void onSubtitlesSaved(String filePath) {
                                if (!isAdded()) return;
                                showBatchExportCompleteDialog(false);
                            }

                            @Override
                            public void onError(String errorMessage) {
                                if (!isAdded()) return;
                                Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_LONG).show();
                            }
                        });
                        });
                    } else {
                        boolean burnSubtitles = (which == 3);
                        String fontName = burnSubtitles ? "RobotoRegular" : null;
                        Runnable proceed = () -> ExportFolderDialog.show(this, "Export batch to folder", outputDir -> {
                            Toast.makeText(requireContext(), "Starting batch video export...", Toast.LENGTH_SHORT).show();
                            viewModel.batchExportVideos(burnSubtitles, fontName, outputDir, new SubtitleGenerator.VideoExportCallback() {
                            @Override
                            public void onVideoExported(String filePath) {
                                if (!isAdded()) return;
                                showBatchExportCompleteDialog(true);
                            }

                            @Override
                            public void onError(String errorMessage) {
                                if (!isAdded()) return;
                                Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_LONG).show();
                            }

                            @Override
                            public void onProgressUpdate(int progress) {
                                // Progress is updated per item in the background sequentially!
                            }
                        });
                        });
                        if (burnSubtitles) {
                            List<Uri> videoUris = new ArrayList<>();
                            List<QueueItem> queueItems = viewModel.getQueueItems().getValue();
                            if (queueItems != null) {
                                for (QueueItem queueItem : queueItems) {
                                    if (queueItem.getStatus() == QueueItem.Status.COMPLETED
                                            && queueItem.getVideoUri() != null) {
                                        videoUris.add(queueItem.getVideoUri());
                                    }
                                }
                            }
                            HardSubtitleExportSettings.show(this, videoUris, proceed::run);
                        }
                        else proceed.run();
                    }
                });
    }

    private void shareQueueItemFile(QueueItem item) {
        String filePath = item.getOutputPath();
        if (filePath == null || filePath.isEmpty()) {
            Toast.makeText(requireContext(), "No file available to share", Toast.LENGTH_SHORT).show();
            return;
        }
        
        java.io.File file = new java.io.File(filePath);
        if (!file.exists()) {
            Toast.makeText(requireContext(), "File does not exist: " + filePath, Toast.LENGTH_SHORT).show();
            return;
        }
        
        Uri fileUri = androidx.core.content.FileProvider.getUriForFile(requireContext(), requireContext().getPackageName() + ".provider", file);
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        
        String mimeType = "text/plain";
        if (filePath.endsWith(".srt")) {
            mimeType = "application/x-subrip";
        } else if (filePath.endsWith(".vtt")) {
            mimeType = "text/vtt";
        }
        
        shareIntent.setType(mimeType);
        shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        
        try {
            startActivity(Intent.createChooser(shareIntent, "Share Subtitles"));
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Error sharing file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void showBatchExportCompleteDialog(boolean videos) {
        AppOptionDialog.show(requireContext(),
                videos ? "Videos exported" : "Subtitles exported",
                "Batch export finished. You can review the files in Exports or open the AutoSub folder.",
                new AppOptionDialog.Option[]{
                        new AppOptionDialog.Option(R.drawable.ri_folder_open_line,
                                "Exports", "Browse, search, filter, and share saved files."),
                        new AppOptionDialog.Option(R.drawable.ri_folder_settings_line,
                                "Files", "Open the AutoSub folder in the device file manager.")
                }, which -> {
                    if (which == 0) {
                        viewModel.setActiveNavigationTab(R.id.nav_exports);
                    } else {
                        ExportFileActions.openInFileManager(requireContext(),
                                new java.io.File(ApplicationPath.applicationPath(requireContext())));
                    }
                });
    }

    private void setupActions() {
        binding.addQueueFAB.setOnClickListener(v -> selectVideo());
        binding.changeModelBT.setOnClickListener(v -> viewModel.setActiveNavigationTab(R.id.nav_models));
        binding.modelPanel.setOnClickListener(v -> viewModel.setActiveNavigationTab(R.id.nav_models));
        binding.cancelQueueFAB.setOnClickListener(v -> viewModel.cancelCurrentQueueItem());
        binding.batchExportFAB.setOnClickListener(v -> setupBatchExport());
        
        binding.queueFilterChipGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            applyQueueFilter();
        });
    }

    private void selectVideo() {
        if (Boolean.FALSE.equals(viewModel.getModelReady().getValue())) {
            Toast.makeText(requireContext(), "Speech model is still loading", Toast.LENGTH_SHORT).show();
            return;
        }
        pickMultipleMedia.launch(new String[]{"video/*"});
    }

    private void addVideosToQueue(List<Uri> uris) {
        List<Uri> urisWithExports = new ArrayList<>();
        List<File> allExistingFiles = new ArrayList<>();
        for (Uri uri : uris) {
            List<File> existing = viewModel.getExistingExportsForVideo(uri);
            if (!existing.isEmpty()) {
                urisWithExports.add(uri);
                allExistingFiles.addAll(existing);
            }
        }

        if (!urisWithExports.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("The following files already exist in your export library for the selected video(s):\n\n");
            for (File file : allExistingFiles) {
                sb.append("• ").append(file.getName()).append("\n");
            }
            sb.append("\nWould you like to overwrite/delete the existing exports and start fresh, or keep them and add the video anyway?");

            AppOptionDialog.show(requireContext(),
                    "Exported Files Already Exist",
                    sb.toString(),
                    new AppOptionDialog.Option[]{
                            new AppOptionDialog.Option(R.drawable.ri_delete_bin_line,
                                    "Overwrite / Delete", "Delete the previous exports from storage and start fresh."),
                            new AppOptionDialog.Option(R.drawable.ri_checkbox_circle_line,
                                    "Keep Existing & Add", "Keep the previous exports and add the video to the queue.")
                    }, which -> {
                        if (which == 0) {
                            for (Uri uri : urisWithExports) {
                                viewModel.deleteExportsForVideo(uri);
                            }
                        }
                        proceedWithAddingVideos(uris);
                    });
        } else {
            proceedWithAddingVideos(uris);
        }
    }

    private void proceedWithAddingVideos(List<Uri> uris) {
        if (viewModel.shouldShowShortsDialog(uris, this::isVerticalVideo)) {
            showShortsDialog(uris);
        } else {
            maybeShowVadDialog(uris);
        }
    }

    private void showShortsDialog(List<Uri> uris) {
        AppOptionDialog.showWithCheckbox(requireContext(),
                "Shorts video detected",
                "Vertical videos can be captioned one word at a time for a Shorts-style preview.",
                new AppOptionDialog.Option[]{
                        new AppOptionDialog.Option(
                                "Word-by-word captions",
                                "Show one recognized word at a time. Best for short-form social clips."),
                        new AppOptionDialog.Option(
                                "Standard captions",
                                "Create normal subtitle lines. Better for readability and longer speech.")
                }, "Don't show this again", false, (which, checked) -> {
                    viewModel.setShortsTranscriptionPreferences(which == 0, checked);
                    maybeShowVadDialog(uris);
                });
    }

    private void maybeShowVadDialog(List<Uri> uris) {
        boolean vadAlreadyOn = Boolean.TRUE.equals(viewModel.getWhisperVadEnabled().getValue());
        if (!vadAlreadyOn && hasLongVideo(uris)) {
            showVadSpeedupDialog(uris);
        } else {
            viewModel.addVideosToQueue(uris, this::getDisplayName, this::isVerticalVideo);
        }
    }

    private void showVadSpeedupDialog(List<Uri> uris) {
        AppOptionDialog.show(requireContext(),
                "Long video detected",
                "This video is longer than " + VAD_PROMPT_THRESHOLD_MINUTES + " minutes. AutoSub can skip silent"
                        + " stretches to finish faster, but skipping audio can occasionally drop quiet speech or"
                        + " shift some subtitle timings.",
                new AppOptionDialog.Option[]{
                        new AppOptionDialog.Option(R.drawable.ri_time_line,
                                "Skip silence (faster)",
                                "Speeds up processing by skipping silent parts. May miss some dialog or shift timings."),
                        new AppOptionDialog.Option(R.drawable.ri_checkbox_circle_line,
                                "Full accuracy",
                                "Processes the entire audio. Slower, but the most reliable timings and dialog.")
                }, which -> viewModel.addVideosToQueue(uris, this::getDisplayName,
                        this::isVerticalVideo, which == 0));
    }

    private boolean hasLongVideo(List<Uri> uris) {
        for (Uri uri : uris) {
            if (getVideoDurationMs(uri) > VAD_PROMPT_THRESHOLD_MINUTES * 60_000L) {
                return true;
            }
        }
        return false;
    }

    private long getVideoDurationMs(Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(requireContext(), uri);
            return parseMetadataLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
        } catch (Exception e) {
            return 0;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private long parseMetadataLong(String value) {
        try {
            return value == null ? 0 : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void updateModelLoadingIndicator() {
        boolean speechModelLoading = !Boolean.TRUE.equals(viewModel.getModelReady().getValue())
                && viewModel.getSelectedModelInfo().getValue() != null
                && !Boolean.TRUE.equals(viewModel.getShortsAnalyzing().getValue());
        binding.modelLoadingIndicator.setVisibility(speechModelLoading ? View.VISIBLE : View.GONE);
    }

    private void observeViewModel() {
        viewModel.getSelectedModelInfo().observe(getViewLifecycleOwner(), info -> {
            if (info != null) {
                binding.modelNameTV.setText(info.getLanguage());
            }
            updateModelLoadingIndicator();
        });

        viewModel.getModelStatusText().observe(getViewLifecycleOwner(), text -> {
            binding.modelStatusTV.setText(text);
        });

        viewModel.getModelReady().observe(getViewLifecycleOwner(), ready -> {
            binding.addQueueFAB.setEnabled(ready);
            updateModelLoadingIndicator();
        });

        viewModel.getShortsAnalyzing().observe(getViewLifecycleOwner(), analyzing ->
                updateModelLoadingIndicator());

        viewModel.getQueueItems().observe(getViewLifecycleOwner(), items -> {
            applyQueueFilter();
        });

        viewModel.getQueueRunning().observe(getViewLifecycleOwner(), running -> {
            updateQueueActionVisibility();
        });

        viewModel.getRamUsage().observe(getViewLifecycleOwner(), usage -> {
            binding.ramUsageTV.setText(usage);
        });

        viewModel.getShowRamUsage().observe(getViewLifecycleOwner(), show -> {
            binding.ramUsageTV.setVisibility(Boolean.TRUE.equals(show) ? View.VISIBLE : View.GONE);
        });

        viewModel.getShortsError().observe(getViewLifecycleOwner(), error -> {
            if (error != null && !error.isEmpty()) {
                Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show();
                viewModel.consumeShortsError();
            }
        });
    }

    private void applyQueueFilter() {
        List<QueueItem> allItems = viewModel.getQueueItems().getValue();
        if (allItems == null) return;
        
        List<QueueItem> filteredList = new ArrayList<>();
        int checkedId = binding.queueFilterChipGroup.getCheckedChipId();
        
        for (QueueItem item : allItems) {
            if (checkedId == R.id.filterAllChip) {
                filteredList.add(item);
            } else if (checkedId == R.id.filterPendingChip) {
                if (item.getStatus() == QueueItem.Status.PENDING) {
                    filteredList.add(item);
                }
            } else if (checkedId == R.id.filterProcessingChip) {
                if (item.getStatus() == QueueItem.Status.PROCESSING
                        || item.getStatus() == QueueItem.Status.EXPORTING
                        || item.getStatus() == QueueItem.Status.ANALYZING_SHORTS) {
                    filteredList.add(item);
                }
            } else if (checkedId == R.id.filterCompletedChip) {
                if (item.getStatus() == QueueItem.Status.COMPLETED
                        || item.getStatus() == QueueItem.Status.ANALYZING_SHORTS) {
                    filteredList.add(item);
                }
            } else if (checkedId == R.id.filterFailedChip) {
                if (item.getStatus() == QueueItem.Status.FAILED || item.getStatus() == QueueItem.Status.CANCELLED) {
                    filteredList.add(item);
                }
            }
        }
        queueAdapter.setItems(filteredList);
        
        int done = 0;
        for (QueueItem item : allItems) {
            if (item.getStatus() == QueueItem.Status.COMPLETED
                    || item.getStatus() == QueueItem.Status.ANALYZING_SHORTS) done++;
        }
        String format = viewModel.getBatchFormat().getValue();
        binding.queueSummaryTV.setText(allItems.size() + " videos - " + done + " completed - " + format.toUpperCase(Locale.getDefault()));
        
        updateQueueActionVisibility();
    }

    private void updateQueueActionVisibility() {
        if (binding == null || viewModel == null) return;

        boolean running = Boolean.TRUE.equals(viewModel.getQueueRunning().getValue());
        int done = 0;
        List<QueueItem> allItems = viewModel.getQueueItems().getValue();
        if (allItems != null) {
            for (QueueItem item : allItems) {
                if (item.getStatus() == QueueItem.Status.COMPLETED
                        || item.getStatus() == QueueItem.Status.ANALYZING_SHORTS) done++;
            }
        }

        boolean hasCompletedItems = done > 0;
        binding.cancelQueueFAB.setVisibility(running ? View.VISIBLE : View.GONE);
        binding.batchExportFAB.setVisibility(hasCompletedItems ? View.VISIBLE : View.GONE);
        binding.secondaryQueueActions.setVisibility((running || hasCompletedItems) ? View.VISIBLE : View.GONE);
    }

    private String getDisplayName(Uri uri) {
        try (Cursor cursor = requireContext().getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) {
        }
        return "Video";
    }

    private boolean isVerticalVideo(Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(requireContext(), uri);
            int width = parseMetadataInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int height = parseMetadataInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            int rotation = parseMetadataInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            if (rotation == 90 || rotation == 270) {
                int oldWidth = width;
                width = height;
                height = oldWidth;
            }
            return height > width;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private int parseMetadataInt(String value) {
        try {
            return value == null ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public void onDestroyView() {
        if (actionMode != null) {
            actionMode.finish();
        }
        super.onDestroyView();
        binding = null;
    }

    // --- ActionMode Callback Implementation ---

    @Override
    public boolean onCreateActionMode(ActionMode mode, Menu menu) {
        MenuInflater inflater = mode.getMenuInflater();
        inflater.inflate(R.menu.queue_selection_menu, menu);
        return true;
    }

    @Override
    public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
        return false;
    }

    @Override
    public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_select_all_queue) {
            queueAdapter.selectAllVisible();
            updateActionModeTitle();
            return true;
        } else if (id == R.id.action_delete_selected) {
            int selectedCount = queueAdapter.getSelectedCount();
            if (selectedCount == 0) return false;
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Delete Selected Items")
                    .setMessage("Are you sure you want to remove the " + selectedCount + " selected items from the queue?")
                    .setPositiveButton("Delete", (dialog, which) -> {
                        viewModel.removeSelectedQueueItems();
                        Toast.makeText(requireContext(), "Selected items removed", Toast.LENGTH_SHORT).show();
                        mode.finish();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return true;
        } else if (id == R.id.action_export_selected) {
            setupBatchExport();
            mode.finish();
            return true;
        }
        return false;
    }

    @Override
    public void onDestroyActionMode(ActionMode mode) {
        actionMode = null;
        queueAdapter.setSelectionMode(false);
        applyQueueFilter();
    }
}
