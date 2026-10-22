package dev.toka.protect.command;

import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.command.data.CommandParamType;
import cn.nukkit.command.data.CommandParameter;
import dev.toka.protect.Feature;
import dev.toka.protect.Main;
import dev.toka.protect.flag.*;
import java.io.IOException;
import java.util.*;

/** /protect: look at the rules here, and for administrators change them layer by layer. */
public final class ProtectCommand extends Command {
    private static final String[] LAYERS = {"world", "region", "claim"};
    private static final String[] VALUES = {"allow", "deny", "inherit"};
    private final Main plugin;

    public ProtectCommand(Main plugin) {
        super("protect", "全服保護", "/protect help");
        this.plugin = plugin;
        setPermission("tokaprotect.use;tokaprotect.admin");
        getCommandParameters().clear();
        addCommandParameters("read", new CommandParameter[]{CommandParameter.newEnum("action", new String[]{"help", "status", "flags", "info"})});
        addCommandParameters("log", new CommandParameter[]{CommandParameter.newEnum("action", new String[]{"log"}),
                CommandParameter.newEnum("what", new String[]{"here", "check", "stats"}), CommandParameter.newType("radius", true, CommandParamType.INT),
                CommandParameter.newType("time", true, CommandParamType.STRING), CommandParameter.newType("page", true, CommandParamType.INT)});
        addCommandParameters("logPlayer", new CommandParameter[]{CommandParameter.newEnum("action", new String[]{"log"}),
                CommandParameter.newEnum("what", new String[]{"player", "commands"}), CommandParameter.newType("name", CommandParamType.STRING),
                CommandParameter.newType("time", true, CommandParamType.STRING), CommandParameter.newType("page", true, CommandParamType.INT)});
        addCommandParameters("reset", new CommandParameter[]{CommandParameter.newEnum("action", new String[]{"reset"}),
                CommandParameter.newEnum("feature", Arrays.stream(Feature.values()).map(Feature::id).toArray(String[]::new))});
        addCommandParameters("show", new CommandParameter[]{CommandParameter.newEnum("action", new String[]{"show"}),
                CommandParameter.newEnum("layer", LAYERS), CommandParameter.newType("target", CommandParamType.STRING)});
        addCommandParameters("set", new CommandParameter[]{CommandParameter.newEnum("action", new String[]{"set"}),
                CommandParameter.newEnum("layer", LAYERS), CommandParameter.newType("target", CommandParamType.STRING),
                CommandParameter.newEnum("flag", Arrays.stream(Flag.values()).map(Flag::id).toArray(String[]::new)),
                CommandParameter.newEnum("value", VALUES), CommandParameter.newEnum("force", true, new String[]{"enforce"})});
    }

    private void send(CommandSender sender, String key, Object... args) { sender.sendMessage(plugin.text(sender, key, args)); }

    @Override public boolean execute(CommandSender sender, String label, String[] args) {
        if (!testPermissionSilent(sender)) { send(sender, "cmd.denied"); return true; }
        String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        try {
            switch (action) {
                case "help" -> help(sender);
                case "flags" -> flags(sender);
                case "info" -> info(sender);
                case "status" -> { admin(sender); status(sender); }
                case "log" -> { admin(sender); log(sender, args); }
                case "reset" -> { admin(sender); reset(sender, args); }
                case "show" -> { admin(sender); show(sender, args); }
                case "set" -> { admin(sender); set(sender, args); }
                default -> send(sender, "cmd.usage");
            }
        } catch (SecurityException denied) {
            send(sender, "cmd.denied");
        } catch (IllegalArgumentException | IOException failure) {
            send(sender, "cmd.failed", failure.getMessage());
        }
        return true;
    }

    private String word(CommandSender sender, boolean allowed) { return plugin.text(sender, allowed ? "word.allow" : "word.deny"); }

    private void admin(CommandSender sender) {
        if (!sender.hasPermission("tokaprotect.admin")) throw new SecurityException();
    }

