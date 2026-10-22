package dev.toka.protect.rollback;

import cn.nukkit.block.Block;
import cn.nukkit.command.CommandSender;
import cn.nukkit.level.Level;
import cn.nukkit.math.Vector3;
import cn.nukkit.scheduler.Task;
import dev.toka.protect.Main;
import dev.toka.protect.log.LogStore;
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
    private record Pending(List<RollbackPlan.Entry> entries, String title, long createdAt) {}

    private final Main plugin;
    private final LogStore store;
    private final int maxRows;
    private final int maxBlocks;
    private final int perTick;
    private final long confirmMillis;
    private final Map<String, Pending> pending = new HashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "tokaProtect-rollback"); t.setDaemon(true); return t; });

    public Rollback(Main plugin, LogStore store, int maxRows, int maxBlocks, int perTick, long confirmMillis) {
        this.plugin = plugin;
        this.store = store;
        this.maxRows = maxRows;
        this.maxBlocks = maxBlocks;
        this.perTick = perTick;
        this.confirmMillis = confirmMillis;
    }

    private void send(CommandSender to, String key, Object... args) { to.sendMessage(plugin.text(to, key, args)); }

    private void later(Runnable action) { plugin.getServer().getScheduler().scheduleTask(plugin, action); }

    /** Reads the log for the query in the background and keeps the resulting plan until it is confirmed or expires. */
    public void preview(CommandSender by, LogStore.Query query, String title) {
        if (running.get()) throw new IllegalArgumentException("ROLLBACK_RUNNING");
        send(by, "rollback.reading");
        worker.execute(() -> {
            try {
                List<LogStore.Row> rows = store.find(withLimit(query), true);
                if (rows.size() > maxRows) { later(() -> send(by, "rollback.tooManyRows", maxRows)); return; }
                List<RollbackPlan.Entry> entries = RollbackPlan.build(rows);
                if (entries.size() > maxBlocks) { later(() -> send(by, "rollback.tooManyBlocks", entries.size(), maxBlocks)); return; }
                later(() -> {
                    synchronized (pending) { pending.put(by.getName(), new Pending(entries, title, System.currentTimeMillis())); }
                    if (entries.isEmpty()) send(by, "rollback.nothing", title);
                    else send(by, "rollback.preview", title, entries.size(), rows.size(), confirmMillis / 1000);
                });
            } catch (Exception failure) {
                plugin.getLogger().warning("還原預覽失敗：" + failure);
                later(() -> send(by, "cmd.failed", failure.getClass().getSimpleName()));
            }
        });
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
        send(by, "rollback.started", plan.entries().size());
        plugin.getLogger().info("方塊還原開始：" + by.getName() + " " + plan.title() + "，" + plan.entries().size() + " 個位置");
        plugin.getServer().getScheduler().scheduleRepeatingTask(plugin, new Task() {
            private int at;
            private int restored;
            private int skipped;

            @Override public void onRun(int currentTick) {
                try {
                    List<RollbackPlan.Entry> entries = plan.entries();
                    for (int done = 0; done < perTick && at < entries.size(); done++, at++) {
                        if (apply(entries.get(at))) restored++; else skipped++;
                    }
                    if (at < entries.size()) return;
                } catch (RuntimeException failure) {
                    plugin.getLogger().warning("方塊還原中斷：" + failure);
                }
                getHandler().cancel();
                running.set(false);
                plugin.getLogger().info("方塊還原結束：" + by.getName() + " 還原 " + restored + "、略過 " + skipped);
                send(by, "rollback.done", restored, skipped);
            }
        }, 1);
    }

    /** True when the block was put back; false when the world is not loaded or the spot changed since the log. */
    private boolean apply(RollbackPlan.Entry entry) {
        Level level = plugin.getServer().getLevelByName(entry.world());
        if (level == null) return false;
        Vector3 at = new Vector3(entry.x(), entry.y(), entry.z());
        if (level.getBlock(at).getId() != entry.expectedId()) return false;
        level.setBlock(at, Block.get(entry.restoreId(), entry.restoreMeta()), false, true);
        TileNbt.restore(level, entry.x(), entry.y(), entry.z(), entry.restoreNbt());
        return true;
    }

    @Override public void close() { worker.shutdown(); }
}
