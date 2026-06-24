package com.serhat.autosub.ui.preview;

import android.Manifest;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.serhat.autosub.R;
import com.serhat.autosub.core.DebugLog;
import com.serhat.autosub.databinding.FragmentPreviewBinding;
import com.serhat.autosub.exports.ExportFileActions;
import com.serhat.autosub.exports.ExportFolderDialog;
import com.serhat.autosub.exports.HardSubtitleExportSettings;
import com.serhat.autosub.queue.QueueItem;
import com.serhat.autosub.subtitles.SubtitleGenerator;
import com.serhat.autosub.subtitles.WordTiming;
import com.serhat.autosub.ui.common.AppOptionDialog;
import com.serhat.autosub.ui.main.MainViewModel;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class PreviewFragment extends Fragment implements ActionMode.Callback {

    private static final long UPDATE_INTERVAL = 100;
    private static final String STATE_SHORTS_VIDEO_COLLAPSED = "shorts_video_collapsed";
    private static final int NORMAL_PLAYER_HEIGHT_DP = 210;
    private static final int SHORTS_PLAYER_EXPANDED_HEIGHT_DP = 560;
    private static final int SHORTS_PLAYER_COLLAPSED_HEIGHT_DP = 280;

    private MainViewModel viewModel;

    private final ActivityResultLauncher<String[]> pickVideoLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    try {
                        requireContext().getContentResolver().takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Exception e) {
                        DebugLog.e("PreviewFragment", "Failed to take persistable URI permission", e);
                    }
                    QueueItem selectedItem = viewModel.getSelectedQueueItem().getValue();
                    if (selectedItem != null) {
                        viewModel.updateQueueItemVideoUri(selectedItem, uri);
                    }
                }
            });

    private FragmentPreviewBinding binding;
    private SubtitleAdapter subtitleAdapter;
    private ExoPlayer player;
    private android.net.Uri loadedPlayerMediaUri;
    private ActionMode actionMode;

    private int currentHighlightedPosition = -1;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private float shortsDragStartRawX;
    private float shortsDragStartRawY;
    private float shortsDragStartViewX;
    private float shortsDragStartViewY;
    private boolean draggingShortsCaption = false;
    private ScaleGestureDetector shortsScaleGestureDetector;
    private float baseShortsCaptionSize = 30f;
    private boolean shortsVideoCollapsedForEditing = false;
    private boolean resumeShortsPlaybackAfterDrag = false;

    private enum PermissionOperation { EXPORT, NONE }
    private PermissionOperation pendingOperation = PermissionOperation.NONE;

    private interface SubtitleLayerChoiceCallback {
        void onChosen(SubtitleGenerator.SubtitleLayerMode layerMode);
    }

    private final Runnable updateHighlightRunnable = new Runnable() {
        @Override
        public void run() {
            if (player != null && player.isPlaying()) {
                long position = player.getCurrentPosition();
                updateHighlightedSubtitle(position);
                updateShortsCaption(position);
                handler.postDelayed(this, UPDATE_INTERVAL);
            }
        }
    };

    private final ActivityResultLauncher<String> requestPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            result -> {
                if (result) {
                    executePendingPermissionOperation();
                } else if (ActivityCompat.shouldShowRequestPermissionRationale(requireActivity(), Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                    new MaterialAlertDialogBuilder(requireContext())
                            .setTitle("AutoSub needs permission")
                            .setMessage("This app requires WRITE_EXTERNAL_STORAGE permission to save files to permanent storage")
                            .setPositiveButton("Give Permission", (dialog, which) -> {
                                executePendingPermissionOperation();
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                }
            }
    );

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentPreviewBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);
        if (savedInstanceState != null) {
            shortsVideoCollapsedForEditing = savedInstanceState.getBoolean(STATE_SHORTS_VIDEO_COLLAPSED, false);
        }

        setHasOptionsMenu(true);
        androidx.appcompat.app.ActionBar actionBar = ((androidx.appcompat.app.AppCompatActivity) requireActivity()).getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setDisplayShowHomeEnabled(true);
        }

        setupPlayer();
        setupAdapter();
        setupActions();
        setupShortsCaptionDragging();
        observeViewModel();
    }

    private void setupPlayer() {
        player = new ExoPlayer.Builder(requireContext()).build();
        binding.playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY) startSubtitleHighlightUpdate();
                else stopSubtitleHighlightUpdate();
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying) startSubtitleHighlightUpdate();
                else stopSubtitleHighlightUpdate();
            }

            @Override
            public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
                updateHighlightedSubtitle(newPosition.positionMs);
                updateShortsCaption(newPosition.positionMs);
            }
        });
    }

    private void setupAdapter() {
        subtitleAdapter = new SubtitleAdapter();
        subtitleAdapter.setOnSubtitleClickListener(this::showEditSubtitleDialog);
        subtitleAdapter.setOnPlayClickListener(this::seekToTime);
        subtitleAdapter.setOnDeleteClickListener(this::deleteSubtitle);
        subtitleAdapter.setOnItemLongClickListener(this::startSelectionMode);

        binding.recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recyclerView.setItemAnimator(null);
        binding.recyclerView.setAdapter(subtitleAdapter);
    }

    private void setupActions() {
        binding.exportVideoBT.setOnClickListener(v -> requestExportOptions());
        binding.adjustPositionBT.setOnClickListener(v -> showSubtitleAdjustMode());
        binding.shortsHeightToggleBT.setOnClickListener(v -> {
            shortsVideoCollapsedForEditing = !shortsVideoCollapsedForEditing;
            updatePlayerHeight(Boolean.TRUE.equals(viewModel.getShortsPreviewMode().getValue()));
        });
        binding.reselectVideoBT.setOnClickListener(v -> {
            pickVideoLauncher.launch(new String[]{"video/*"});
        });
    }

    private void observeViewModel() {
        viewModel.getCurrentVideoUri().observe(getViewLifecycleOwner(), uri -> {
            if (uri != null) {
                if (isUriAccessible(uri)) {
                    preparePlayerMediaIfChanged(uri);
                    binding.playerFrame.setVisibility(View.VISIBLE);
                    binding.fallbackWarningLayout.setVisibility(View.GONE);
                } else {
                    QueueItem selectedItem = viewModel.getSelectedQueueItem().getValue();
                    String audioPath = selectedItem != null ? selectedItem.getAudioPath() : "";
                    java.io.File audioFile = new java.io.File(audioPath);
                    if (!audioPath.isEmpty() && audioFile.exists()) {
                        preparePlayerMediaIfChanged(android.net.Uri.fromFile(audioFile));
                        binding.playerFrame.setVisibility(View.VISIBLE);
                        
                        binding.fallbackWarningLayout.setVisibility(View.VISIBLE);
                        binding.fallbackWarningTV.setText("Video source inaccessible (app restarted). Playing extracted audio fallback.");
                    } else {
                        binding.playerFrame.setVisibility(View.GONE);
                        binding.fallbackWarningLayout.setVisibility(View.VISIBLE);
                        binding.fallbackWarningTV.setText("Video source inaccessible (app restarted). Please select the video again.");
                    }
                }
            } else {
                loadedPlayerMediaUri = null;
                player.clearMediaItems();
                binding.playerFrame.setVisibility(View.GONE);
                binding.fallbackWarningLayout.setVisibility(View.GONE);
            }
        });

        viewModel.getSubtitleEntries().observe(getViewLifecycleOwner(), entries -> {
            subtitleAdapter.setSubtitles(entries);
            boolean hasSubtitles = entries != null && !entries.isEmpty();
            binding.recyclerView.setVisibility(hasSubtitles ? View.VISIBLE : View.GONE);
            Boolean shortsMode = viewModel.getShortsPreviewMode().getValue();
            binding.adjustPositionBT.setVisibility(hasSubtitles && !Boolean.TRUE.equals(shortsMode) ? View.VISIBLE : View.GONE);
            binding.exportVideoBT.setVisibility(hasSubtitles ? View.VISIBLE : View.GONE);
            
            if (entries != null) {
                updateHighlightedSubtitle(player.getCurrentPosition());
                updateShortsCaption(player.getCurrentPosition());
            }
        });

        viewModel.getShortsPreviewMode().observe(getViewLifecycleOwner(), isShorts -> {
            updatePlayerHeight(Boolean.TRUE.equals(isShorts));
            List<SubtitleGenerator.SubtitleEntry> entries = viewModel.getSubtitleEntries().getValue();
            boolean hasSubtitles = entries != null && !entries.isEmpty();
            binding.adjustPositionBT.setVisibility(hasSubtitles && !Boolean.TRUE.equals(isShorts) ? View.VISIBLE : View.GONE);
            binding.shortsOverlay.post(() -> {
                applyShortsCaptionPosition();
                updateShortsCaption(player.getCurrentPosition());
            });
        });

        viewModel.getShortsCaptionSize().observe(getViewLifecycleOwner(), size -> {
            baseShortsCaptionSize = size == null ? 30f : size;
            applyShortsCaptionScale();
            binding.shortsWordTV.post(this::applyShortsCaptionPosition);
        });

        viewModel.getShortsCaptionScale().observe(getViewLifecycleOwner(), scale -> {
            applyShortsCaptionScale();
            binding.shortsWordTV.post(this::applyShortsCaptionPosition);
        });

        viewModel.getShortsCaptionX().observe(getViewLifecycleOwner(), x -> {
            binding.shortsOverlay.post(this::applyShortsCaptionPosition);
        });

        viewModel.getShortsCaptionY().observe(getViewLifecycleOwner(), y -> {
            binding.shortsOverlay.post(this::applyShortsCaptionPosition);
        });
    }

    private void preparePlayerMediaIfChanged(android.net.Uri mediaUri) {
        if (mediaUri == null || mediaUri.equals(loadedPlayerMediaUri)) return;
        loadedPlayerMediaUri = mediaUri;
        player.setMediaItem(MediaItem.fromUri(mediaUri));
        player.prepare();
        player.play();
    }

    private void updatePlayerHeight(boolean isShorts) {
        int heightDp = NORMAL_PLAYER_HEIGHT_DP;
        if (isShorts) {
            heightDp = shortsVideoCollapsedForEditing
                    ? SHORTS_PLAYER_COLLAPSED_HEIGHT_DP
                    : SHORTS_PLAYER_EXPANDED_HEIGHT_DP;
        }
        ViewGroup.LayoutParams params = binding.playerFrame.getLayoutParams();
        params.height = dpToPx(heightDp);
        binding.playerFrame.setLayoutParams(params);
        updateShortsHeightToggle(isShorts);
        binding.shortsOverlay.post(() -> {
            applyShortsCaptionPosition();
            if (player != null) {
                updateShortsCaption(player.getCurrentPosition());
            }
        });
    }

    private void updateShortsHeightToggle(boolean isShorts) {
        binding.shortsHeightToggleBT.setVisibility(isShorts ? View.VISIBLE : View.GONE);
        binding.shortsHeightToggleBT.setImageResource(shortsVideoCollapsedForEditing
                ? R.drawable.ri_arrow_down_s_line
                : R.drawable.ri_arrow_up_s_line);
        binding.shortsHeightToggleBT.setContentDescription(shortsVideoCollapsedForEditing
                ? "Expand shorts video preview"
                : "Shrink video for subtitle editing");
    }

    private void setupShortsCaptionDragging() {
        shortsScaleGestureDetector = new ScaleGestureDetector(requireContext(), new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                Float currentScale = viewModel.getShortsCaptionScale().getValue();
                viewModel.setShortsCaptionScale((currentScale == null ? 1f : currentScale) * detector.getScaleFactor());
                binding.shortsWordTV.post(() -> {
                    applyShortsCaptionPosition();
                    persistShortsCaptionPosition();
                });
                return true;
            }
        });

        binding.shortsWordTV.setOnTouchListener((view, event) -> {
            Boolean shortsMode = viewModel.getShortsPreviewMode().getValue();
            if (!Boolean.TRUE.equals(shortsMode)) {
                return false;
            }

            shortsScaleGestureDetector.onTouchEvent(event);
            if (event.getPointerCount() > 1 || shortsScaleGestureDetector.isInProgress()) {
                if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    draggingShortsCaption = false;
                    persistShortsCaptionPosition();
                    resumeShortsPlaybackIfNeeded();
                    view.getParent().requestDisallowInterceptTouchEvent(false);
                }
                return true;
            }

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    draggingShortsCaption = true;
                    resumeShortsPlaybackAfterDrag = player != null && player.isPlaying();
                    if (resumeShortsPlaybackAfterDrag) {
                        player.pause();
                    }
                    shortsDragStartRawX = event.getRawX();
                    shortsDragStartRawY = event.getRawY();
                    shortsDragStartViewX = view.getX();
                    shortsDragStartViewY = view.getY();
                    view.getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float nextX = shortsDragStartViewX + event.getRawX() - shortsDragStartRawX;
                    float nextY = shortsDragStartViewY + event.getRawY() - shortsDragStartRawY;
                    moveShortsCaptionTo(nextX, nextY);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    draggingShortsCaption = false;
                    persistShortsCaptionPosition();
                    view.getParent().requestDisallowInterceptTouchEvent(false);
                    resumeShortsPlaybackIfNeeded();
                    return true;
                default:
                    return false;
            }
        });
    }

    private void updateHighlightedSubtitle(long positionMs) {
        List<SubtitleGenerator.SubtitleEntry> entries = viewModel.getSubtitleEntries().getValue();
        if (entries == null) return;
        int newHighlightedPosition = -1;
        for (int i = 0; i < entries.size(); i++) {
            SubtitleGenerator.SubtitleEntry entry = entries.get(i);
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

    private void updateShortsCaption(long positionMs) {
        Boolean shortsMode = viewModel.getShortsPreviewMode().getValue();
        List<SubtitleGenerator.SubtitleEntry> entries = viewModel.getSubtitleEntries().getValue();
        if (shortsMode == null || !shortsMode || entries == null || binding.playerFrame.getVisibility() != View.VISIBLE) {
            binding.shortsOverlay.setVisibility(View.GONE);
            binding.shortsTranslationTV.setVisibility(View.GONE);
            return;
        }

        String activeText = "";
        String activeTranslation = "";
        for (SubtitleGenerator.SubtitleEntry entry : entries) {
            long entryStart = parseTime(entry.getStartTime());
            long entryEnd = parseTime(entry.getEndTime());
            if (positionMs >= entryStart && positionMs < entryEnd && entry.hasTranslation()) {
                activeTranslation = entry.getTranslationText();
            }
            for (WordTiming word : entry.getWords()) {
                if (positionMs >= word.getStartMs() && positionMs <= word.getEndMs()) {
                    activeText = word.getWord();
                    break;
                }
            }
            if (!activeText.isEmpty()) break;
        }

        binding.shortsOverlay.setVisibility(activeText.isEmpty() ? View.GONE : View.VISIBLE);
        binding.shortsTranslationTV.setVisibility(activeTranslation.isEmpty() || activeText.isEmpty() ? View.GONE : View.VISIBLE);
        Boolean isUppercase = viewModel.getShortsUppercase().getValue();
        if (isUppercase != null && isUppercase) {
            activeText = activeText.toUpperCase(Locale.getDefault());
        }
        binding.shortsWordTV.setText(activeText);
        binding.shortsTranslationTV.setText(activeTranslation);
        binding.shortsWordTV.post(this::applyShortsCaptionPosition);
    }

    private void applyShortsCaptionScale() {
        Float scale = viewModel.getShortsCaptionScale().getValue();
        binding.shortsWordTV.setTextSize(baseShortsCaptionSize * (scale == null ? 1f : scale));
    }

    private void applyShortsCaptionPosition() {
        if (draggingShortsCaption || binding == null || binding.shortsOverlay.getWidth() == 0 || binding.shortsWordTV.getWidth() == 0) {
            return;
        }
        Float normalizedX = viewModel.getShortsCaptionX().getValue();
        Float normalizedY = viewModel.getShortsCaptionY().getValue();
        float centerX = (normalizedX == null ? 0.5f : normalizedX) * binding.shortsOverlay.getWidth();
        float centerY = (normalizedY == null ? 0.5f : normalizedY) * binding.shortsOverlay.getHeight();
        moveShortsCaptionTo(centerX - binding.shortsWordTV.getWidth() / 2f,
                centerY - binding.shortsWordTV.getHeight() / 2f);
    }

    private void moveShortsCaptionTo(float x, float y) {
        float maxX = Math.max(0f, binding.shortsOverlay.getWidth() - binding.shortsWordTV.getWidth());
        float maxY = Math.max(0f, binding.shortsOverlay.getHeight() - binding.shortsWordTV.getHeight());
        binding.shortsWordTV.setX(Math.max(0f, Math.min(maxX, x)));
        binding.shortsWordTV.setY(Math.max(0f, Math.min(maxY, y)));
    }

    private void persistShortsCaptionPosition() {
        if (binding == null || binding.shortsOverlay.getWidth() == 0 || binding.shortsOverlay.getHeight() == 0) {
            return;
        }
        float centerX = binding.shortsWordTV.getX() + binding.shortsWordTV.getWidth() / 2f;
        float centerY = binding.shortsWordTV.getY() + binding.shortsWordTV.getHeight() / 2f;
        viewModel.setShortsCaptionPosition(centerX / binding.shortsOverlay.getWidth(),
                centerY / binding.shortsOverlay.getHeight());
    }

    private void resumeShortsPlaybackIfNeeded() {
        if (resumeShortsPlaybackAfterDrag && player != null) {
            player.play();
        }
        resumeShortsPlaybackAfterDrag = false;
    }

    private void showSubtitleAdjustMode() {
        android.net.Uri videoUri = viewModel.getCurrentVideoUri().getValue();
        if (videoUri == null || !isUriAccessible(videoUri)) {
            Toast.makeText(requireContext(), "Video is not available for adjustment", Toast.LENGTH_SHORT).show();
            return;
        }

        if (player != null) {
            player.pause();
        }

        Dialog dialog = new Dialog(requireContext());
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        FrameLayout root = new FrameLayout(requireContext());
        root.setBackgroundColor(Color.BLACK);

        PlayerView adjustPlayerView = new PlayerView(requireContext());
        adjustPlayerView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(adjustPlayerView);

        TextView captionView = new TextView(requireContext());
        captionView.setBackgroundResource(R.drawable.shorts_caption_background);
        captionView.setTextColor(Color.WHITE);
        captionView.setTextSize(28f * getLiveDataValue(viewModel.getSubtitleCaptionScale(), 1f));
        captionView.setGravity(Gravity.CENTER);
        captionView.setPadding(dpToPx(18), dpToPx(10), dpToPx(18), dpToPx(10));
        captionView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(captionView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        MaterialButton doneButton = new MaterialButton(requireContext());
        doneButton.setText("Done");
        doneButton.setIconResource(R.drawable.ri_checkbox_circle_line);
        doneButton.setIconPadding(dpToPx(6));
        doneButton.setContentDescription("Done");
        FrameLayout.LayoutParams doneParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dpToPx(48),
                Gravity.TOP | Gravity.END);
        doneParams.setMargins(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16));
        root.addView(doneButton, doneParams);

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        int oldOrientation = requireActivity().getRequestedOrientation();
        int restoreOrientation = oldOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                : oldOrientation;
        requireActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

        ExoPlayer adjustPlayer = new ExoPlayer.Builder(requireContext()).build();
        adjustPlayerView.setPlayer(adjustPlayer);
        long startPosition = player == null ? 0 : player.getCurrentPosition();
        adjustPlayer.setMediaItem(MediaItem.fromUri(videoUri));
        adjustPlayer.prepare();
        adjustPlayer.seekTo(startPosition);
        adjustPlayer.play();

        Handler adjustHandler = new Handler(Looper.getMainLooper());
        boolean[] draggingCaption = new boolean[]{false};
        Runnable[] updateRunnable = new Runnable[1];
        updateRunnable[0] = () -> {
            captionView.setText(findSubtitleTextForPosition(adjustPlayer.getCurrentPosition()));
            if (!draggingCaption[0]) {
                captionView.setTextSize(28f * getLiveDataValue(viewModel.getSubtitleCaptionScale(), 1f));
                captionView.post(() -> applyCaptionPosition(root, captionView,
                        viewModel.getSubtitleCaptionX().getValue(),
                        viewModel.getSubtitleCaptionY().getValue()));
            }
            adjustHandler.postDelayed(updateRunnable[0], UPDATE_INTERVAL);
        };

        setupAdjustCaptionGestures(root, captionView, draggingCaption, adjustPlayer);
        root.post(() -> {
            applyCaptionPosition(root, captionView, viewModel.getSubtitleCaptionX().getValue(), viewModel.getSubtitleCaptionY().getValue());
            updateRunnable[0].run();
        });

        doneButton.setOnClickListener(v -> {
            persistCaptionPosition(root, captionView);
            dialog.dismiss();
        });

        dialog.setOnDismissListener(d -> {
            long adjustedPosition = adjustPlayer.getCurrentPosition();
            persistCaptionPosition(root, captionView);
            adjustHandler.removeCallbacks(updateRunnable[0]);
            adjustPlayer.release();
            requireActivity().setRequestedOrientation(restoreOrientation);
            if (player != null) {
                player.seekTo(adjustedPosition);
                player.play();
            }
        });

        dialog.show();
        Window shownWindow = dialog.getWindow();
        if (shownWindow != null) {
            shownWindow.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
    }

    private void setupAdjustCaptionGestures(FrameLayout overlay, TextView captionView, boolean[] draggingCaption, ExoPlayer adjustPlayer) {
        boolean[] resumePlaybackAfterDrag = new boolean[]{false};
        ScaleGestureDetector scaleGestureDetector = new ScaleGestureDetector(requireContext(), new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                Float currentScale = viewModel.getSubtitleCaptionScale().getValue();
                viewModel.setSubtitleCaptionScale((currentScale == null ? 1f : currentScale) * detector.getScaleFactor());
                captionView.setTextSize(28f * getLiveDataValue(viewModel.getSubtitleCaptionScale(), 1f));
                captionView.post(() -> applyCaptionPosition(overlay, captionView,
                        viewModel.getSubtitleCaptionX().getValue(),
                        viewModel.getSubtitleCaptionY().getValue()));
                return true;
            }
        });

        captionView.setOnTouchListener(new View.OnTouchListener() {
            private float startRawX;
            private float startRawY;
            private float startViewX;
            private float startViewY;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                scaleGestureDetector.onTouchEvent(event);
                if (event.getPointerCount() > 1 || scaleGestureDetector.isInProgress()) {
                    if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                        draggingCaption[0] = false;
                        persistCaptionPosition(overlay, captionView);
                        resumeAdjustPlaybackIfNeeded(adjustPlayer, resumePlaybackAfterDrag);
                    }
                    return true;
                }

                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        draggingCaption[0] = true;
                        resumePlaybackAfterDrag[0] = adjustPlayer != null && adjustPlayer.isPlaying();
                        if (resumePlaybackAfterDrag[0]) {
                            adjustPlayer.pause();
                        }
                        startRawX = event.getRawX();
                        startRawY = event.getRawY();
                        startViewX = view.getX();
                        startViewY = view.getY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        moveCaptionTo(overlay, captionView,
                                startViewX + event.getRawX() - startRawX,
                                startViewY + event.getRawY() - startRawY);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        draggingCaption[0] = false;
                        persistCaptionPosition(overlay, captionView);
                        resumeAdjustPlaybackIfNeeded(adjustPlayer, resumePlaybackAfterDrag);
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void resumeAdjustPlaybackIfNeeded(ExoPlayer adjustPlayer, boolean[] resumePlaybackAfterDrag) {
        if (resumePlaybackAfterDrag[0] && adjustPlayer != null) {
            adjustPlayer.play();
        }
        resumePlaybackAfterDrag[0] = false;
    }

    private String findSubtitleTextForPosition(long positionMs) {
        List<SubtitleGenerator.SubtitleEntry> entries = viewModel.getSubtitleEntries().getValue();
        if (entries == null || entries.isEmpty()) return "";
        for (SubtitleGenerator.SubtitleEntry entry : entries) {
            long startTime = parseTime(entry.getStartTime());
            long endTime = parseTime(entry.getEndTime());
            if (positionMs >= startTime && positionMs < endTime) {
                return entry.getText();
            }
        }
        return entries.get(0).getText();
    }

    private void applyCaptionPosition(FrameLayout overlay, TextView captionView, Float normalizedX, Float normalizedY) {
        if (overlay.getWidth() == 0 || overlay.getHeight() == 0 || captionView.getWidth() == 0 || captionView.getHeight() == 0) {
            return;
        }
        float centerX = (normalizedX == null ? 0.5f : normalizedX) * overlay.getWidth();
        float centerY = (normalizedY == null ? 0.88f : normalizedY) * overlay.getHeight();
        moveCaptionTo(overlay, captionView, centerX - captionView.getWidth() / 2f, centerY - captionView.getHeight() / 2f);
    }

    private void moveCaptionTo(FrameLayout overlay, TextView captionView, float x, float y) {
        float maxX = Math.max(0f, overlay.getWidth() - captionView.getWidth());
        float maxY = Math.max(0f, overlay.getHeight() - captionView.getHeight());
        captionView.setX(Math.max(0f, Math.min(maxX, x)));
        captionView.setY(Math.max(0f, Math.min(maxY, y)));
    }

    private void persistCaptionPosition(FrameLayout overlay, TextView captionView) {
        if (overlay.getWidth() == 0 || overlay.getHeight() == 0) {
            return;
        }
        float centerX = captionView.getX() + captionView.getWidth() / 2f;
        float centerY = captionView.getY() + captionView.getHeight() / 2f;
        viewModel.setSubtitleCaptionPosition(centerX / overlay.getWidth(), centerY / overlay.getHeight());
    }

    private float getLiveDataValue(androidx.lifecycle.LiveData<Float> liveData, float fallback) {
        Float value = liveData.getValue();
        return value == null ? fallback : value;
    }

    private long parseTime(String timeString) {
        String[] parts = timeString.split("[:,]");
        return Long.parseLong(parts[0]) * 3600000L +
                Long.parseLong(parts[1]) * 60000L +
                Long.parseLong(parts[2]) * 1000L +
                Long.parseLong(parts[3]);
    }

    private void seekToTime(long timeMs) {
        if (player != null) {
            player.seekTo(timeMs);
            player.play();
        }
    }

    private void showEditSubtitleDialog(int position, SubtitleGenerator.SubtitleEntry entry) {
        if (entry.hasTranslation()) {
            android.widget.LinearLayout layout = new android.widget.LinearLayout(requireContext());
            layout.setOrientation(android.widget.LinearLayout.VERTICAL);
            final EditText originalInput = new EditText(requireContext());
            originalInput.setHint("Original");
            originalInput.setText(entry.getText());
            final EditText translationInput = new EditText(requireContext());
            translationInput.setHint("Translation");
            translationInput.setText(entry.getTranslationText());
            layout.addView(originalInput);
            layout.addView(translationInput);
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Edit Subtitle")
                    .setView(layout)
                    .setPositiveButton("Save", (dialog, which) -> {
                        viewModel.updateSubtitle(position, originalInput.getText().toString(), translationInput.getText().toString());
                    })
                    .setNegativeButton("Cancel", (dialog, which) -> dialog.cancel())
                    .show();
            return;
        }
        final EditText input = new EditText(requireContext());
        input.setText(entry.getText());
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Edit Subtitle")
                .setView(input)
                .setPositiveButton("Save", (dialog, which) -> {
                    viewModel.updateSubtitle(position, input.getText().toString());
                })
                .setNegativeButton("Cancel", (dialog, which) -> dialog.cancel())
                .show();
    }

    private void deleteSubtitle(int position) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Delete Subtitle")
                .setMessage("Are you sure you want to delete this subtitle?")
                .setPositiveButton("Yes", (dialog, which) -> {
                    viewModel.deleteSubtitle(position);
                })
                .setNegativeButton("No", null)
                .show();
    }

    private void startSelectionMode(int position) {
        if (actionMode == null) {
            actionMode = ((AppCompatActivity) requireActivity()).startSupportActionMode(this);
        }
        subtitleAdapter.setSelectionMode(true);
        subtitleAdapter.toggleSelection(position);
    }

    // --- ActionMode Callback Implementation ---

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
            Set<Integer> selected = subtitleAdapter.getSelectedPositions();
            if (selected.size() < 2) {
                Toast.makeText(requireContext(), "Select at least two subtitles to merge", Toast.LENGTH_SHORT).show();
                return false;
            }
            viewModel.mergeSelectedSubtitles(selected);
            mode.finish();
            return true;
        } else if (id == R.id.action_delete) {
            viewModel.deleteSelectedSubtitles(subtitleAdapter.getSelectedPositions());
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

    // --- Save/Export and Permissions ---

    private void requestExportOptions() {
        if (checkStoragePermission()) {
            showExportOptions();
        } else {
            pendingOperation = PermissionOperation.EXPORT;
            requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
    }

    private boolean checkStoragePermission() {
        return (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R);
    }

    private void executePendingPermissionOperation() {
        if (pendingOperation == PermissionOperation.EXPORT) {
            showExportOptions();
        }
        pendingOperation = PermissionOperation.NONE;
    }

    private void showExportOptions() {
        AppOptionDialog.show(requireContext(),
                "Export",
                "Choose what AutoSub should create from these subtitles.",
                new AppOptionDialog.Option[]{
                        new AppOptionDialog.Option(
                                "SRT subtitle file",
                                "Most common subtitle format. Works with many editors, players, and upload sites."),
                        new AppOptionDialog.Option(
                                "VTT subtitle file",
                                "Web-friendly subtitle format for browsers and HTML video workflows."),
                        new AppOptionDialog.Option(
                                "Soft subtitle video",
                                "Add a subtitle track that can be turned on or off in compatible players."),
                        new AppOptionDialog.Option(
                                "Hard subtitle video",
                                "Burn subtitles into the video image so they show everywhere.")
                }, which -> {
                    if (which == 0) {
                        saveSubtitlesInFormat("srt");
                    } else if (which == 1) {
                        saveSubtitlesInFormat("vtt");
                    } else {
                        boolean hardSubtitles = which == 3;
                        if (!hardSubtitles && shouldAskSoftSubtitleContainer()) {
                            chooseSubtitleLayerMode(this::choosePositionedSoftExportFormat);
                        } else {
                            Runnable proceed = () -> chooseSubtitleLayerMode(layerMode ->
                                    startExport(hardSubtitles, hardSubtitles ? "RobotoRegular" : null, false, layerMode));
                            QueueItem selected = viewModel.getSelectedQueueItem().getValue();
                            if (hardSubtitles) HardSubtitleExportSettings.show(this,
                                    selected == null ? null : selected.getVideoUri(), proceed::run);
                            else proceed.run();
                        }
                    }
                });
    }

    private void saveSubtitlesInFormat(String format) {
        chooseSubtitleLayerMode(layerMode ->
                ExportFolderDialog.show(this, "Save to folder", outputDir ->
                        executeSaveSubtitles(format, outputDir, layerMode)));
    }

    private void executeSaveSubtitles(String format, File outputDir, SubtitleGenerator.SubtitleLayerMode layerMode) {
        binding.progressBar.setVisibility(View.VISIBLE);
        setStatusMessage("Saving subtitles in " + format.toUpperCase() + " format...");
        viewModel.saveSubtitlesInFormat(format, outputDir, layerMode, new SubtitleGenerator.SubtitleSaveCallback() {
            @Override
            public void onSubtitlesSaved(String filePath) {
                if (!isAdded()) return;
                binding.progressBar.setVisibility(View.GONE);
                setStatusMessage(format.toUpperCase() + " subtitles saved: " + filePath);
                ExportFileActions.showExportCompleteDialog(PreviewFragment.this, viewModel, filePath, false);
            }

            @Override
            public void onError(String errorMessage) {
                if (!isAdded()) return;
                binding.progressBar.setVisibility(View.GONE);
                if (errorMessage != null && errorMessage.startsWith("Already exported subtitles")) {
                    showAlreadyExistsDialog(errorMessage, false, () -> {
                        String prefix = "Already exported subtitles for this video and model: ";
                        String filename = errorMessage.substring(prefix.length());
                        File targetFile = new File(outputDir, filename);
                        if (targetFile.exists()) {
                            targetFile.delete();
                        }
                        executeSaveSubtitles(format, outputDir, layerMode);
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
                    setStatusMessage("Error saving subtitles: " + errorMessage);
                }
            }
        });
    }

    private boolean shouldAskSoftSubtitleContainer() {
        if (Boolean.TRUE.equals(viewModel.getShortsPreviewMode().getValue())) {
            return true;
        }
        QueueItem item = viewModel.getSelectedQueueItem().getValue();
        return item != null && item.isSubtitleCaptionPositionAdjusted();
    }

    private void choosePositionedSoftExportFormat(SubtitleGenerator.SubtitleLayerMode layerMode) {
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
                }, which -> startExport(false, null, which == 1, layerMode));
    }

    private void startExport(boolean burnSubtitles, String fontName, boolean forceMp4SoftSubtitles,
                             SubtitleGenerator.SubtitleLayerMode layerMode) {
        ExportFolderDialog.show(this, "Export to folder", outputDir -> {
            executeVideoExport(burnSubtitles, fontName, forceMp4SoftSubtitles, outputDir, layerMode);
        });
    }

    private void executeVideoExport(boolean burnSubtitles, String fontName, boolean forceMp4SoftSubtitles,
                                    File outputDir, SubtitleGenerator.SubtitleLayerMode layerMode) {
        binding.progressBar.setVisibility(View.VISIBLE);
        binding.progressBar.setIndeterminate(true);
        setStatusMessage("Exporting video with " + (burnSubtitles ? "hard" : "soft") + " subtitles...");
        viewModel.exportVideoWithSubtitles(burnSubtitles, fontName, forceMp4SoftSubtitles, outputDir, layerMode,
                new SubtitleGenerator.VideoExportCallback() {
            @Override
            public void onVideoExported(String filePath) {
                if (!isAdded()) return;
                binding.progressBar.setVisibility(View.GONE);
                setStatusMessage("Video exported: " + filePath);
                ExportFileActions.showExportCompleteDialog(PreviewFragment.this, viewModel, filePath, true);
            }

            @Override
            public void onError(String errorMessage) {
                if (!isAdded()) return;
                binding.progressBar.setVisibility(View.GONE);
                if (errorMessage != null && errorMessage.startsWith("Already exported this video with this model")) {
                    showAlreadyExistsDialog(errorMessage, true, () -> {
                        String prefix = "Already exported this video with this model: ";
                        String filename = errorMessage.substring(prefix.length());
                        File targetFile = new File(outputDir, filename);
                        if (targetFile.exists()) {
                            targetFile.delete();
                        }
                        executeVideoExport(burnSubtitles, fontName, forceMp4SoftSubtitles, outputDir, layerMode);
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
                    setStatusMessage("Error exporting video: " + errorMessage);
                }
            }

            @Override
            public void onProgressUpdate(int progress) {
                if (!isAdded()) return;
                if (progress < 0) {
                    binding.progressBar.setIndeterminate(true);
                } else {
                    binding.progressBar.setIndeterminate(false);
                    binding.progressBar.setProgress(progress);
                }
            }
        });
    }

    private void chooseSubtitleLayerMode(SubtitleLayerChoiceCallback callback) {
        List<SubtitleGenerator.SubtitleEntry> entries = viewModel.getSubtitleEntries().getValue();
        if (!SubtitleGenerator.hasTranslatedSubtitles(entries)) {
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

    private void setStatusMessage(String message) {
        binding.statusRow.setVisibility(View.VISIBLE);
        binding.statusTV.setText(message);
    }

    // --- Helpers ---

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void startSubtitleHighlightUpdate() {
        handler.removeCallbacks(updateHighlightRunnable);
        handler.post(updateHighlightRunnable);
    }

    private void stopSubtitleHighlightUpdate() {
        handler.removeCallbacks(updateHighlightRunnable);
    }

    @Override
    public void onStart() {
        super.onStart();
        if (player != null && player.getPlaybackState() == Player.STATE_READY) {
            startSubtitleHighlightUpdate();
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putBoolean(STATE_SHORTS_VIDEO_COLLAPSED, shortsVideoCollapsedForEditing);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onStop() {
        stopSubtitleHighlightUpdate();
        if (player != null) {
            player.pause();
        }
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        stopSubtitleHighlightUpdate();
        if (player != null) {
            player.release();
            player = null;
        }
        if (actionMode != null) {
            actionMode.finish();
        }
        
        androidx.appcompat.app.ActionBar actionBar = ((androidx.appcompat.app.AppCompatActivity) requireActivity()).getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(false);
            actionBar.setDisplayShowHomeEnabled(false);
        }

        super.onDestroyView();
        binding = null;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull android.view.MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            getParentFragmentManager().popBackStack();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private boolean isUriAccessible(android.net.Uri uri) {
        if (uri == null) return false;
        try {
            java.io.InputStream in = requireContext().getContentResolver().openInputStream(uri);
            if (in != null) {
                in.close();
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}
