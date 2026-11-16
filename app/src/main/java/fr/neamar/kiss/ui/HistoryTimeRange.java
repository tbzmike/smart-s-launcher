package fr.neamar.kiss.ui;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The real recorded time of the visible History rows, not the device clock.
 * Independent of Android UI and persistent storage. Calculating it during scroll
 * never scans the History database or changes the order of existing rows.
 */
public final class HistoryTimeRange {
    public static final String PREF_FORMAT = "history-visible-time-format";
    public static final String FORMAT_24H_H = "24h-h";
    public static final String FORMAT_24H_COLON = "24h-colon";
    public static final String FORMAT_12H = "12h";

    private long min = Long.MAX_VALUE;
    private long max = Long.MIN_VALUE;

    public void include(long epochMillis) {
        if (epochMillis <= 0L) return;
        min = Math.min(min, epochMillis);
        max = Math.max(max, epochMillis);
    }

    public boolean hasTime() {
        return min != Long.MAX_VALUE;
    }

    public long startMinute() {
        return hasTime() ? min / 60_000L : -1L;
    }

    public long endMinute() {
        return hasTime() ? max / 60_000L : -1L;
    }

    public String format(String chosen, Locale locale, TimeZone zone) {
        if (!hasTime()) return "";
        String pattern;
        if (FORMAT_12H.equals(chosen)) pattern = "h:mm a";
        else if (FORMAT_24H_COLON.equals(chosen)) pattern = "HH:mm";
        else pattern = "HH'h'mm";
        DateFormat time = new SimpleDateFormat(pattern, locale == null ? Locale.getDefault() : locale);
        time.setTimeZone(zone == null ? TimeZone.getDefault() : zone);
        String first = time.format(new Date(min));
        String last = time.format(new Date(max));
        return first.equals(last) ? first : first + " – " + last;
    }
}
