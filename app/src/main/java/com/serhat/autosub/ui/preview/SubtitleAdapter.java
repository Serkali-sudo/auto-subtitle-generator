package com.serhat.autosub.ui.preview;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.serhat.autosub.R;
import com.serhat.autosub.subtitles.SubtitleGenerator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class SubtitleAdapter extends RecyclerView.Adapter<SubtitleAdapter.SubtitleViewHolder> {

    private List<SubtitleGenerator.SubtitleEntry> subtitles = new ArrayList<>();
    private final List<SubtitleRowState> rowStates = new ArrayList<>();
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
        if (subtitles == null) {
            subtitles = new ArrayList<>();
        }
        int oldSize = rowStates.size();
        int submittedSize = subtitles.size();
        if (highlightedPosition >= submittedSize) highlightedPosition = -1;
        selectedPositions.removeIf(position -> position < 0 || position >= submittedSize);

        List<SubtitleRowState> newStates = buildRowStates(subtitles);
        boolean samePrefix = rowStates.size() <= newStates.size();
        if (samePrefix) {
            for (int i = 0; i < rowStates.size(); i++) {
                if (!sameIdentity(rowStates.get(i), newStates.get(i))) {
                    samePrefix = false;
                    break;
                }
            }
        }

        boolean sameStructure = rowStates.size() == newStates.size() && samePrefix;
        List<Integer> changedPositions = new ArrayList<>();
        if (samePrefix) {
            int compareCount = Math.min(rowStates.size(), newStates.size());
            for (int i = 0; i < compareCount; i++) {
                if (!newStates.get(i).equals(rowStates.get(i))) {
                    changedPositions.add(i);
                }
            }
        }

        this.subtitles = subtitles;
        rowStates.clear();
        rowStates.addAll(newStates);

        if (!samePrefix) {
            notifyDataSetChanged();
            return;
        }

        for (int position : changedPositions) {
            notifyItemChanged(position);
        }
        if (newStates.size() > oldSize) {
            notifyItemRangeInserted(oldSize, newStates.size() - oldSize);
        } else if (!sameStructure) {
            notifyDataSetChanged();
        }
    }

    public void setHighlightedPosition(int position) {
        if (highlightedPosition == position) return;
        int oldHighlightedPosition = highlightedPosition;
        highlightedPosition = position;
        if (oldHighlightedPosition != -1) {
            updateRowState(oldHighlightedPosition);
            notifyItemChanged(oldHighlightedPosition);
        }
        if (highlightedPosition != -1) {
            updateRowState(highlightedPosition);
            notifyItemChanged(highlightedPosition);
        }
    }

    public void setSelectionMode(boolean selectionMode) {
        isSelectionMode = selectionMode;
        if (!isSelectionMode) {
            selectedPositions.clear();
        }
        rebuildRowStates();
        notifyItemRangeChanged(0, subtitles.size());
    }

    public boolean isSelectionMode() {
        return isSelectionMode;
    }

    public Set<Integer> getSelectedPositions() {
        return new HashSet<>(selectedPositions);
    }

    public void toggleSelection(int position) {
        if (position < 0 || position >= subtitles.size()) {
            return;
        }
        if (selectedPositions.contains(position)) {
            selectedPositions.remove(position);
        } else {
            selectedPositions.add(position);
        }
        updateRowState(position);
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
                    holder.itemView.getContext().getResources().getColor(R.color.subtitle_item_background));
            }
        }
    }

    class SubtitleViewHolder extends RecyclerView.ViewHolder {
        TextView numberTV, timeTV, textTV, translationTV;
        ImageButton playBT, editBT, deleteBT;

        SubtitleViewHolder(@NonNull View itemView) {
            super(itemView);
            numberTV = itemView.findViewById(R.id.numberTV);
            timeTV = itemView.findViewById(R.id.timeTV);
            textTV = itemView.findViewById(R.id.textTV);
            translationTV = itemView.findViewById(R.id.translationTV);
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
            translationTV.setText(entry.getTranslationText());
            translationTV.setVisibility(entry.hasTranslation() ? View.VISIBLE : View.GONE);
            
            if (isSelectionMode) {
                itemView.setBackgroundColor(isSelected ?
                    itemView.getContext().getResources().getColor(R.color.selected_subtitle,itemView.getContext().getTheme()) :
                    itemView.getContext().getResources().getColor(R.color.subtitle_item_background,itemView.getContext().getTheme()));
            } else {
                itemView.setBackgroundColor(isHighlighted ?
                    itemView.getContext().getResources().getColor(R.color.highlighted_subtitle,itemView.getContext().getTheme()) :
                    itemView.getContext().getResources().getColor(R.color.subtitle_item_background,itemView.getContext().getTheme()));
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
            updateRowState(position);
            notifyItemChanged(position);
        }
    }

    public void deleteSubtitle(int position) {
        if (position >= 0 && position < subtitles.size()) {
            subtitles.remove(position);
            notifyItemRemoved(position);
            notifyItemRangeChanged(position, subtitles.size() - position);
            rebuildRowStates();
        }
    }

    private List<SubtitleRowState> buildRowStates(List<SubtitleGenerator.SubtitleEntry> entries) {
        List<SubtitleRowState> states = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            states.add(new SubtitleRowState(entries.get(i), i == highlightedPosition, selectedPositions.contains(i)));
        }
        return states;
    }

    private void updateRowState(int position) {
        if (position < 0 || position >= subtitles.size() || position >= rowStates.size()) {
            return;
        }
        rowStates.set(position, new SubtitleRowState(subtitles.get(position),
                position == highlightedPosition, selectedPositions.contains(position)));
    }

    private void rebuildRowStates() {
        rowStates.clear();
        rowStates.addAll(buildRowStates(subtitles));
    }

    private boolean sameIdentity(SubtitleRowState first, SubtitleRowState second) {
        return first.number == second.number
                && Objects.equals(first.startTime, second.startTime);
    }

    private static class SubtitleRowState {
        private final int number;
        private final String startTime;
        private final String endTime;
        private final String text;
        private final String translationText;
        private final boolean highlighted;
        private final boolean selected;

        private SubtitleRowState(SubtitleGenerator.SubtitleEntry entry, boolean highlighted, boolean selected) {
            number = entry.getNumber();
            startTime = entry.getStartTime();
            endTime = entry.getEndTime();
            text = entry.getText();
            translationText = entry.getTranslationText();
            this.highlighted = highlighted;
            this.selected = selected;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof SubtitleRowState)) return false;
            SubtitleRowState other = (SubtitleRowState) obj;
            return number == other.number
                    && highlighted == other.highlighted
                    && selected == other.selected
                    && Objects.equals(startTime, other.startTime)
                    && Objects.equals(endTime, other.endTime)
                    && Objects.equals(text, other.text)
                    && Objects.equals(translationText, other.translationText);
        }

        @Override
        public int hashCode() {
            return Objects.hash(number, startTime, endTime, text, translationText, highlighted, selected);
        }
    }
}
