package dev.toka.protect.log;

import java.util.Locale;

/** Large numbers with thousands separators, the same as money is shown. */
public final class Num {
    private Num() {}

    public static String group(long value) { return String.format(Locale.ROOT, "%,d", value); }
}
