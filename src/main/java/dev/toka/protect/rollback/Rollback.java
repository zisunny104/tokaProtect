package dev.toka.protect.rollback;

import cn.nukkit.block.Block;
import cn.nukkit.command.CommandSender;
import cn.nukkit.level.Level;
import cn.nukkit.math.Vector3;
import cn.nukkit.scheduler.Task;
import dev.toka.protect.Main;
import dev.toka.protect.log.ItemCodec;
import dev.toka.protect.log.ItemStore;
import dev.toka.protect.log.LogStore;
import dev.toka.protect.log.Num;
import dev.toka.protect.log.TileNbt;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Undoes logged block changes in two steps: a preview that reads the log in the background and says how much would change,
 * and a confirmation that applies it a slice per tick so the server never stalls. A spot someone changed again afterwards is
 * left alone, and blocks come back with the tile data of the configured kinds (signs, heads, banners), not chest contents.
 */
public final class Rollback implements AutoCloseable {
    private record Pending(List<RollbackPlan.Entry> entries, List<ItemPlan.Entry> items, java.util.Set<String> spills, List<ItemPlan.Pickup> pickups, String title, long createdAt) {}

    /** What the item log says for a query: slots to refill, spots where containers broke, and pickups from those spills. */
    private record ItemPart(List<ItemPlan.Entry> entries, java.util.Set<String> spills, List<ItemPlan.Pickup> pickups) {
        static final ItemPart NONE = new ItemPart(List.of(), java.util.Set.of(), List.of());
    }

    /** A pickup still to be taken back, with how many of the stack are left to take. */
    private static final class TakeBack {
        final ItemPlan.Pickup pickup;
        int left;
        TakeBack(ItemPlan.Pickup pickup, int left) { this.pickup = pickup; this.left = left; }
    }

