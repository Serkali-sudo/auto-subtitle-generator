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

public class QueueAdapter extends RecyclerView.Adapter<QueueAdapter.QueueViewHolder> {
    private final List<QueueItem> items = new ArrayList<>();
    private QueueActionListener listener;
    private boolean selectionMode = false;

    public interface QueueActionListener {
        void onRetry(QueueItem item);
        void onRemove(QueueItem item);
        void onExportVideo(QueueItem item);
        void onExportSubtitle(QueueItem item);
        void onShare(QueueItem item);
        void onPlay(QueueItem item);
        void onPreview(QueueItem item);
        default void onSelectionChanged() {}
        default void onLongPress(QueueItem item) {}
    }

    public void setListener(QueueActionListener listener) {
        this.listener = listener;
    }

    public void setItems(List<QueueItem> queueItems) {
        items.clear();
        items.addAll(queueItems);
        notifyDataSetChanged();
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

    class QueueViewHolder extends RecyclerView.ViewHolder {
        TextView titleTV, statusTV, outputTV;
        LinearProgressIndicator progressIndicator;
        MaterialButton retryBT, removeBT, exportVideoBT, exportSubtitleBT, shareBT, playBT;
        android.widget.ImageView queueThumbIV;
        android.widget.ImageButton queueEditBT;
        android.widget.CheckBox queueSelectCB;

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
            queueSelectCB = itemView.findViewById(R.id.queueSelectCB);
        }

        void bind(QueueItem item) {
            titleTV.setText(item.getDisplayName());
            statusTV.setText(item.getStatus().name().toLowerCase(Locale.getDefault()) + statusSuffix(item));
            if (item.getStatus() == QueueItem.Status.EXPORTING) {
                outputTV.setText(item.getMessage());
            } else {
                outputTV.setText(item.getOutputPath().isEmpty() ? item.getMessage() : item.getOutputPath());
                if (!item.getPreviewText().isEmpty()) {
                    outputTV.setText(item.getPreviewText());
                }
            }
            outputTV.setVisibility(outputTV.getText().length() == 0 ? View.GONE : View.VISIBLE);

            boolean active = item.getStatus() == QueueItem.Status.PROCESSING || item.getStatus() == QueueItem.Status.EXPORTING;
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

            // Load visual thumbnail
            if (!item.getThumbnailPath().isEmpty() && new java.io.File(item.getThumbnailPath()).exists()) {
                queueThumbIV.setImageURI(android.net.Uri.fromFile(new java.io.File(item.getThumbnailPath())));
            } else {
                queueThumbIV.setImageResource(R.drawable.ri_file_video_line);
            }

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
            if (item.getStatus() == QueueItem.Status.PROCESSING || item.getStatus() == QueueItem.Status.EXPORTING) {
                if (item.getProgress() < 0) {
                    return item.getStatus() == QueueItem.Status.EXPORTING
                            ? " - exporting video"
                            : " - extracting audio";
                }
                return " - " + item.getProgress() + "%";
            }
            return "";
        }
    }
}
