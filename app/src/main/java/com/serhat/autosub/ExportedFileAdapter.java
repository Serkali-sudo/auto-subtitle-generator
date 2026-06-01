package com.serhat.autosub;

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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ExportedFileAdapter extends RecyclerView.Adapter<ExportedFileAdapter.ExportViewHolder> {
    public static final int VIEW_TYPE_LIST = 0;
    public static final int VIEW_TYPE_GRID = 1;

    private final List<ExportedFileItem> items = new ArrayList<>();
    private final Set<String> selectedPaths = new HashSet<>();
    private Listener listener;
    private boolean selectionMode = false;
    private int layoutMode = VIEW_TYPE_LIST;
    private ThumbnailResolver thumbnailResolver;

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
        this.layoutMode = layoutMode;
        notifyDataSetChanged();
    }

    public int getLayoutMode() {
        return layoutMode;
    }

    public void submit(List<ExportedFileItem> nextItems) {
        items.clear();
        items.addAll(nextItems);
        selectedPaths.removeIf(path -> findByPath(path) == null);
        notifyDataSetChanged();
    }

    public void setSelectionMode(boolean selectionMode) {
        this.selectionMode = selectionMode;
        if (!selectionMode) {
            selectedPaths.clear();
        }
        notifyDataSetChanged();
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
        notifyDataSetChanged();
        if (listener != null) listener.onSelectionChanged();
    }

    public void select(ExportedFileItem item) {
        if (item == null) {
            return;
        }
        selectedPaths.add(item.getFile().getAbsolutePath());
        notifyDataSetChanged();
        if (listener != null) listener.onSelectionChanged();
    }

    public void selectAllVisible() {
        for (ExportedFileItem item : items) {
            selectedPaths.add(item.getFile().getAbsolutePath());
        }
        notifyDataSetChanged();
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
        for (ExportedFileItem item : items) {
            if (item.getFile().getAbsolutePath().equals(path)) {
                return item;
            }
        }
        return null;
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

    class ExportViewHolder extends RecyclerView.ViewHolder {
        ImageView iconIV;
        TextView titleTV, detailTV;
        CheckBox selectCB;
        MaterialButton playBT, shareBT;

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

            String thumbPath = "";
            if (video && thumbnailResolver != null) {
                thumbPath = thumbnailResolver.getThumbnailPath(item);
            }

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
    }
}
