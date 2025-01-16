package com.serhat.autosub;

import android.Manifest;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import android.app.AlertDialog;
import android.widget.EditText;
import android.graphics.Typeface;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.view.ActionMode;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.serhat.autosub.databinding.ActivityMainBinding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.io.File;

public class MainActivity extends AppCompatActivity implements ActionMode.Callback {

    private ActivityMainBinding binding;
    private SubtitleGenerator subtitleGenerator;
    private SubtitleAdapter subtitleAdapter;
    private List<SubtitleGenerator.SubtitleEntry> subtitleEntries;
    private ExoPlayer player;
    private Uri currentVideoUri;
    private ActionMode actionMode;
    private MenuItem select_video_menu;

    private enum Operation {SUBTITLE, VIDEO, NONE}

    private Operation operation = Operation.NONE;

    ActivityResultLauncher<PickVisualMediaRequest> pickMedia =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null) {
                    Log.d("PhotoPicker", "Selected URI: " + uri);
                    binding.selectVideoBT.setVisibility(View.GONE);
                    if (select_video_menu != null) {
                        select_video_menu.setVisible(true);
                    }
                    generateSubtitles(uri);
                } else {
                    Log.d("PhotoPicker", "No media selected");
                }
            });

    private static final long UPDATE_INTERVAL = 100;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int currentHighlightedPosition = -1;
    private final Runnable updateHighlightRunnable = new Runnable() {
        @Override
        public void run() {
            if (player != null && player.isPlaying()) {
                updateHighlightedSubtitle(player.getCurrentPosition());
                handler.postDelayed(this, UPDATE_INTERVAL);
            }
        }
    };

    private static final String TAG = "MainActivity";

    private void selectVideo() {
        pickMedia.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly.INSTANCE)
                .build());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        player = new ExoPlayer.Builder(this).build();
        PlayerView playerView = binding.playerView;
        playerView.setPlayer(player);

        binding.selectVideoBT.setEnabled(false);

        subtitleGenerator = new SubtitleGenerator(this);
        binding.selectVideoBT.setText("Loading Model...");
        subtitleGenerator.initModel(new SubtitleGenerator.ModelInitCallback() {
            @Override
            public void onModelInitialized() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        binding.selectVideoBT.setText("Select Video");
                        binding.selectVideoBT.setEnabled(true);
                    }
                });
            }

            @Override
            public void onError(String errorMessage) {
                runOnUiThread(() -> {
                    binding.selectVideoBT.setEnabled(false);
                    Toast.makeText(MainActivity.this, "Error initializing model: " + errorMessage, Toast.LENGTH_LONG).show();
                });
            }
        });

        subtitleAdapter = new SubtitleAdapter();
        subtitleAdapter.setOnSubtitleClickListener(this::showEditSubtitleDialog);
        subtitleAdapter.setOnPlayClickListener(this::seekToTime);
        subtitleAdapter.setOnDeleteClickListener(this::deleteSubtitle);
        subtitleAdapter.setOnItemLongClickListener(this::startSelectionMode);

        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        binding.recyclerView.setLayoutManager(layoutManager);
        binding.recyclerView.setAdapter(subtitleAdapter);

        binding.selectVideoBT.setOnClickListener(v -> {
            selectVideo();
        });

        binding.saveSubtitlesBT.setOnClickListener(v -> {
            if (subtitleEntries != null && !subtitleEntries.isEmpty()) {
                if ((ActivityCompat.checkSelfPermission(this,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                        && !(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)) {
                    operation = Operation.SUBTITLE;
                    requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                } else {
                    saveSubtitles();
                }
            } else {
                Toast.makeText(this, "No subtitles to save", Toast.LENGTH_SHORT).show();
            }
        });

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY) {
                    startSubtitleHighlightUpdate();
                } else {
                    stopSubtitleHighlightUpdate();
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying) {
                    startSubtitleHighlightUpdate();
                } else {
                    stopSubtitleHighlightUpdate();
                }
            }

            @Override
            public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
                updateHighlightedSubtitle(newPosition.positionMs);
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                Player.Listener.super.onPlayerError(error);
            }
        });

        Button exportVideoBT = binding.exportVideoBT;
        exportVideoBT.setOnClickListener(v -> exportVideoWithSubtitles());


        binding.cancelBT.setOnClickListener(v -> {
            subtitleGenerator.cancelGeneration();
            binding.cancelBT.setVisibility(View.GONE);
            binding.statusTV.setText("Cancelling...");
        });


    }

    private void generateSubtitles(Uri videoUri) {
        subtitleEntries = new ArrayList<>();
        subtitleAdapter.setSubtitles(subtitleEntries);
        subtitleAdapter.notifyDataSetChanged();

        binding.progressBar.setVisibility(View.VISIBLE);
        binding.progressBar.setIndeterminate(false);
        binding.progressPercentageTV.setVisibility(View.VISIBLE);
        binding.cancelBT.setVisibility(View.VISIBLE);
        binding.statusTV.setText("Generating subtitles...");
        binding.recyclerView.setVisibility(View.VISIBLE);
        binding.saveSubtitlesBT.setVisibility(View.GONE);
        binding.playerView.setVisibility(View.GONE);

        currentVideoUri = videoUri;

        Log.d(TAG, "Starting subtitle generation for video: " + videoUri);

        subtitleGenerator.generateSubtitles(videoUri, new SubtitleGenerator.SubtitleGenerationCallback() {
            @Override
            public void onPartialSubtitlesGenerated(List<SubtitleGenerator.SubtitleEntry> partialSubtitles) {
                runOnUiThread(() -> {
                    subtitleEntries = partialSubtitles;
                    subtitleAdapter.setSubtitles(partialSubtitles);
                    binding.recyclerView.post(() -> {
                        int lastPosition = subtitleAdapter.getItemCount() - 1;
                        if (lastPosition >= 0) {
                            binding.recyclerView.smoothScrollToPosition(lastPosition);
                        }
                    });
                });
            }

            @Override
            public void onSubtitlesGenerated(List<SubtitleGenerator.SubtitleEntry> entries) {
                Log.d(TAG, "Subtitles generated successfully. Total entries: " + entries.size());
                runOnUiThread(() -> {
                    binding.progressBar.setVisibility(View.GONE);
                    binding.progressPercentageTV.setVisibility(View.GONE);
                    binding.cancelBT.setVisibility(View.GONE);
                    binding.statusTV.setText("Subtitles generated. Review and save:");
                    binding.saveSubtitlesBT.setVisibility(View.VISIBLE);
                    binding.playerView.setVisibility(View.VISIBLE);

                    subtitleEntries = entries;
                    subtitleAdapter.setSubtitles(entries);

                    prepareVideo(videoUri);

                    binding.exportVideoBT.setVisibility(View.VISIBLE);
                });
            }

            @Override
            public void onError(String errorMessage) {
                Log.e(TAG, "Error generating subtitles: " + errorMessage);
                runOnUiThread(() -> {
                    binding.progressBar.setVisibility(View.GONE);
                    binding.progressPercentageTV.setVisibility(View.GONE);
                    binding.cancelBT.setVisibility(View.GONE);
                    binding.statusTV.setText("Error: " + errorMessage);
                });
            }

            @Override
            public void onProgressUpdate(int progress) {
                Log.d(TAG, "Subtitle generation progress: " + progress + "%");
                runOnUiThread(() -> {
                    binding.progressBar.setProgress(progress);
                    binding.progressPercentageTV.setText(progress + "%");
                });
            }

            @Override
            public void onCancelled() {
                Log.d(TAG, "Subtitle generation cancelled");
                runOnUiThread(() -> {
                    binding.progressBar.setVisibility(View.GONE);
                    binding.progressPercentageTV.setVisibility(View.GONE);
                    binding.cancelBT.setVisibility(View.GONE);
                    binding.statusTV.setText("Subtitle generation cancelled");
                });
            }
        });
    }

    private void prepareVideo(Uri videoUri) {
        MediaItem mediaItem = MediaItem.fromUri(videoUri);
        player.setMediaItem(mediaItem);
        player.prepare();
        player.play();
    }

    private void updateHighlightedSubtitle(long positionMs) {
        int newHighlightedPosition = -1;
        for (int i = 0; i < subtitleEntries.size(); i++) {
            SubtitleGenerator.SubtitleEntry entry = subtitleEntries.get(i);
            long startTime = parseTime(entry.getStartTime());
            long endTime = parseTime(entry.getEndTime());
            if (positionMs >= startTime && positionMs < endTime) {
                newHighlightedPosition = i;
                break;
            }
        }

        if (newHighlightedPosition != currentHighlightedPosition) {
            currentHighlightedPosition = newHighlightedPosition;
            subtitleAdapter.setHighlightedPosition(currentHighlightedPosition);
            if (currentHighlightedPosition != -1) {
                binding.recyclerView.smoothScrollToPosition(currentHighlightedPosition);
            }
        }
    }

    private long parseTime(String timeString) {
        String[] parts = timeString.split("[:,]");
        return Long.parseLong(parts[0]) * 3600000L +
                Long.parseLong(parts[1]) * 60000L +
                Long.parseLong(parts[2]) * 1000L +
                Long.parseLong(parts[3]);
    }

    private void saveSubtitles() {
        if (subtitleEntries == null || subtitleEntries.isEmpty()) {
            Toast.makeText(this, "No subtitles to save", Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Choose subtitle format")
                .setItems(new CharSequence[]{"SRT", "VTT"}, (dialog, which) -> {
                    String format = (which == 0) ? "srt" : "vtt";
                    saveSubtitlesInFormat(format);
                });
        builder.create().show();
    }

    private void saveSubtitlesInFormat(String format) {
        if (currentVideoUri == null) {
            Toast.makeText(this, "No video selected", Toast.LENGTH_SHORT).show();
            return;
        }

        binding.progressBar.setVisibility(View.VISIBLE);
        binding.statusTV.setText("Saving subtitles in " + format.toUpperCase() + " format...");

        List<SubtitleGenerator.SubtitleEntry> updatedSubtitles = subtitleAdapter.getSubtitles();

        subtitleGenerator.saveSubtitlesToFile(updatedSubtitles, format, currentVideoUri, new SubtitleGenerator.SubtitleSaveCallback() {
            @Override
            public void onSubtitlesSaved(String filePath) {
                runOnUiThread(() -> {
                    binding.progressBar.setVisibility(View.GONE);
                    binding.statusTV.setText(format.toUpperCase() + " subtitles saved: " + filePath);
                    Toast.makeText(MainActivity.this, "Subtitles saved successfully", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String errorMessage) {
                runOnUiThread(() -> {
                    binding.progressBar.setVisibility(View.GONE);
                    binding.statusTV.setText("Error saving subtitles: " + errorMessage);
                    Toast.makeText(MainActivity.this, "Error saving subtitles", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    public final ActivityResultLauncher<String> requestPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            new ActivityResultCallback<Boolean>() {
                @Override
                public void onActivityResult(Boolean result) {
                    if (!result) {
                        if (ActivityCompat.shouldShowRequestPermissionRationale(MainActivity.this,
                                "android.permission.WRITE_EXTERNAL_STORAGE")) {
                            new MaterialAlertDialogBuilder(MainActivity.this)
                                    .setTitle(getString(R.string.app_name) + " needs permission")
                                    .setMessage("This app requires WRITE_EXTERNAL_STORAGE permission to save the file to permanent storage")
                                    .setPositiveButton("Give Permission", new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {
                                            saveSubtitleOrVideo();
                                            dialog.dismiss();
                                        }
                                    })
                                    .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {
                                            dialog.dismiss();
                                        }
                                    }).show();

                        }
                    } else {
                        saveSubtitleOrVideo();
                    }
                }
            }
    );

    private void saveSubtitleOrVideo() {
        if (operation == Operation.VIDEO) {
            exportVideoWithSubtitles();
        } else if (operation == Operation.SUBTITLE) {
            saveSubtitles();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        select_video_menu = menu.findItem(R.id.select_video_menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.select_video_menu) {
            selectVideo();
        } else if (id == R.id.open_project_menu) {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("https://github.com/Serkali-sudo/auto-subtitle-generator"));
            startActivity(intent);
        }
        return super.onOptionsItemSelected(item);
    }

    private void startSubtitleHighlightUpdate() {
        handler.removeCallbacks(updateHighlightRunnable);
        handler.post(updateHighlightRunnable);
    }

    private void stopSubtitleHighlightUpdate() {
        handler.removeCallbacks(updateHighlightRunnable);
    }

    @Override
    protected void onDestroy() {
        stopSubtitleHighlightUpdate();
        if (subtitleGenerator != null) {
            subtitleGenerator.cancelGeneration();
        }
        player.release();
        super.onDestroy();
    }

    private void showEditSubtitleDialog(int position, SubtitleGenerator.SubtitleEntry entry) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Edit Subtitle");

        final EditText input = new EditText(this);
        input.setText(entry.getText());
        builder.setView(input);

        builder.setPositiveButton("Save", (dialog, which) -> {
            String newText = input.getText().toString();
            subtitleAdapter.updateSubtitle(position, newText);
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());

        builder.show();
    }

    private void exportVideoWithSubtitles() {

        if ((ActivityCompat.checkSelfPermission(this,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                && !(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)) {
            operation = Operation.VIDEO;
            requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        } else {
            if (currentVideoUri == null || subtitleEntries == null || subtitleEntries.isEmpty()) {
                Toast.makeText(this, "No video or subtitles available", Toast.LENGTH_SHORT).show();
                return;
            }

            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Choose Subtitle Type")
                    .setItems(new CharSequence[]{"Soft Subtitles", "Hard Subtitles"}, (dialog, which) -> {
                        boolean burnSubtitles = (which == 1);
                        if (burnSubtitles) {
                            startExport(true, "RobotoRegular");
                        } else {
                            startExport(false, null);
                        }
                    });
            builder.create().show();
        }

    }

    private void startExport(boolean burnSubtitles, String fontName) {
        binding.progressBar.setVisibility(View.VISIBLE);
        binding.progressBar.setIndeterminate(true);
        binding.statusTV.setText("Exporting video with " + (burnSubtitles ? "hard" : "soft") + " subtitles...");

        List<SubtitleGenerator.SubtitleEntry> updatedSubtitles = subtitleAdapter.getSubtitles();

        subtitleGenerator.exportVideoWithSubtitles(currentVideoUri, updatedSubtitles, burnSubtitles, fontName, new SubtitleGenerator.VideoExportCallback() {
            @Override
            public void onVideoExported(String filePath) {
                runOnUiThread(() -> {
                    binding.progressBar.setVisibility(View.GONE);
                    binding.statusTV.setText("Video exported: " + filePath);
                    Toast.makeText(MainActivity.this, "Video exported successfully", Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onError(String errorMessage) {
                runOnUiThread(() -> {
                    binding.progressBar.setVisibility(View.GONE);
                    binding.statusTV.setText("Error exporting video: " + errorMessage);
                    Toast.makeText(MainActivity.this, "Error exporting video", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onProgressUpdate(int progress) {
                runOnUiThread(() -> {
                    binding.progressBar.setProgress(progress);
                });
            }
        });
    }

    private void seekToTime(long timeMs) {
        if (player != null) {
            player.seekTo(timeMs);
            player.play();
        }
    }

    private void deleteSubtitle(int position) {
        new AlertDialog.Builder(this)
                .setTitle("Delete Subtitle")
                .setMessage("Are you sure you want to delete this subtitle?")
                .setPositiveButton("Yes", (dialog, which) -> {
                    subtitleAdapter.deleteSubtitle(position);
                    List<SubtitleGenerator.SubtitleEntry> updatedSubtitles = subtitleAdapter.getSubtitles();
                    for (int i = position; i < updatedSubtitles.size(); i++) {
                        updatedSubtitles.get(i).setNumber(i + 1);
                    }
                    subtitleAdapter.notifyItemRangeChanged(position, updatedSubtitles.size() - position);
                })
                .setNegativeButton("No", null)
                .show();
    }

    private void startSelectionMode(int position) {
        if (actionMode == null) {
            actionMode = startSupportActionMode(this);
        }
        subtitleAdapter.setSelectionMode(true);
        subtitleAdapter.toggleSelection(position);
    }


    @Override
    public boolean onCreateActionMode(ActionMode mode, Menu menu) {
        MenuInflater inflater = mode.getMenuInflater();
        inflater.inflate(R.menu.selection_menu, menu);
        return true;
    }

    @Override
    public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
        return false;
    }

    @Override
    public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_merge) {
            mergeSelectedSubtitles();
            mode.finish();
            return true;
        } else if (id == R.id.action_delete) {
            deleteSelectedSubtitles();
            mode.finish();
            return true;
        }
        return false;
    }

    @Override
    public void onDestroyActionMode(ActionMode mode) {
        actionMode = null;
        subtitleAdapter.setSelectionMode(false);
    }

    private void mergeSelectedSubtitles() {
        Set<Integer> selectedPositions = subtitleAdapter.getSelectedPositions();
        if (selectedPositions.size() < 2) {
            Toast.makeText(this, "Select at least two subtitles to merge", Toast.LENGTH_SHORT).show();
            return;
        }

        List<Integer> sortedPositions = new ArrayList<>(selectedPositions);
        Collections.sort(sortedPositions);

        int startPosition = sortedPositions.get(0);
        int endPosition = sortedPositions.get(sortedPositions.size() - 1);

        SubtitleGenerator.SubtitleEntry mergedEntry = subtitleAdapter.getSubtitles().get(startPosition);
        StringBuilder mergedText = new StringBuilder(mergedEntry.getText());

        for (int i = startPosition + 1; i <= endPosition; i++) {
            SubtitleGenerator.SubtitleEntry entry = subtitleAdapter.getSubtitles().get(i);
            mergedText.append(" ").append(entry.getText());
        }

        mergedEntry.setText(mergedText.toString());
        mergedEntry.setEndTime(subtitleAdapter.getSubtitles().get(endPosition).getEndTime());

        for (int i = endPosition; i > startPosition; i--) {
            subtitleAdapter.deleteSubtitle(i);
        }

        subtitleAdapter.notifyDataSetChanged();

        List<SubtitleGenerator.SubtitleEntry> updatedSubtitles = subtitleAdapter.getSubtitles();
        for (int i = startPosition; i < updatedSubtitles.size(); i++) {
            updatedSubtitles.get(i).setNumber(i + 1);
        }
        subtitleAdapter.notifyItemRangeChanged(startPosition, updatedSubtitles.size() - startPosition);
    }

    private void deleteSelectedSubtitles() {
        Set<Integer> selectedPositions = subtitleAdapter.getSelectedPositions();
        List<Integer> sortedPositions = new ArrayList<>(selectedPositions);
        Collections.sort(sortedPositions, Collections.reverseOrder());

        for (int position : sortedPositions) {
            subtitleAdapter.deleteSubtitle(position);
        }

        subtitleAdapter.notifyDataSetChanged();
    }
}