package dev.toka.protect.flag;

/** One layer's say on one flag. An enforced setting cannot be overridden by a more specific layer. */
public record Setting(Value value, boolean enforced) {
    public enum Value { INHERIT, ALLOW, DENY }

    public Setting {
        if (value == null) throw new IllegalArgumentException("value is required");
        if (value == Value.INHERIT && enforced) throw new IllegalArgumentException("inherit cannot be enforced");
    }

    public static Setting inherit() { return new Setting(Value.INHERIT, false); }
}
