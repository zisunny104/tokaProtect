package dev.toka.protect.flag;

import java.util.List;
import java.util.Map;

/**
 * The question the event handlers ask: is this flag on at this block? Looking up the region and the claims costs
 * something, so it is only done for a flag that some region or claim actually overrides (or that a claim grants by its own
 * type); every other flag is answered from the world setting and the default alone.
 */
public final class Rules {
    /** Where regions and claims come from; tokaRegions and tokaLand in the server, a fake in tests. */
    public interface Places {
        /** The region id at the block, or null. */
        String regionAt(String world, int x, int y, int z);
        /** The claims at the block, or null when that cannot be known (no land plugin, or it failed): never guess "none". */
        List<Claim> claimsAt(String world, int x, int y, int z);
    }

    public record Claim(String id, String kind, String type) {}

    /** A claim of this type grants the flag by its nature: a PvP claim grants pvp. */
    private static final Map<Flag, String> GRANTED_BY_TYPE = Map.of(Flag.PVP, "pvp");

    private final Places places;
    private volatile FlagTable table;

    public Rules(Places places, FlagTable table) {
        this.places = places;
        this.table = table;
    }

    public FlagTable table() { return table; }

    public void table(FlagTable next) { this.table = next; }

    public boolean allowed(Flag flag, String world, int x, int y, int z) {
        FlagTable current = table;
        if (!current.setBelowWorld(flag) && !GRANTED_BY_TYPE.containsKey(flag)) {
            // Nothing below the world can change the answer: no lookups and no allocation.
            Boolean value = current.worldValue(world, flag);
            return value != null ? value : flag.defaultValue();
        }
        return explain(flag, world, x, y, z).allowed();
    }

    public Resolver.Result explain(Flag flag, String world, int x, int y, int z) {
        FlagTable current = table;
        String grantingType = GRANTED_BY_TYPE.get(flag);
        if (!current.setBelowWorld(flag) && grantingType == null)
            return Resolver.resolve(current, flag, world, null, List.of(), List.of());
        String region = places.regionAt(world, x, y, z);
        List<Claim> claims = places.claimsAt(world, x, y, z);
        if (claims == null) claims = List.of();
        List<String> ids = claims.stream().map(Claim::id).toList();
        List<String> granting = grantingType == null ? List.of()
                : claims.stream().filter(c -> grantingType.equals(c.type())).map(Claim::id).toList();
        return Resolver.resolve(current, flag, world, region, ids, granting);
    }

    /** True only when claims are known and none covers the block; if claims cannot be known the block is not called unclaimed. */
    public boolean unclaimed(String world, int x, int y, int z) {
        List<Claim> claims = places.claimsAt(world, x, y, z);
        return claims != null && claims.isEmpty();
    }
}
