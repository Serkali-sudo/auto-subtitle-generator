package com.serhat.autosub;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.serhat.autosub.databinding.FragmentSettingsBinding;

public class SettingsFragment extends Fragment {

    private FragmentSettingsBinding binding;
    private MainViewModel viewModel;
    private boolean syncingExportLocation;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentSettingsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);

        setupActions();
        observeViewModel();
    }

    private void setupActions() {
        binding.batchFormatToggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                String format = checkedId == R.id.batchVttBT ? "vtt" : "srt";
                viewModel.setBatchFormat(format);
            }
        });

        binding.exportLocationToggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked && !syncingExportLocation) {
                ExportSettings.setMode(requireContext(),
                        checkedId == R.id.exportPublicBT ? ExportSettings.MODE_PUBLIC : ExportSettings.MODE_PRIVATE);
            }
        });

        binding.shortsUppercaseSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            viewModel.setShortsUppercase(isChecked);
        });

        binding.shortsWordByWordSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            viewModel.setShortsWordByWordDefault(isChecked);
        });

        binding.skipShortsDialogSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            viewModel.setSkipShortsDialog(isChecked);
        });

        binding.completionNotificationsSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            viewModel.setShowCompletionNotifications(isChecked);
        });

        binding.showRamUsageSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            viewModel.setShowRamUsage(isChecked);
        });

        binding.shortsSizeSlider.addOnChangeListener((slider, value, fromUser) -> {
            viewModel.setShortsCaptionSize(value);
        });
    }

    private void observeViewModel() {
        viewModel.getBatchFormat().observe(getViewLifecycleOwner(), format -> {
            binding.batchFormatToggle.check("vtt".equalsIgnoreCase(format) ? R.id.batchVttBT : R.id.batchSrtBT);
        });

        syncingExportLocation = true;
        binding.exportLocationToggle.check(ExportSettings.MODE_PUBLIC.equals(ExportSettings.getMode(requireContext()))
                ? R.id.exportPublicBT
                : R.id.exportPrivateBT);
        syncingExportLocation = false;

        viewModel.getShortsUppercase().observe(getViewLifecycleOwner(), uppercase -> {
            binding.shortsUppercaseSwitch.setChecked(uppercase);
        });

        viewModel.getShortsWordByWordDefault().observe(getViewLifecycleOwner(), enabled -> {
            binding.shortsWordByWordSwitch.setChecked(enabled);
        });

        viewModel.getSkipShortsDialog().observe(getViewLifecycleOwner(), skip -> {
            binding.skipShortsDialogSwitch.setChecked(skip);
        });

        viewModel.getShowCompletionNotifications().observe(getViewLifecycleOwner(), enabled -> {
            binding.completionNotificationsSwitch.setChecked(enabled);
        });

        viewModel.getShowRamUsage().observe(getViewLifecycleOwner(), enabled -> {
            binding.showRamUsageSwitch.setChecked(enabled);
        });

        viewModel.getShortsCaptionSize().observe(getViewLifecycleOwner(), size -> {
            binding.shortsSizeSlider.setValue(size);
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
