package com.serhat.autosub;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.serhat.autosub.databinding.FragmentModelsBinding;

import java.util.List;

public class ModelsFragment extends Fragment {

    private FragmentModelsBinding binding;
    private MainViewModel viewModel;
    private ModelAdapter modelAdapter;
    private AlertDialog modelProbeDialog;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentModelsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);

        setupAdapter();
        setupFilters();
        observeViewModel();
    }

    private void setupAdapter() {
        modelAdapter = new ModelAdapter(viewModel.getModelManager());
        modelAdapter.setListener(new ModelAdapter.ModelActionListener() {
            @Override
            public void onUse(VoskModelInfo modelInfo) {
                maybeSelectModel(modelInfo);
            }

            @Override
            public void onDownload(VoskModelInfo modelInfo) {
                maybeDownloadModel(modelInfo);
            }

            @Override
            public void onCancelDownload() {
                viewModel.cancelActiveDownload();
            }

            @Override
            public void onPauseDownload() {
                viewModel.pauseActiveDownload();
            }

            @Override
            public void onResumeDownload(VoskModelInfo modelInfo) {
                viewModel.startModelDownload(modelInfo);
            }

            @Override
            public void onDelete(VoskModelInfo modelInfo) {
                confirmDeleteModel(modelInfo);
            }

            @Override
            public void onCancelQueuedDownload(VoskModelInfo modelInfo) {
                viewModel.cancelQueuedDownload(modelInfo.getId());
            }
        });

        binding.modelsRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.modelsRecyclerView.setAdapter(modelAdapter);
    }

    private void setupFilters() {
        binding.modelSearchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { refreshModels(); }
            @Override public void afterTextChanged(Editable s) {}
        });

        binding.modelFilterChips.setOnCheckedStateChangeListener((group, checkedIds) -> refreshModels());
    }

    private void refreshModels() {
        String query = binding.modelSearchInput.getText() == null ? "" : binding.modelSearchInput.getText().toString();
        int checkedChipId = binding.modelFilterChips.getCheckedChipId();
        viewModel.refreshModels(query, checkedChipId);
    }

    private void observeViewModel() {
        viewModel.getCatalogModels().observe(getViewLifecycleOwner(), models -> {
            submitModelAdapter(models);
        });

        viewModel.getSelectedModelInfo().observe(getViewLifecycleOwner(), selected -> {
            boolean isLoading = !Boolean.TRUE.equals(viewModel.getModelReady().getValue()) && selected != null;
            binding.modelLoadingContainer.setVisibility(isLoading ? View.VISIBLE : View.GONE);
            refreshModels();
        });

        viewModel.getModelReady().observe(getViewLifecycleOwner(), ready -> {
            boolean isLoading = !Boolean.TRUE.equals(ready) && viewModel.getSelectedModelInfo().getValue() != null;
            binding.modelLoadingContainer.setVisibility(isLoading ? View.VISIBLE : View.GONE);
        });

        viewModel.getActiveDownloadModelId().observe(getViewLifecycleOwner(), downloading -> {
            refreshModels();
        });

        viewModel.getActiveDownloadProgress().observe(getViewLifecycleOwner(), progress -> {
            refreshModels();
        });

        viewModel.getActiveDownloadSpeedText().observe(getViewLifecycleOwner(), speed -> {
            refreshModels();
        });

        viewModel.getActiveDownloadEtaText().observe(getViewLifecycleOwner(), eta -> {
            refreshModels();
        });

        viewModel.getActiveDownloadPaused().observe(getViewLifecycleOwner(), paused -> {
            refreshModels();
        });

        viewModel.getQueuedDownloadModelIds().observe(getViewLifecycleOwner(), queued -> {
            refreshModels();
        });
    }

    private void submitModelAdapter(List<VoskModelInfo> models) {
        String selectedId = viewModel.getSelectedModelInfo().getValue() != null 
                ? viewModel.getSelectedModelInfo().getValue().getId() : "";
        String downloadingId = viewModel.getActiveDownloadModelId().getValue();
        int progress = viewModel.getActiveDownloadProgress().getValue() != null 
                ? viewModel.getActiveDownloadProgress().getValue() : 0;
        String speed = viewModel.getActiveDownloadSpeedText().getValue() != null
                ? viewModel.getActiveDownloadSpeedText().getValue() : "";
        String eta = viewModel.getActiveDownloadEtaText().getValue() != null
                ? viewModel.getActiveDownloadEtaText().getValue() : "";
        boolean paused = Boolean.TRUE.equals(viewModel.getActiveDownloadPaused().getValue());
        List<String> queuedIds = viewModel.getQueuedDownloadModelIds().getValue();
        
        modelAdapter.submit(models, selectedId, downloadingId, progress, speed, eta, paused, queuedIds);
    }

    // --- Action Implementations ---

    private void maybeSelectModel(VoskModelInfo modelInfo) {
        viewModel.maybeSelectModel(modelInfo, new MainViewModel.ProbeCallback() {
            @Override
            public void onProbeStarted() {
                showModelProbeDialog(modelInfo);
            }

            @Override
            public void onProbeFinished() {
                dismissModelProbeDialog();
            }

            @Override
            public void onProbeSuccess() {
                Toast.makeText(requireContext(), "Model loaded successfully", Toast.LENGTH_SHORT).show();
                viewModel.setActiveNavigationTab(R.id.nav_generate);
            }

            @Override
            public void onProbeError(String error) {
                if ("ASK_COMPATIBILITY".equals(error)) {
                    showCompatibilityDialog(modelInfo);
                } else if ("ASK_PROBE".equals(error)) {
                    showProbeDialog(modelInfo);
                } else {
                    Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show();
                }
            }
        });
        
        if (modelInfo.isMobileRecommended() && !modelInfo.isVeryLarge()) {
            viewModel.setActiveNavigationTab(R.id.nav_generate);
        }
    }

    private void showCompatibilityDialog(VoskModelInfo modelInfo) {
        VoskModelManager.ModelLoadMode mode = viewModel.getModelLoadMode(modelInfo);
        AppOptionDialog.show(requireContext(),
                mode.getLabel() + " active",
                "This model is currently loading with a RAM-saving mode. Choose how you want to load it next.",
                new AppOptionDialog.Option[]{
                        new AppOptionDialog.Option(
                                "Test full quality",
                                "Try the complete model with rescore and RNNLM enabled. Best accuracy, highest RAM use."),
                        new AppOptionDialog.Option(
                                "Compatibility",
                                "Keep rescore enabled for better accuracy, but turn RNNLM off to reduce memory use."),
                        new AppOptionDialog.Option(
                                "Super compatibility",
                                "Turn both rescore and RNNLM off. Lowest memory use for devices that cannot load the other modes.")
                }, which -> {
                    if (which == 0) {
                        probeAndSelectHeavyModel(modelInfo);
                    } else if (which == 1) {
                        selectModelWithLoadMode(modelInfo, VoskModelManager.ModelLoadMode.COMPATIBILITY);
                    } else {
                        selectModelWithLoadMode(modelInfo, VoskModelManager.ModelLoadMode.LEGACY_COMPATIBILITY);
                    }
                });
    }

    private void showProbeDialog(VoskModelInfo modelInfo) {
        AppOptionDialog.show(requireContext(),
                "Load large model",
                "Large non-mobile models can fail on phones with limited RAM. Pick the quality/memory balance to try.",
                new AppOptionDialog.Option[]{
                        new AppOptionDialog.Option(
                                "Full quality",
                                "Use the complete model with rescore and RNNLM enabled. Best accuracy, highest RAM use."),
                        new AppOptionDialog.Option(
                                "Compatibility",
                                "Use rescore for accuracy, but disable RNNLM to lower memory use."),
                        new AppOptionDialog.Option(
                                "Super compatibility",
                                "Disable both rescore and RNNLM. Use this when the model cannot load in the other modes.")
                }, which -> {
                    if (which == 0) {
                        probeAndSelectHeavyModel(modelInfo);
                    } else if (which == 1) {
                        selectModelWithLoadMode(modelInfo, VoskModelManager.ModelLoadMode.COMPATIBILITY);
                    } else {
                        selectModelWithLoadMode(modelInfo, VoskModelManager.ModelLoadMode.LEGACY_COMPATIBILITY);
                    }
                });
    }

    private void showCompatibilityModeDialog(VoskModelInfo modelInfo) {
        AppOptionDialog.show(requireContext(),
                "Compatibility mode",
                "Choose how aggressively AutoSub should reduce this model's memory use.",
                new AppOptionDialog.Option[]{
                        new AppOptionDialog.Option(
                                "Compatibility",
                                "Keep rescore enabled for better recognition quality, but turn RNNLM off."),
                        new AppOptionDialog.Option(
                                "Super compatibility",
                                "Turn both rescore and RNNLM off for the lowest memory use.")
                }, which -> {
                    VoskModelManager.ModelLoadMode mode = which == 0
                            ? VoskModelManager.ModelLoadMode.COMPATIBILITY
                            : VoskModelManager.ModelLoadMode.LEGACY_COMPATIBILITY;
                    selectModelWithLoadMode(modelInfo, mode);
                });
    }

    private void probeAndSelectHeavyModel(VoskModelInfo modelInfo) {
        viewModel.probeAndSelectHeavyModel(modelInfo, new MainViewModel.ProbeCallback() {
            @Override
            public void onProbeStarted() {
                showModelProbeDialog(modelInfo);
            }

            @Override
            public void onProbeFinished() {
                dismissModelProbeDialog();
            }

            @Override
            public void onProbeSuccess() {
                Toast.makeText(requireContext(), "Heavy model loaded successfully", Toast.LENGTH_SHORT).show();
                viewModel.setActiveNavigationTab(R.id.nav_generate);
            }

            @Override
            public void onProbeError(String error) {
                showCompatibilityFallback(modelInfo, error);
            }
        });
    }

    private void showCompatibilityFallback(VoskModelInfo modelInfo, String errorMessage) {
        AppOptionDialog.show(requireContext(),
                "Full-quality load failed",
                errorMessage + "\n\nTry a lighter mode for this model.",
                new AppOptionDialog.Option[]{
                        new AppOptionDialog.Option(
                                "Compatibility",
                                "Retry with RNNLM off while keeping rescore enabled."),
                        new AppOptionDialog.Option(
                                "Super compatibility",
                                "Retry with both rescore and RNNLM off for the smallest memory footprint.")
                }, which -> selectModelWithLoadMode(modelInfo, which == 0
                        ? VoskModelManager.ModelLoadMode.COMPATIBILITY
                        : VoskModelManager.ModelLoadMode.LEGACY_COMPATIBILITY));
    }

    private void showModelProbeDialog(VoskModelInfo modelInfo) {
        if (!isAdded()) {
            return;
        }
        dismissModelProbeDialog();

        int padding = dpToPx(24);
        LinearLayout content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        content.setPadding(padding, dpToPx(12), padding, dpToPx(4));

        CircularProgressIndicator progressIndicator = new CircularProgressIndicator(requireContext());
        progressIndicator.setIndeterminate(true);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dpToPx(48), dpToPx(48));
        content.addView(progressIndicator, progressParams);

        TextView message = new TextView(requireContext());
        message.setText("Testing " + modelInfo.getLanguage() + " in full quality. This can take a while for large models.");
        message.setGravity(android.view.Gravity.CENTER);
        message.setTextSize(14);
        message.setLineSpacing(dpToPx(2), 1f);
        LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        messageParams.topMargin = dpToPx(16);
        content.addView(message, messageParams);

        modelProbeDialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Testing model load")
                .setView(content)
                .setCancelable(false)
                .create();
        modelProbeDialog.show();
    }

    private void dismissModelProbeDialog() {
        if (modelProbeDialog != null) {
            modelProbeDialog.dismiss();
            modelProbeDialog = null;
        }
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void selectModelWithLoadMode(VoskModelInfo modelInfo, VoskModelManager.ModelLoadMode mode) {
        viewModel.selectModelInLoadMode(modelInfo, mode);
        Toast.makeText(requireContext(), mode.getLabel() + " selected", Toast.LENGTH_SHORT).show();
        viewModel.setActiveNavigationTab(R.id.nav_generate);
    }

    private void maybeDownloadModel(VoskModelInfo modelInfo) {
        if (!modelInfo.isMobileRecommended()) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Large model")
                    .setMessage("This model is " + modelInfo.getSize() + ". It can take a long time to download and may be too heavy for a phone to load.")
                    .setPositiveButton("Download", (dialog, which) -> viewModel.startModelDownload(modelInfo))
                    .setNegativeButton("Cancel", null)
                    .show();
        } else {
            viewModel.startModelDownload(modelInfo);
        }
    }

    private void confirmDeleteModel(VoskModelInfo modelInfo) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Delete model")
                .setMessage("Delete " + modelInfo.getLanguage() + " from this device?")
                .setPositiveButton("Delete", (dialog, which) -> {
                    viewModel.confirmDeleteModel(modelInfo);
                    Toast.makeText(requireContext(), "Model deleted", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onDestroyView() {
        dismissModelProbeDialog();
        super.onDestroyView();
        binding = null;
    }
}
