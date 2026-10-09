package fr.neamar.kiss.db;

import androidx.annotation.NonNull;

/** One persistent transparency/audit event for metadata and semantic HNSW indexing activity. */
public final class SemanticActivityRecord {
    public long id;
    public long eventTime;

    @NonNull public String eventType = "";
    @NonNull public String sessionId = "";
    @NonNull public String packageName = "";
    @NonNull public String appName = "";
    @NonNull public String source = "";
    @NonNull public String details = "";

    public SemanticActivityRecord() { }

    public SemanticActivityRecord(long eventTime,
                                  @NonNull String eventType,
                                  @NonNull String sessionId,
                                  @NonNull String packageName,
                                  @NonNull String appName,
                                  @NonNull String source,
                                  @NonNull String details) {
        this.eventTime = eventTime;
        this.eventType = eventType;
        this.sessionId = sessionId;
        this.packageName = packageName;
        this.appName = appName;
        this.source = source;
        this.details = details;
    }
}
