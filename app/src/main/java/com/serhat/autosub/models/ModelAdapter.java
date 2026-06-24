package com.serhat.autosub.models;


import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.serhat.autosub.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class ModelAdapter extends RecyclerView.Adapter<ModelAdapter.ModelViewHolder> {
    private final VoskModelManager modelManager;
    private final List<VoskModelInfo> models = new ArrayList<>();
    private final List<ModelRowState> rowStates = new ArrayList<>();
    private ModelActionListener listener;
    private String selectedModelId = "";
    private String activeDownloadModelId = "";
    private int activeDownloadProgress;
    private String activeDownloadSpeed = "";
    private String activeDownloadEta = "";
    private boolean activeDownloadPaused = false;
    private List<String> queuedModelIds = new ArrayList<>();
    private boolean gemmaInstalled;
    private boolean gemmaDownloading;
    private int gemmaDownloadProgress;
    private String gemmaDownloadStatus = "";
    private boolean gemmaDownloadPaused;
    private boolean gemmaSupported;

    public interface ModelActionListener {
        void onUse(VoskModelInfo modelInfo);
        void onDownload(VoskModelInfo modelInfo);
        void onCancelDownload(VoskModelInfo modelInfo);
        void onPauseDownload(VoskModelInfo modelInfo);
        void onResumeDownload(VoskModelInfo modelInfo);
        void onDelete(VoskModelInfo modelInfo);
        void onCancelQueuedDownload(VoskModelInfo modelInfo);
    }

    public ModelAdapter(VoskModelManager modelManager) {
        this.modelManager = modelManager;
        setHasStableIds(true);
    }

    public void setListener(ModelActionListener listener) {
        this.listener = listener;
    }

    public void submit(List<VoskModelInfo> newModels, String selectedModelId,
                       String activeDownloadModelId, int activeDownloadProgress,
                       String activeDownloadSpeed, String activeDownloadEta,
                       boolean activeDownloadPaused, List<String> queuedModelIds,
                       boolean gemmaInstalled, boolean gemmaDownloading,
                       int gemmaDownloadProgress, String gemmaDownloadStatus,
                       boolean gemmaDownloadPaused, boolean gemmaSupported) {
        if (newModels == null) {
            newModels = new ArrayList<>();
        }
        this.selectedModelId = selectedModelId == null ? "" : selectedModelId;
        this.activeDownloadModelId = activeDownloadModelId == null ? "" : activeDownloadModelId;
        this.activeDownloadProgress = activeDownloadProgress;
        this.activeDownloadSpeed = activeDownloadSpeed == null ? "" : activeDownloadSpeed;
        this.activeDownloadEta = activeDownloadEta == null ? "" : activeDownloadEta;
        this.activeDownloadPaused = activeDownloadPaused;
        this.queuedModelIds = queuedModelIds == null ? new ArrayList<>() : queuedModelIds;
        this.gemmaInstalled = gemmaInstalled;
        this.gemmaDownloading = gemmaDownloading;
        this.gemmaDownloadProgress = gemmaDownloadProgress;
        this.gemmaDownloadStatus = gemmaDownloadStatus == null ? "" : gemmaDownloadStatus;
        this.gemmaDownloadPaused = gemmaDownloadPaused;
        this.gemmaSupported = gemmaSupported;

        List<ModelRowState> newStates = new ArrayList<>(newModels.size());
        for (VoskModelInfo modelInfo : newModels) {
            newStates.add(new ModelRowState(modelInfo));
        }

        boolean sameStructure = models.size() == newModels.size() && rowStates.size() == newModels.size();
        if (sameStructure) {
            for (int i = 0; i < newModels.size(); i++) {
                if (!Objects.equals(models.get(i).getId(), newModels.get(i).getId())) {
                    sameStructure = false;
                    break;
                }
            }
        }

        if (!sameStructure) {
            models.clear();
            models.addAll(newModels);
            rowStates.clear();
            rowStates.addAll(newStates);
            notifyDataSetChanged();
            return;
        }

        List<Integer> changedPositions = new ArrayList<>();
        for (int i = 0; i < newStates.size(); i++) {
            if (!newStates.get(i).equals(rowStates.get(i))) {
                changedPositions.add(i);
            }
        }

        models.clear();
        models.addAll(newModels);
        rowStates.clear();
        rowStates.addAll(newStates);

        for (int position : changedPositions) {
            notifyItemChanged(position);
        }
    }

    @NonNull
    @Override
    public ModelViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_model, parent, false);
        return new ModelViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ModelViewHolder holder, int position) {
        holder.bind(models.get(position));
    }

    @Override
    public int getItemCount() {
        return models.size();
    }

    @Override
    public long getItemId(int position) {
        if (position < 0 || position >= models.size()) {
            return RecyclerView.NO_ID;
        }
        return stableId(models.get(position).getId());
    }

    private long stableId(String value) {
        long result = 1125899906842597L;
        if (value == null) return result;
        for (int i = 0; i < value.length(); i++) {
            result = 31 * result + value.charAt(i);
        }
        return result;
    }

    class ModelViewHolder extends RecyclerView.ViewHolder {
        TextView titleTV, detailTV, sizeBadgeTV;
        ChipGroup chipGroup;
        LinearProgressIndicator downloadProgress;
        MaterialButton primaryBT, deleteBT;

        ModelViewHolder(@NonNull View itemView) {
            super(itemView);
            titleTV = itemView.findViewById(R.id.modelTitleTV);
            detailTV = itemView.findViewById(R.id.modelDetailTV);
            sizeBadgeTV = itemView.findViewById(R.id.modelSizeBadge);
            chipGroup = itemView.findViewById(R.id.modelChipGroup);
            downloadProgress = itemView.findViewById(R.id.modelDownloadProgress);
            primaryBT = itemView.findViewById(R.id.modelPrimaryBT);
            deleteBT = itemView.findViewById(R.id.modelDeleteBT);
        }

        void bind(VoskModelInfo modelInfo) {
            if (isGemmaModel(modelInfo)) {
                bindGemma(modelInfo);
                return;
            }
            boolean installed = modelManager.isInstalled(modelInfo.getId());
            boolean selected = selectedModelId.equals(modelInfo.getId());
            boolean downloading = activeDownloadModelId.equals(modelInfo.getId());
            boolean queued = queuedModelIds.contains(modelInfo.getId());
            boolean hasPartial = !installed && !selected && !downloading && !queued && modelManager.hasPartialDownload(modelInfo.getId());
            VoskModelManager.ModelLoadMode loadMode = modelManager.getModelLoadMode(modelInfo);

            titleTV.setText(modelInfo.getLanguage());
            sizeBadgeTV.setText(modelInfo.getSize());
            applyCardOutline(modelInfo, selected);

            if (downloading) {
                String speedEta = "";
                if (!activeDownloadSpeed.isEmpty()) {
                    speedEta += activeDownloadSpeed;
                }
                if (!activeDownloadEta.isEmpty()) {
                    if (!speedEta.isEmpty()) speedEta += " / ";
                    speedEta += activeDownloadEta;
                }
                if (!speedEta.isEmpty()) {
                    detailTV.setText(detailText(modelInfo,
                            modelInfo.getId() + " - Downloading " + activeDownloadProgress + "% (" + speedEta + ")"));
                } else {
                    detailTV.setText(detailText(modelInfo,
                            modelInfo.getId() + " - Downloading " + activeDownloadProgress + "%"));
                }
            } else if (hasPartial) {
                int partialProgress = modelManager.getDownloadProgress(modelInfo.getId());
                detailTV.setText(detailText(modelInfo, modelInfo.getId() + " (Paused / " + partialProgress + "%)"));
            } else {
                detailTV.setText(detailText(modelInfo, modelInfo.getId() + " - " + modelInfo.getLicense()));
            }

            renderChips(modelInfo, installed, selected, downloading, hasPartial, queued);
            downloadProgress.setVisibility(downloading || hasPartial ? View.VISIBLE : View.GONE);
            downloadProgress.setProgress(downloading ? activeDownloadProgress : modelManager.getDownloadProgress(modelInfo.getId()));

            if (downloading) {
                deleteBT.setVisibility(View.VISIBLE);
                deleteBT.setIconResource(R.drawable.ri_close_line);
                deleteBT.setText("Cancel");
                deleteBT.setContentDescription("Cancel download");
                deleteBT.setOnClickListener(v -> {
                    if (listener != null) listener.onCancelDownload(modelInfo);
                });
            } else if (hasPartial) {
                deleteBT.setVisibility(View.VISIBLE);
                deleteBT.setIconResource(R.drawable.ri_delete_bin_line);
                deleteBT.setText("Del");
                deleteBT.setContentDescription("Delete partial download");
                deleteBT.setOnClickListener(v -> {
                    if (listener != null) listener.onDelete(modelInfo);
                });
            } else {
                deleteBT.setVisibility(installed && !modelInfo.isBundled() ? View.VISIBLE : View.GONE);
                deleteBT.setIconResource(R.drawable.ri_delete_bin_line);
                deleteBT.setText("Del");
                deleteBT.setContentDescription("Delete model");
                deleteBT.setOnClickListener(v -> {
                    if (listener != null) listener.onDelete(modelInfo);
                });
            }

            if (selected
                    && !modelInfo.isWhisper()
                    && loadMode != VoskModelManager.ModelLoadMode.FULL_QUALITY
                    && (modelInfo.isVeryLarge() || !modelInfo.isMobileRecommended())) {
                primaryBT.setText("Mode");
                primaryBT.setIconResource(R.drawable.ri_settings_3_line);
                primaryBT.setContentDescription("Change model load mode");
                primaryBT.setEnabled(true);
                primaryBT.setOnClickListener(v -> {
                    if (listener != null) listener.onUse(modelInfo);
                });
            } else if (selected) {
                primaryBT.setText("On");
                primaryBT.setIconResource(R.drawable.ri_checkbox_circle_line);
                primaryBT.setContentDescription("Selected model");
                primaryBT.setEnabled(false);
            } else if (downloading) {
                if (activeDownloadPaused) {
                    primaryBT.setText("Resume");
                    primaryBT.setIconResource(R.drawable.ri_play_line);
                    primaryBT.setContentDescription("Resume download " + activeDownloadProgress + "%");
                    primaryBT.setOnClickListener(v -> {
                        if (listener != null) listener.onResumeDownload(modelInfo);
                    });
                } else {
                    primaryBT.setText("Pause");
                    primaryBT.setIconResource(R.drawable.ri_pause_line);
                    primaryBT.setContentDescription("Pause download " + activeDownloadProgress + "%");
                    primaryBT.setOnClickListener(v -> {
                        if (listener != null) listener.onPauseDownload(modelInfo);
                    });
                }
                primaryBT.setEnabled(true);
            } else if (hasPartial) {
                primaryBT.setText("Resume");
                primaryBT.setIconResource(R.drawable.ri_play_line);
                primaryBT.setContentDescription("Resume download");
                primaryBT.setEnabled(true);
                primaryBT.setOnClickListener(v -> {
                    if (listener != null) listener.onResumeDownload(modelInfo);
                });
            } else if (installed) {
                primaryBT.setText("Use");
                primaryBT.setIconResource(R.drawable.ri_checkbox_circle_line);
                primaryBT.setContentDescription("Use model");
                primaryBT.setEnabled(true);
                primaryBT.setOnClickListener(v -> {
                    if (listener != null) listener.onUse(modelInfo);
                });
            } else if (queued) {
                primaryBT.setText("Queued");
                primaryBT.setIconResource(R.drawable.ri_time_line);
                primaryBT.setContentDescription("Queued for download");
                primaryBT.setEnabled(false);
                primaryBT.setOnClickListener(null);

                deleteBT.setVisibility(View.VISIBLE);
                deleteBT.setIconResource(R.drawable.ri_close_line);
                deleteBT.setText("Cancel");
                deleteBT.setContentDescription("Cancel queued download");
                deleteBT.setOnClickListener(v -> {
                    if (listener != null) listener.onCancelQueuedDownload(modelInfo);
                });
            } else {
                primaryBT.setText("Get");
                primaryBT.setIconResource(R.drawable.ri_download_line);
                primaryBT.setContentDescription("Download model");
                primaryBT.setEnabled(true);
                primaryBT.setOnClickListener(v -> {
                    if (listener != null) listener.onDownload(modelInfo);
                });
            }
        }

        private void bindGemma(VoskModelInfo modelInfo) {
            titleTV.setText(modelInfo.getLanguage());
            sizeBadgeTV.setText(modelInfo.getSize());
            applyCardOutline(modelInfo, false);

            if (!gemmaSupported) {
                detailTV.setText(detailText(modelInfo, "Requires Android 12 or newer"));
            } else if (gemmaInstalled) {
                detailTV.setText(detailText(modelInfo, modelInfo.getId() + " - Ready for Shorts extraction"));
            } else if (gemmaDownloading) {
                String status = gemmaDownloadStatus.isEmpty() ? "" : " (" + gemmaDownloadStatus + ")";
                detailTV.setText(detailText(modelInfo,
                        modelInfo.getId() + " - Downloading " + gemmaDownloadProgress + "%" + status));
            } else if (gemmaDownloadPaused) {
                detailTV.setText(detailText(modelInfo,
                        modelInfo.getId() + " - Paused at " + gemmaDownloadProgress + "%"));
            } else {
                detailTV.setText(detailText(modelInfo, modelInfo.getId() + " - " + modelInfo.getLicense()));
            }

            chipGroup.removeAllViews();
            addChip(gemmaInstalled ? "Installed" : "Cloud");
            addChip("AI");
            addChip("Shorts");
            addChip("Heavy");
            if (gemmaDownloading) addChip("Downloading");
            else if (gemmaDownloadPaused) addChip("Paused");

            downloadProgress.setVisibility(gemmaDownloading || gemmaDownloadPaused ? View.VISIBLE : View.GONE);
            downloadProgress.setProgress(Math.max(0, gemmaDownloadProgress));

            if (gemmaDownloading || gemmaDownloadPaused) {
                deleteBT.setVisibility(View.VISIBLE);
                deleteBT.setIconResource(R.drawable.ri_close_line);
                deleteBT.setText("Cancel");
                deleteBT.setContentDescription("Cancel Gemma download");
                deleteBT.setOnClickListener(v -> {
                    if (listener != null) listener.onCancelDownload(modelInfo);
                });
            } else if (gemmaInstalled) {
                deleteBT.setVisibility(View.VISIBLE);
                deleteBT.setIconResource(R.drawable.ri_delete_bin_line);
                deleteBT.setText("Del");
                deleteBT.setContentDescription("Delete Gemma model");
                deleteBT.setOnClickListener(v -> {
                    if (listener != null) listener.onDelete(modelInfo);
                });
            } else {
                deleteBT.setVisibility(View.GONE);
                deleteBT.setOnClickListener(null);
            }

            if (!gemmaSupported) {
                primaryBT.setText("Unavailable");
                primaryBT.setIconResource(R.drawable.ri_error_warning_line);
                primaryBT.setContentDescription("Gemma requires Android 12 or newer");
                primaryBT.setEnabled(false);
                primaryBT.setOnClickListener(null);
            } else if (gemmaInstalled) {
                primaryBT.setText("Ready");
                primaryBT.setIconResource(R.drawable.ri_checkbox_circle_line);
                primaryBT.setContentDescription("Gemma is installed");
                primaryBT.setEnabled(false);
                primaryBT.setOnClickListener(null);
            } else if (gemmaDownloading) {
                primaryBT.setText("Pause");
                primaryBT.setIconResource(R.drawable.ri_pause_line);
                primaryBT.setContentDescription("Pause Gemma download " + gemmaDownloadProgress + "%");
                primaryBT.setEnabled(true);
                primaryBT.setOnClickListener(v -> {
                    if (listener != null) listener.onPauseDownload(modelInfo);
                });
            } else if (gemmaDownloadPaused) {
                primaryBT.setText("Resume");
                primaryBT.setIconResource(R.drawable.ri_play_line);
                primaryBT.setContentDescription("Resume Gemma download");
                primaryBT.setEnabled(true);
                primaryBT.setOnClickListener(v -> {
                    if (listener != null) listener.onResumeDownload(modelInfo);
                });
            } else {
                primaryBT.setText("Get");
                primaryBT.setIconResource(R.drawable.ri_download_line);
                primaryBT.setContentDescription("Download Gemma model");
                primaryBT.setEnabled(true);
                primaryBT.setOnClickListener(v -> {
                    if (listener != null) listener.onDownload(modelInfo);
                });
            }
        }

        private void applyCardOutline(VoskModelInfo modelInfo, boolean selected) {
            if (!(itemView instanceof MaterialCardView)) {
                return;
            }
            MaterialCardView card = (MaterialCardView) itemView;
            int color = MaterialColors.getColor(
                    itemView.getContext(),
                    modelInfo.isWhisper() || isGemmaModel(modelInfo)
                            ? com.google.android.material.R.attr.colorTertiary
                            : com.google.android.material.R.attr.colorPrimary,
                    0
            );
            card.setStrokeColor(color);
            card.setStrokeWidth(dp(selected ? 2 : 1));
        }

        private int dp(int value) {
            return Math.round(value * itemView.getResources().getDisplayMetrics().density);
        }

        private String detailText(VoskModelInfo modelInfo, String baseText) {
            String details = modelInfo.getDetails();
            if (details == null || details.trim().isEmpty()) {
                return baseText;
            }
            return baseText + "\n" + details.trim();
        }

        private void renderChips(VoskModelInfo modelInfo, boolean installed, boolean selected, boolean downloading, boolean hasPartial, boolean queued) {
            chipGroup.removeAllViews();
            addChip(selected ? "Selected" : installed ? "Installed" : "Cloud");
            if (modelInfo.isWhisper()) addChip("Whisper");
            if (modelInfo.isMobileRecommended()) addChip("Mobile");
            if (modelInfo.isVeryLarge()) addChip("Heavy");
            if (!modelInfo.isWhisper()) {
                VoskModelManager.ModelLoadMode loadMode = modelManager.getModelLoadMode(modelInfo);
                if (loadMode != VoskModelManager.ModelLoadMode.FULL_QUALITY) addChip(loadMode.getLabel());
            }
            if (downloading) addChip("Downloading");
            else if (queued) addChip("Queued");
            else if (hasPartial) addChip("Paused");
        }

        private void addChip(String text) {
            Chip chip = new Chip(itemView.getContext());
            chip.setText(text);
            chip.setClickable(false);
            chip.setCheckable(false);
            chipGroup.addView(chip);
        }
    }

    private boolean isGemmaModel(VoskModelInfo modelInfo) {
        return modelInfo != null && GemmaModelManager.MODEL_ID.equals(modelInfo.getId());
    }

    private class ModelRowState {
        private final String id;
        private final String language;
        private final String size;
        private final String license;
        private final String details;
        private final boolean whisper;
        private final boolean mobileRecommended;
        private final boolean veryLarge;
        private final boolean bundled;
        private final boolean installed;
        private final boolean selected;
        private final boolean downloading;
        private final boolean queued;
        private final boolean hasPartial;
        private final VoskModelManager.ModelLoadMode loadMode;
        private final int downloadProgress;
        private final int partialProgress;
        private final String downloadSpeed;
        private final String downloadEta;
        private final boolean downloadPaused;
        private final boolean supported;

        private ModelRowState(VoskModelInfo modelInfo) {
            boolean gemma = isGemmaModel(modelInfo);
            id = modelInfo.getId();
            language = modelInfo.getLanguage();
            size = modelInfo.getSize();
            license = modelInfo.getLicense();
            details = modelInfo.getDetails();
            whisper = modelInfo.isWhisper();
            mobileRecommended = modelInfo.isMobileRecommended();
            veryLarge = modelInfo.isVeryLarge();
            bundled = modelInfo.isBundled();
            installed = gemma ? gemmaInstalled : modelManager.isInstalled(id);
            selected = !gemma && selectedModelId.equals(id);
            downloading = gemma ? gemmaDownloading : activeDownloadModelId.equals(id);
            queued = !gemma && queuedModelIds.contains(id);
            hasPartial = gemma
                    ? gemmaDownloadPaused
                    : !installed && !selected && !downloading && !queued && modelManager.hasPartialDownload(id);
            loadMode = gemma ? VoskModelManager.ModelLoadMode.FULL_QUALITY : modelManager.getModelLoadMode(modelInfo);
            downloadProgress = downloading ? (gemma ? gemmaDownloadProgress : activeDownloadProgress) : 0;
            partialProgress = hasPartial ? (gemma ? gemmaDownloadProgress : modelManager.getDownloadProgress(id)) : 0;
            downloadSpeed = downloading ? (gemma ? gemmaDownloadStatus : activeDownloadSpeed) : "";
            downloadEta = downloading && !gemma ? activeDownloadEta : "";
            downloadPaused = gemma ? gemmaDownloadPaused : downloading && activeDownloadPaused;
            supported = !gemma || gemmaSupported;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof ModelRowState)) return false;
            ModelRowState other = (ModelRowState) obj;
            return whisper == other.whisper
                    && mobileRecommended == other.mobileRecommended
                    && veryLarge == other.veryLarge
                    && bundled == other.bundled
                    && installed == other.installed
                    && selected == other.selected
                    && downloading == other.downloading
                    && queued == other.queued
                    && hasPartial == other.hasPartial
                    && downloadProgress == other.downloadProgress
                    && partialProgress == other.partialProgress
                    && downloadPaused == other.downloadPaused
                    && supported == other.supported
                    && loadMode == other.loadMode
                    && Objects.equals(id, other.id)
                    && Objects.equals(language, other.language)
                    && Objects.equals(size, other.size)
                    && Objects.equals(license, other.license)
                    && Objects.equals(details, other.details)
                    && Objects.equals(downloadSpeed, other.downloadSpeed)
                    && Objects.equals(downloadEta, other.downloadEta);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, language, size, license, details, whisper, mobileRecommended, veryLarge,
                    bundled, installed, selected, downloading, queued, hasPartial, loadMode,
                    downloadProgress, partialProgress, downloadSpeed, downloadEta, downloadPaused, supported);
        }
    }
}
