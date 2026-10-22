package dev.toka.protect.flag;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * The server-wide rules that can be switched per world, region or claim. Each default is what the old rules did, so
 * turning tokaProtect on changes nothing until someone changes a setting.
 */
public enum Flag {
    /** Players may hurt each other. Claims of type pvp allow it for themselves. */
    PVP("pvp", false),
    /** Explosions may break blocks and hurt things. The old block log cancelled every explosion. */
    EXPLOSIONS("explosions", false),
    /** Players and mobs may trample farmland into dirt. */
    FARMLAND_TRAMPLE("farmland-trample", false),
    /** Farmland may dry out or be formed by the game. */
    FARMLAND_DECAY("farmland-decay", false),
    /** Water and lava may exist or flow outside any claim; when denied they are removed. */
    UNCLAIMED_LIQUIDS("unclaimed-liquids", true),
    /** Fire may burn outside any claim; when denied it is removed. */
    UNCLAIMED_FIRE("unclaimed-fire", false),
    /** Pistons may work outside any claim; when denied they are removed. */
    UNCLAIMED_PISTONS("unclaimed-pistons", false),
    /** Pistons may push or pull chests. */
    PISTON_CHESTS("piston-chests", false),
    /** Block changes here are written to the log. */
    RECORD("record", true);

    private final String id;
    private final boolean defaultValue;

    Flag(String id, boolean defaultValue) {
        this.id = id;
        this.defaultValue = defaultValue;
    }

    public String id() { return id; }

    public boolean defaultValue() { return defaultValue; }

    public static Optional<Flag> byId(String id) {
        return Arrays.stream(values()).filter(f -> f.id.equals(id.toLowerCase(Locale.ROOT))).findFirst();
    }
}
