package dev.toka.protect.log;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A length of time typed as a number and a unit: 30m, 2h, 7d, 1w. */
public final class TimeSpan {
    private static final Pattern FORM = Pattern.compile("(\\d{1,6})([smhdw])");

    private TimeSpan() {}

    public static long parseMillis(String text) {
        Matcher m = FORM.matcher(text.strip().toLowerCase(Locale.ROOT));
        if (!m.matches()) throw new IllegalArgumentException("TIME_FORMAT");
        long n = Long.parseLong(m.group(1));
        long unit = switch (m.group(2)) {
            case "s" -> 1_000L;
            case "m" -> 60_000L;
            case "h" -> 3_600_000L;
            case "d" -> 86_400_000L;
            default -> 604_800_000L;
        };
        return Math.multiplyExact(n, unit);
    }
}
