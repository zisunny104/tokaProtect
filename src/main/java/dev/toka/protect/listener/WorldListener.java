package dev.toka.protect.listener;

import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.block.BlockID;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.BlockExplodeEvent;
import cn.nukkit.event.block.BlockFormEvent;
import cn.nukkit.event.block.BlockPistonEvent;
import cn.nukkit.event.block.BlockUpdateEvent;
import cn.nukkit.event.entity.EntityDamageByEntityEvent;
import cn.nukkit.event.entity.EntityExplodeEvent;
import cn.nukkit.event.entity.EntityInteractEvent;
import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.level.Position;
import cn.nukkit.plugin.Plugin;
import dev.toka.protect.flag.Flag;
import dev.toka.protect.flag.Rules;
import java.util.HashSet;
import java.util.Set;

/**
 * Enforces the server-wide flags. Every handler first checks the cheap thing (the block or event type), and only then asks
 * the rules, which answer most flags without any lookup. Nothing here runs unless tokaProtect is switched on.
 */
public final class WorldListener implements Listener {
    private final Plugin plugin;
    private final Rules rules;
    /** Blocks already queued for removal this tick; updates arrive many times for the same block. */
    private final Set<Long> removing = new HashSet<>();

    public WorldListener(Plugin plugin, Rules rules) {
        this.plugin = plugin;
        this.rules = rules;
    }

    private boolean allowed(Flag flag, Position at) {
        return at.getLevel() == null || rules.allowed(flag, at.getLevel().getFolderName(), at.getFloorX(), at.getFloorY(), at.getFloorZ());
    }

    // ---- explosions -------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!allowed(Flag.EXPLOSIONS, event.getPosition())) event.setCancelled();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!allowed(Flag.EXPLOSIONS, event.getPosition())) event.setCancelled();
    }

    // ---- farmland ---------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTrample(PlayerInteractEvent event) {
        if (event.getAction() == PlayerInteractEvent.Action.PHYSICAL && event.getBlock().getId() == BlockID.FARMLAND
                && !allowed(Flag.FARMLAND_TRAMPLE, event.getBlock())) event.setCancelled();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMobTrample(EntityInteractEvent event) {
        if (!(event.getEntity() instanceof Player) && event.getBlock().getId() == BlockID.FARMLAND
                && !allowed(Flag.FARMLAND_TRAMPLE, event.getBlock())) event.setCancelled();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFarmlandForm(BlockFormEvent event) {
        if (event.getBlock().getId() == BlockID.FARMLAND && !allowed(Flag.FARMLAND_DECAY, event.getBlock())) event.setCancelled();
    }

    // ---- player versus player ---------------------------------------------------------------------------------

    /** Both fighters must stand where pvp is on, so nobody is pulled into a fight from outside. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim) || !(event.getDamager() instanceof Player attacker) || victim == attacker) return;
        if (!allowed(Flag.PVP, victim.getPosition()) || !allowed(Flag.PVP, attacker.getPosition())) event.setCancelled();
    }

    // ---- hazards outside claims -------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockUpdate(BlockUpdateEvent event) {
        Block block = event.getBlock();
        Flag flag = hazard(block.getId());
        if (flag == null || block.getLevel() == null || allowed(flag, block)) return;
        // The flag says "no": it only applies where no claim covers the block.
        if (!rules.unclaimed(block.getLevel().getFolderName(), block.getFloorX(), block.getFloorY(), block.getFloorZ())) return;
        event.setCancelled();
        remove(block);
    }

    private static Flag hazard(int id) {
        return switch (id) {
            case BlockID.WATER, BlockID.STILL_WATER, BlockID.LAVA, BlockID.STILL_LAVA -> Flag.UNCLAIMED_LIQUIDS;
            case BlockID.FIRE, BlockID.SOUL_FIRE -> Flag.UNCLAIMED_FIRE;
            case BlockID.PISTON, BlockID.STICKY_PISTON, BlockID.PISTON_HEAD, BlockID.PISTON_HEAD_STICKY, BlockID.PISTON_EXTENSION -> Flag.UNCLAIMED_PISTONS;
            default -> null;
        };
    }

    /** Removes the block on the next tick on the server thread, once however many updates arrive. */
    private void remove(Block block) {
        long key = ((long) block.getFloorX() & 0x3FFFFFF) << 38 | ((long) block.getFloorZ() & 0x3FFFFFF) << 12 | ((long) block.getFloorY() & 0xFFF);
        if (!removing.add(key)) return;
        plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
            removing.remove(key);
            if (hazard(block.getLevel().getBlock(block).getId()) != null) block.getLevel().setBlock(block, Block.get(BlockID.AIR));
        });
    }

    // ---- pistons and chests -----------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPiston(BlockPistonEvent event) {
        if (allowed(Flag.PISTON_CHESTS, event.getBlock())) return;
        if (touchesChest(event.getBlocks()) || touchesChest(event.getDestroyedBlocks())) event.setCancelled();
    }

    private static boolean touchesChest(java.util.List<Block> blocks) {
        for (Block block : blocks) if (block.getId() == BlockID.CHEST || block.getId() == BlockID.TRAPPED_CHEST) return true;
        return false;
    }
}
