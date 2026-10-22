package dev.toka.protect.flag;

import java.util.Objects;

/** Where a setting applies: a whole world (by name), a region or a claim (by id). */
public record Layer(Kind kind, String id) {
    public enum Kind { WORLD, REGION, CLAIM }

    public Layer {
        Objects.requireNonNull(kind, "kind");
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id is required");
    }

    public static Layer world(String name) { return new Layer(Kind.WORLD, name); }
    public static Layer region(String id) { return new Layer(Kind.REGION, id); }
    public static Layer claim(String id) { return new Layer(Kind.CLAIM, id); }
}
