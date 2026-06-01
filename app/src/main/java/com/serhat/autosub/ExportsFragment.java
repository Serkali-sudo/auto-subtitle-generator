package com.serhat.autosub;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.serhat.autosub.databinding.FragmentExportsBinding;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class ExportsFragment extends Fragment implements ActionMode.Callback {
    private FragmentExportsBinding binding;
    private ExportedFileAdapter adapter;
    private MainViewModel viewModel;
    private ExportStore exportStore;
    private final List<ExportedFileItem> allItems = new ArrayList<>();
    private File rootDir;
    private File currentDir;
    private ActionMode actionMode;
    private OnBackPressedCallback backCallback;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentExportsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);
        exportStore = new ExportStore(requireContext());
        rootDir = new File(ApplicationPath.applicationPath(requireContext()));
        currentDir = rootDir;
        setupAdapter();
        setupControls();
        setupBackNavigation();
        loadExports();
    }

    @Override
    public void onResume() {
        super.onResume();
        File nextRoot = new File(ApplicationPath.applicationPath(requireContext()));
        if (rootDir == null || !sameFile(rootDir, nextRoot)) {
            rootDir = nextRoot;
            currentDir = rootDir;
        }
        loadExports();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) {
            File nextRoot = new File(ApplicationPath.applicationPath(requireContext()));
            if (rootDir == null || !sameFile(rootDir, nextRoot)) {
                rootDir = nextRoot;
                currentDir = rootDir;
            }
            loadExports();
        }
    }

    private void setupAdapter() {
        adapter = new ExportedFileAdapter();
        adapter.setThumbnailResolver(item -> {
            ExportRecord record = item.getRecord();
            if (record != null) {
                return viewModel.getThumbnailPathForUri(record.getSourceVideoUri());
            }
            return "";
        });
        adapter.setListener(new ExportedFileAdapter.Listener() {
            @Override
            public void onOpen(ExportedFileItem item) {
                if (item.getType() == ExportedFileItem.Type.FOLDER) {
                    openFolder(item.getFile());
                } else {
                    ExportFileActions.openFile(requireContext(), item.getFile());
                }
            }

            @Override
            public void onSelectionChanged() {
                updateActionModeTitle();
            }

            @Override
            public void onDragMove(List<ExportedFileItem> items, ExportedFileItem folder) {
                if (folder != null && folder.getType() == ExportedFileItem.Type.FOLDER) {
                    moveExports(items, folder.getFile());
                }
            }

            @Override
            public void onShare(ExportedFileItem item) {
                ExportFileActions.shareFile(requireContext(), item.getFile());
            }

            @Override
            public void onPlay(ExportedFileItem item) {
                ExportFileActions.playVideo(requireContext(), item.getFile());
            }
        });

        android.content.SharedPreferences prefs = requireContext().getSharedPreferences("autosub_exports_prefs", android.content.Context.MODE_PRIVATE);
        int savedMode = prefs.getInt("exports_layout_mode", ExportedFileAdapter.VIEW_TYPE_LIST);
        adapter.setLayoutMode(savedMode);

        binding.exportsRecyclerView.setAdapter(adapter);
        updateLayoutManager();
    }

    private void updateLayoutManager() {
        boolean isGrid = adapter.getLayoutMode() == ExportedFileAdapter.VIEW_TYPE_GRID;
        if (isGrid) {
            binding.exportsRecyclerView.setLayoutManager(new androidx.recyclerview.widget.GridLayoutManager(requireContext(), 2));
        } else {
            binding.exportsRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        }
        binding.toggleLayoutBT.setIconResource(isGrid ? R.drawable.ri_list_line : R.drawable.ri_grid_line);
    }

    private void setupControls() {
        binding.exportSearchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { applyFilters(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        binding.exportFilterChips.setOnCheckedStateChangeListener((group, checkedIds) -> applyFilters());
        binding.createFolderBT.setOnClickListener(v -> showCreateFolderDialog());
        binding.upFolderBT.setOnClickListener(v -> openParentFolder());

        binding.toggleLayoutBT.setOnClickListener(v -> {
            int currentMode = adapter.getLayoutMode();
            int nextMode = currentMode == ExportedFileAdapter.VIEW_TYPE_LIST
                    ? ExportedFileAdapter.VIEW_TYPE_GRID
                    : ExportedFileAdapter.VIEW_TYPE_LIST;
            adapter.setLayoutMode(nextMode);
            requireContext().getSharedPreferences("autosub_exports_prefs", android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putInt("exports_layout_mode", nextMode)
                    .apply();
            updateLayoutManager();
        });
    }

    private void setupBackNavigation() {
        backCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                openParentFolder();
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), backCallback);
    }

    private void loadExports() {
        allItems.clear();
        if (rootDir == null || (!rootDir.isDirectory() && !rootDir.mkdirs())) {
            applyFilters();
            return;
        }
        exportStore.addFolder(rootDir, rootDir);
        if (currentDir == null || !isInsideRoot(currentDir) || !currentDir.isDirectory()) {
            currentDir = rootDir;
        }
        exportStore.addFolder(currentDir, rootDir);
        for (ExportRecord record : exportStore.getChildren(currentDir)) {
            allItems.add(new ExportedFileItem(record, rootDir));
        }
        Collections.sort(allItems, Comparator
                .comparing((ExportedFileItem item) -> item.getType() != ExportedFileItem.Type.FOLDER)
                .thenComparing((ExportedFileItem item) -> -item.getFile().lastModified()));
        applyFilters();
    }

    private void applyFilters() {
        String query = binding.exportSearchInput.getText() == null
                ? ""
                : binding.exportSearchInput.getText().toString().trim().toLowerCase(Locale.US);
        int filterId = binding.exportFilterChips.getCheckedChipId();
        List<ExportedFileItem> filtered = new ArrayList<>();
        int videoCount = 0;
        int subtitleCount = 0;
        int folderCount = 0;
        for (ExportedFileItem item : allItems) {
            if (item.getType() == ExportedFileItem.Type.VIDEO) videoCount++;
            if (item.getType() == ExportedFileItem.Type.SUBTITLE) subtitleCount++;
            if (item.getType() == ExportedFileItem.Type.FOLDER) folderCount++;
            if (!item.matches(query)) {
                continue;
            }
            if (filterId == R.id.filterExportsVideosChip && item.getType() != ExportedFileItem.Type.VIDEO) {
                continue;
            }
            if (filterId == R.id.filterExportsSubtitlesChip && item.getType() != ExportedFileItem.Type.SUBTITLE) {
                continue;
            }
            if (filterId == R.id.filterExportsFoldersChip && item.getType() != ExportedFileItem.Type.FOLDER) {
                continue;
            }
            filtered.add(item);
        }
        adapter.submit(filtered);
        updateActionModeTitle();
        binding.exportEmptyTV.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
        binding.currentFolderTV.setText(currentFolderLabel());
        binding.upFolderBT.setVisibility(isRootFolder() ? View.GONE : View.VISIBLE);
        if (backCallback != null) {
            backCallback.setEnabled(!isRootFolder());
        }
        binding.exportSummaryTV.setText(videoCount + " videos - " + subtitleCount
                + " subtitles - " + folderCount + " folders");
    }

    private void openFolder(File folder) {
        if (folder == null || !folder.isDirectory() || !isInsideRoot(folder)) {
            Toast.makeText(requireContext(), "Folder is not available", Toast.LENGTH_SHORT).show();
            return;
        }
        finishSelectionMode();
        currentDir = folder;
        loadExports();
    }

    private void openParentFolder() {
        if (isRootFolder()) {
            return;
        }
        File parent = currentDir.getParentFile();
        if (parent != null && isInsideRoot(parent)) {
            openFolder(parent);
        }
    }

    private boolean isRootFolder() {
        try {
            return currentDir != null && rootDir != null
                    && currentDir.getCanonicalPath().equals(rootDir.getCanonicalPath());
        } catch (IOException e) {
            return true;
        }
    }

    private boolean isInsideRoot(File file) {
        try {
            String rootPath = rootDir.getCanonicalPath();
            String filePath = file.getCanonicalPath();
            return filePath.equals(rootPath) || filePath.startsWith(rootPath + File.separator);
        } catch (IOException e) {
            return false;
        }
    }

    private boolean sameFile(File first, File second) {
        try {
            return first.getCanonicalPath().equals(second.getCanonicalPath());
        } catch (IOException e) {
            return first.getAbsolutePath().equals(second.getAbsolutePath());
        }
    }

    private String currentFolderLabel() {
        if (isRootFolder()) {
            return "AutoSub";
        }
        try {
            String rootPath = rootDir.getCanonicalPath();
            String folderPath = currentDir.getCanonicalPath();
            String relative = folderPath.substring(rootPath.length());
            while (relative.startsWith(File.separator)) {
                relative = relative.substring(1);
            }
            return "AutoSub / " + relative.replace(File.separatorChar, '/');
        } catch (IOException e) {
            return currentDir == null ? "AutoSub" : currentDir.getName();
        }
    }

    private void showCreateFolderDialog() {
        EditText input = new EditText(requireContext());
        input.setSingleLine(true);
        input.setHint("Folder name");
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        input.setPadding(padding, padding / 2, padding, padding / 2);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Create folder")
                .setView(input)
                .setPositiveButton("Create", (dialog, which) -> createFolder(input.getText().toString()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void createFolder(String rawName) {
        File folder = ExportFolderDialog.createFolder(requireContext(), currentDir, rawName);
        if (folder != null) {
            exportStore.addFolder(folder, rootDir);
            Toast.makeText(requireContext(), "Folder created", Toast.LENGTH_SHORT).show();
            loadExports();
        }
    }

    private void showMoveDialog(List<ExportedFileItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        ExportFolderDialog.show(this, "Move to folder", folder -> moveExports(items, folder));
    }

    private void moveExports(List<ExportedFileItem> items, File destinationFolder) {
        int moved = 0;
        for (ExportedFileItem item : items) {
            if (moveExport(item.getFile(), destinationFolder)) {
                moved++;
            }
        }
        if (moved > 0) {
            Toast.makeText(requireContext(), "Moved " + moved + (moved == 1 ? " file" : " files"), Toast.LENGTH_SHORT).show();
            loadExports();
            finishSelectionMode();
        }
    }

    private boolean moveExport(File source, File destinationFolder) {
        if (source == null || !source.isFile()) {
            Toast.makeText(requireContext(), "File is not available", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (destinationFolder == null || (!destinationFolder.isDirectory() && !destinationFolder.mkdirs())) {
            Toast.makeText(requireContext(), "Destination folder is not available", Toast.LENGTH_SHORT).show();
            return false;
        }
        File currentParent = source.getParentFile();
        if (currentParent != null && currentParent.equals(destinationFolder)) {
            Toast.makeText(requireContext(), "File is already in that folder", Toast.LENGTH_SHORT).show();
            return false;
        }

        File destination = uniqueDestination(destinationFolder, source.getName());
        String oldPath = source.getAbsolutePath();
        try {
            if (!source.renameTo(destination)) {
                copyFile(source, destination);
                if (!source.delete()) {
                    destination.delete();
                    Toast.makeText(requireContext(), "Could not move file", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }
            viewModel.updateMovedExportPath(oldPath, destination.getAbsolutePath());
            exportStore.updatePath(oldPath, destination, rootDir);
            return true;
        } catch (IOException e) {
            Toast.makeText(requireContext(), "Could not move file: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void updateActionModeTitle() {
        int selectedCount = adapter == null ? 0 : adapter.getSelectedCount();
        if (selectedCount > 0 && actionMode == null) {
            actionMode = ((AppCompatActivity) requireActivity()).startSupportActionMode(this);
            adapter.setSelectionMode(true);
        }
        if (actionMode != null) {
            actionMode.setTitle(selectedCount + " selected");
            if (selectedCount == 0) {
                actionMode.finish();
            }
        }
    }

    @Override
    public boolean onCreateActionMode(ActionMode mode, Menu menu) {
        MenuInflater inflater = mode.getMenuInflater();
        inflater.inflate(R.menu.exports_selection_menu, menu);
        return true;
    }

    @Override
    public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
        return false;
    }

    @Override
    public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
        if (item.getItemId() == R.id.action_select_all_exports) {
            adapter.selectAllVisible();
            updateActionModeTitle();
            return true;
        } else if (item.getItemId() == R.id.action_move_exports) {
            showMoveDialog(adapter.getSelectedItems());
            return true;
        } else if (item.getItemId() == R.id.action_share_exports) {
            shareExports(adapter.getSelectedItems());
            return true;
        } else if (item.getItemId() == R.id.action_delete_exports) {
            showDeleteDialog(adapter.getSelectedItems());
            return true;
        }
        return false;
    }

    private void showDeleteDialog(List<ExportedFileItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        int count = items.size();
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Delete Exports")
                .setMessage("Are you sure you want to permanently delete the " + count + " selected " + (count == 1 ? "file" : "files") + "? This cannot be undone.")
                .setPositiveButton("Delete", (dialog, which) -> deleteExports(items))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteExports(List<ExportedFileItem> items) {
        int deleted = 0;
        for (ExportedFileItem item : items) {
            File file = item.getFile();
            if (file != null) {
                String path = file.getAbsolutePath();
                boolean removed;
                if (file.isDirectory()) {
                    removed = deleteRecursive(file);
                } else {
                    removed = file.delete();
                }
                if (removed || !file.exists()) {
                    exportStore.deletePath(path);
                    deleted++;
                }
            }
        }
        if (deleted > 0) {
            Toast.makeText(requireContext(), "Deleted " + deleted + (deleted == 1 ? " item" : " items"), Toast.LENGTH_SHORT).show();
            loadExports();
            finishSelectionMode();
        } else {
            Toast.makeText(requireContext(), "Could not delete files", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean deleteRecursive(File fileOrDir) {
        if (fileOrDir.isDirectory()) {
            File[] children = fileOrDir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        return fileOrDir.delete();
    }

    private void shareExports(List<ExportedFileItem> items) {
        if (items == null || items.isEmpty()) return;
        ArrayList<android.net.Uri> uris = new ArrayList<>();
        for (ExportedFileItem item : items) {
            File file = item.getFile();
            if (file != null && file.isFile()) {
                uris.add(androidx.core.content.FileProvider.getUriForFile(
                        requireContext(), requireContext().getPackageName() + ".provider", file));
            }
        }
        if (uris.isEmpty()) {
            Toast.makeText(requireContext(), "No files to share", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent shareIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);
        shareIntent.setType("*/*");
        shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(shareIntent, "Share exports"));
        } catch (Exception e) {
            Toast.makeText(requireContext(), "No app can share these files", Toast.LENGTH_SHORT).show();
        }
        finishSelectionMode();
    }

    @Override
    public void onDestroyActionMode(ActionMode mode) {
        actionMode = null;
        if (adapter != null) {
            adapter.setSelectionMode(false);
        }
    }

    private void finishSelectionMode() {
        if (actionMode != null) {
            actionMode.finish();
        }
    }

    private File uniqueDestination(File folder, String fileName) {
        File destination = new File(folder, fileName);
        if (!destination.exists()) {
            return destination;
        }
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String extension = dot > 0 ? fileName.substring(dot) : "";
        int counter = 1;
        while (true) {
            destination = new File(folder, base + "_" + counter + extension);
            if (!destination.exists()) {
                return destination;
            }
            counter++;
        }
    }

    private void copyFile(File source, File destination) throws IOException {
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[1024 * 8];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
    }

    @Override
    public void onDestroyView() {
        finishSelectionMode();
        super.onDestroyView();
        binding = null;
    }
}
