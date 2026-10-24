package dev.toka.protect.log;

import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.command.CommandSender;
import dev.toka.protect.Main;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Looks things up in the log without touching the server thread: the query runs on a helper thread and the answer is sent
 * back on the next tick. Output is collapsed and paged so it stays readable; block names come from the core.
 */
public final class LogView implements AutoCloseable {
    private static final int PAGE = 10;
    private final Main plugin;
    private final LogStore store;
    private final int maxRows;
    private final long gapMillis;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "tokaProtect-log-query"); t.setDaemon(true); return t; });

    public LogView(Main plugin, LogStore store, int maxRows, long gapMillis) {
        this.plugin = plugin;
        this.store = store;
        this.maxRows = maxRows;
        this.gapMillis = gapMillis;
    }

    public LogStore store() { return store; }

    private void send(CommandSender to, String key, Object... args) { to.sendMessage(plugin.text(to, key, args)); }

    /** Runs the query in the background and shows the requested page when it is ready. */
    public void show(CommandSender to, LogStore.Query query, String title, int page) {
        send(to, "log.searching");
        worker.execute(() -> {
            try {
                List<LogFormat.Line> lines = LogFormat.collapse(store.find(withLimit(query)), gapMillis);
                plugin.getServer().getScheduler().scheduleTask(plugin, () -> render(to, title, lines, page));
            } catch (Exception failure) {
                plugin.warn("log.queryFailed", failure);
                plugin.getServer().getScheduler().scheduleTask(plugin, () -> send(to, "cmd.failed", failure.getClass().getSimpleName()));
            }
        });
    }

    /** The collapsed result lines, delivered on the server thread; null when the query failed. */
    public void lines(LogStore.Query query, java.util.function.Consumer<List<LogFormat.Line>> done) {
        worker.execute(() -> {
            List<LogFormat.Line> lines = null;
            try { lines = LogFormat.collapse(store.find(withLimit(query)), gapMillis); }
            catch (Exception failure) { plugin.warn("log.queryFailed", failure); }
            List<LogFormat.Line> result = lines;
            plugin.getServer().getScheduler().scheduleTask(plugin, () -> done.accept(result));
        });
    }

    /** One result line as text. */
    public String format(CommandSender to, LogFormat.Line line) {
        LogStore.Row row = line.newest();
        String who = row.actor() != null ? row.actorName() : plugin.text(to, "log.cause", row.cause());
        return plugin.text(to, "log.row", new SimpleDateFormat("MM-dd HH:mm").format(new Date(row.time())), who, plugin.text(to, row.action().langKey()), blockName(row.block(), row.meta()),
                row.world() + " " + row.x() + "," + row.y() + "," + row.z(), line.count() > 1 ? plugin.text(to, "log.times", line.count()) : "");
    }

    private LogStore.Query withLimit(LogStore.Query q) {
        return new LogStore.Query(q.world(), q.minX(), q.maxX(), q.minY(), q.maxY(), q.minZ(), q.maxZ(), q.actor(), q.since(), q.until(), q.action(), maxRows, 0);
    }

    private void render(CommandSender to, String title, List<LogFormat.Line> lines, int page) {
        if (lines.isEmpty()) { send(to, "log.none", title); return; }
        int pages = (lines.size() + PAGE - 1) / PAGE;
        int shown = Math.min(Math.max(page, 1), pages);
        send(to, "log.header", title, shown, pages, lines.size());
        SimpleDateFormat when = new SimpleDateFormat("MM-dd HH:mm");
        for (LogFormat.Line line : lines.subList((shown - 1) * PAGE, Math.min(lines.size(), shown * PAGE))) {
            LogStore.Row row = line.newest();
            String who = row.actor() != null ? row.actorName() : plugin.text(to, "log.cause", row.cause());
            send(to, "log.row", when.format(new Date(row.time())), who, plugin.text(to, row.action().langKey()), blockName(row.block(), row.meta()),
                    row.world() + " " + row.x() + "," + row.y() + "," + row.z(), line.count() > 1 ? plugin.text(to, "log.times", line.count()) : "");
        }
    }

    /** The core's own name for the block; the numbers if the core does not know it. */
    private static String blockName(int id, int meta) {
        try { return Block.get(id, meta).getName(); }
        catch (RuntimeException unknown) { return id + ":" + meta; }
    }

    public void stats(CommandSender to) {
        worker.execute(() -> {
            try {
                LogStore.Stats stats = store.stats();
                String oldest = stats.oldest() == null ? "-" : new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(stats.oldest()));
                plugin.getServer().getScheduler().scheduleTask(plugin, () -> send(to, "log.stats", Num.group(stats.rows()), oldest, Num.group(stats.fileBytes() / 1024 / 1024)));
            } catch (Exception failure) {
                plugin.getServer().getScheduler().scheduleTask(plugin, () -> send(to, "cmd.failed", failure.getClass().getSimpleName()));
            }
        });
    }

    /** The player a typed name stands for, only when the log knows exactly one. */
    public UUID player(String name) {
        return store.playerByName(name).orElseThrow(() -> new IllegalArgumentException("LOG_PLAYER_UNKNOWN"));
    }

    public Player requirePlayer(CommandSender sender) {
        if (!(sender instanceof Player player)) throw new IllegalArgumentException("PLAYER_ONLY");
        return player;
    }

    @Override public void close() { worker.shutdown(); }
}
