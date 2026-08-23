package fr.neamar.kiss.pojo;

public final class CallLogPojo extends Pojo {
    public final long callId;
    public final String phoneNumber;
    public final long callTimestamp;
    public final long durationSeconds;
    public final int callType;

    public CallLogPojo(long callId, String phoneNumber, long callTimestamp,
                       long durationSeconds, int callType, String displayName) {
        super("calllog://" + callId);
        this.callId = callId;
        this.phoneNumber = phoneNumber == null ? "" : phoneNumber;
        this.callTimestamp = callTimestamp;
        this.durationSeconds = durationSeconds;
        this.callType = callType;
        setName(displayName == null || displayName.trim().isEmpty()
                ? this.phoneNumber : displayName.trim());
    }

    @Override
    public String getCustomIconId() {
        return "calllog://default";
    }
}
