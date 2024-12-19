package com.serhat.autosub;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SubtitleAdapter extends RecyclerView.Adapter<SubtitleAdapter.SubtitleViewHolder> {

    private List<SubtitleGenerator.SubtitleEntry> subtitles = new ArrayList<>();
    private int highlightedPosition = -1;
    private OnSubtitleClickListener onSubtitleClickListener;
    private OnPlayClickListener onPlayClickListener;
    private OnDeleteClickListener onDeleteClickListener;
    private boolean isSelectionMode = false;
    private Set<Integer> selectedPositions = new HashSet<>();
    private OnItemLongClickListener onItemLongClickListener;

    public interface OnSubtitleClickListener {
        void onSubtitleClick(int position, SubtitleGenerator.SubtitleEntry entry);
    }

    public interface OnPlayClickListener {
        void onPlayClick(long startTimeMs);
    }

    public interface OnDeleteClickListener {
        void onDeleteClick(int position);
    }

    public interface OnItemLongClickListener {
        void onItemLongClick(int position);
    }

    public void setOnSubtitleClickListener(OnSubtitleClickListener listener) {
        this.onSubtitleClickListener = listener;
    }

    public void setOnPlayClickListener(OnPlayClickListener listener) {
        this.onPlayClickListener = listener;
    }

    public void setOnDeleteClickListener(OnDeleteClickListener listener) {
        this.onDeleteClickListener = listener;
    }

    public void setOnItemLongClickListener(OnItemLongClickListener listener) {
        this.onItemLongClickListener = listener;
    }

    public void setSubtitles(List<SubtitleGenerator.SubtitleEntry> subtitles) {
        highlightedPosition = -1;
        this.subtitles = subtitles;
        notifyDataSetChanged();
    }

    public void setHighlightedPosition(int position) {
        int oldHighlightedPosition = highlightedPosition;
        highlightedPosition = position;
        if (oldHighlightedPosition != -1) {
            notifyItemChanged(oldHighlightedPosition);
        }
        if (highlightedPosition != -1) {
            notifyItemChanged(highlightedPosition);
        }
    }

    public void setSelectionMode(boolean selectionMode) {
        isSelectionMode = selectionMode;
        if (!isSelectionMode) {
            selectedPositions.clear();
        }
        notifyDataSetChanged();
    }

    public boolean isSelectionMode() {
        return isSelectionMode;
    }

    public Set<Integer> getSelectedPositions() {
        return new HashSet<>(selectedPositions);
    }

    public void toggleSelection(int position) {
        if (selectedPositions.contains(position)) {
            selectedPositions.remove(position);
        } else {
            selectedPositions.add(position);
        }
        notifyItemChanged(position, "selection");
    }

    @NonNull
    @Override
    public SubtitleViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_subtitle, parent, false);
        return new SubtitleViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull SubtitleViewHolder holder, int position) {
        SubtitleGenerator.SubtitleEntry entry = subtitles.get(position);
        holder.bind(entry, position == highlightedPosition, selectedPositions.contains(position));
    }

    @Override
    public int getItemCount() {
        return subtitles.size();
    }

    @Override
    public void onBindViewHolder(@NonNull SubtitleViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads);
        } else {
            if (payloads.contains("selection")) {
                holder.itemView.setActivated(selectedPositions.contains(position));
                holder.itemView.setBackgroundColor(selectedPositions.contains(position) ? 
                    holder.itemView.getContext().getResources().getColor(R.color.selected_subtitle) : 
                    Color.TRANSPARENT);
            }
        }
    }

    class SubtitleViewHolder extends RecyclerView.ViewHolder {
        TextView numberTV, timeTV, textTV;
        ImageButton playBT, editBT, deleteBT;

        SubtitleViewHolder(@NonNull View itemView) {
            super(itemView);
            numberTV = itemView.findViewById(R.id.numberTV);
            timeTV = itemView.findViewById(R.id.timeTV);
            textTV = itemView.findViewById(R.id.textTV);
            playBT = itemView.findViewById(R.id.playBT);
            editBT = itemView.findViewById(R.id.editBT);
            deleteBT = itemView.findViewById(R.id.deleteBT);

            editBT.setOnClickListener(v -> {
                int position = getBindingAdapterPosition();
                if (position != RecyclerView.NO_POSITION && onSubtitleClickListener != null) {
                    onSubtitleClickListener.onSubtitleClick(position, subtitles.get(position));
                }
            });

            playBT.setOnClickListener(v -> {
                int position = getBindingAdapterPosition();
                if (position != RecyclerView.NO_POSITION && onPlayClickListener != null) {
                    SubtitleGenerator.SubtitleEntry entry = subtitles.get(position);
                    long startTimeMs = parseTimeToMs(entry.getStartTime());
                    onPlayClickListener.onPlayClick(startTimeMs);
                }
            });

            deleteBT.setOnClickListener(v -> {
                int position = getBindingAdapterPosition();
                if (position != RecyclerView.NO_POSITION && onDeleteClickListener != null) {
                    onDeleteClickListener.onDeleteClick(position);
                }
            });

            itemView.setOnLongClickListener(v -> {
                int position = getBindingAdapterPosition();
                if (position != RecyclerView.NO_POSITION && onItemLongClickListener != null) {
                    onItemLongClickListener.onItemLongClick(position);
                    return true;
                }
                return false;
            });

            itemView.setOnClickListener(v -> {
                if (isSelectionMode) {
                    toggleSelection(getBindingAdapterPosition());
                } else {
                    int position = getBindingAdapterPosition();
                    if (position != RecyclerView.NO_POSITION && onPlayClickListener != null) {
                        SubtitleGenerator.SubtitleEntry entry = subtitles.get(position);
                        long startTimeMs = parseTimeToMs(entry.getStartTime());
                        onPlayClickListener.onPlayClick(startTimeMs);
                    }
                }
            });
        }

        void bind(SubtitleGenerator.SubtitleEntry entry, boolean isHighlighted, boolean isSelected) {
            numberTV.setText(String.valueOf(entry.getNumber()));
            timeTV.setText(String.format("%s --> %s", entry.getStartTime(), entry.getEndTime()));
            textTV.setText(entry.getText());
            
            if (isSelectionMode) {
                itemView.setBackgroundColor(isSelected ? 
                    itemView.getContext().getResources().getColor(R.color.selected_subtitle,itemView.getContext().getTheme()) :
                    Color.TRANSPARENT);
            } else {
                itemView.setBackgroundColor(isHighlighted ? 
                    itemView.getContext().getResources().getColor(R.color.highlighted_subtitle,itemView.getContext().getTheme()) :
                    Color.TRANSPARENT);
            }

            itemView.setActivated(isSelected);
        }
    }

    private long parseTimeToMs(String timeString) {
        String[] parts = timeString.split("[:,]");
        return Long.parseLong(parts[0]) * 3600000L +
               Long.parseLong(parts[1]) * 60000L +
               Long.parseLong(parts[2]) * 1000L +
               Long.parseLong(parts[3]);
    }

    public List<SubtitleGenerator.SubtitleEntry> getSubtitles() {
        return subtitles;
    }

    public void updateSubtitle(int position, String newText) {
        if (position >= 0 && position < subtitles.size()) {
            subtitles.get(position).setText(newText);
            notifyItemChanged(position);
        }
    }

    public void deleteSubtitle(int position) {
        if (position >= 0 && position < subtitles.size()) {
            subtitles.remove(position);
            notifyItemRemoved(position);
            notifyItemRangeChanged(position, subtitles.size() - position);
        }
    }
}
