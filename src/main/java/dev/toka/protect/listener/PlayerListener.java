package dev.toka.protect.listener;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.BlockBreakEvent;
import cn.nukkit.event.player.PlayerDeathEvent;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.inventory.PlayerInventory;
import cn.nukkit.item.Item;
import cn.nukkit.item.ItemArmor;
import cn.nukkit.item.ItemTool;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static cn.nukkit.item.enchantment.Enchantment.ID_MENDING;
import static cn.nukkit.item.enchantment.Enchantment.ID_SILK_TOUCH;

/**
 * The rules of the server that follow the player: what is carried is kept on death, what a broken block drops goes straight into the
 * inventory (and its experience repairs worn or held gear that has Mending, else goes to the player), and the server does not check
 * the player's movement itself.
 */
public final class PlayerListener implements Listener {
    private final boolean keepInventory, keepExperience, checkMovement, autoPickup;

    public PlayerListener(boolean keepInventory, boolean keepExperience, boolean checkMovement, boolean autoPickup) {
        this.keepInventory = keepInventory;
        this.keepExperience = keepExperience;
        this.checkMovement = checkMovement;
        this.autoPickup = autoPickup;
    }

    public boolean autoPickup() { return autoPickup; }

    @EventHandler public void onDeath(PlayerDeathEvent event) {
        if (keepInventory) event.setKeepInventory(true);
        if (keepExperience) event.setKeepExperience(true);
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        event.getPlayer().setCheckMovement(checkMovement);
    }

    /** What does not fit stays a drop on the ground. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void onBreak(BlockBreakEvent event) {
        if (!autoPickup) return;
        Player player = event.getPlayer();
        PlayerInventory inventory = player.getInventory();
        Item[] drops = event.getDrops();
        if (drops.length > 0) {
            List<Item> left = new ArrayList<>();
            for (Item item : drops) {
                if (inventory.canAddItem(item)) inventory.addItem(item); else left.add(item);
            }
            event.setDrops(left.toArray(new Item[0]));
        }
        int experience = event.getDropExp();
        if (experience <= 0) return;
        Item tool = event.getItem();
        boolean silk = tool != null && event.getBlock().canSilkTouch() && tool.hasEnchantment(ID_SILK_TOUCH);
        if (tool != null && !silk) {
            List<Integer> mending = new ArrayList<>();
            int size = inventory.getSize();
            for (int i = 0; i < 4; i++) if (inventory.getArmorItem(i).getEnchantment(ID_MENDING) != null) mending.add(size + i);
            int held = inventory.getHeldItemIndex();
            if (inventory.getItem(held).getEnchantment(ID_MENDING) != null) mending.add(held);
            if (mending.isEmpty()) {
                player.addExperience(experience);
            } else {
                int slot = mending.get(ThreadLocalRandom.current().nextInt(mending.size()));
                Item gear = inventory.getItem(slot);
                if ((gear instanceof ItemTool || gear instanceof ItemArmor) && gear.getDamage() > 0) {
                    gear.setDamage(Math.max(0, gear.getDamage() - 2));
                    inventory.setItem(slot, gear);
                }
            }
        }
        event.setDropExp(0);
    }
}