    private void help(CommandSender sender) {
        send(sender, "help.title");
        for (String key : List.of("flags", "info")) send(sender, "help." + key);
        if (sender.hasPermission("tokaprotect.admin")) for (String key : List.of("status", "reset", "show", "set", "layers", "enforce", "log", "logCheck", "logPlayer", "logCommands", "logStats")) send(sender, "help." + key);
    }

    private void flags(CommandSender sender) {
        send(sender, "flags.title");
        for (Flag flag : Flag.values()) send(sender, "flags.row", flag.id(), word(sender, flag.defaultValue()), plugin.text(sender, "flag." + flag.id()));
    }

    /** Every flag at the sender's feet, and which layer decided it. */
    private void info(CommandSender sender) {
        if (!(sender instanceof Player player)) throw new IllegalArgumentException("PLAYER_ONLY");
        String world = player.getLevel().getFolderName();
        int x = player.getFloorX(), y = player.getFloorY(), z = player.getFloorZ();
        send(sender, "info.title", world, x, y, z);
        for (Flag flag : Flag.values()) {
            var result = plugin.rules().explain(flag, world, x, y, z);
            String source = result.decidedBy().map(layer -> plugin.text(sender, "layer." + layer.kind().name().toLowerCase(Locale.ROOT), shorten(layer))).orElse(plugin.text(sender, "layer.default"));
            send(sender, "info.row", flag.id(), word(sender, result.allowed()), source);
        }
    }

    private void status(CommandSender sender) {
        int settings = plugin.rules().table().all().values().stream().mapToInt(Map::size).sum();
        send(sender, "status", plugin.mode().name().toLowerCase(Locale.ROOT), settings,
                plugin.text(sender, plugin.places().hasRegions() ? "word.yes" : "word.no"), plugin.text(sender, plugin.places().hasClaims() ? "word.yes" : "word.no"));
        for (Feature feature : Feature.values()) {
            var state = plugin.breaker().state(feature);
            String how = !plugin.enabled().contains(feature) ? plugin.text(sender, "feature.off")
                    : state.tripped() ? plugin.text(sender, "feature.tripped", state.lastError()) : plugin.text(sender, "feature.running", state.recentFailures());
            send(sender, "feature.row", feature.id(), how);
        }
    }

    /** /protect log here [radius] [time] [page] | check | player <name> [time] [page] | stats */
    private void log(CommandSender sender, String[] args) {
        if (need(args, 1).equalsIgnoreCase("commands")) { commands(sender, args); return; }
        var view = plugin.logView();
        if (view == null) throw new IllegalArgumentException("LOG_OFF");
        switch (need(args, 1).toLowerCase(Locale.ROOT)) {
            case "stats" -> view.stats(sender);
            case "check" -> {
                Player player = view.requirePlayer(sender);
                send(sender, plugin.inspector().toggle(player) ? "log.checkOn" : "log.checkOff");
            }
            case "here" -> {
                Player player = view.requirePlayer(sender);
                int radius = args.length > 2 ? Integer.parseInt(args[2]) : 5;
                if (radius < 0 || radius > 64) throw new IllegalArgumentException("RADIUS");
                long span = args.length > 3 ? dev.toka.protect.log.TimeSpan.parseMillis(args[3]) : 86_400_000L;
                int page = args.length > 4 ? Integer.parseInt(args[4]) : 1;
                view.show(sender, dev.toka.protect.log.LogStore.Query.at(player.getLevel().getFolderName(), player.getFloorX(), player.getFloorY(), player.getFloorZ(),
                        radius, System.currentTimeMillis() - span, 0, 0), player.getLevel().getFolderName() + " ±" + radius, page);
            }
            case "player" -> {
                java.util.UUID who = view.player(need(args, 2));
                long span = args.length > 3 ? dev.toka.protect.log.TimeSpan.parseMillis(args[3]) : 86_400_000L;
                int page = args.length > 4 ? Integer.parseInt(args[4]) : 1;
                view.show(sender, dev.toka.protect.log.LogStore.Query.byPlayer(who, System.currentTimeMillis() - span, 0, 0), args[2], page);
            }
            default -> throw new IllegalArgumentException("USAGE");
        }
    }

