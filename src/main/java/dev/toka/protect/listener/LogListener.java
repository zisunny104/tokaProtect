package dev.toka.protect.listener;

import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.block.BlockID;
import cn.nukkit.entity.Entity;
import cn.nukkit.entity.item.EntityPrimedTNT;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.BlockBreakEvent;
import cn.nukkit.event.block.BlockExplodeEvent;
import cn.nukkit.event.block.BlockIgniteEvent;
import cn.nukkit.event.block.BlockPlaceEvent;
import cn.nukkit.event.entity.EntityExplodeEvent;
import cn.nukkit.event.player.PlayerBucketEmptyEvent;
import cn.nukkit.event.player.PlayerBucketFillEvent;
import cn.nukkit.item.ItemBucket;
import cn.nukkit.level.Position;
import dev.toka.protect.Main;
import dev.toka.protect.Breaker;
import dev.toka.protect.Feature;
import dev.toka.protect.flag.Flag;
import dev.toka.protect.flag.Rules;
import dev.toka.protect.log.Action;
import dev.toka.protect.log.LogRecord;
import dev.toka.protect.log.LogWriter;
import dev.toka.protect.log.TileNbt;
import java.util.List;
import java.util.Locale;

/**
 * Writes block changes to the log. It listens last, after every protection has decided, and ignores anything that was
 * cancelled, so the log holds only what really happened. Each change is checked against the record flag, so worlds, regions
 * and claims can opt out. Only changes with a clear responsible party are logged: players, and explosions with their cause.
 * It never waits: records go to a queue that is written in the background.
 */
public final class LogListener implements Listener {
    private final Main plugin;
    private final Rules rules;
    private final LogWriter<LogRecord> writer;
    private final Breaker breaker;
    private final int explosionLimit;
    private final TileNbt tiles;

    public LogListener(Main plugin, Rules rules, LogWriter<LogRecord> writer, Breaker breaker, int explosionLimit, TileNbt tiles) {
        this.plugin = plugin;
        this.rules = rules;
        this.writer = writer;
        this.breaker = breaker;
        this.explosionLimit = explosionLimit;
        this.tiles = tiles;
    }

    private boolean recording(Position at) {
        if (!breaker.working(Feature.RECORD) || at.getLevel() == null) return false;
        try {
            return rules.allowed(Flag.RECORD, at.getLevel().getFolderName(), at.getFloorX(), at.getFloorY(), at.getFloorZ());
        } catch (RuntimeException | LinkageError error) {
            failed(error);
            return Flag.RECORD.defaultValue();
        }
    }

    private void failed(Throwable error) {
        if (breaker.failed(Feature.RECORD, error)) plugin.error("log.recordErrorTripped", error);
        else plugin.warn("log.recordError", error);
    }

    private void write(Player player, Action action, Block block, int id, int meta, int prevId, int prevMeta, boolean keepTile) {
        try {
            writer.offer(LogRecord.byPlayer(System.currentTimeMillis(), player.getUniqueId(), player.getName(), action,
                    block.getLevel().getFolderName(), block.getFloorX(), block.getFloorY(), block.getFloorZ(), id, meta, prevId, prevMeta, keepTile ? tiles.capture(block) : null));
        } catch (RuntimeException error) {
            failed(error);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlock();
        if (recording(block)) write(event.getPlayer(), Action.PLACE, block, block.getId(), block.getDamage(), event.getBlockReplace().getId(), event.getBlockReplace().getDamage(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (recording(block)) write(event.getPlayer(), Action.BREAK, block, block.getId(), block.getDamage(), block.getId(), block.getDamage(), true);
    }

    /** The liquid poured is whatever the core says the bucket holds, so no ids are listed here. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEmpty(PlayerBucketEmptyEvent event) {
        Block target = event.getBlockClicked().getSide(event.getBlockFace());
        if (recording(target)) write(event.getPlayer(), Action.EMPTY, target, ItemBucket.getBlockByDamage(event.getBucket().getDamage()), 0, target.getId(), target.getDamage(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFill(PlayerBucketFillEvent event) {
        Block liquid = event.getBlockClicked();
        if (recording(liquid)) write(event.getPlayer(), Action.FILL, liquid, liquid.getId(), liquid.getDamage(), liquid.getId(), liquid.getDamage(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Block block = event.getBlock();
        if (recording(block)) write(player, Action.IGNITE, block, BlockID.FIRE, 0, block.getId(), block.getDamage(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        Entity source = event.getEntity();
        Player player = source instanceof EntityPrimedTNT tnt && tnt.getSource() instanceof Player p ? p : null;
        explosion(event.getPosition(), event.getBlockList(), player, cause(source));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        explosion(event.getPosition(), event.getBlockList(), null, "block");
    }

    /** A short readable cause from the entity's class, for example "primedtnt" or "creeper". */
    private static String cause(Entity entity) {
        String name = entity == null ? "unknown" : entity.getClass().getSimpleName().replaceFirst("^Entity", "");
        return name.isEmpty() ? "unknown" : name.toLowerCase(Locale.ROOT);
    }

    /** At most the configured number of blocks per explosion, so one huge blast cannot flood the log. */
    private void explosion(Position at, List<Block> blocks, Player player, String cause) {
        if (blocks.isEmpty() || !recording(at)) return;
        long now = System.currentTimeMillis();
        int written = 0;
        try {
            for (Block block : blocks) {
                if (written++ >= explosionLimit) break;
                String world = block.getLevel().getFolderName();
                writer.offer(player != null
                        ? LogRecord.byPlayer(now, player.getUniqueId(), player.getName(), Action.EXPLODE, world, block.getFloorX(), block.getFloorY(), block.getFloorZ(), block.getId(), block.getDamage(), block.getId(), block.getDamage(), tiles.capture(block))
                        : LogRecord.byCause(now, cause, Action.EXPLODE, world, block.getFloorX(), block.getFloorY(), block.getFloorZ(), block.getId(), block.getDamage(), tiles.capture(block)));
            }
        } catch (RuntimeException error) {
            failed(error);
        }
    }
}
