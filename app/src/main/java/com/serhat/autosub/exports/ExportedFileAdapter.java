package com.serhat.autosub.exports;


import android.content.ClipData;
import android.os.Build;
import android.view.DragEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.button.MaterialButton;
import com.serhat.autosub.R;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class ExportedFileAdapter extends RecyclerView.Adapter<ExportedFileAdapter.ExportViewHolder> {
    public static final int VIEW_TYPE_LIST = 0;
    public static final int VIEW_TYPE_GRID = 1;

    private final List<ExportedFileItem> items = new ArrayList<>();
    private final List<ExportRowState> rowStates = new ArrayList<>();
    private final Set<String> selectedPaths = new HashSet<>();
    private Listener listener;
    private boolean selectionMode = false;
    private int layoutMode = VIEW_TYPE_LIST;
    private ThumbnailResolver thumbnailResolver;

    public ExportedFileAdapter() {
        setHasStableIds(true);
    }

    public interface Listener {
        void onOpen(ExportedFileItem item);
        void onSelectionChanged();
        void onDragMove(List<ExportedFileItem> items, ExportedFileItem folder);
        void onShare(ExportedFileItem item);
        void onPlay(ExportedFileItem item);
    }

    public interface ThumbnailResolver {
        String getThumbnailPath(ExportedFileItem item);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setThumbnailResolver(ThumbnailResolver resolver) {
        this.thumbnailResolver = resolver;
    }

    public void setLayoutMode(int layoutMode) {
        if (this.layoutMode == layoutMode) {
            return;
        }
        this.layoutMode = layoutMode;
        notifyDataSetChanged();
    }

    public int getLayoutMode() {
        return layoutMode;
    }

    public void submit(List<ExportedFileItem> nextItems) {
        if (nextItems == null) {
            nextItems = new ArrayList<>();
        }
        List<ExportedFileItem> submittedItems = nextItems;
        selectedPaths.removeIf(path -> findByPath(submittedItems, path) == null);

        List<ExportRowState> newStates = new ArrayList<>(submittedItems.size());
        for (ExportedFileItem item : submittedItems) {
            newStates.add(new ExportRowState(item));
        }

        boolean sameStructure = items.size() == submittedItems.size() && rowStates.size() == submittedItems.size();
        if (sameStructure) {
            for (int i = 0; i < submittedItems.size(); i++) {
                if (!Objects.equals(pathOf(items.get(i)), pathOf(submittedItems.get(i)))) {
                    sameStructure = false;
                    break;
                }
            }
        }

        if (!sameStructure) {
            items.clear();
            items.addAll(submittedItems);
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
        items.addAll(submittedItems);
        rowStates.clear();
        rowStates.addAll(newStates);

        for (int position : changedPositions) {
            notifyItemChanged(position);
        }
    }

    public void setSelectionMode(boolean selectionMode) {
        this.selectionMode = selectionMode;
        if (!selectionMode) {
            selectedPaths.clear();
        }
        rebuildRowStates();
        notifyItemRangeChanged(0, items.size());
    }

    public boolean isSelectionMode() {
        return selectionMode;
    }

    public void toggleSelection(ExportedFileItem item) {
        if (item == null) {
            return;
        }
        String path = item.getFile().getAbsolutePath();
        if (selectedPaths.contains(path)) {
            selectedPaths.remove(path);
        } else {
            selectedPaths.add(path);
        }
        int position = items.indexOf(item);
        if (position >= 0) {
            updateRowState(position);
            notifyItemChanged(position);
        }
        if (listener != null) listener.onSelectionChanged();
    }

    public void select(ExportedFileItem item) {
        if (item == null) {
            return;
        }
        selectedPaths.add(item.getFile().getAbsolutePath());
        int position = items.indexOf(item);
        if (position >= 0) {
            updateRowState(position);
            notifyItemChanged(position);
        }
        if (listener != null) listener.onSelectionChanged();
    }

    public void selectAllVisible() {
        for (ExportedFileItem item : items) {
            selectedPaths.add(item.getFile().getAbsolutePath());
        }
        rebuildRowStates();
        notifyItemRangeChanged(0, items.size());
        if (listener != null) listener.onSelectionChanged();
    }

    public int getSelectedCount() {
        return selectedPaths.size();
    }

    public List<ExportedFileItem> getSelectedItems() {
        List<ExportedFileItem> selected = new ArrayList<>();
        for (String path : selectedPaths) {
            ExportedFileItem item = findByPath(path);
            if (item != null) {
                selected.add(item);
            }
        }
        return selected;
    }

    private ExportedFileItem findByPath(String path) {
        return findByPath(items, path);
    }

    private ExportedFileItem findByPath(List<ExportedFileItem> source, String path) {
        if (source == null) {
            return null;
        }
        for (ExportedFileItem item : source) {
            if (item.getFile().getAbsolutePath().equals(path)) {
                return item;
            }
        }
        return null;
    }

    private String pathOf(ExportedFileItem item) {
        return item == null ? "" : item.getFile().getAbsolutePath();
    }

    private void updateRowState(int position) {
        if (position < 0 || position >= items.size() || position >= rowStates.size()) {
            return;
        }
        rowStates.set(position, new ExportRowState(items.get(position)));
    }

    private void rebuildRowStates() {
        rowStates.clear();
        for (ExportedFileItem item : items) {
            rowStates.add(new ExportRowState(item));
        }
    }

    @Override
    public int getItemViewType(int position) {
        return layoutMode;
    }

    @NonNull
    @Override
    public ExportViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layoutId = viewType == VIEW_TYPE_GRID ? R.layout.item_export_grid : R.layout.item_export;
        View view = LayoutInflater.from(parent.getContext()).inflate(layoutId, parent, false);
        return new ExportViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ExportViewHolder holder, int position) {
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
        return stableId(pathOf(items.get(position)));
    }

    private long stableId(String value) {
        long result = 1125899906842597L;
        if (value == null) return result;
        for (int i = 0; i < value.length(); i++) {
            result = 31 * result + value.charAt(i);
        }
        return result;
    }

    class ExportViewHolder extends RecyclerView.ViewHolder {
        ImageView iconIV;
        TextView titleTV, detailTV;
        CheckBox selectCB;
        MaterialButton playBT, shareBT;
        private String boundThumbPath;
        private ExportedFileItem.Type boundIconType;
        private int boundLayoutMode = -1;

        ExportViewHolder(@NonNull View itemView) {
            super(itemView);
            iconIV = itemView.findViewById(R.id.exportIconIV);
            titleTV = itemView.findViewById(R.id.exportTitleTV);
            detailTV = itemView.findViewById(R.id.exportDetailTV);
            selectCB = itemView.findViewById(R.id.exportSelectCB);
            playBT = itemView.findViewById(R.id.exportPlayBT);
            shareBT = itemView.findViewById(R.id.exportShareBT);
        }

        void bind(ExportedFileItem item) {
            titleTV.setText(item.getName());
            detailTV.setText(item.getDetail());

            boolean folder = item.getType() == ExportedFileItem.Type.FOLDER;
            boolean video = item.getType() == ExportedFileItem.Type.VIDEO;

            bindIcon(item, video);

            boolean selected = selectedPaths.contains(item.getFile().getAbsolutePath());
            selectCB.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
            selectCB.setOnCheckedChangeListener(null);
            selectCB.setChecked(selected);
            selectCB.setOnCheckedChangeListener((buttonView, isChecked) -> toggleSelection(item));
            playBT.setVisibility(video && !selectionMode ? View.VISIBLE : View.GONE);
            shareBT.setVisibility(!folder && !selectionMode ? View.VISIBLE : View.GONE);
            itemView.setSelected(selected);
            if (itemView instanceof MaterialCardView) {
                MaterialCardView card = (MaterialCardView) itemView;
                card.setStrokeWidth(selected ? dp(2) : 0);
                card.setStrokeColor(ContextCompat.getColor(itemView.getContext(), R.color.m3_primary));
            }

            itemView.setOnClickListener(v -> {
                if (selectionMode) {
                    toggleSelection(item);
                } else if (listener != null) {
                    listener.onOpen(item);
                }
            });
            itemView.setOnLongClickListener(v -> {
                if (!selectionMode && listener != null) {
                    select(item);
                } else if (!selected) {
                    select(item);
                }
                if (!folder) {
                    startDrag(itemView);
                }
                return true;
            });
            shareBT.setOnClickListener(v -> {
                if (listener != null) listener.onShare(item);
            });
            playBT.setOnClickListener(v -> {
                if (listener != null) listener.onPlay(item);
            });
            itemView.setOnDragListener(folder ? (v, event) -> {
                if (event.getAction() == DragEvent.ACTION_DROP && listener != null) {
                    List<ExportedFileItem> draggedItems = getSelectedItems();
                    if (draggedItems.isEmpty() && event.getClipData() != null
                            && event.getClipData().getItemCount() > 0) {
                        ExportedFileItem draggedItem = findByPath(event.getClipData().getItemAt(0).getText().toString());
                        if (draggedItem != null) draggedItems.add(draggedItem);
                    }
                    listener.onDragMove(draggedItems, item);
                    return true;
                }
                return event.getAction() == DragEvent.ACTION_DRAG_STARTED;
            } : null);
        }

        private void startDrag(View view) {
            List<ExportedFileItem> selectedItems = getSelectedItems();
            if (selectedItems.isEmpty()) {
                return;
            }
            ClipData data = ClipData.newPlainText("export_path",
                    selectedItems.get(0).getFile().getAbsolutePath());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                view.startDragAndDrop(data, new View.DragShadowBuilder(view), null, 0);
            } else {
                view.startDrag(data, new View.DragShadowBuilder(view), null, 0);
            }
        }

        private int iconFor(ExportedFileItem.Type type) {
            if (type == ExportedFileItem.Type.FOLDER) return R.drawable.ri_folder_open_line;
            if (type == ExportedFileItem.Type.VIDEO) return R.drawable.ri_file_video_line;
            if (type == ExportedFileItem.Type.SUBTITLE) return R.drawable.ri_file_text_line;
            return R.drawable.ri_export_line;
        }

        private int dp(int value) {
            return Math.round(value * itemView.getResources().getDisplayMetrics().density);
        }

        private void bindIcon(ExportedFileItem item, boolean video) {
            String thumbPath = "";
            if (video && thumbnailResolver != null) {
                thumbPath = thumbnailResolver.getThumbnailPath(item);
            }
            if (Objects.equals(boundThumbPath, thumbPath)
                    && boundIconType == item.getType()
                    && boundLayoutMode == layoutMode) {
                return;
            }
            boundThumbPath = thumbPath;
            boundIconType = item.getType();
            boundLayoutMode = layoutMode;

            if (video && thumbPath != null && !thumbPath.isEmpty() && new java.io.File(thumbPath).exists()) {
                iconIV.setImageURI(android.net.Uri.fromFile(new java.io.File(thumbPath)));
                iconIV.setImageTintList(null);
                iconIV.setPadding(0, 0, 0, 0);
                if (iconIV.getScaleType() != ImageView.ScaleType.CENTER_CROP) {
                    iconIV.setScaleType(ImageView.ScaleType.CENTER_CROP);
                }
            } else {
                iconIV.setImageResource(iconFor(item.getType()));
                android.util.TypedValue typedValue = new android.util.TypedValue();
                itemView.getContext().getTheme().resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true);
                iconIV.setImageTintList(android.content.res.ColorStateList.valueOf(typedValue.data));

                if (layoutMode == VIEW_TYPE_GRID) {
                    int p = Math.round(20 * itemView.getResources().getDisplayMetrics().density);
                    iconIV.setPadding(p, p, p, p);
                    iconIV.setScaleType(ImageView.ScaleType.FIT_CENTER);
                } else {
                    int p = Math.round(8 * itemView.getResources().getDisplayMetrics().density);
                    iconIV.setPadding(p, p, p, p);
                    iconIV.setScaleType(ImageView.ScaleType.FIT_CENTER);
                }
            }
        }
    }

    private class ExportRowState {
        private final String path;
        private final String name;
        private final String detail;
        private final ExportedFileItem.Type type;
        private final boolean selected;
        private final boolean selectionModeSnapshot;
        private final String thumbnailPath;

        private ExportRowState(ExportedFileItem item) {
            path = pathOf(item);
            name = item.getName();
            detail = item.getDetail();
            type = item.getType();
            selected = selectedPaths.contains(path);
            selectionModeSnapshot = selectionMode;
            thumbnailPath = item.getType() == ExportedFileItem.Type.VIDEO && thumbnailResolver != null
                    ? thumbnailResolver.getThumbnailPath(item)
                    : "";
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof ExportRowState)) return false;
            ExportRowState other = (ExportRowState) obj;
            return selected == other.selected
                    && selectionModeSnapshot == other.selectionModeSnapshot
                    && type == other.type
                    && Objects.equals(path, other.path)
                    && Objects.equals(name, other.name)
                    && Objects.equals(detail, other.detail)
                    && Objects.equals(thumbnailPath, other.thumbnailPath);
        }

        @Override
        public int hashCode() {
            return Objects.hash(path, name, detail, type, selected, selectionModeSnapshot, thumbnailPath);
        }
    }
}
