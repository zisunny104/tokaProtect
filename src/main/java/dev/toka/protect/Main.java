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
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.Map;

/**
 * Switchboard: opens storage, registers /protect and, only when switched on, the listeners that enforce the rules.
 * Until {@code active} is true nothing is intercepted, so it never competes with the rules it is meant to replace.
 */
public final class Main extends PluginBase implements Listener {
    private static volatile Main instance;
    private DocumentStore store;
    private FlagRepository repository;
    private Places places;
    private Rules rules;
    private ProtectCommand command;
    private Mode mode = Mode.STANDBY;
    private Breaker breaker;
    private Set<Feature> enabled = Set.of();
    private final Map<String, Messages> languages = new HashMap<>();

    @Override public void onEnable() {
        instance = this;
        saveDefaultConfig();
        try {
            var config = getConfig();
            Path pluginDirectory = getDataFolder().toPath().toAbsolutePath().normalize();
            Path directory = pluginDirectory.resolve(config.getString("storage.directory", "storage")).normalize();
            if (!directory.startsWith(pluginDirectory)) throw new IllegalArgumentException("storage.directory must remain inside the plugin data folder");
            Backend backend = Backend.valueOf(config.getString("storage.backend", "SQLITE").strip().toUpperCase(Locale.ROOT));
            store = DocumentStore.open(new StoreConfig(backend, directory, config.getInt("storage.schema-version", 1)));
            repository = new FlagRepository(store);
            mode = Mode.parse(config.getString("mode", "standby"));
            // Minimal mode never touches another plugin: it has no places to look in, so only world settings and defaults count.
            places = new Places(getServer());
            Rules.Places lookup = mode.usesRegionsAndClaims() ? places : new Rules.Places() {
                public String regionAt(String world, int x, int y, int z) { return null; }
                public List<Rules.Claim> claimsAt(String world, int x, int y, int z) { return null; }
            };
            rules = new Rules(lookup, repository.load());
            breaker = new Breaker(config.getInt("breaker.failures", 5), config.getInt("breaker.window-seconds", 60) * 1000L, System::currentTimeMillis);
            EnumSet<Feature> chosen = EnumSet.noneOf(Feature.class);
            for (Feature feature : Feature.values())
                if (mode.allows(feature) && config.getBoolean("features." + feature.id(), true)) chosen.add(feature);
            enabled = Collections.unmodifiableSet(chosen);
            getServer().getPluginManager().registerEvents(this, this);
            if (mode.enforces()) {
                getServer().getPluginManager().registerEvents(new WorldListener(this, rules, breaker, enabled), this);
                getLogger().warning("tokaProtect 已啟用（" + mode + "）：" + enabled + "。請確認 zero 的舊保護規則已停用，否則兩邊會同時攔截。");
            } else {
                getLogger().info("tokaProtect 待命中：mode 為 standby，不攔截任何事件。");
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
        if (instance == this) instance = null;
        if (store != null) try { store.close(); } catch (IOException error) { getLogger().error("關閉保護儲存庫失敗。", error); }
    }

    /**
     * Whether tokaProtect is deciding pvp right now: it is running with the pvp feature on and not tripped. Other plugins that
     * can also settle pvp (tokaLand) step aside while this is true and resume when it is not.
     */
    public static boolean handlesPvp() {
        Main main = instance;
        return main != null && main.enabled.contains(Feature.PVP) && main.breaker.working(Feature.PVP);
    }

    public Mode mode() { return mode; }
    public Breaker breaker() { return breaker; }
    /** The features switched on by the mode and the configuration; one that has tripped is still listed here. */
    public Set<Feature> enabled() { return enabled; }
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
