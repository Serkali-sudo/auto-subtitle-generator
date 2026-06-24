package com.serhat.autosub.ui.main;

import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.view.Window;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;

import com.serhat.autosub.R;
import com.serhat.autosub.core.DebugLog;
import com.serhat.autosub.databinding.ActivityMainBinding;
import com.serhat.autosub.exports.ExportsFragment;
import com.serhat.autosub.models.ModelsFragment;
import com.serhat.autosub.shorts.ShortsReviewFragment;
import com.serhat.autosub.ui.common.AppOptionDialog;
import com.serhat.autosub.ui.generate.GenerateFragment;
import com.serhat.autosub.ui.preview.PreviewFragment;
import com.serhat.autosub.ui.settings.SettingsFragment;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int VAD_PROMPT_THRESHOLD_MINUTES = 10;

    private ActivityMainBinding binding;
    private MainViewModel viewModel;

    private final ActivityResultLauncher<String[]> pickMultipleMedia =
            registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
                if (uris != null && !uris.isEmpty()) {
                    for (Uri uri : uris) {
                        try {
                            getContentResolver().takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (Exception e) {
                            DebugLog.e("MainActivity", "Failed to take persistable URI permission", e);
                        }
                    }
                    addVideosToQueue(uris);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setupEdgeToEdge();
        setSupportActionBar(binding.toolbar);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }

        viewModel = new ViewModelProvider(this).get(MainViewModel.class);

        setupNavigation();
        observeViewModel();

        // Initialize speech model load on startup
        viewModel.initializeSelectedModel();

        // Default screen
        if (savedInstanceState == null) {
            viewModel.setActiveNavigationTab(R.id.nav_generate);
        }
    }

    private void setupEdgeToEdge() {
        Window window = getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }

        boolean lightBars = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                != Configuration.UI_MODE_NIGHT_YES;
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, window.getDecorView());
        controller.setAppearanceLightStatusBars(lightBars);
        controller.setAppearanceLightNavigationBars(lightBars);

        applySystemBarInsets();
    }

    private void applySystemBarInsets() {
        final int toolbarInitialHeight = binding.toolbar.getLayoutParams().height;
        final int toolbarInitialPaddingLeft = binding.toolbar.getPaddingLeft();
        final int toolbarInitialPaddingTop = binding.toolbar.getPaddingTop();
        final int toolbarInitialPaddingRight = binding.toolbar.getPaddingRight();
        final int toolbarInitialPaddingBottom = binding.toolbar.getPaddingBottom();

        final int bottomNavInitialPaddingLeft = binding.bottomNavigation.getPaddingLeft();
        final int bottomNavInitialPaddingTop = binding.bottomNavigation.getPaddingTop();
        final int bottomNavInitialPaddingRight = binding.bottomNavigation.getPaddingRight();
        final int bottomNavInitialPaddingBottom = binding.bottomNavigation.getPaddingBottom();

        final ViewGroup.MarginLayoutParams contentInitialParams =
                (ViewGroup.MarginLayoutParams) binding.contentHost.getLayoutParams();
        final int contentInitialLeftMargin = contentInitialParams.leftMargin;
        final int contentInitialRightMargin = contentInitialParams.rightMargin;

        ViewCompat.setOnApplyWindowInsetsListener(binding.main, (view, windowInsets) -> {
            Insets systemBars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());

            ViewGroup.LayoutParams toolbarParams = binding.toolbar.getLayoutParams();
            toolbarParams.height = toolbarInitialHeight + systemBars.top;
            binding.toolbar.setLayoutParams(toolbarParams);
            binding.toolbar.setPadding(
                    toolbarInitialPaddingLeft + systemBars.left,
                    toolbarInitialPaddingTop + systemBars.top,
                    toolbarInitialPaddingRight + systemBars.right,
                    toolbarInitialPaddingBottom);

            binding.bottomNavigation.setPadding(
                    bottomNavInitialPaddingLeft + systemBars.left,
                    bottomNavInitialPaddingTop,
                    bottomNavInitialPaddingRight + systemBars.right,
                    bottomNavInitialPaddingBottom + systemBars.bottom);

            ViewGroup.MarginLayoutParams contentParams =
                    (ViewGroup.MarginLayoutParams) binding.contentHost.getLayoutParams();
            contentParams.leftMargin = contentInitialLeftMargin + systemBars.left;
            contentParams.rightMargin = contentInitialRightMargin + systemBars.right;
            binding.contentHost.setLayoutParams(contentParams);

            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(binding.main);
    }

    private void setupNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (viewModel.getActiveNavigationTab().getValue() != id) {
                viewModel.setActiveNavigationTab(id);
            }
            return true;
        });
    }

    private void observeViewModel() {
        viewModel.getActiveNavigationTab().observe(this, id -> {
            binding.bottomNavigation.setSelectedItemId(id);
            navigateToTab(id);
        });

        viewModel.getNavigateToPreviewTrigger().observe(this, trigger -> {
            if (Boolean.TRUE.equals(trigger)) {
                navigateToPreview();
                viewModel.consumeNavigateToPreviewTrigger();
            }
        });
        viewModel.getNavigateToShortsTrigger().observe(this, trigger -> {
            if (Boolean.TRUE.equals(trigger)) {
                navigateToShortsReview();
                viewModel.consumeNavigateToShortsTrigger();
            }
        });
    }

    private void navigateToTab(int itemId) {
        // Clear back stack when switching primary tabs to keep navigation linear and clean
        getSupportFragmentManager().popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);

        FragmentManager fm = getSupportFragmentManager();
        androidx.fragment.app.FragmentTransaction transaction = fm.beginTransaction();

        String generateTag = "generate";
        String modelsTag = "models";
        String exportsTag = "exports";
        String settingsTag = "settings";

        Fragment generate = fm.findFragmentByTag(generateTag);
        Fragment models = fm.findFragmentByTag(modelsTag);
        Fragment exports = fm.findFragmentByTag(exportsTag);
        Fragment settings = fm.findFragmentByTag(settingsTag);

        // Hide all first
        if (generate != null) transaction.hide(generate);
        if (models != null) transaction.hide(models);
        if (exports != null) transaction.hide(exports);
        if (settings != null) transaction.hide(settings);

        String title;
        if (itemId == R.id.nav_generate) {
            if (generate == null) {
                generate = new GenerateFragment();
                transaction.add(R.id.contentHost, generate, generateTag);
            } else {
                transaction.show(generate);
            }
            title = getString(R.string.app_name);
        } else if (itemId == R.id.nav_models) {
            if (models == null) {
                models = new ModelsFragment();
                transaction.add(R.id.contentHost, models, modelsTag);
            } else {
                transaction.show(models);
            }
            title = "Models";
        } else if (itemId == R.id.nav_exports) {
            if (exports == null) {
                exports = new ExportsFragment();
                transaction.add(R.id.contentHost, exports, exportsTag);
            } else {
                transaction.show(exports);
            }
            title = "Exports";
        } else if (itemId == R.id.nav_settings) {
            if (settings == null) {
                settings = new SettingsFragment();
                transaction.add(R.id.contentHost, settings, settingsTag);
            } else {
                transaction.show(settings);
            }
            title = "Settings";
        } else {
            return;
        }

        binding.toolbar.setTitle(title);
        transaction.commit();
    }

    private void navigateToPreview() {
        binding.toolbar.setTitle("Preview");
        FragmentManager fm = getSupportFragmentManager();
        Fragment activeFragment = null;

        if (fm.findFragmentByTag("generate") != null && !fm.findFragmentByTag("generate").isHidden()) {
            activeFragment = fm.findFragmentByTag("generate");
        } else if (fm.findFragmentByTag("models") != null && !fm.findFragmentByTag("models").isHidden()) {
            activeFragment = fm.findFragmentByTag("models");
        } else if (fm.findFragmentByTag("exports") != null && !fm.findFragmentByTag("exports").isHidden()) {
            activeFragment = fm.findFragmentByTag("exports");
        } else if (fm.findFragmentByTag("settings") != null && !fm.findFragmentByTag("settings").isHidden()) {
            activeFragment = fm.findFragmentByTag("settings");
        }

        androidx.fragment.app.FragmentTransaction transaction = fm.beginTransaction();
        if (activeFragment != null) {
            transaction.hide(activeFragment);
        }
        transaction.add(R.id.contentHost, new PreviewFragment(), "preview")
                .addToBackStack("preview")
                .commit();
    }

    private void navigateToShortsReview() {
        binding.toolbar.setTitle("Shorts Review");
        FragmentManager fm = getSupportFragmentManager();
        Fragment active = fm.findFragmentByTag("generate");
        androidx.fragment.app.FragmentTransaction transaction = fm.beginTransaction();
        if (active != null && !active.isHidden()) transaction.hide(active);
        transaction.add(R.id.contentHost, new ShortsReviewFragment(), "shorts_review")
                .addToBackStack("shorts_review")
                .commit();
    }

    // --- Toolbar Options Menu ---

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.select_video_menu) {
            selectVideo();
            return true;
        } else if (id == R.id.manage_models_menu) {
            viewModel.setActiveNavigationTab(R.id.nav_models);
            return true;
        } else if (id == R.id.open_project_menu) {
            startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, 
                Uri.parse("https://github.com/Serkali-sudo/auto-subtitle-generator")));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void selectVideo() {
        if (Boolean.FALSE.equals(viewModel.getModelReady().getValue())) {
            android.widget.Toast.makeText(this, "Speech model is still loading", android.widget.Toast.LENGTH_SHORT).show();
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

            AppOptionDialog.show(this,
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
        AppOptionDialog.showWithCheckbox(this,
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
        AppOptionDialog.show(this,
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
            retriever.setDataSource(this, uri);
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

    // --- Resolver Helpers ---

    private String getDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
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
            retriever.setDataSource(this, uri);
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
}
