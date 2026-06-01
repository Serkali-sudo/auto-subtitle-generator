package com.serhat.autosub;

import org.json.JSONException;
import org.json.JSONObject;

public class VoskModelInfo {
    private final String id;
    private final String language;
    private final String locale;
    private final String size;
    private final String license;
    private final String downloadUrl;
    private final boolean mobileRecommended;
    private final String bundledAssetName;

    public VoskModelInfo(String id, String language, String locale, String size, String license,
                         String downloadUrl, boolean mobileRecommended, String bundledAssetName) {
        this.id = id;
        this.language = language;
        this.locale = locale;
        this.size = size;
        this.license = license;
        this.downloadUrl = downloadUrl;
        this.mobileRecommended = mobileRecommended;
        this.bundledAssetName = bundledAssetName;
    }

    public static VoskModelInfo fromJson(JSONObject object) throws JSONException {
        return new VoskModelInfo(
                object.getString("id"),
                object.getString("language"),
                object.optString("locale", ""),
                object.optString("size", ""),
                object.optString("license", ""),
                object.optString("downloadUrl", ""),
                object.optBoolean("mobileRecommended", false),
                object.optString("bundledAssetName", "")
        );
    }

    public String getId() {
        return id;
    }

    public String getLanguage() {
        return language;
    }

    public String getLocale() {
        return locale;
    }

    public String getSize() {
        return size;
    }

    public String getLicense() {
        return license;
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }

    public boolean isMobileRecommended() {
        return mobileRecommended;
    }

    public int getApproxSizeMb() {
        if (size == null || size.trim().isEmpty()) {
            return 0;
        }
        String normalized = size.trim().toUpperCase();
        try {
            if (normalized.endsWith("GB")) {
                String number = normalized.substring(0, normalized.length() - 2).trim();
                return (int) Math.ceil(Double.parseDouble(number) * 1024);
            }
            if (normalized.endsWith("G")) {
                String number = normalized.substring(0, normalized.length() - 1).trim();
                return (int) Math.ceil(Double.parseDouble(number) * 1024);
            }
            if (normalized.endsWith("MB")) {
                String number = normalized.substring(0, normalized.length() - 2).trim();
                return (int) Math.ceil(Double.parseDouble(number));
            }
            if (normalized.endsWith("M")) {
                String number = normalized.substring(0, normalized.length() - 1).trim();
                return (int) Math.ceil(Double.parseDouble(number));
            }
            return (int) Math.ceil(Double.parseDouble(normalized));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public boolean isVeryLarge() {
        return getApproxSizeMb() >= 1000;
    }

    public boolean shouldAutoLoadAfterDownload() {
        return mobileRecommended && getApproxSizeMb() > 0 && getApproxSizeMb() <= 300;
    }

    public String getBundledAssetName() {
        return bundledAssetName;
    }

    public boolean isBundled() {
        return bundledAssetName != null && !bundledAssetName.isEmpty();
    }

    public String getDisplayTitle() {
        return language + " - " + id;
    }

    public String getStatusLabel(boolean installed, boolean selected) {
        if (selected) {
            return "Selected";
        }
        if (isBundled()) {
            return "Bundled";
        }
        if (installed) {
            return "Downloaded";
        }
        return "Not downloaded";
    }

    public String getSubtitleLanguageCode() {
        String code = locale.toLowerCase();
        if (code.startsWith("en")) return "eng";
        if (code.startsWith("zh") || code.startsWith("cn")) return "chi";
        if (code.startsWith("ru")) return "rus";
        if (code.startsWith("fr")) return "fre";
        if (code.startsWith("de")) return "ger";
        if (code.startsWith("es")) return "spa";
        if (code.startsWith("pt")) return "por";
        if (code.startsWith("el")) return "gre";
        if (code.startsWith("tr")) return "tur";
        if (code.startsWith("vi")) return "vie";
        if (code.startsWith("it")) return "ita";
        if (code.startsWith("nl")) return "dut";
        if (code.startsWith("ca")) return "cat";
        if (code.startsWith("ar")) return "ara";
        if (code.startsWith("fa")) return "per";
        if (code.startsWith("tl")) return "tgl";
        if (code.startsWith("uk")) return "ukr";
        if (code.startsWith("kk") || code.startsWith("kz")) return "kaz";
        if (code.startsWith("sv")) return "swe";
        if (code.startsWith("ja")) return "jpn";
        if (code.startsWith("eo")) return "epo";
        if (code.startsWith("hi")) return "hin";
        if (code.startsWith("cs")) return "cze";
        if (code.startsWith("pl")) return "pol";
        if (code.startsWith("uz")) return "uzb";
        if (code.startsWith("ko")) return "kor";
        if (code.startsWith("br")) return "bre";
        if (code.startsWith("gu")) return "guj";
        if (code.startsWith("tg")) return "tgk";
        if (code.startsWith("te")) return "tel";
        if (code.startsWith("ky")) return "kir";
        if (code.startsWith("ka")) return "geo";
        return "und";
    }
}
