package fr.neamar.kiss.pojo;

public final class CommunicationPojo extends Pojo {
    public enum Kind { CALL, SMS, TRUECALLER_NOTIFICATION }

    public final Kind kind;
    public final String packageName;
    public final String address;
    public final String displayName;
    public final String body;
    public final long timestamp;
    public final String notificationId;

    public CommunicationPojo(String id, Kind kind, String packageName, String address,
                             String displayName, String body, long timestamp,
                             String notificationId) {
        super(id);
        this.kind = kind;
        this.packageName = packageName == null ? "" : packageName;
        this.address = address == null ? "" : address;
        this.displayName = displayName == null || displayName.trim().isEmpty()
                ? this.address : displayName.trim();
        this.body = body == null ? "" : body;
        this.timestamp = timestamp;
        this.notificationId = notificationId == null ? "" : notificationId;
        String searchable = this.displayName;
        if (!this.address.isEmpty() && !searchable.contains(this.address)) searchable += " " + this.address;
        if (!this.body.isEmpty()) searchable += " " + this.body;
        setName(searchable.trim());
    }

    public String primaryLabel() {
        return displayName;
    }

    @Override public String getHistoryId() { return id; }
}
