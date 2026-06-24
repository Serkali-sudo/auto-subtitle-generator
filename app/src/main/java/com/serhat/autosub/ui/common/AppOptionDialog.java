package com.serhat.autosub.ui.common;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.serhat.autosub.R;

public final class AppOptionDialog {

    public interface OptionCallback {
        void onSelected(int which);
    }

    public interface CheckedOptionCallback {
        void onSelected(int which, boolean checked);
    }

    public static final class Option {
        private final int iconResId;
        private final String title;
        private final String description;

        public Option(String title, String description) {
            this(0, title, description);
        }

        public Option(int iconResId, String title, String description) {
            this.iconResId = iconResId;
            this.title = title;
            this.description = description;
        }
    }

    private AppOptionDialog() {
    }

    public static void show(Context context, String title, @Nullable String message,
                            Option[] options, OptionCallback callback) {
        showInternal(context, title, message, options, null, false,
                (which, checked) -> callback.onSelected(which));
    }

    public static void showWithCheckbox(Context context, String title, @Nullable String message,
                                        Option[] options, String checkboxText, boolean checked,
                                        CheckedOptionCallback callback) {
        showInternal(context, title, message, options, checkboxText, checked, callback);
    }

    private static void showInternal(Context context, String title, @Nullable String message,
                                     Option[] options, @Nullable String checkboxText,
                                     boolean checked, CheckedOptionCallback callback) {
        int horizontalPadding = dp(context, 24);
        int verticalPadding = dp(context, 8);
        int surfaceColor = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface,
                ContextCompat.getColor(context, R.color.m3_surface));
        int onSurfaceColor = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface,
                ContextCompat.getColor(context, R.color.m3_on_surface));
        int onSurfaceVariantColor = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant,
                onSurfaceColor);
        int outlineColor = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutline,
                ContextCompat.getColor(context, R.color.panel_stroke));
        int primaryColor = MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimary,
                ContextCompat.getColor(context, R.color.m3_primary));
        int rippleColor = MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimaryContainer,
                ContextCompat.getColor(context, R.color.m3_primary_container));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(horizontalPadding, verticalPadding, horizontalPadding, dp(context, 4));

        if (message != null && !message.trim().isEmpty()) {
            TextView messageView = new TextView(context);
            messageView.setText(message);
            messageView.setTextSize(14);
            messageView.setLineSpacing(dp(context, 2), 1f);
            messageView.setTextColor(onSurfaceVariantColor);
            content.addView(messageView, matchWrap());
        }

        CheckBox checkBox = null;
        if (checkboxText != null && !checkboxText.trim().isEmpty()) {
            checkBox = new CheckBox(context);
            checkBox.setText(checkboxText);
            checkBox.setTextSize(14);
            checkBox.setChecked(checked);
            LinearLayout.LayoutParams checkParams = matchWrap();
            checkParams.topMargin = dp(context, 8);
            content.addView(checkBox, checkParams);
        }

        LinearLayout optionList = new LinearLayout(context);
        optionList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listParams = matchWrap();
        listParams.topMargin = dp(context, 8);
        content.addView(optionList, listParams);

        AlertDialog[] dialogRef = new AlertDialog[1];
        CheckBox finalCheckBox = checkBox;
        for (int i = 0; i < options.length; i++) {
            int index = i;
            MaterialCardView card = new MaterialCardView(context);
            card.setRadius(dp(context, 8));
            card.setCardElevation(0);
            card.setStrokeWidth(dp(context, 1));
            card.setStrokeColor(outlineColor);
            card.setCardBackgroundColor(surfaceColor);
            card.setRippleColor(ColorStateList.valueOf(rippleColor));
            card.setClickable(true);
            card.setFocusable(true);

            LinearLayout cardContent = new LinearLayout(context);
            cardContent.setOrientation(LinearLayout.HORIZONTAL);
            cardContent.setGravity(android.view.Gravity.CENTER_VERTICAL);
            cardContent.setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12));

            if (options[i].iconResId != 0) {
                ImageView icon = new ImageView(context);
                icon.setImageResource(options[i].iconResId);
                icon.setColorFilter(primaryColor);
                LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(context, 28), dp(context, 28));
                iconParams.rightMargin = dp(context, 12);
                cardContent.addView(icon, iconParams);
            }

            LinearLayout textContent = new LinearLayout(context);
            textContent.setOrientation(LinearLayout.VERTICAL);

            TextView optionTitle = new TextView(context);
            optionTitle.setText(options[i].title);
            optionTitle.setTextSize(16);
            optionTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            optionTitle.setTextColor(onSurfaceColor);
            textContent.addView(optionTitle, matchWrap());

            TextView optionDescription = new TextView(context);
            optionDescription.setText(options[i].description);
            optionDescription.setTextSize(13);
            optionDescription.setLineSpacing(dp(context, 2), 1f);
            optionDescription.setTextColor(onSurfaceVariantColor);
            LinearLayout.LayoutParams descriptionParams = matchWrap();
            descriptionParams.topMargin = dp(context, 4);
            textContent.addView(optionDescription, descriptionParams);
            cardContent.addView(textContent, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            card.addView(cardContent);
            card.setOnClickListener(v -> {
                if (dialogRef[0] != null) {
                    dialogRef[0].dismiss();
                }
                callback.onSelected(index, finalCheckBox != null && finalCheckBox.isChecked());
            });

            LinearLayout.LayoutParams cardParams = matchWrap();
            cardParams.topMargin = i == 0 ? 0 : dp(context, 8);
            optionList.addView(card, cardParams);
        }

        ScrollView scrollView = new ScrollView(context);
        scrollView.addView(content);

        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setView(scrollView)
                .setNegativeButton("Cancel", null)
                .create();
        dialogRef[0] = dialog;
        dialog.show();
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
