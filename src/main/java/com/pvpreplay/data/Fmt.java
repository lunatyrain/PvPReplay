package com.pvpreplay.data;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class Fmt {
    private Fmt() {
    }

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("dd MMM HH:mm", Locale.ENGLISH).withZone(ZoneId.systemDefault());

    /** Frames (ticks) to m:ss. */
    public static String time(int frames) {
        int s = Math.max(0, frames) / 20;
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    public static String date(long millis) {
        return DATE.format(Instant.ofEpochMilli(millis));
    }

    public static String num(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    public static String num1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
