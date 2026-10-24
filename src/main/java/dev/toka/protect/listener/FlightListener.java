package dev.toka.protect.listener;

import cn.nukkit.AdventureSettings;
import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.entity.EntityDamageEvent;
import cn.nukkit.event.player.PlayerGameModeChangeEvent;
import cn.nukkit.event.player.PlayerMoveEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.event.player.PlayerTeleportEvent;
import cn.nukkit.event.player.PlayerToggleFlightEvent;
import cn.nukkit.level.Location;
import dev.toka.protect.Main;
import dev.toka.protect.Breaker;
import dev.toka.protect.Feature;
import dev.toka.protect.flag.Flag;
import dev.toka.protect.flag.Rules;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Lets some players fly in places where the {@code fly} flag allows it. Who may fly is a permission (a role such as sponsor
 * grants it); where is the flag, layered by world, region and claim, and off by default. Only players holding the permission
 * are ever looked up, so everyone else costs one permission check per block moved. It only takes back flight it gave, so
 * flight granted by anything else is left alone. Survival and adventure only: creative and spectator fly natively.
 */
public final class FlightListener implements Listener {
    public static final String PERMISSION = "tokaprotect.fly";
    public static final String BYPASS = "tokaprotect.fly.bypass";
    private static final long LANDING_MILLIS = 30_000L;

    private final Main plugin;
    private final Rules rules;
    private final Breaker breaker;
    private final boolean kickIllegal;
    /** Players this listener gave flight to. */
    private final Set<UUID> granted = new HashSet<>();
    /** Players who lost flight in the air: their next fall damage is waived until the time shown. */
    private final Map<UUID, Long> landing = new HashMap<>();

    public FlightListener(Main plugin, Rules rules, Breaker breaker, boolean kickIllegal) {
        this.plugin = plugin;
        this.rules = rules;
        this.breaker = breaker;
        this.kickIllegal = kickIllegal;
    }

    private static boolean walking(Player player) { return !player.isCreative() && !player.isSpectator(); }

    /** Whether flight should be on: allowed place, permitted player. A pure rule so it can be tested. */
    static boolean shouldFly(boolean permitted, boolean placeAllows) { return permitted && placeAllows; }

    private boolean placeAllows(Location at) {
        if (at.getLevel() == null) return false;
        try {
            return rules.allowed(Flag.FLY, at.getLevel().getFolderName(), at.getFloorX(), at.getFloorY(), at.getFloorZ());
        } catch (RuntimeException | LinkageError error) {
            if (breaker.failed(Feature.FLIGHT, error)) plugin.error("log.flightTripped", error);
            return Flag.FLY.defaultValue();
        }
    }

    private void apply(Player player, Location at) {
        boolean has = granted.contains(player.getUniqueId());
        // A tripped feature takes back the flight it gave instead of leaving it on everywhere.
        if (!breaker.working(Feature.FLIGHT)) { if (has) revoke(player); return; }
        if (!walking(player)) return;
        if (!has && !player.hasPermission(PERMISSION)) return;
        boolean want = shouldFly(player.hasPermission(PERMISSION), placeAllows(at));
        if (want == has) return;
        if (want) {
            granted.add(player.getUniqueId());
            AdventureSettings settings = player.getAdventureSettings();
            settings.set(AdventureSettings.Type.ALLOW_FLIGHT, true);
            settings.update();
        } else {
            revoke(player);
        }
    }

    /** Takes back flight this listener gave; a player in the air lands without fall damage. */
    private void revoke(Player player) {
        if (!granted.remove(player.getUniqueId())) return;
        AdventureSettings settings = player.getAdventureSettings();
        if (settings.get(AdventureSettings.Type.FLYING)) landing.put(player.getUniqueId(), System.currentTimeMillis() + LANDING_MILLIS);
        settings.set(AdventureSettings.Type.ALLOW_FLIGHT, false);
        settings.set(AdventureSettings.Type.FLYING, false);
        settings.update();
    }

    /** For when the plugin stops: nobody keeps flight that only this listener allowed. */
    public void release() {
        for (Player player : plugin.getServer().getOnlinePlayers().values()) if (granted.contains(player.getUniqueId())) revoke(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom(), to = event.getTo();
        // Only a step into another block can change the answer.
        if (from.getFloorX() == to.getFloorX() && from.getFloorY() == to.getFloorY() && from.getFloorZ() == to.getFloorZ() && from.getLevel() == to.getLevel()) return;
        apply(event.getPlayer(), to);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) { apply(event.getPlayer(), event.getTo()); }

    /** A client flying where nothing allows it is cheating (or a plugin forgot to tell us); stop it, and kick if configured. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onToggle(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        if (!event.isFlying() || !walking(player) || granted.contains(player.getUniqueId()) || player.hasPermission(BYPASS)) return;
        if (!breaker.working(Feature.FLIGHT)) return;
        event.setCancelled();
        if (kickIllegal) {
            plugin.warn("log.flightKick", player.getName());
            player.kick("[保護] 檢測移動異常");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFall(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL || !(event.getEntity() instanceof Player player)) return;
        Long until = landing.remove(player.getUniqueId());
        if (until != null && until >= System.currentTimeMillis()) event.setCancelled();
    }

    /** The new game mode brings its own flight settings, so what was given here is simply forgotten. */
    @EventHandler public void onGameMode(PlayerGameModeChangeEvent event) { granted.remove(event.getPlayer().getUniqueId()); }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        granted.remove(event.getPlayer().getUniqueId());
        landing.remove(event.getPlayer().getUniqueId());
    }
}
