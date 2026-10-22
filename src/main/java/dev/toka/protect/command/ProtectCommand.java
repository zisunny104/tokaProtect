package dev.toka.protect.command;

import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.command.data.CommandParamType;
import cn.nukkit.command.data.CommandParameter;
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
        if (sender.hasPermission("tokaprotect.admin")) for (String key : List.of("status", "show", "set", "layers", "enforce")) send(sender, "help." + key);
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
        send(sender, "status", plugin.text(sender, plugin.active() ? "word.on" : "word.standby"), settings,
                plugin.text(sender, plugin.places().hasRegions() ? "word.yes" : "word.no"), plugin.text(sender, plugin.places().hasClaims() ? "word.yes" : "word.no"));
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
