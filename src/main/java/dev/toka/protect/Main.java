package dev.toka.protect;

import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.plugin.PluginEnableEvent;
import cn.nukkit.plugin.PluginBase;
import dev.toka.i18n.Messages;
import dev.toka.protect.command.ProtectCommand;
import dev.toka.protect.flag.FlagTable;
import dev.toka.protect.flag.Rules;
import dev.toka.protect.link.Places;
import dev.toka.protect.listener.WorldListener;
import dev.toka.protect.storage.FlagRepository;
import dev.toka.storage.Backend;
import dev.toka.storage.DocumentStore;
import dev.toka.storage.StoreConfig;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Switchboard: opens storage, registers /protect and, only when switched on, the listeners that enforce the rules.
 * Until {@code active} is true nothing is intercepted, so it never competes with the rules it is meant to replace.
 */
public final class Main extends PluginBase implements Listener {
    private DocumentStore store;
    private FlagRepository repository;
    private Places places;
    private Rules rules;
    private ProtectCommand command;
    private boolean active;
    private final Map<String, Messages> languages = new HashMap<>();

    @Override public void onEnable() {
        saveDefaultConfig();
        try {
            var config = getConfig();
            Path pluginDirectory = getDataFolder().toPath().toAbsolutePath().normalize();
            Path directory = pluginDirectory.resolve(config.getString("storage.directory", "storage")).normalize();
            if (!directory.startsWith(pluginDirectory)) throw new IllegalArgumentException("storage.directory must remain inside the plugin data folder");
            Backend backend = Backend.valueOf(config.getString("storage.backend", "SQLITE").strip().toUpperCase(Locale.ROOT));
            store = DocumentStore.open(new StoreConfig(backend, directory, config.getInt("storage.schema-version", 1)));
            repository = new FlagRepository(store);
            places = new Places(getServer());
            rules = new Rules(places, repository.load());
            active = config.getBoolean("active", false);
            getServer().getPluginManager().registerEvents(this, this);
            if (active) {
                getServer().getPluginManager().registerEvents(new WorldListener(this, rules), this);
                getLogger().warning("tokaProtect 已啟用：請確認 zero 的舊保護規則已停用，否則兩邊會同時攔截。");
            } else {
                getLogger().info("tokaProtect 待命中：active 為 false，不攔截任何事件。");
            }
            command = new ProtectCommand(this);
            var commands = getServer().getCommandMap();
            var existing = commands.getCommand("protect");
            if (existing != null && existing != command) {
                getLogger().warning("/protect 已由其他插件註冊；請先停用該入口再重啟。此次不註冊替代指令。");
                command = null;
            } else if (!commands.register("tokaProtect", command) || commands.getCommand("protect") != command) {
                getLogger().error("無法註冊指令 /protect；請檢查指令衝突。");
                command = null;
            }
        } catch (Exception error) {
            getLogger().error("tokaProtect 未能載入；為避免以空資料覆蓋原資料，插件將停用。", error);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    /** Regions and claims come from other plugins, which may start after this one or be reloaded. */
    @EventHandler public void onPluginEnabled(PluginEnableEvent event) {
        String name = event.getPlugin().getName();
        if (places != null && (name.equals("tokaRegions") || name.equals("tokaLand"))) places.refresh();
    }

    @Override public void onDisable() {
        if (command != null) {
            var commands = getServer().getCommandMap();
            commands.getCommands().entrySet().removeIf(entry -> entry.getValue() == command);
            command.unregister(commands);
        }
        if (store != null) try { store.close(); } catch (IOException error) { getLogger().error("關閉保護儲存庫失敗。", error); }
    }

    public boolean active() { return active; }
    public Rules rules() { return rules; }
    public Places places() { return places; }

    /** Saves a changed table (read back before it counts) and switches the live rules to it. */
    public void save(FlagTable next) throws IOException { rules.table(repository.save(next)); }

    public String text(CommandSender sender, String key, Object... args) {
        String tag = sender instanceof Player player ? player.getLocale().toLanguageTag() : getConfig().getString("language", "zh-TW");
        String selected = tag.startsWith("en") ? "en-US" : "zh-TW";
        return languages.computeIfAbsent(selected, t -> new Messages(getClass().getClassLoader(), getDataFolder().toPath(), t, line -> getLogger().warning(line))).text(key, args);
    }
}
