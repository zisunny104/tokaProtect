package dev.toka.protect.flag;

import org.junit.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class RulesTest {
    private static final class CountingPlaces implements Rules.Places {
        final AtomicInteger lookups = new AtomicInteger();
        String region;
        List<Rules.Claim> claims = List.of();
        public String regionAt(String world, int x, int y, int z) { lookups.incrementAndGet(); return region; }
        public List<Rules.Claim> claimsAt(String world, int x, int y, int z) { lookups.incrementAndGet(); return claims; }
    }

    @Test public void aFlagNobodyOverridesIsAnsweredWithoutAnyLookup() {
        var places = new CountingPlaces();
        var rules = new Rules(places, FlagTable.empty().with(Layer.world("w"), Flag.EXPLOSIONS, new Setting(Setting.Value.ALLOW, false)));
        assertTrue(rules.allowed(Flag.EXPLOSIONS, "w", 1, 2, 3));
        assertFalse(rules.allowed(Flag.FARMLAND_TRAMPLE, "w", 1, 2, 3));
        assertEquals(0, places.lookups.get());
    }

    @Test public void aFlagSetInARegionLooksTheRegionUp() {
        var places = new CountingPlaces();
        places.region = "city";
        var rules = new Rules(places, FlagTable.empty().with(Layer.region("city"), Flag.UNCLAIMED_LIQUIDS, new Setting(Setting.Value.DENY, false)));
        assertFalse(rules.allowed(Flag.UNCLAIMED_LIQUIDS, "w", 0, 0, 0));
        assertTrue(places.lookups.get() > 0);
        places.region = null;
        assertTrue(rules.allowed(Flag.UNCLAIMED_LIQUIDS, "w", 0, 0, 0));
    }

    @Test public void pvpClaimsGrantPvpEvenWhenNoSettingExists() {
        var places = new CountingPlaces();
        places.claims = List.of(new Rules.Claim("arena", "PLAYER", "pvp"));
        var rules = new Rules(places, FlagTable.empty());
        assertTrue(rules.allowed(Flag.PVP, "w", 0, 0, 0));
        places.claims = List.of(new Rules.Claim("home", "PLAYER", "land"));
        assertFalse(rules.allowed(Flag.PVP, "w", 0, 0, 0));
    }

    @Test public void unclaimedMeansNoClaimAtTheBlock() {
        var places = new CountingPlaces();
        var rules = new Rules(places, FlagTable.empty());
        assertTrue(rules.unclaimed("w", 0, 0, 0));
        places.claims = List.of(new Rules.Claim("a", "PLAYER", "land"));
        assertFalse(rules.unclaimed("w", 0, 0, 0));
    }

    @Test public void replacingTheTableTakesEffectAtOnce() {
        var rules = new Rules(new CountingPlaces(), FlagTable.empty());
        assertFalse(rules.allowed(Flag.EXPLOSIONS, "w", 0, 0, 0));
        rules.table(rules.table().with(Layer.world("w"), Flag.EXPLOSIONS, new Setting(Setting.Value.ALLOW, false)));
        assertTrue(rules.allowed(Flag.EXPLOSIONS, "w", 0, 0, 0));
    }

    @Test public void claimsThatCannotBeKnownAreNeverCalledUnclaimed() {
        var places = new CountingPlaces();
        places.claims = null;
        var rules = new Rules(places, FlagTable.empty());
        assertFalse(rules.unclaimed("w", 0, 0, 0));
        // And a flag that needs them still resolves, falling back to the layers it can see.
        assertFalse(rules.allowed(Flag.PVP, "w", 0, 0, 0));
    }

    @Test public void withoutRegionsOrClaimsOnlyTheWorldAndTheDefaultCount() {
        Rules.Places none = new Rules.Places() {
            public String regionAt(String world, int x, int y, int z) { return null; }
            public List<Rules.Claim> claimsAt(String world, int x, int y, int z) { return null; }
        };
        var table = FlagTable.empty().with(Layer.world("w"), Flag.EXPLOSIONS, new Setting(Setting.Value.ALLOW, false))
                .with(Layer.region("r"), Flag.EXPLOSIONS, new Setting(Setting.Value.DENY, false));
        var rules = new Rules(none, table);
        assertTrue(rules.allowed(Flag.EXPLOSIONS, "w", 0, 0, 0));
    }
}
