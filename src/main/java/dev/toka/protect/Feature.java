package dev.toka.protect;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** The parts of tokaProtect that can be switched off, or trip, on their own. */
public enum Feature {
    EXPLOSIONS("explosions", false),
    FARMLAND("farmland", false),
    PVP("pvp", false),
    /** Water, lava, fire and pistons outside claims. Needs to know where claims are, so it only runs in full mode. */
    HAZARDS("hazards", true),
    PISTONS("pistons", false),
    /** Writing block changes to the log. Needs no other plugin. */
    RECORD("record", false),
    /** Keeping a record of commands and sign text. Needs no other plugin. */
    AUDIT("audit", false);

    private final String id;
    private final boolean needsClaims;

    Feature(String id, boolean needsClaims) {
        this.id = id;
        this.needsClaims = needsClaims;
    }

    public String id() { return id; }

    public boolean needsClaims() { return needsClaims; }

    public static Optional<Feature> byId(String id) {
        return Arrays.stream(values()).filter(f -> f.id.equals(id.toLowerCase(Locale.ROOT))).findFirst();
    }
}
