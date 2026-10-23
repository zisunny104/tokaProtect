package dev.toka.protect.listener;

import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.block.BlockChest;
import cn.nukkit.block.BlockFarmland;
import cn.nukkit.block.BlockFire;
import cn.nukkit.block.BlockID;
import cn.nukkit.block.BlockLiquid;
import cn.nukkit.block.BlockPistonBase;
import cn.nukkit.block.BlockPistonExtension;
import cn.nukkit.block.BlockPistonHead;
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
import dev.toka.protect.Breaker;
import dev.toka.protect.Feature;
import dev.toka.protect.flag.Flag;
import dev.toka.protect.flag.Rules;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Enforces the server-wide flags. Each feature runs on its own: it can be switched off, and a feature that keeps failing
 * trips and stops without touching the others. When a lookup fails the flag falls back to its default, which is what the old
 * rules did, so a broken part never changes behaviour in a surprising direction. Handlers test the cheap thing first (the
 * block's class or the event) and only then ask the rules, which answer most flags without any lookup.
 */
public final class WorldListener implements Listener {
    private final Plugin plugin;
    private final Rules rules;
    private final Breaker breaker;
    private final Set<Feature> enabled;
    /** Blocks already queued for removal this tick; updates arrive many times for the same block. */
    private final Set<Long> removing = new HashSet<>();

    public WorldListener(Plugin plugin, Rules rules, Breaker breaker, Set<Feature> enabled) {
        this.plugin = plugin;
        this.rules = rules;
        this.breaker = breaker;
        this.enabled = Set.copyOf(enabled);
    }

    private boolean on(Feature feature) { return enabled.contains(feature) && breaker.working(feature); }

    private void failed(Feature feature, Throwable error) {
        if (breaker.failed(feature, error)) plugin.getLogger().error("保護功能 " + feature.id() + " 連續出錯，已自動停用；其他功能不受影響。修正後用 /protect reset " + feature.id() + " 重新啟用。", error);
        else plugin.getLogger().warning("保護功能 " + feature.id() + " 出錯，這次改用旗標預設值：" + error);
    }

    /** The flag's value at the position, or its default if anything goes wrong. */
    private boolean allowed(Feature feature, Flag flag, Position at) {
        if (at.getLevel() == null) return true;
        try {
            return rules.allowed(flag, at.getLevel().getFolderName(), at.getFloorX(), at.getFloorY(), at.getFloorZ());
        } catch (RuntimeException | LinkageError error) {
            failed(feature, error);
            return flag.defaultValue();
        }
    }

    // ---- explosions -------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (on(Feature.EXPLOSIONS) && !allowed(Feature.EXPLOSIONS, Flag.EXPLOSIONS, event.getPosition())) event.setCancelled();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (on(Feature.EXPLOSIONS) && !allowed(Feature.EXPLOSIONS, Flag.EXPLOSIONS, event.getPosition())) event.setCancelled();
    }

    // ---- farmland ---------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTrample(PlayerInteractEvent event) {
        if (event.getAction() == PlayerInteractEvent.Action.PHYSICAL && event.getBlock() instanceof BlockFarmland && on(Feature.FARMLAND)
                && !allowed(Feature.FARMLAND, Flag.FARMLAND_TRAMPLE, event.getBlock())) event.setCancelled();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMobTrample(EntityInteractEvent event) {
        if (!(event.getEntity() instanceof Player) && event.getBlock() instanceof BlockFarmland && on(Feature.FARMLAND)
                && !allowed(Feature.FARMLAND, Flag.FARMLAND_TRAMPLE, event.getBlock())) event.setCancelled();
    }

    // The core dries farmland in its random tick and fires no event for it, so this only catches forms that do raise one.
    // TODO: make farmland-decay work when the core offers an event (or a hook) for farmland drying out.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFarmlandForm(BlockFormEvent event) {
        if (event.getBlock() instanceof BlockFarmland && on(Feature.FARMLAND)
                && !allowed(Feature.FARMLAND, Flag.FARMLAND_DECAY, event.getBlock())) event.setCancelled();
    }

    // ---- player versus player ---------------------------------------------------------------------------------

    /** Both fighters must stand where pvp is on, so nobody is pulled into a fight from outside. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim) || !(event.getDamager() instanceof Player attacker) || victim == attacker || !on(Feature.PVP)) return;
        if (!allowed(Feature.PVP, Flag.PVP, victim.getPosition()) || !allowed(Feature.PVP, Flag.PVP, attacker.getPosition())) event.setCancelled();
    }

    // ---- hazards outside claims -------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockUpdate(BlockUpdateEvent event) {
        Block block = event.getBlock();
        Flag flag = hazard(block);
        if (flag == null || block.getLevel() == null || !on(Feature.HAZARDS)) return;
        try {
            if (allowed(Feature.HAZARDS, flag, block)) return;
            // The flag says "no": it only applies where claims are known and none covers the block.
            if (!rules.unclaimed(block.getLevel().getFolderName(), block.getFloorX(), block.getFloorY(), block.getFloorZ())) return;
            event.setCancelled();
            remove(block);
        } catch (RuntimeException | LinkageError error) {
            failed(Feature.HAZARDS, error);
        }
    }

    /** Which flag governs a block, decided by the core's block classes so new variants are covered without a list of ids. */
    private static Flag hazard(Block block) {
        if (block instanceof BlockLiquid) return Flag.UNCLAIMED_LIQUIDS;
        if (block instanceof BlockFire) return Flag.UNCLAIMED_FIRE;
        if (block instanceof BlockPistonBase || block instanceof BlockPistonHead || block instanceof BlockPistonExtension) return Flag.UNCLAIMED_PISTONS;
        return null;
    }

    /** Removes the block on the next tick on the server thread, once however many updates arrive. */
    private void remove(Block block) {
        long key = ((long) block.getFloorX() & 0x3FFFFFF) << 38 | ((long) block.getFloorZ() & 0x3FFFFFF) << 12 | ((long) block.getFloorY() & 0xFFF);
        if (!removing.add(key)) return;
        plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
            removing.remove(key);
            if (hazard(block.getLevel().getBlock(block)) != null) block.getLevel().setBlock(block, Block.get(BlockID.AIR));
        });
    }

    // ---- pistons and chests -----------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPiston(BlockPistonEvent event) {
        if (!on(Feature.PISTONS) || allowed(Feature.PISTONS, Flag.PISTON_CHESTS, event.getBlock())) return;
        if (touchesChest(event.getBlocks()) || touchesChest(event.getDestroyedBlocks())) event.setCancelled();
    }

    private static boolean touchesChest(List<Block> blocks) {
        for (Block block : blocks) if (block instanceof BlockChest) return true;
        return false;
    }
}