    /** /protect log commands [player] [time] [page]: commands and sign text, newest first. */
    private void commands(CommandSender sender, String[] args) {
        var audit = plugin.auditView();
        if (audit == null) throw new IllegalArgumentException("AUDIT_OFF");
        String who = args.length > 2 ? args[2] : "*";
        java.util.UUID actor = null;
        if (!who.equals("*")) {
            Player online = plugin.getServer().getPlayer(who);
            actor = online != null ? online.getUniqueId() : plugin.logView() != null ? plugin.logView().player(who) : null;
            if (actor == null) throw new IllegalArgumentException("LOG_PLAYER_UNKNOWN");
        }
        long span = args.length > 3 ? dev.toka.protect.log.TimeSpan.parseMillis(args[3]) : 86_400_000L;
        int page = args.length > 4 ? Integer.parseInt(args[4]) : 1;
        audit.show(sender, actor, who.equals("*") ? "*" : who, System.currentTimeMillis() - span, page);
    }

    private void reset(CommandSender sender, String[] args) {
        Feature feature = Feature.byId(need(args, 1)).orElseThrow(() -> new IllegalArgumentException("UNKNOWN_FEATURE"));
        plugin.breaker().reset(feature);
        send(sender, "done");
    }

    private void show(CommandSender sender, String[] args) {
        Layer layer = layer(need(args, 1), need(args, 2));
        var flags = plugin.rules().table().all().getOrDefault(layer, Map.of());
        send(sender, "show.title", shorten(layer), flags.size());
        flags.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e ->
                send(sender, "show.row", e.getKey().id(), e.getValue().value().name().toLowerCase(Locale.ROOT), e.getValue().enforced() ? plugin.text(sender, "show.enforced") : ""));
    }

    private void set(CommandSender sender, String[] args) throws IOException {
        Layer layer = layer(need(args, 1), need(args, 2));
        Flag flag = Flag.byId(need(args, 3)).orElseThrow(() -> new IllegalArgumentException("UNKNOWN_FLAG"));
        Setting.Value value = switch (need(args, 4).toLowerCase(Locale.ROOT)) {
            case "allow" -> Setting.Value.ALLOW;
            case "deny" -> Setting.Value.DENY;
            case "inherit" -> Setting.Value.INHERIT;
            default -> throw new IllegalArgumentException("USAGE");
        };
        boolean enforce = args.length > 5 && args[5].equalsIgnoreCase("enforce");
        plugin.save(plugin.rules().table().with(layer, flag, new Setting(value, enforce && value != Setting.Value.INHERIT)));
        plugin.getLogger().info("保護旗標：" + sender.getName() + " " + layer.kind() + " " + layer.id() + " " + flag.id() + " " + value + (enforce ? " enforce" : ""));
        send(sender, "done");
    }

    /** A world by its folder name, a region by name, or a claim by its id. */
    private Layer layer(String kind, String target) {
        return switch (kind.toLowerCase(Locale.ROOT)) {
            case "world" -> {
                if (plugin.getServer().getLevelByName(target) == null) throw new IllegalArgumentException("WORLD_UNKNOWN");
                yield Layer.world(plugin.getServer().getLevelByName(target).getFolderName());
            }
            case "region" -> {
                String id = plugin.places().regionByName(target);
                if (id == null) throw new IllegalArgumentException("REGION_UNKNOWN");
                yield Layer.region(id);
            }
            case "claim" -> Layer.claim(UUID.fromString(target).toString());
            default -> throw new IllegalArgumentException("USAGE");
        };
    }

    private static String shorten(Layer layer) {
        return layer.kind() == Layer.Kind.WORLD ? layer.id() : layer.id().substring(0, Math.min(8, layer.id().length()));
    }

    private static String need(String[] args, int index) {
        if (args.length <= index) throw new IllegalArgumentException("USAGE");
        return args[index];
    }
}
