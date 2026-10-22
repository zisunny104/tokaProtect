package dev.toka.protect.audit;

import java.util.Objects;
import java.util.UUID;

/** One audited input: a command someone typed, or the text written on a sign. */
public record AuditRecord(long time, UUID actor, String name, Kind kind, String text, String world, int x, int y, int z) {
    /** The code is what is stored, so existing numbers must never change meaning. */
    public enum Kind {
        COMMAND(0), CONSOLE(1), REMOTE(2), SIGN(3);

        private final int code;

        Kind(int code) { this.code = code; }

        public int code() { return code; }

        public static Kind byCode(int code) {
            for (Kind kind : values()) if (kind.code == code) return kind;
            throw new IllegalArgumentException("Unknown audit kind " + code);
        }

        public String langKey() { return "audit.kind." + name().toLowerCase(java.util.Locale.ROOT); }
    }

    public AuditRecord {
        Objects.requireNonNull(kind, "kind");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("a name is required");
        if (text == null) throw new IllegalArgumentException("text is required");
    }
}
