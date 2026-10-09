package fr.neamar.kiss.db;

import androidx.annotation.NonNull;

/** Cached public app-store/catalog text associated with one installed Android package. */
public final class AppSourceMetadataRecord {
    @NonNull public String packageName = "";
    @NonNull public String source = "";
    @NonNull public String installerPackage = "";
    @NonNull public String title = "";
    @NonNull public String description = "";
    @NonNull public String sourceUrl = "";
    @NonNull public String lastError = "";
    public long fetchedAt;

    @NonNull
    public String semanticText() {
        StringBuilder text = new StringBuilder();
        if (!title.isEmpty()) text.append(title).append(' ');
        if (!description.isEmpty()) text.append(description).append(' ');
        if (!source.isEmpty()) text.append(source);
        return text.toString().trim();
    }
}
