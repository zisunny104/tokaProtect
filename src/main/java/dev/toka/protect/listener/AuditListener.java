package dev.toka.protect.listener;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.SignChangeEvent;
import cn.nukkit.event.player.PlayerCommandPreprocessEvent;
import cn.nukkit.event.server.RemoteServerCommandEvent;
import cn.nukkit.event.server.ServerCommandEvent;
import dev.toka.protect.Main;
import cn.nukkit.utils.TextFormat;
import dev.toka.protect.Breaker;
import dev.toka.protect.Feature;
import dev.toka.protect.audit.AuditMask;
import dev.toka.protect.audit.AuditRecord;
import dev.toka.protect.link.DiscordSender;
import dev.toka.protect.log.LogWriter;
import java.util.Arrays;
import java.util.Set;

/**
 * Keeps a record of the commands people type and the text written on signs, with secrets in login-style commands hidden,
 * and can pass each line to a Discord channel. It runs last and only notes what was typed; it never changes the outcome.
 */
public final class AuditListener implements Listener {
    private final Main plugin;
    private final LogWriter<AuditRecord> writer;
    private final Breaker breaker;
    private final Set<String> hidden;
    private final int maxLength;
    private final DiscordSender discord;
    private final String channel;
    private final boolean relay;

    public AuditListener(Main plugin, LogWriter<AuditRecord> writer, Breaker breaker, Set<String> hidden, int maxLength,
                         DiscordSender discord, String channel, boolean relay) {
        this.plugin = plugin;
        this.writer = writer;
        this.breaker = breaker;
        this.hidden = hidden;
        this.maxLength = maxLength;
        this.discord = discord;
        this.channel = channel;
        this.relay = relay;
    }

    private void note(Player player, AuditRecord.Kind kind, String raw, String label) {
        if (!breaker.working(Feature.AUDIT)) return;
        try {
            String text = kind == AuditRecord.Kind.SIGN ? AuditMask.mask(raw, Set.of(), maxLength) : AuditMask.mask(raw, hidden, maxLength);
            String name = player == null ? label : player.getName();
            var level = player == null ? null : player.getLevel();
            writer.offer(new AuditRecord(System.currentTimeMillis(), player == null ? null : player.getUniqueId(), name, kind, text,
                    level == null ? null : level.getFolderName(), player == null ? 0 : player.getFloorX(), player == null ? 0 : player.getFloorY(), player == null ? 0 : player.getFloorZ()));
            if (relay) discord.send("<" + TextFormat.clean(name) + (kind == AuditRecord.Kind.SIGN ? "|sign" : "") + "> " + TextFormat.clean(text), channel);
        } catch (RuntimeException | LinkageError error) {
            if (breaker.failed(Feature.AUDIT, error)) plugin.error("log.auditErrorTripped", error);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) { note(event.getPlayer(), AuditRecord.Kind.COMMAND, event.getMessage(), null); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onConsole(ServerCommandEvent event) { note(null, AuditRecord.Kind.CONSOLE, event.getCommand(), "Server"); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemote(RemoteServerCommandEvent event) { note(null, AuditRecord.Kind.REMOTE, event.getCommand(), "Remote"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSign(SignChangeEvent event) { note(event.getPlayer(), AuditRecord.Kind.SIGN, String.join(" | ", Arrays.asList(event.getLines())), null); }
}
