package dev.toka.protect.audit;

import java.util.Locale;
import java.util.Set;

/** Makes a command safe to keep and to show: secrets in the arguments are hidden, hidden characters removed, length limited. */
public final class AuditMask {
    private AuditMask() {}

    /**
     * @param hidden command names (without the slash, lower case) whose arguments must never be stored, such as login commands
     */
    public static String mask(String raw, Set<String> hidden, int maxLength) {
        String text = raw == null ? "" : raw.replaceAll("\\p{Cntrl}", " ").strip();
        String bare = text.startsWith("/") ? text.substring(1) : text;
        int space = bare.indexOf(' ');
        String name = (space < 0 ? bare : bare.substring(0, space)).toLowerCase(Locale.ROOT);
        // A plugin prefix such as "plugin:login" hides the same command.
        String plain = name.contains(":") ? name.substring(name.indexOf(':') + 1) : name;
        if (space >= 0 && (hidden.contains(name) || hidden.contains(plain))) text = text.substring(0, text.indexOf(' ')) + " ****";
        return text.length() > maxLength ? text.substring(0, maxLength) + "…" : text;
    }
}
