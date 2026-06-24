package com.serhat.autosub.exports;

import java.io.File;
import java.text.DateFormat;
import java.util.Date;

public class ExportedFileItem {
    public enum Type {
        FOLDER,
        VIDEO,
        SUBTITLE,
        OTHER
    }

    private final File file;
    private final File root;
    private final Type type;
    private final ExportRecord record;

    public ExportedFileItem(File file, File root) {
        this(file, root, null);
    }

    public ExportedFileItem(ExportRecord record, File root) {
        this(new File(record.getPath()), root, record);
    }

    private ExportedFileItem(File file, File root, ExportRecord record) {
        this.file = file;
        this.root = root;
        this.record = record;
        if (file.isDirectory()) {
            this.type = Type.FOLDER;
        } else if (ExportFileActions.isVideo(file)) {
            this.type = Type.VIDEO;
        } else if (ExportFileActions.isSubtitle(file)) {
            this.type = Type.SUBTITLE;
        } else {
            this.type = Type.OTHER;
        }
    }

    public File getFile() {
        return file;
    }

    public Type getType() {
        return type;
    }

    public ExportRecord getRecord() {
        return record;
    }

    public String getName() {
        return file.getName();
    }

    public String getDetail() {
        String location = getRelativeParent();
        if (type == Type.FOLDER) {
            return location.isEmpty() ? "Folder" : "Folder - " + location;
        }
        String size = formatSize(file.length());
        String modified = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(file.lastModified()));
        String model = record == null || record.getModelId() == null || record.getModelId().isEmpty()
                ? ""
                : " - " + record.getModelId();
        return size + " - " + modified + model + (location.isEmpty() ? "" : " - " + location);
    }

    public boolean matches(String query) {
        if (query == null || query.trim().isEmpty()) {
            return true;
        }
        String normalized = query.toLowerCase();
        return getName().toLowerCase().contains(normalized)
                || getRelativePath().toLowerCase().contains(normalized);
    }

    private String getRelativePath() {
        try {
            String rootPath = root.getCanonicalPath();
            String filePath = file.getCanonicalPath();
            if (filePath.startsWith(rootPath)) {
                String relative = filePath.substring(rootPath.length());
                while (relative.startsWith(File.separator)) {
                    relative = relative.substring(1);
                }
                return relative;
            }
        } catch (Exception ignored) {
        }
        return file.getName();
    }

    private String getRelativeParent() {
        File parent = file.getParentFile();
        if (parent == null || root.equals(parent)) {
            return "";
        }
        try {
            String rootPath = root.getCanonicalPath();
            String parentPath = parent.getCanonicalPath();
            if (parentPath.startsWith(rootPath)) {
                String relative = parentPath.substring(rootPath.length());
                while (relative.startsWith(File.separator)) {
                    relative = relative.substring(1);
                }
                return relative;
            }
        } catch (Exception ignored) {
        }
        return parent.getName();
    }

    private String formatSize(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) {
            return String.format(java.util.Locale.US, "%.1f GB", bytes / (1024f * 1024f * 1024f));
        }
        if (bytes >= 1024L * 1024L) {
            return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024f * 1024f));
        }
        if (bytes >= 1024L) {
            return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024f);
        }
        return bytes + " B";
    }
}
