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
import dev.toka.protect.listener.Inspector;
import dev.toka.protect.listener.LogListener;
import dev.toka.protect.listener.WorldListener;
import dev.toka.protect.log.LogRecord;
import dev.toka.protect.log.LogStore;
import dev.toka.protect.log.LogView;
import dev.toka.protect.log.LogWriter;
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
    private LogView logView;
    private LogWriter logWriter;
    private Inspector inspector;
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
            if (mode.enforces() && enabled.contains(Feature.RECORD)) startLog(config, pluginDirectory);
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

    /** Opens the log database and starts writing. A failure here only turns the log off; the other features go on. */
    private void startLog(cn.nukkit.utils.Config config, Path pluginDirectory) {
        try {
            Path file = pluginDirectory.resolve(config.getString("log.file", "log/blocks.db")).normalize();
            if (!file.startsWith(pluginDirectory)) throw new IllegalArgumentException("log.file must remain inside the plugin data folder");
            LogStore logStore = new LogStore(file);
            long retention = config.getInt("log.retention-days", 90) * 86_400_000L;
            long maxRows = config.getLong("log.max-rows", 3_000_000L);
            logWriter = new LogWriter(new LogWriter.Sink() {
                public void write(List<LogRecord> batch) throws Exception { logStore.insert(batch); }
                public void maintain() throws Exception {
                    long removed = logStore.purge(System.currentTimeMillis() - retention, maxRows);
                    if (removed > 0) getLogger().info("方塊紀錄已清除 " + removed + " 筆過期或超量的舊紀錄。");
                }
            }, config.getInt("log.queue-size", 20_000), config.getInt("log.batch-size", 500), config.getInt("log.flush-seconds", 2) * 1000L,
                    config.getInt("log.maintenance-minutes", 30) * 60_000L, error -> {
                if (breaker.failed(Feature.RECORD, error)) getLogger().error("方塊紀錄寫入連續失敗，已自動停用；其他保護功能不受影響。", error);
                else getLogger().warning("方塊紀錄寫入失敗，這一批已丟棄：" + error);
            });
            logView = new LogView(this, logStore, config.getInt("log.query-rows", 300), config.getInt("log.collapse-seconds", 60) * 1000L);
            inspector = new Inspector(logView, null);
            getServer().getPluginManager().registerEvents(new LogListener(this, rules, logWriter, breaker, config.getInt("log.explosion-block-limit", 2000)), this);
            getServer().getPluginManager().registerEvents(inspector, this);
            getLogger().info("方塊紀錄啟用：保留 " + config.getInt("log.retention-days", 90) + " 天、最多 " + maxRows + " 筆。");
        } catch (Exception failure) {
            getLogger().error("方塊紀錄無法啟動，已關閉；其他保護功能照常。", failure);
            EnumSet<Feature> without = EnumSet.noneOf(Feature.class);
            without.addAll(enabled);
            without.remove(Feature.RECORD);
            enabled = Collections.unmodifiableSet(without);
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
        if (logWriter != null) logWriter.close();
        if (logView != null) {
            logView.close();
            try { logView.store().close(); } catch (Exception error) { getLogger().error("關閉方塊紀錄資料庫失敗。", error); }
        }
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

    public LogView logView() { return logView; }
    public Inspector inspector() { return inspector; }
    public LogWriter logWriter() { return logWriter; }
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
