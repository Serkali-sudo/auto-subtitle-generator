package com.serhat.autosub;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.ArrayList;
import java.util.List;

public class ModelAdapter extends RecyclerView.Adapter<ModelAdapter.ModelViewHolder> {
    private final VoskModelManager modelManager;
    private final List<VoskModelInfo> models = new ArrayList<>();
    private ModelActionListener listener;
    private String selectedModelId = "";
    private String activeDownloadModelId = "";
    private int activeDownloadProgress;
    private String activeDownloadSpeed = "";
    private String activeDownloadEta = "";
    private boolean activeDownloadPaused = false;
    private List<String> queuedModelIds = new ArrayList<>();

    public interface ModelActionListener {
        void onUse(VoskModelInfo modelInfo);
        void onDownload(VoskModelInfo modelInfo);
        void onCancelDownload();
        void onPauseDownload();
        void onResumeDownload(VoskModelInfo modelInfo);
        void onDelete(VoskModelInfo modelInfo);
        void onCancelQueuedDownload(VoskModelInfo modelInfo);
    }

    public ModelAdapter(VoskModelManager modelManager) {
        this.modelManager = modelManager;
    }

    public void setListener(ModelActionListener listener) {
        this.listener = listener;
    }

    public void submit(List<VoskModelInfo> newModels, String selectedModelId,
                       String activeDownloadModelId, int activeDownloadProgress,
                       String activeDownloadSpeed, String activeDownloadEta,
                       boolean activeDownloadPaused, List<String> queuedModelIds) {
        models.clear();
        models.addAll(newModels);
        this.selectedModelId = selectedModelId == null ? "" : selectedModelId;
        this.activeDownloadModelId = activeDownloadModelId == null ? "" : activeDownloadModelId;
        this.activeDownloadProgress = activeDownloadProgress;
        this.activeDownloadSpeed = activeDownloadSpeed == null ? "" : activeDownloadSpeed;
        this.activeDownloadEta = activeDownloadEta == null ? "" : activeDownloadEta;
        this.activeDownloadPaused = activeDownloadPaused;
        this.queuedModelIds = queuedModelIds == null ? new ArrayList<>() : queuedModelIds;
        notifyDataSetChanged();
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
            boolean installed = modelManager.isInstalled(modelInfo.getId());
            boolean selected = selectedModelId.equals(modelInfo.getId());
            boolean downloading = activeDownloadModelId.equals(modelInfo.getId());
            boolean queued = queuedModelIds.contains(modelInfo.getId());
            boolean hasPartial = !installed && !selected && !downloading && !queued && modelManager.hasPartialDownload(modelInfo.getId());
            VoskModelManager.ModelLoadMode loadMode = modelManager.getModelLoadMode(modelInfo);

            titleTV.setText(modelInfo.getLanguage());
            sizeBadgeTV.setText(modelInfo.getSize());

            if (downloading) {
                String speedEta = "";
                if (!activeDownloadSpeed.isEmpty()) {
                    speedEta += activeDownloadSpeed;
                }
                if (!activeDownloadEta.isEmpty()) {
                    if (!speedEta.isEmpty()) speedEta += " • ";
                    speedEta += activeDownloadEta;
                }
                if (!speedEta.isEmpty()) {
                    detailTV.setText(modelInfo.getId() + " - Downloading " + activeDownloadProgress + "% (" + speedEta + ")");
                } else {
                    detailTV.setText(modelInfo.getId() + " - Downloading " + activeDownloadProgress + "%");
                }
            } else if (hasPartial) {
                int partialProgress = modelManager.getDownloadProgress(modelInfo.getId());
                detailTV.setText(modelInfo.getId() + " (Paused • " + partialProgress + "%)");
            } else {
                detailTV.setText(modelInfo.getId() + " - " + modelInfo.getLicense());
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
                    if (listener != null) listener.onCancelDownload();
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
                        if (listener != null) listener.onPauseDownload();
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

        private void renderChips(VoskModelInfo modelInfo, boolean installed, boolean selected, boolean downloading, boolean hasPartial, boolean queued) {
            chipGroup.removeAllViews();
            addChip(selected ? "Selected" : installed ? "Installed" : "Cloud");
            if (modelInfo.isMobileRecommended()) addChip("Mobile");
            if (modelInfo.isVeryLarge()) addChip("Heavy");
            VoskModelManager.ModelLoadMode loadMode = modelManager.getModelLoadMode(modelInfo);
            if (loadMode != VoskModelManager.ModelLoadMode.FULL_QUALITY) addChip(loadMode.getLabel());
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
}
