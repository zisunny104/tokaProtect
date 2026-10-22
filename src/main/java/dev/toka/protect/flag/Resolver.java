package dev.toka.protect.flag;

import java.util.Collection;
import java.util.Optional;

/**
 * Works out whether a flag is on at a place. Layers are read from the widest to the narrowest: the flag's default, the
 * world, the region, then the claims. A narrower layer overrides a wider one unless an enforced setting above it already
 * decided. Several claims at one spot cannot contradict each other quietly: any denial wins.
 */
public final class Resolver {
    private Resolver() {}

    /** What decided the answer: the layer, or empty when the flag's default did. */
    public record Result(boolean allowed, Optional<Layer> decidedBy) {}

    public static Result resolve(FlagTable table, Flag flag, String world, String regionId, Collection<String> claimIds, Collection<String> allowingClaimIds) {
        boolean value = flag.defaultValue();
        Layer source = null;
        boolean locked = false;

        Layer worldLayer = Layer.world(world);
        var setting = table.get(worldLayer, flag);
        if (setting.isPresent()) {
            value = setting.get().value() == Setting.Value.ALLOW;
            source = worldLayer;
            locked = setting.get().enforced();
        }
        if (!locked && regionId != null) {
            Layer regionLayer = Layer.region(regionId);
            setting = table.get(regionLayer, flag);
            if (setting.isPresent()) {
                value = setting.get().value() == Setting.Value.ALLOW;
                source = regionLayer;
                locked = setting.get().enforced();
            }
        }
        if (!locked && !claimIds.isEmpty()) {
            Boolean claimValue = null;
            Layer claimSource = null;
            for (String claim : claimIds) {
                Layer layer = Layer.claim(claim);
                var claimSetting = table.get(layer, flag);
                if (claimSetting.isEmpty()) continue;
                boolean allow = claimSetting.get().value() == Setting.Value.ALLOW;
                if (claimValue == null || (claimValue && !allow)) { claimValue = allow; claimSource = layer; }
            }
            // Claims that grant the flag by their own nature (a PvP claim grants pvp) count as an allow, but never beat a denial.
            if (claimValue == null && !allowingClaimIds.isEmpty()) {
                claimValue = true;
                claimSource = Layer.claim(allowingClaimIds.iterator().next());
            }
            if (claimValue != null) { value = claimValue; source = claimSource; }
        }
        return new Result(value, Optional.ofNullable(source));
    }
}
