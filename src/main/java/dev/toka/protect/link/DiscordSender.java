package dev.toka.protect.link;

import cn.nukkit.Server;
import cn.nukkit.plugin.Plugin;
import java.lang.reflect.Method;

/** Sends a line to a Discord channel through tokaDiscordLink if it is there; otherwise nothing happens. */
public final class DiscordSender {
    private final Server server;

    public DiscordSender(Server server) { this.server = server; }

    public void send(String text, String channel) {
        Plugin link = server.getPluginManager().getPlugin("tokaDiscordLink");
        if (link == null || !link.isEnabled()) return;
        try {
            Method send = Class.forName("dev.toka.discord.api.DiscordLink", true, link.getClass().getClassLoader()).getMethod("sendMessage", String.class, String.class);
            send.invoke(null, text, channel);
        } catch (ReflectiveOperationException | LinkageError unavailable) { /* Discord is optional */ }
    }
}
