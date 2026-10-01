package fr.neamar.kiss.db;

public class ValuedHistoryRecord {
    /**
     * ID for the record
     */
    public String record;

    /**
     * Context dependant value, e.g. number of access
     */
    public int value;

    /**
     * Latest event timestamp when the producing query supplies one.
     * Zero means the query did not include timeline time.
     */
    public long timestamp;

    /**
     * Latest history row id when supplied. Used only as a deterministic tie-breaker for equal
     * millisecond timestamps.
     */
    public long sequence;
}
