package com.sortcam.app.model;

/**
 * Historical class name kept for database/UI compatibility.
 * A record can now represent either a photo or a video.
 */
public class PhotoRecord {
    public static final String TYPE_PHOTO = "photo";
    public static final String TYPE_VIDEO = "video";

    public long id;
    public String uri;
    public long categoryId;
    public long capturedAt;
    public String tags;
    public String memo;
    public String ocrText;
    public String aiIndex;
    public String mediaType;
    public long durationMs;

    public PhotoRecord(long id, String uri, long categoryId, long capturedAt,
                       String tags, String memo, String ocrText, String aiIndex,
                       String mediaType, long durationMs) {
        this.id = id;
        this.uri = uri;
        this.categoryId = categoryId;
        this.capturedAt = capturedAt;
        this.tags = tags;
        this.memo = memo;
        this.ocrText = ocrText;
        this.aiIndex = aiIndex;
        this.mediaType = mediaType == null || mediaType.isEmpty() ? TYPE_PHOTO : mediaType;
        this.durationMs = durationMs;
    }

    public boolean isVideo() {
        return TYPE_VIDEO.equals(mediaType);
    }

    public boolean isPhoto() {
        return !isVideo();
    }
}
