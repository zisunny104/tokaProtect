package dev.toka.protect;

import java.util.Locale;

/**
 * How much tokaProtect does.
 * STANDBY intercepts nothing. MINIMAL enforces the world-level rules from its own data only, with no other plugin involved,
 * so it keeps working if tokaRegions or tokaLand are missing or broken. FULL adds regions, claims and the hazard clean-up.
 */
public enum Mode {
    STANDBY, MINIMAL, FULL;

    /** An unknown word means STANDBY, never a stronger mode than the administrator wrote. */
    public static Mode parse(String text) {
        if (text == null) return STANDBY;
        return switch (text.strip().toLowerCase(Locale.ROOT)) {
            case "minimal" -> MINIMAL;
            case "full" -> FULL;
            default -> STANDBY;
        };
    }

    public boolean enforces() { return this != STANDBY; }

    /** Whether a feature may run in this mode. */
    public boolean allows(Feature feature) { return this == FULL || (this == MINIMAL && !feature.needsClaims()); }

    /** Whether region and claim layers are consulted. */
    public boolean usesRegionsAndClaims() { return this == FULL; }
}
