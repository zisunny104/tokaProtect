package dev.toka.protect.listener;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.BlockBreakEvent;
import cn.nukkit.event.block.BlockPlaceEvent;
import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import dev.toka.protect.log.LogStore;
import dev.toka.protect.log.LogView;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Inspect mode: while on, touching a block shows its history instead of changing it. */
public final class Inspector implements Listener {
    private final LogView view;
    private final Long sinceMillis;
    private final Set<UUID> inspecting = new HashSet<>();

    public Inspector(LogView view, Long sinceMillis) {
        this.view = view;
        this.sinceMillis = sinceMillis;
    }

    /** Switches the player's inspect mode; returns whether it is now on. */
    public boolean toggle(Player player) {
        if (!inspecting.remove(player.getUniqueId())) { inspecting.add(player.getUniqueId()); return true; }
        return false;
    }

    private void look(Player player, cn.nukkit.block.Block block) {
        long since = sinceMillis == null ? 0 : System.currentTimeMillis() - sinceMillis;
        view.show(player, LogStore.Query.at(block.getLevel().getFolderName(), block.getFloorX(), block.getFloorY(), block.getFloorZ(), 0, since, 0, 0),
                block.getLevel().getFolderName() + " " + block.getFloorX() + "," + block.getFloorY() + "," + block.getFloorZ(), 1);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        var action = event.getAction();
        if ((action != PlayerInteractEvent.Action.LEFT_CLICK_BLOCK && action != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK)
                || !inspecting.contains(event.getPlayer().getUniqueId())) return;
        event.setCancelled();
        look(event.getPlayer(), event.getBlock());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent event) {
        if (!inspecting.contains(event.getPlayer().getUniqueId())) return;
        event.setCancelled();
        look(event.getPlayer(), event.getBlock());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent event) {
        if (!inspecting.contains(event.getPlayer().getUniqueId())) return;
        event.setCancelled();
        look(event.getPlayer(), event.getBlock());
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) { inspecting.remove(event.getPlayer().getUniqueId()); }
}
