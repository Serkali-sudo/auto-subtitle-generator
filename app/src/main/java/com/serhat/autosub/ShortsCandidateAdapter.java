package com.serhat.autosub;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ShortsCandidateAdapter extends RecyclerView.Adapter<ShortsCandidateAdapter.Holder> {
    public interface Listener {
        void onPreview(ShortsCandidate candidate);
        void onEdit(ShortsCandidate candidate);
        void onChanged();
    }

    private final List<ShortsCandidate> items = new ArrayList<>();
    private Listener listener;
    private boolean phraseMontage;
    private boolean silenceRemoval;

    public void setListener(Listener listener) { this.listener = listener; }
    public void setPhraseMontage(boolean phraseMontage) {
        this.phraseMontage = phraseMontage;
        notifyDataSetChanged();
    }
    public void setSilenceRemoval(boolean silenceRemoval) {
        this.silenceRemoval = silenceRemoval;
        notifyDataSetChanged();
    }
    public void submit(List<ShortsCandidate> candidates) {
        items.clear();
        if (candidates != null) items.addAll(candidates);
        notifyDataSetChanged();
    }
    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_short_candidate, parent, false));
    }
    @Override public void onBindViewHolder(@NonNull Holder holder, int position) { holder.bind(items.get(position)); }
    @Override public int getItemCount() { return items.size(); }

    class Holder extends RecyclerView.ViewHolder {
        final CheckBox selected;
        final TextView title, score, time, reason, renderStatus;
        final MaterialSwitch captions;
        final Spinner layer;
        final MaterialButton edit, preview;

        Holder(View view) {
            super(view);
            selected = view.findViewById(R.id.candidateSelectedCB);
            title = view.findViewById(R.id.candidateTitleTV);
            score = view.findViewById(R.id.candidateScoreTV);
            time = view.findViewById(R.id.candidateTimeTV);
            reason = view.findViewById(R.id.candidateReasonTV);
            captions = view.findViewById(R.id.candidateCaptionsSwitch);
            layer = view.findViewById(R.id.candidateLayerSpinner);
            edit = view.findViewById(R.id.candidateEditBT);
            preview = view.findViewById(R.id.candidatePreviewBT);
            renderStatus = view.findViewById(R.id.candidateRenderStatusTV);
            layer.setAdapter(new ArrayAdapter<>(view.getContext(), android.R.layout.simple_spinner_dropdown_item,
                    new String[]{"Original", "Translation", "Both"}));
        }

        void bind(ShortsCandidate item) {
            title.setText(item.getTitle());
            score.setText(item.getScore() + "/100");
            score.setVisibility(phraseMontage ? View.GONE : View.VISIBLE);
            time.setText(format(item.getStartMs()) + " – " + format(item.getEndMs()) +
                    " • " + String.format(Locale.US, "%.1fs", item.getDurationMs() / 1000f));
            reason.setText(item.getHook().isEmpty() ? item.getReason() : item.getHook() + "\n" + item.getReason());
            selected.setOnCheckedChangeListener(null);
            selected.setChecked(item.isSelected());
            selected.setOnCheckedChangeListener((button, checked) -> { item.setSelected(checked); changed(); });
            captions.setOnCheckedChangeListener(null);
            captions.setVisibility(phraseMontage || silenceRemoval ? View.GONE : View.VISIBLE);
            layer.setVisibility(phraseMontage || silenceRemoval ? View.GONE : View.VISIBLE);
            captions.setChecked(item.isBurnCaptions());
            captions.setOnCheckedChangeListener((button, checked) -> { item.setBurnCaptions(checked); layer.setEnabled(checked); changed(); });
            layer.setEnabled(item.isBurnCaptions());
            layer.setOnItemSelectedListener(null);
            layer.setSelection(item.getCaptionLayer() == SubtitleGenerator.SubtitleLayerMode.TRANSLATION ? 1
                    : item.getCaptionLayer() == SubtitleGenerator.SubtitleLayerMode.DOUBLE ? 2 : 0, false);
            layer.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                    SubtitleGenerator.SubtitleLayerMode value = position == 1
                            ? SubtitleGenerator.SubtitleLayerMode.TRANSLATION
                            : position == 2 ? SubtitleGenerator.SubtitleLayerMode.DOUBLE : SubtitleGenerator.SubtitleLayerMode.ORIGINAL;
                    if (item.getCaptionLayer() != value) { item.setCaptionLayer(value); changed(); }
                }
                @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
            });
            edit.setOnClickListener(v -> { if (listener != null) listener.onEdit(item); });
            preview.setOnClickListener(v -> { if (listener != null) listener.onPreview(item); });
            itemView.setOnClickListener(v -> { if (listener != null) listener.onPreview(item); });
            if (item.getRenderState() == ShortsCandidate.RenderState.PENDING) renderStatus.setVisibility(View.GONE);
            else {
                renderStatus.setVisibility(View.VISIBLE);
                String value = item.getRenderState().name().toLowerCase(Locale.US);
                if (!item.getErrorMessage().isEmpty()) value += " • " + item.getErrorMessage();
                if (!item.getOutputPath().isEmpty()) value += " • " + item.getOutputPath();
                renderStatus.setText(value);
            }
        }

        private void changed() { if (listener != null) listener.onChanged(); }
    }

    private static String format(long ms) {
        long total = ms / 1000;
        return String.format(Locale.US, "%02d:%02d", total / 60, total % 60);
    }
}