    private final Main plugin;
    private final LogStore store;
    /** Container contents; null when the item log is off. */
    private final ItemStore itemStore;
    private final int maxRows;
    private final int maxBlocks;
    private final int perTick;
    private final long confirmMillis;
    private final Map<String, Pending> pending = new HashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "tokaProtect-rollback"); t.setDaemon(true); return t; });

    public Rollback(Main plugin, LogStore store, ItemStore itemStore, int maxRows, int maxBlocks, int perTick, long confirmMillis) {
        this.plugin = plugin;
        this.store = store;
        this.itemStore = itemStore;
        this.maxRows = maxRows;
        this.maxBlocks = maxBlocks;
        this.perTick = perTick;
        this.confirmMillis = confirmMillis;
    }

    private void send(CommandSender to, String key, Object... args) { to.sendMessage(plugin.text(to, key, args)); }

    private void later(Runnable action) { plugin.getServer().getScheduler().scheduleTask(plugin, action); }

    /** Reads the log for the query in the background and keeps the resulting plan until it is confirmed or expires. */
    public void preview(CommandSender by, LogStore.Query query, String title) { preview(by, query, title, null); }

    /** Same, and when the plan has places to restore the chat preview is skipped and {@code ready} gets their number instead. */
    public void preview(CommandSender by, LogStore.Query query, String title, java.util.function.IntConsumer ready) {
        if (running.get()) throw new IllegalArgumentException("ROLLBACK_RUNNING");
        send(by, "rollback.reading");
        worker.execute(() -> {
            try {
                List<LogStore.Row> rows = store.find(withLimit(query), true);
                if (rows.size() > maxRows) { later(() -> send(by, "rollback.tooManyRows", Num.group(maxRows))); return; }
                List<RollbackPlan.Entry> entries = RollbackPlan.build(rows);
                if (entries.size() > maxBlocks) { later(() -> send(by, "rollback.tooManyBlocks", Num.group(entries.size()), Num.group(maxBlocks))); return; }
                // Container contents of the same area and window come back with the blocks; slots used since are left alone.
                ItemPart part = ItemPart.NONE;
                if (itemStore != null) {
                    List<ItemStore.Row> itemRows = itemStore.find(new ItemStore.Query(query.world(), query.minX(), query.maxX(), query.minY(), query.maxY(),
                            query.minZ(), query.maxZ(), query.actor(), query.since(), maxRows + 1));
                    if (itemRows.size() <= maxRows) part = itemPart(itemRows);
                    if (part.entries().size() + entries.size() > maxBlocks) part = ItemPart.NONE;
                }
                ItemPart itemPart = part;
                later(() -> {
                    synchronized (pending) { pending.put(by.getName(), new Pending(entries, itemPart.entries(), itemPart.spills(), itemPart.pickups(), title, System.currentTimeMillis())); }
                    int total = entries.size() + itemPart.entries().size();
                    if (total == 0) send(by, "rollback.nothing", title);
                    else if (ready != null) ready.accept(total);
                    else send(by, "rollback.preview", title, Num.group(total), Num.group(rows.size()), confirmMillis / 1000);
                    if (total > 0) tellTakeBack(by, itemPart.pickups());
                });
            } catch (Exception failure) {
                plugin.warn("log.rollbackPreviewFailed", failure);
                later(() -> send(by, "cmd.failed", failure.getClass().getSimpleName()));
            }
        });
    }


    /** Same for container contents: reads the item log in the background and keeps the plan until it is confirmed or expires. */
    public void previewItems(CommandSender by, ItemStore.Query query, String title) { previewItems(by, query, title, null); }

    /** Same, and when the plan has slots to restore the chat preview is skipped and {@code ready} gets their number instead. */
    public void previewItems(CommandSender by, ItemStore.Query query, String title, java.util.function.IntConsumer ready) {
        if (itemStore == null) throw new IllegalArgumentException("ITEMS_OFF");
        if (running.get()) throw new IllegalArgumentException("ROLLBACK_RUNNING");
        send(by, "rollback.reading");
        worker.execute(() -> {
            try {
                List<dev.toka.protect.log.ItemStore.Row> rows = itemStore.find(new ItemStore.Query(query.world(), query.minX(), query.maxX(), query.minY(), query.maxY(),
                        query.minZ(), query.maxZ(), query.actor(), query.since(), maxRows + 1));
                if (rows.size() > maxRows) { later(() -> send(by, "rollback.tooManyRows", Num.group(maxRows))); return; }
                ItemPart part = itemPart(rows);
                List<ItemPlan.Entry> entries = part.entries();
                if (entries.size() > maxBlocks) { later(() -> send(by, "rollback.tooManyBlocks", Num.group(entries.size()), Num.group(maxBlocks))); return; }
                later(() -> {
                    synchronized (pending) { pending.put(by.getName(), new Pending(List.of(), entries, part.spills(), part.pickups(), title, System.currentTimeMillis())); }
                    if (entries.isEmpty()) send(by, "rollback.nothing", title);
                    else if (ready != null) ready.accept(entries.size());
                    else send(by, "rollback.previewItems", title, Num.group(entries.size()), Num.group(rows.size()), confirmMillis / 1000);
                    if (!entries.isEmpty()) tellTakeBack(by, part.pickups());
                });
            } catch (Exception failure) {
                plugin.warn("log.rollbackPreviewFailed", failure);
                later(() -> send(by, "cmd.failed", failure.getClass().getSimpleName()));
            }
        });
    }

    private static ItemPart itemPart(List<ItemStore.Row> rows) {
        return new ItemPart(ItemPlan.build(rows), ItemPlan.spills(rows), ItemPlan.pickups(rows));
    }

    /** Names who picked up fallen stacks and how many, so the admin knows what a confirmation will take back. */
    private void tellTakeBack(CommandSender by, List<ItemPlan.Pickup> pickups) {
        if (pickups.isEmpty()) return;
        Map<String, Integer> perPlayer = new java.util.TreeMap<>();
        for (ItemPlan.Pickup p : pickups) {
            try { perPlayer.merge(p.name(), ItemCodec.decode(p.stack()).getCount(), Integer::sum); } catch (java.io.IOException | RuntimeException ignored) { /* unreadable stack */ }
        }
        if (perPlayer.isEmpty()) return;
        StringBuilder who = new StringBuilder();
        perPlayer.forEach((name, count) -> who.append(who.length() == 0 ? "" : ", ").append(name).append(" x").append(count));
        send(by, "rollback.takeBack", who.toString());
    }

    private LogStore.Query withLimit(LogStore.Query q) {
        return new LogStore.Query(q.world(), q.minX(), q.maxX(), q.minY(), q.maxY(), q.minZ(), q.maxZ(), q.actor(), q.since(), q.until(), q.action(), maxRows + 1, 0);
    }

    public void cancel(CommandSender by) {
        synchronized (pending) { pending.remove(by.getName()); }
        send(by, "rollback.cancelled");
    }

    /** Applies the previewed plan slice by slice. */
    public void confirm(CommandSender by) {
        Pending plan;
        synchronized (pending) { plan = pending.remove(by.getName()); }
        if (plan == null || System.currentTimeMillis() - plan.createdAt() > confirmMillis) { send(by, "rollback.noPlan"); return; }
        if (!running.compareAndSet(false, true)) { send(by, "rollback.running"); return; }
        send(by, "rollback.started", Num.group(plan.entries().size() + plan.items().size()));
        plugin.info("log.rollbackStarted", by.getName(), plan.title(), plan.entries().size() + plan.items().size());
        plugin.getServer().getScheduler().scheduleRepeatingTask(plugin, new Task() {
            private int at;
            private int restored;
            private int skipped;
            private final List<TakeBack> takeBacks = takeBacks(plan.pickups());

            @Override public void onRun(int currentTick) {
                try {
                    List<RollbackPlan.Entry> entries = plan.entries();
                    List<ItemPlan.Entry> items = plan.items();
                    for (int done = 0; done < perTick && at < entries.size() + items.size(); done++, at++) {
                        boolean ok = at < entries.size() ? apply(entries.get(at)) : applyItem(items.get(at - entries.size()), plan.spills(), takeBacks);
                        if (ok) restored++; else skipped++;
                    }
                    if (at < entries.size() + items.size()) return;
                } catch (RuntimeException | LinkageError failure) {
                    plugin.warn("log.rollbackInterrupted", failure);
                }
                getHandler().cancel();
                running.set(false);
                plugin.info("log.rollbackFinished", by.getName(), restored, skipped);
                send(by, "rollback.done", Num.group(restored), Num.group(skipped));
            }
        }, 1);
    }

    /** True when the block was put back; false when the world is not loaded or the spot changed since the log. */
    private boolean apply(RollbackPlan.Entry entry) {
        Level level = plugin.getServer().getLevelByName(entry.world());
        if (level == null) return false;
        Vector3 at = new Vector3(entry.x(), entry.y(), entry.z());
        var now = level.getBlock(at);
        if (now.getId() != entry.expectedId() || now.getDamage() != entry.expectedMeta()) return false;
        level.setBlock(at, Block.get(entry.restoreId(), entry.restoreMeta()), false, true);
        TileNbt.restore(level, entry.x(), entry.y(), entry.z(), entry.restoreNbt());
        // A container without saved data still has to be a working one, so give it an empty tile (the item step fills it).
        if (entry.restoreNbt() == null && level.getBlockEntity(at) == null) {
            try { rebuiltContainer(level, at); } catch (RuntimeException ignored) { /* the block is back; it opens as a plain container later */ }
        }
        return true;
    }


    /** True when the slot got its old stack back; false when the container is gone, is not one, or the slot was used since the log. */
    private boolean applyItem(ItemPlan.Entry entry, java.util.Set<String> spills, List<TakeBack> takeBacks) {
        Level level = plugin.getServer().getLevelByName(entry.world());
        if (level == null) return false;
        try {
            Vector3 at = new Vector3(entry.x(), entry.y(), entry.z());
            var tile = level.getBlockEntity(at);
            if (tile == null) tile = rebuiltContainer(level, at);
            cn.nukkit.inventory.Inventory inventory = tile instanceof cn.nukkit.blockentity.BlockEntityChest chest ? chest.getRealInventory()
                    : tile instanceof cn.nukkit.inventory.InventoryHolder holder && tile instanceof cn.nukkit.blockentity.BlockEntityContainer ? holder.getInventory() : null;
            if (inventory == null || entry.slot() < 0 || entry.slot() >= inventory.getSize()) return false;
            if (!ItemCodec.same(entry.expected(), inventory.getItem(entry.slot()))) return false;
            var stack = ItemCodec.decode(entry.restore());
            // A container that broke dropped these stacks; clear what is still lying around or was picked up so nothing doubles.
            if (stack.getId() != 0 && spills.contains(ItemPlan.key(entry.world(), entry.x(), entry.y(), entry.z()))) reclaim(level, entry, stack, takeBacks);
            inventory.setItem(entry.slot(), stack);
            return true;
        } catch (java.io.IOException | RuntimeException unreadable) {
            return false;
        }
    }

    private static List<TakeBack> takeBacks(List<ItemPlan.Pickup> pickups) {
        List<TakeBack> list = new java.util.ArrayList<>();
        for (ItemPlan.Pickup p : pickups) {
            try { list.add(new TakeBack(p, ItemCodec.decode(p.stack()).getCount())); } catch (java.io.IOException | RuntimeException ignored) { /* unreadable stack */ }
        }
        return list;
    }

    /** Removes the dropped copies of a stack near its container (up to its count), then takes the rest back from players who picked it up. */
    private static void reclaim(Level level, ItemPlan.Entry entry, cn.nukkit.item.Item stack, List<TakeBack> takeBacks) throws java.io.IOException {
        int left = stack.getCount();
        var box = new cn.nukkit.math.SimpleAxisAlignedBB(entry.x() - 8, entry.y() - 8, entry.z() - 8, entry.x() + 9, entry.y() + 9, entry.z() + 9);
        for (var near : level.getNearbyEntities(box)) {
            if (left <= 0) break;
            if (!(near instanceof cn.nukkit.entity.item.EntityItem dropped) || dropped.isClosed()) continue;
            var lying = dropped.getItem();
            if (lying == null || !lying.equals(stack, true, true)) continue;
            int n = Math.min(left, lying.getCount());
            left -= n;
            if (n >= lying.getCount()) dropped.close(); else lying.setCount(lying.getCount() - n);
        }
        for (TakeBack back : takeBacks) {
            if (left <= 0) break;
            var p = back.pickup;
            if (back.left <= 0 || !p.world().equals(entry.world()) || p.x() != entry.x() || p.y() != entry.y() || p.z() != entry.z()) continue;
            var picked = ItemCodec.decode(p.stack());
            if (!picked.equals(stack, true, true)) continue;
            var player = level.getServer().getPlayer(p.player()).orElse(null);
            if (player == null) continue;
            int n = Math.min(left, back.left);
            var take = picked.clone();
            take.setCount(n);
            var leftover = player.getInventory().removeItem(take);
            int notRemoved = 0;
            for (var item : leftover) notRemoved += item.getCount();
            back.left -= n;
            left -= Math.max(n - notRemoved, 0);
        }
    }

    /** A container block that came back from a block rollback has no tile yet; make an empty one so the items have a place to go. */
    private static cn.nukkit.blockentity.BlockEntity rebuiltContainer(Level level, Vector3 at) {
        int id = level.getBlockIdAt(at.getFloorX(), at.getFloorY(), at.getFloorZ());
        String type = id == cn.nukkit.block.BlockID.CHEST || id == cn.nukkit.block.BlockID.TRAPPED_CHEST ? cn.nukkit.blockentity.BlockEntity.CHEST
                : id == cn.nukkit.block.BlockID.BARREL ? cn.nukkit.blockentity.BlockEntity.BARREL : null;
        if (type == null) return null;
        var chunk = level.getChunk(at.getFloorX() >> 4, at.getFloorZ() >> 4, true);
        return cn.nukkit.blockentity.BlockEntity.createBlockEntity(type, chunk, cn.nukkit.blockentity.BlockEntity.getDefaultCompound(at, type));
    }

    @Override public void close() { worker.shutdown(); }
}
