package dev.toka.protect.listener;

import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerDeathEvent;
import cn.nukkit.event.player.PlayerJoinEvent;

/** Two rules of the server that follow the player: what is carried is kept on death, and the server does not check the player's movement itself. */
public final class PlayerListener implements Listener {
    private final boolean keepInventory, keepExperience, checkMovement;

    public PlayerListener(boolean keepInventory, boolean keepExperience, boolean checkMovement) {
        this.keepInventory = keepInventory;
        this.keepExperience = keepExperience;
        this.checkMovement = checkMovement;
    }

    @EventHandler public void onDeath(PlayerDeathEvent event) {
        if (keepInventory) event.setKeepInventory(true);
        if (keepExperience) event.setKeepExperience(true);
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        event.getPlayer().setCheckMovement(checkMovement);
    }
}
