package com.serhat.autosub;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class QueueAdapter extends RecyclerView.Adapter<QueueAdapter.QueueViewHolder> {
    private final List<QueueItem> items = new ArrayList<>();
    private final List<QueueRowState> rowStates = new ArrayList<>();
    private QueueActionListener listener;
    private boolean selectionMode = false;

    public QueueAdapter() {
        setHasStableIds(true);
    }

    public interface QueueActionListener {
        void onRetry(QueueItem item);
        void onRemove(QueueItem item);
        void onExportVideo(QueueItem item);
        void onExportSubtitle(QueueItem item);
        void onShare(QueueItem item);
        void onPlay(QueueItem item);
        void onPreview(QueueItem item);
        void onTranslate(QueueItem item);
        default void onSelectionChanged() {}
        default void onLongPress(QueueItem item) {}
    }

    public void setListener(QueueActionListener listener) {
        this.listener = listener;
    }

    public void setItems(List<QueueItem> queueItems) {
        if (queueItems == null) {
            queueItems = new ArrayList<>();
        }

        List<QueueRowState> newStates = new ArrayList<>(queueItems.size());
        for (QueueItem item : queueItems) {
            newStates.add(QueueRowState.from(item));
        }

        boolean sameStructure = items.size() == queueItems.size() && rowStates.size() == queueItems.size();
        if (sameStructure) {
            for (int i = 0; i < queueItems.size(); i++) {
                if (items.get(i).getId() != queueItems.get(i).getId()) {
                    sameStructure = false;
                    break;
                }
            }
        }

        if (!sameStructure) {
            items.clear();
            items.addAll(queueItems);
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

        items.clear();
        items.addAll(queueItems);
        rowStates.clear();
        rowStates.addAll(newStates);

        for (int position : changedPositions) {
            notifyItemChanged(position);
        }
    }

    public boolean isSelectionMode() {
        return selectionMode;
    }

    public void setSelectionMode(boolean selectionMode) {
        this.selectionMode = selectionMode;
        if (!selectionMode) {
            for (QueueItem item : items) {
                item.setSelected(false);
            }
        }
        notifyDataSetChanged();
    }

    public int getSelectedCount() {
        int count = 0;
        for (QueueItem item : items) {
            if (item.isSelected()) count++;
        }
        return count;
    }

    public void selectAllVisible() {
        for (QueueItem item : items) {
            item.setSelected(true);
        }
        notifyDataSetChanged();
        if (listener != null) listener.onSelectionChanged();
    }

    @NonNull
    @Override
    public QueueViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_queue, parent, false);
        return new QueueViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull QueueViewHolder holder, int position) {
        holder.bind(items.get(position));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public long getItemId(int position) {
        if (position < 0 || position >= items.size()) {
            return RecyclerView.NO_ID;
        }
        long id = items.get(position).getId();
        return id == 0 ? RecyclerView.NO_ID : id;
    }

    class QueueViewHolder extends RecyclerView.ViewHolder {
        TextView titleTV, statusTV, outputTV;
        LinearProgressIndicator progressIndicator;
        MaterialButton retryBT, removeBT, exportVideoBT, exportSubtitleBT, shareBT, playBT;
        android.widget.ImageView queueThumbIV;
        android.widget.ImageButton queueEditBT, queueTranslateBT;
        android.widget.CheckBox queueSelectCB;
        private String boundThumbnailPath;

        QueueViewHolder(@NonNull View itemView) {
            super(itemView);
            titleTV = itemView.findViewById(R.id.queueTitleTV);
            statusTV = itemView.findViewById(R.id.queueStatusTV);
            outputTV = itemView.findViewById(R.id.queueOutputTV);
            progressIndicator = itemView.findViewById(R.id.queueProgress);
            retryBT = itemView.findViewById(R.id.queueRetryBT);
            removeBT = itemView.findViewById(R.id.queueRemoveBT);
            exportVideoBT = itemView.findViewById(R.id.queueExportVideoBT);
            exportSubtitleBT = itemView.findViewById(R.id.queueExportSubtitleBT);
            shareBT = itemView.findViewById(R.id.queueShareBT);
            playBT = itemView.findViewById(R.id.queuePlayBT);
            queueThumbIV = itemView.findViewById(R.id.queueThumbIV);
            queueEditBT = itemView.findViewById(R.id.queueEditBT);
            queueTranslateBT = itemView.findViewById(R.id.queueTranslateBT);
            queueSelectCB = itemView.findViewById(R.id.queueSelectCB);
        }

        void bind(QueueItem item) {
            titleTV.setText(item.getDisplayName());
            statusTV.setText(item.getStatus().name().toLowerCase(Locale.getDefault()) + statusSuffix(item));
            if (item.getStatus() == QueueItem.Status.EXPORTING
                    || item.getStatus() == QueueItem.Status.TRANSLATING) {
                outputTV.setText(item.getMessage());
            } else {
                outputTV.setText(item.getOutputPath().isEmpty() ? item.getMessage() : item.getOutputPath());
                if (!item.getPreviewText().isEmpty()) {
                    outputTV.setText(item.getPreviewText());
                }
            }
            outputTV.setVisibility(outputTV.getText().length() == 0 ? View.GONE : View.VISIBLE);

            boolean active = item.getStatus() == QueueItem.Status.PROCESSING
                    || item.getStatus() == QueueItem.Status.EXPORTING
                    || item.getStatus() == QueueItem.Status.TRANSLATING;
            progressIndicator.setVisibility(active ? View.VISIBLE : View.GONE);
            if (active && item.getProgress() < 0) {
                progressIndicator.setIndeterminate(true);
            } else {
                progressIndicator.setIndeterminate(false);
                progressIndicator.setProgress(Math.max(0, item.getProgress()));
            }

            retryBT.setVisibility(item.getStatus() == QueueItem.Status.FAILED || item.getStatus() == QueueItem.Status.CANCELLED ? View.VISIBLE : View.GONE);
            
            boolean completed = item.getStatus() == QueueItem.Status.COMPLETED;
            exportVideoBT.setVisibility(completed && !selectionMode ? View.VISIBLE : View.GONE);
            exportSubtitleBT.setVisibility(completed && !selectionMode ? View.VISIBLE : View.GONE);
            shareBT.setVisibility(completed && !selectionMode ? View.VISIBLE : View.GONE);
            
            boolean hasVideo = !item.getSoftVideoPath().isEmpty() || !item.getHardVideoPath().isEmpty();
            playBT.setVisibility(completed && hasVideo && !selectionMode ? View.VISIBLE : View.GONE);

            // Checkbox: only visible in selection mode
            queueSelectCB.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
            queueSelectCB.setOnCheckedChangeListener(null);
            queueSelectCB.setChecked(item.isSelected());
            queueSelectCB.setOnCheckedChangeListener((buttonView, isChecked) -> {
                item.setSelected(isChecked);
                if (listener != null) listener.onSelectionChanged();
            });

            bindThumbnail(item);

            retryBT.setOnClickListener(v -> {
                if (listener != null) listener.onRetry(item);
            });
            removeBT.setOnClickListener(v -> {
                if (listener != null) listener.onRemove(item);
            });
            exportVideoBT.setOnClickListener(v -> {
                if (listener != null) listener.onExportVideo(item);
            });
            exportSubtitleBT.setOnClickListener(v -> {
                if (listener != null) listener.onExportSubtitle(item);
            });
            shareBT.setOnClickListener(v -> {
                if (listener != null) listener.onShare(item);
            });
            playBT.setOnClickListener(v -> {
                if (listener != null) listener.onPlay(item);
            });
            
            // Edit button and item card click
            queueEditBT.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
            queueTranslateBT.setVisibility(completed && !selectionMode && !item.getSubtitles().isEmpty()
                    ? View.VISIBLE : View.GONE);
            queueTranslateBT.setAlpha(item.hasTranslations() ? 0.78f : 1f);
            queueTranslateBT.setOnClickListener(v -> {
                if (listener != null) listener.onTranslate(item);
            });
            queueEditBT.setOnClickListener(v -> {
                if (listener != null) listener.onPreview(item);
            });

            itemView.setOnClickListener(v -> {
                if (selectionMode) {
                    item.setSelected(!item.isSelected());
                    queueSelectCB.setChecked(item.isSelected());
                    if (listener != null) listener.onSelectionChanged();
                } else {
                    if (listener != null) listener.onPreview(item);
                }
            });

            itemView.setOnLongClickListener(v -> {
                if (listener != null) listener.onLongPress(item);
                return true;
            });
        }

        private String statusSuffix(QueueItem item) {
            if (item.getStatus() == QueueItem.Status.PROCESSING
                    || item.getStatus() == QueueItem.Status.EXPORTING
                    || item.getStatus() == QueueItem.Status.TRANSLATING) {
                if (item.getProgress() < 0) {
                    String message = item.getMessage();
                    if (message != null && !message.trim().isEmpty()) {
                        return " - " + message.trim();
                    }
                    if (item.getStatus() == QueueItem.Status.TRANSLATING) return " - translating";
                    return item.getStatus() == QueueItem.Status.EXPORTING ? " - exporting video" : "";
                }
                return " - " + item.getProgress() + "%";
            }
            return "";
        }

        private void bindThumbnail(QueueItem item) {
            String thumbnailPath = item.getThumbnailPath();
            if (Objects.equals(boundThumbnailPath, thumbnailPath)) {
                return;
            }
            boundThumbnailPath = thumbnailPath;
            if (!thumbnailPath.isEmpty() && new java.io.File(thumbnailPath).exists()) {
                queueThumbIV.setImageURI(android.net.Uri.fromFile(new java.io.File(thumbnailPath)));
            } else {
                queueThumbIV.setImageResource(R.drawable.ri_file_video_line);
            }
        }
    }

    private static class QueueRowState {
        private final long id;
        private final String displayName;
        private final QueueItem.Status status;
        private final int progress;
        private final String outputPath;
        private final String message;
        private final String previewText;
        private final String softVideoPath;
        private final String hardVideoPath;
        private final String thumbnailPath;
        private final String translationSourceLanguage;
        private final String translationTargetLanguage;
        private final String translationStatus;
        private final boolean hasTranslations;
        private final boolean selected;

        private QueueRowState(QueueItem item) {
            id = item.getId();
            displayName = item.getDisplayName();
            status = item.getStatus();
            progress = item.getProgress();
            outputPath = item.getOutputPath();
            message = item.getMessage();
            previewText = item.getPreviewText();
            softVideoPath = item.getSoftVideoPath();
            hardVideoPath = item.getHardVideoPath();
            thumbnailPath = item.getThumbnailPath();
            translationSourceLanguage = item.getTranslationSourceLanguage();
            translationTargetLanguage = item.getTranslationTargetLanguage();
            translationStatus = item.getTranslationStatus();
            hasTranslations = item.hasTranslations();
            selected = item.isSelected();
        }

        static QueueRowState from(QueueItem item) {
            return new QueueRowState(item);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof QueueRowState)) return false;
            QueueRowState other = (QueueRowState) obj;
            return id == other.id
                    && progress == other.progress
                    && selected == other.selected
                    && status == other.status
                    && Objects.equals(displayName, other.displayName)
                    && Objects.equals(outputPath, other.outputPath)
                    && Objects.equals(message, other.message)
                    && Objects.equals(previewText, other.previewText)
                    && Objects.equals(softVideoPath, other.softVideoPath)
                    && Objects.equals(hardVideoPath, other.hardVideoPath)
                    && Objects.equals(thumbnailPath, other.thumbnailPath)
                    && Objects.equals(translationSourceLanguage, other.translationSourceLanguage)
                    && Objects.equals(translationTargetLanguage, other.translationTargetLanguage)
                    && Objects.equals(translationStatus, other.translationStatus)
                    && hasTranslations == other.hasTranslations;
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, displayName, status, progress, outputPath, message, previewText,
                    softVideoPath, hardVideoPath, thumbnailPath, translationSourceLanguage,
                    translationTargetLanguage, translationStatus, hasTranslations, selected);
        }
    }
}
