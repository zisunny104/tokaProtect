package dev.toka.protect.link;

import cn.nukkit.Server;
import cn.nukkit.plugin.Plugin;
import com.google.gson.JsonParser;
import dev.toka.protect.flag.Rules;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Regions from tokaRegions and claims from tokaLand, reached reflectively so tokaProtect links neither. When one is not
 * installed it simply has no regions or no claims. Both lookups read immutable snapshots, so any thread may ask.
 */
public final class Places implements Rules.Places {
    private final Server server;
    private volatile Method regionAt;
    private volatile Method regionByName;
    private volatile Method claimsAt;

    public Places(Server server) {
        this.server = server;
        refresh();
    }

    /** Looks the other plugins up again; call after any plugin is enabled. */
    public void refresh() {
        Class<?> regions = api("tokaRegions", "dev.toka.regions.Main");
        Class<?> land = api("tokaLand", "dev.toka.land.Main");
        regionAt = method(regions, "regionIdAt", String.class, int.class, int.class, int.class);
        regionByName = method(regions, "regionIdByName", String.class);
        claimsAt = method(land, "claimsAtJson", String.class, int.class, int.class, int.class);
    }

    public boolean hasRegions() { return regionAt != null; }

    public boolean hasClaims() { return claimsAt != null; }

    private Class<?> api(String plugin, String type) {
        Plugin found = server.getPluginManager().getPlugin(plugin);
        if (found == null || !found.isEnabled()) return null;
        try { return Class.forName(type, true, found.getClass().getClassLoader()); }
        catch (ClassNotFoundException missing) { return null; }
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) {
        if (type == null) return null;
        try { return type.getMethod(name, parameters); }
        catch (NoSuchMethodException older) { return null; }
    }

    @Override public String regionAt(String world, int x, int y, int z) {
        Method lookup = regionAt;
        if (lookup == null) return null;
        try { return (String) lookup.invoke(null, world, x, y, z); }
        catch (ReflectiveOperationException | LinkageError unavailable) { return null; }
    }

    public String regionByName(String name) {
        Method lookup = regionByName;
        if (lookup == null) return null;
        try { return (String) lookup.invoke(null, name); }
        catch (ReflectiveOperationException | LinkageError unavailable) { return null; }
    }

    @Override public List<Rules.Claim> claimsAt(String world, int x, int y, int z) {
        Method lookup = claimsAt;
        if (lookup == null) return List.of();
        try {
            String json = (String) lookup.invoke(null, world, x, y, z);
            if (json == null || json.equals("[]")) return List.of();
            List<Rules.Claim> claims = new ArrayList<>();
            for (var row : JsonParser.parseString(json).getAsJsonArray()) {
                var claim = row.getAsJsonObject();
                claims.add(new Rules.Claim(claim.get("id").getAsString(), claim.get("kind").getAsString(), claim.get("type").getAsString()));
            }
            return claims;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return List.of();
        }
    }
}
