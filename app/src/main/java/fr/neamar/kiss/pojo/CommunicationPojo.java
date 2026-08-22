package fr.neamar.kiss.pojo;

public final class CommunicationPojo extends Pojo {
    public enum Kind { CALL, SMS, TRUECALLER_NOTIFICATION }

    public final Kind kind;
    public final String packageName;
    public final String address;
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
        this.body = body == null ? "" : body;
        this.timestamp = timestamp;
        this.notificationId = notificationId == null ? "" : notificationId;
        String name = displayName == null || displayName.trim().isEmpty() ? this.address : displayName.trim();
        String searchable = name;
        if (!this.address.isEmpty() && !searchable.contains(this.address)) searchable += " " + this.address;
        if (!this.body.isEmpty()) searchable += " " + this.body;
        setName(searchable.trim());
    }

    public String primaryLabel() {
        String full = getName();
        if (full == null) return "";
        int split = full.indexOf(' ');
        return split > 0 ? full.substring(0, split) : full;
    }

    @Override public String getHistoryId() { return id; }
}
