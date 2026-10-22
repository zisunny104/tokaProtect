package dev.toka.protect.audit;

import cn.nukkit.command.CommandSender;
import dev.toka.protect.Main;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Looks up the audit on a helper thread and shows a page when it is ready, so the server thread never waits for the disk. */
public final class AuditView implements AutoCloseable {
    private static final int PAGE = 10;
    private final Main plugin;
    private final AuditStore store;
    private final int maxRows;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "tokaProtect-audit-query"); t.setDaemon(true); return t; });

    public AuditView(Main plugin, AuditStore store, int maxRows) {
        this.plugin = plugin;
        this.store = store;
        this.maxRows = maxRows;
    }

    public AuditStore store() { return store; }

    public void show(CommandSender to, UUID actor, String title, Long since, int page) {
        to.sendMessage(plugin.text(to, "log.searching"));
        worker.execute(() -> {
            try {
                List<AuditStore.Row> rows = store.find(actor, since, maxRows);
                plugin.getServer().getScheduler().scheduleTask(plugin, () -> render(to, title, rows, page));
            } catch (Exception failure) {
                plugin.getLogger().warning("指令稽核查詢失敗：" + failure);
                plugin.getServer().getScheduler().scheduleTask(plugin, () -> to.sendMessage(plugin.text(to, "cmd.failed", failure.getClass().getSimpleName())));
            }
        });
    }

    private void render(CommandSender to, String title, List<AuditStore.Row> rows, int page) {
        if (rows.isEmpty()) { to.sendMessage(plugin.text(to, "log.none", title)); return; }
        int pages = (rows.size() + PAGE - 1) / PAGE;
        int shown = Math.min(Math.max(page, 1), pages);
        to.sendMessage(plugin.text(to, "log.header", title, shown, pages, rows.size()));
        SimpleDateFormat when = new SimpleDateFormat("MM-dd HH:mm:ss");
        for (AuditStore.Row row : rows.subList((shown - 1) * PAGE, Math.min(rows.size(), shown * PAGE)))
            to.sendMessage(plugin.text(to, "audit.row", when.format(new Date(row.time())), row.name(), plugin.text(to, row.kind().langKey()), row.text()));
    }

    @Override public void close() { worker.shutdown(); }
}
