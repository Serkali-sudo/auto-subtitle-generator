package com.serhat.autosub;

import android.graphics.Matrix;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.serhat.autosub.databinding.FragmentShortsReviewBinding;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class ShortsReviewFragment extends Fragment {
    private FragmentShortsReviewBinding binding;
    private MainViewModel viewModel;
    private ShortsCandidateAdapter adapter;
    private ExoPlayer player;
    private ShortsProject project;
    private QueueItem queueItem;
    private ShortsCandidate current;
    private VideoSize videoSize = VideoSize.UNKNOWN;
    private ExecutorService framingExecutor;
    private final AtomicBoolean framingCancelled = new AtomicBoolean(false);
    private volatile boolean framingRunning;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable clipGuard = new Runnable() {
        @Override public void run() {
            if (player != null && current != null && player.getCurrentPosition() >= current.getEndMs()) {
                player.seekTo(current.getStartMs());
            }
            if (current != null && current.hasAutoFraming()) applyCropPreview();
            handler.postDelayed(this, 100);
        }
    };

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                                                  @Nullable Bundle savedInstanceState) {
        binding = FragmentShortsReviewBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setHasOptionsMenu(true);
        viewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);
        framingExecutor = Executors.newSingleThreadExecutor();
        queueItem = viewModel.getSelectedQueueItem().getValue();
        setupToolbar();
        setupPlayer();
        setupList();
        setupControls();
        observe();
    }

    private void setupToolbar() {
        androidx.appcompat.app.ActionBar bar = ((androidx.appcompat.app.AppCompatActivity) requireActivity()).getSupportActionBar();
        if (bar != null) bar.setDisplayHomeAsUpEnabled(true);
    }

    private void setupPlayer() {
        player = new ExoPlayer.Builder(requireContext()).build();
        binding.shortPlayerView.setPlayer(player);
        if (queueItem != null) {
            player.setMediaItem(MediaItem.fromUri(queueItem.getVideoUri()));
            player.prepare();
        }
        player.addListener(new Player.Listener() {
            @Override public void onVideoSizeChanged(VideoSize size) {
                videoSize = size;
                applyCropPreview();
            }
        });
        handler.post(clipGuard);
    }

    private void setupList() {
        adapter = new ShortsCandidateAdapter();
        adapter.setListener(new ShortsCandidateAdapter.Listener() {
            @Override public void onPreview(ShortsCandidate candidate) { preview(candidate); }
            @Override public void onEdit(ShortsCandidate candidate) { editRange(candidate); }
            @Override public void onChanged() { persist(); }
        });
        binding.candidatesRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.candidatesRecyclerView.setAdapter(adapter);
    }

    private void setupControls() {
        binding.cropSlider.addOnChangeListener((slider, value, fromUser) -> {
            if (current == null) return;
            if (fromUser && current.hasAutoFraming()) current.clearCropKeyframes();
            current.setCropPosition(value);
            binding.cropHintTV.setText(value < 0.4f ? "Frame left" : value > 0.6f ? "Frame right" : "Centered");
            updateAutoFrameButton();
            applyCropPreview();
        });
        binding.cropSlider.addOnSliderTouchListener(new com.google.android.material.slider.Slider.OnSliderTouchListener() {
            @Override public void onStartTrackingTouch(@NonNull com.google.android.material.slider.Slider slider) { }
            @Override public void onStopTrackingTouch(@NonNull com.google.android.material.slider.Slider slider) { persist(); }
        });
        binding.autoFrameBT.setOnClickListener(v -> runAutoFraming());
        binding.removeSilenceSwitch.setOnCheckedChangeListener((button, checked) -> {
            applyRemoveSilenceSetting(checked);
        });
        binding.exportSelectedBT.setOnClickListener(v -> {
            persist();
            Runnable proceed = this::exportSelectedShorts;
            if (hasSelectedBurnedCaptions()) HardSubtitleExportSettings.show(this,
                    queueItem == null ? null : queueItem.getVideoUri(), proceed::run);
            else proceed.run();
        });
    }

    private boolean hasSelectedBurnedCaptions() {
        if (project == null || project.isPhraseMontage() || project.isRemoveSilence()) return false;
        for (ShortsCandidate candidate : project.getCandidates()) {
            if (candidate.isSelected() && candidate.isBurnCaptions()) return true;
        }
        return false;
    }

    private void exportSelectedShorts() {
        ExportSettings.ensureLocationChosen(this, () -> {
            File root = ExportSettings.getExportRoot(requireContext());
            if (!root.exists() && !root.mkdirs()) {
                Toast.makeText(requireContext(), "Could not create the export folder", Toast.LENGTH_LONG).show();
                return;
            }
            if (project != null && project.isPhraseMontage()) {
                viewModel.exportPhraseMontageProject(root, new SubtitleGenerator.VideoExportCallback() {
                    @Override public void onVideoExported(String filePath) {
                        ExportFileActions.showExportCompleteDialog(
                                ShortsReviewFragment.this, viewModel, filePath, true);
                    }

                    @Override public void onError(String errorMessage) {
                        if (isAdded()) Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_LONG).show();
                    }

                    @Override public void onProgressUpdate(int progress) { }
                });
            } else {
                viewModel.exportShorts(root);
            }
        });
    }

    private void observe() {
        viewModel.getShortsProject().observe(getViewLifecycleOwner(), value -> {
            if (value == null) return;
            project = value;
            adapter.setPhraseMontage(project.isPhraseMontage());
            adapter.setSilenceRemoval(project.isRemoveSilence());
            binding.removeSilenceSwitch.setVisibility(project.isPhraseMontage() ? View.GONE : View.VISIBLE);
            binding.removeSilenceSwitch.setOnCheckedChangeListener(null);
            binding.removeSilenceSwitch.setChecked(project.isRemoveSilence());
            binding.removeSilenceSwitch.setOnCheckedChangeListener((button, checked) -> {
                applyRemoveSilenceSetting(checked);
            });
            binding.exportSelectedBT.setText(project.isPhraseMontage()
                    ? "Merge selected"
                    : "Export selected");
            androidx.appcompat.app.ActionBar bar =
                    ((androidx.appcompat.app.AppCompatActivity) requireActivity()).getSupportActionBar();
            if (bar != null) bar.setTitle(project.isPhraseMontage()
                    ? "Phrase Montage Review"
                    : "Shorts Review");
            adapter.submit(project.getCandidates());
            if (current == null && !project.getCandidates().isEmpty()) preview(project.getCandidates().get(0));
        });
        viewModel.getShortsAnalyzing().observe(getViewLifecycleOwner(), active -> {
            binding.shortsProgress.setVisibility(Boolean.TRUE.equals(active) ? View.VISIBLE : View.GONE);
            binding.shortsProgress.setIndeterminate(Boolean.TRUE.equals(active));
        });
        viewModel.getShortsError().observe(getViewLifecycleOwner(), error -> {
            if (error != null && !error.isEmpty()) {
                Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show();
                viewModel.consumeShortsError();
            }
        });
        viewModel.getShortsExportCompletedPath().observe(getViewLifecycleOwner(), filePath -> {
            if (filePath != null && !filePath.isEmpty()) {
                ExportFileActions.showExportCompleteDialog(
                        ShortsReviewFragment.this, viewModel, filePath, true);
                viewModel.consumeShortsExportCompletedPath();
            }
        });
    }

    private void preview(ShortsCandidate candidate) {
        current = candidate;
        binding.currentCandidateTitleTV.setText(candidate.getTitle());
        binding.currentCandidateTimeTV.setText(format(candidate.getStartMs()) + " – " + format(candidate.getEndMs()) +
                " • " + String.format(Locale.US, "%.1fs", candidate.getDurationMs() / 1000f));
        binding.cropSlider.setValue(candidate.getCropPosition());
        binding.cropSlider.setEnabled(!framingRunning);
        updateAutoFrameButton();
        if (player != null) {
            player.seekTo(candidate.getStartMs());
            player.play();
        }
        applyCropPreview();
    }

    @OptIn(markerClass = UnstableApi.class)
    private void applyCropPreview() {
        if (binding == null || current == null || videoSize.width <= 0 || videoSize.height <= 0) return;
        View surface = binding.shortPlayerView.getVideoSurfaceView();
        if (!(surface instanceof TextureView)) return;
        TextureView texture = (TextureView) surface;
        int viewWidth = binding.shortPlayerFrame.getWidth();
        int viewHeight = binding.shortPlayerFrame.getHeight();
        if (viewWidth <= 0 || viewHeight <= 0) {
            binding.shortPlayerFrame.post(this::applyCropPreview);
            return;
        }
        float videoWidth = videoSize.width * videoSize.pixelWidthHeightRatio;
        float videoHeight = videoSize.height;
        float scale = Math.max(viewWidth / videoWidth, viewHeight / videoHeight);
        float scaledWidth = videoWidth * scale;
        float scaledHeight = videoHeight * scale;
        long localMs = player == null ? 0 : Math.max(0, player.getCurrentPosition() - current.getStartMs());
        boolean smooth = viewModel != null && Boolean.TRUE.equals(viewModel.getShortsSmoothAutoFraming().getValue());
        float position = current.getCropPositionAt(localMs, smooth);
        float dx = (viewWidth - scaledWidth) * position;
        float dy = (viewHeight - scaledHeight) / 2f;
        Matrix matrix = new Matrix();
        matrix.setScale(scaledWidth / viewWidth, scaledHeight / viewHeight);
        matrix.postTranslate(dx, dy);
        texture.setTransform(matrix);
    }

    private void runAutoFraming() {
        if (current == null || queueItem == null || framingRunning) return;
        ShortsCandidate candidate = current;
        android.content.Context appContext = requireContext().getApplicationContext();
        framingRunning = true;
        framingCancelled.set(false);
        binding.autoFrameBT.setEnabled(false);
        binding.cropSlider.setEnabled(false);
        binding.shortsProgress.setVisibility(View.VISIBLE);
        binding.shortsProgress.setIndeterminate(false);
        binding.shortsProgress.setProgress(0);
        binding.cropHintTV.setText("Finding the active speaker…");
        framingExecutor.execute(() -> {
            try {
                List<ShortsCropKeyframe> keyframes = new ShortsAutoFramer(appContext)
                        .analyze(queueItem.getVideoUri(), candidate, (percent, message) -> handler.post(() -> {
                            if (binding == null) return;
                            binding.shortsProgress.setProgress(percent);
                            binding.cropHintTV.setText(message);
                        }), framingCancelled::get);
                handler.post(() -> {
                    if (binding == null || framingCancelled.get()) return;
                    candidate.setCropKeyframes(keyframes);
                    framingRunning = false;
                    binding.shortsProgress.setVisibility(View.GONE);
                    persist();
                    adapter.notifyDataSetChanged();
                    updateAutoFrameButton();
                    applyCropPreview();
                    Toast.makeText(requireContext(), "Automatic speaker framing ready", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception error) {
                handler.post(() -> {
                    if (binding == null) return;
                    framingRunning = false;
                    binding.shortsProgress.setVisibility(View.GONE);
                    updateAutoFrameButton();
                    binding.cropSlider.setEnabled(current != null);
                    if (!framingCancelled.get()) Toast.makeText(requireContext(),
                            error.getMessage() == null ? "Automatic framing failed" : error.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void updateAutoFrameButton() {
        if (binding == null) return;
        binding.autoFrameBT.setEnabled(current != null && !framingRunning);
        if (current != null && current.hasAutoFraming()) {
            binding.autoFrameBT.setText("Re-run auto framing");
            binding.cropHintTV.setText("Following the likely active speaker • drag slider for manual framing");
            binding.cropSlider.setEnabled(!framingRunning);
        } else {
            binding.autoFrameBT.setText("Auto frame speaker");
            binding.cropSlider.setEnabled(current != null && !framingRunning);
        }
    }

    private void applyRemoveSilenceSetting(boolean enabled) {
        if (project == null || project.isPhraseMontage()) return;
        project.setRemoveSilence(enabled);
        if (adapter != null) adapter.setSilenceRemoval(enabled);
        if (enabled) {
            for (ShortsCandidate candidate : project.getCandidates()) {
                candidate.setBurnCaptions(false);
            }
            if (adapter != null) adapter.notifyDataSetChanged();
        }
        persist();
    }

    private void editRange(ShortsCandidate candidate) {
        LinearLayout content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, 0, padding, 0);
        EditText start = decimalField("Start seconds", candidate.getStartMs() / 1000d);
        EditText end = decimalField("End seconds", candidate.getEndMs() / 1000d);
        content.addView(start); content.addView(end);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Edit clip boundaries")
                .setMessage(project != null && project.isPhraseMontage()
                        ? "Adjust this occurrence precisely. Selected occurrences are merged in timeline order."
                        : "Boundaries snap to the nearest subtitle. Duration must remain " +
                        project.getMinDurationSeconds() + "–" + project.getMaxDurationSeconds() + " seconds.")
                .setView(content)
                .setPositiveButton("Apply", (dialog, which) -> applyRange(candidate, start, end))
                .setNegativeButton("Cancel", null).show();
    }

    private EditText decimalField(String hint, double value) {
        EditText field = new EditText(requireContext());
        field.setHint(hint);
        field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        field.setText(String.format(Locale.US, "%.3f", value));
        return field;
    }

    private void applyRange(ShortsCandidate candidate, EditText startField, EditText endField) {
        try {
            long wantedStart = Math.round(Double.parseDouble(startField.getText().toString()) * 1000);
            long wantedEnd = Math.round(Double.parseDouble(endField.getText().toString()) * 1000);
            SubtitleGenerator.SubtitleEntry startEntry = nearestEntry(wantedStart, true);
            SubtitleGenerator.SubtitleEntry endEntry = nearestEntry(wantedEnd, false);
            if (startEntry == null || endEntry == null) throw new IllegalArgumentException("No subtitle at that range");
            if (project != null && project.isPhraseMontage()) {
                if (wantedEnd <= wantedStart) throw new IllegalArgumentException("End must be after start");
                candidate.setStartMs(wantedStart);
                candidate.setEndMs(wantedEnd);
                candidate.setStartSubtitleId(startEntry.getNumber());
                candidate.setEndSubtitleId(endEntry.getNumber());
                candidate.clearCropKeyframes();
                persist();
                adapter.notifyDataSetChanged();
                preview(candidate);
                return;
            }
            long start = ShortsTranscriptAnalyzer.parseTimeMs(startEntry.getStartTime());
            long end = ShortsTranscriptAnalyzer.parseTimeMs(endEntry.getEndTime());
            long duration = end - start;
            long minDurationAllowed = Math.max(3000L, project.getMinDurationSeconds() * 1000L - ShortsTranscriptAnalyzer.DURATION_TOLERANCE_MS);
            long maxDurationAllowed = project.getMaxDurationSeconds() * 1000L + ShortsTranscriptAnalyzer.DURATION_TOLERANCE_MS;
            if (end <= start || duration < minDurationAllowed || duration > maxDurationAllowed) {
                throw new IllegalArgumentException("Snapped duration must be " + project.getMinDurationSeconds() + "–" + project.getMaxDurationSeconds() + " seconds");
            }
            candidate.setStartMs(start); candidate.setEndMs(end);
            candidate.setStartSubtitleId(startEntry.getNumber()); candidate.setEndSubtitleId(endEntry.getNumber());
            candidate.clearCropKeyframes();
            persist(); adapter.notifyDataSetChanged(); preview(candidate);
        } catch (Exception e) {
            Toast.makeText(requireContext(), e.getMessage() == null ? "Invalid range" : e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private SubtitleGenerator.SubtitleEntry nearestEntry(long target, boolean useStart) {
        if (queueItem == null) return null;
        SubtitleGenerator.SubtitleEntry best = null;
        long distance = Long.MAX_VALUE;
        for (SubtitleGenerator.SubtitleEntry entry : queueItem.getSubtitles()) {
            long value = ShortsTranscriptAnalyzer.parseTimeMs(useStart ? entry.getStartTime() : entry.getEndTime());
            long delta = Math.abs(value - target);
            if (delta < distance) { distance = delta; best = entry; }
        }
        return best;
    }

    private void persist() { if (project != null) viewModel.saveShortsProject(project); }
    private String format(long ms) { long s = ms / 1000; return String.format(Locale.US, "%02d:%02d", s / 60, s % 60); }

    @Override public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            requireActivity().getSupportFragmentManager().popBackStack();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override public void onDestroyView() {
        framingCancelled.set(true);
        if (framingExecutor != null) framingExecutor.shutdownNow();
        framingExecutor = null;
        handler.removeCallbacks(clipGuard);
        if (player != null) player.release();
        player = null;
        androidx.appcompat.app.ActionBar bar = ((androidx.appcompat.app.AppCompatActivity) requireActivity()).getSupportActionBar();
        if (bar != null) bar.setDisplayHomeAsUpEnabled(false);
        binding = null;
        super.onDestroyView();
    }
}
