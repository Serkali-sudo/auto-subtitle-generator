package com.serhat.autosub.exports;

public class ExportRecord {
    public static final String TYPE_FOLDER = "folder";
    public static final String TYPE_VIDEO = "video";
    public static final String TYPE_SUBTITLE = "subtitle";

    private final long id;
    private final String path;
    private final String parentPath;
    private final String type;
    private final String modelId;
    private final String modelLanguage;
    private final String sourceVideoUri;
    private final String sourceVideoName;
    private final String exportKind;
    private final String format;
    private final long createdAt;

    public ExportRecord(long id, String path, String parentPath, String type, String modelId,
                        String modelLanguage, String sourceVideoUri, String sourceVideoName,
                        String exportKind, String format, long createdAt) {
        this.id = id;
        this.path = path;
        this.parentPath = parentPath;
        this.type = type;
        this.modelId = modelId;
        this.modelLanguage = modelLanguage;
        this.sourceVideoUri = sourceVideoUri;
        this.sourceVideoName = sourceVideoName;
        this.exportKind = exportKind;
        this.format = format;
        this.createdAt = createdAt;
    }

    public long getId() { return id; }
    public String getPath() { return path; }
    public String getParentPath() { return parentPath; }
    public String getType() { return type; }
    public String getModelId() { return modelId; }
    public String getModelLanguage() { return modelLanguage; }
    public String getSourceVideoUri() { return sourceVideoUri; }
    public String getSourceVideoName() { return sourceVideoName; }
    public String getExportKind() { return exportKind; }
    public String getFormat() { return format; }
    public long getCreatedAt() { return createdAt; }
}
