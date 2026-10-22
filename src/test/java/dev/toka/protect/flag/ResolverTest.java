package dev.toka.protect.flag;

import org.junit.Test;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class ResolverTest {
    private static final Setting ALLOW = new Setting(Setting.Value.ALLOW, false);
    private static final Setting DENY = new Setting(Setting.Value.DENY, false);
    private static final Setting FORCE_DENY = new Setting(Setting.Value.DENY, true);

    private static FlagTable table(Object... layersAndSettings) {
        FlagTable t = FlagTable.empty();
        for (int i = 0; i < layersAndSettings.length; i += 3)
            t = t.with((Layer) layersAndSettings[i], (Flag) layersAndSettings[i + 1], (Setting) layersAndSettings[i + 2]);
        return t;
    }

    private static boolean on(FlagTable t, Flag f, String region, List<String> claims) {
        return Resolver.resolve(t, f, "w", region, claims, List.of()).allowed();
    }

    @Test public void withNothingSetTheFlagsDefaultApplies() {
        assertFalse(on(FlagTable.empty(), Flag.EXPLOSIONS, null, List.of()));
        assertTrue(on(FlagTable.empty(), Flag.UNCLAIMED_LIQUIDS, null, List.of()));
    }

    @Test public void aNarrowerLayerOverridesAWiderOne() {
        var t = table(Layer.world("w"), Flag.EXPLOSIONS, DENY, Layer.region("r"), Flag.EXPLOSIONS, ALLOW, Layer.claim("c"), Flag.EXPLOSIONS, DENY);
        assertFalse(on(t, Flag.EXPLOSIONS, null, List.of()));
        assertTrue(on(t, Flag.EXPLOSIONS, "r", List.of()));
        assertFalse(on(t, Flag.EXPLOSIONS, "r", List.of("c")));
    }

    @Test public void aRegionWithoutASettingFollowsTheWorld() {
        var t = table(Layer.world("w"), Flag.PISTON_CHESTS, ALLOW);
        assertTrue(on(t, Flag.PISTON_CHESTS, "r", List.of()));
    }

    @Test public void anEnforcedSettingCannotBeOverriddenBelow() {
        var t = table(Layer.region("city"), Flag.PVP, FORCE_DENY, Layer.claim("arena"), Flag.PVP, ALLOW);
        assertFalse(on(t, Flag.PVP, "city", List.of("arena")));
        // Outside the city the claim's own setting works.
        assertTrue(on(t, Flag.PVP, null, List.of("arena")));
    }

    @Test public void anEnforcedWorldSettingBeatsEverythingBelow() {
        var t = table(Layer.world("w"), Flag.FARMLAND_TRAMPLE, FORCE_DENY, Layer.region("r"), Flag.FARMLAND_TRAMPLE, ALLOW);
        assertFalse(on(t, Flag.FARMLAND_TRAMPLE, "r", List.of()));
    }

    @Test public void severalClaimsAtOneSpotLetAnyDenialWin() {
        var t = table(Layer.claim("a"), Flag.UNCLAIMED_FIRE, ALLOW, Layer.claim("b"), Flag.UNCLAIMED_FIRE, DENY);
        assertFalse(on(t, Flag.UNCLAIMED_FIRE, null, List.of("a", "b")));
        assertFalse(on(t, Flag.UNCLAIMED_FIRE, null, List.of("b", "a")));
        assertTrue(on(t, Flag.UNCLAIMED_FIRE, null, List.of("a")));
    }

    @Test public void aClaimThatGrantsTheFlagByItsNatureBeatsAWorldDenialButNotAnEnforcedOne() {
        var plain = table(Layer.world("w"), Flag.PVP, DENY);
        assertTrue(Resolver.resolve(plain, Flag.PVP, "w", null, List.of("p"), List.of("p")).allowed());
        var forced = table(Layer.world("w"), Flag.PVP, FORCE_DENY);
        assertFalse(Resolver.resolve(forced, Flag.PVP, "w", null, List.of("p"), List.of("p")).allowed());
    }

    @Test public void anExplicitClaimDenialBeatsTheNatureGrant() {
        var t = table(Layer.claim("p"), Flag.PVP, DENY);
        assertFalse(Resolver.resolve(t, Flag.PVP, "w", null, List.of("p"), List.of("p")).allowed());
    }

    @Test public void theResultSaysWhichLayerDecided() {
        var t = table(Layer.region("r"), Flag.EXPLOSIONS, ALLOW);
        assertEquals(Layer.region("r"), Resolver.resolve(t, Flag.EXPLOSIONS, "w", "r", List.of(), List.of()).decidedBy().orElseThrow());
        assertTrue(Resolver.resolve(t, Flag.PVP, "w", "r", List.of(), List.of()).decidedBy().isEmpty());
    }

    @Test public void inheritRemovesASettingAndAnInheritCannotBeEnforced() {
        var t = table(Layer.region("r"), Flag.EXPLOSIONS, ALLOW).with(Layer.region("r"), Flag.EXPLOSIONS, Setting.inherit());
        assertTrue(t.all().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new Setting(Setting.Value.INHERIT, true));
    }

    @Test public void theTableKnowsWhichFlagsNeedTheLookupBelowTheWorld() {
        var t = table(Layer.world("w"), Flag.EXPLOSIONS, DENY, Layer.region("r"), Flag.PVP, ALLOW);
        assertFalse(t.setBelowWorld(Flag.EXPLOSIONS));
        assertTrue(t.setBelowWorld(Flag.PVP));
    }

    @Test public void editsNeverChangeAnEarlierTable() {
        var before = table(Layer.world("w"), Flag.EXPLOSIONS, DENY);
        before.with(Layer.world("w"), Flag.EXPLOSIONS, ALLOW);
        assertEquals(Map.of(Flag.EXPLOSIONS, DENY), before.all().get(Layer.world("w")));
    }
}
