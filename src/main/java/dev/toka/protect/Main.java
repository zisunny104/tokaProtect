package dev.toka.protect;

import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.command.PluginCommand;
import cn.nukkit.event.HandlerList;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.plugin.PluginEnableEvent;
import cn.nukkit.plugin.PluginBase;
import dev.toka.i18n.Messages;
import dev.toka.protect.command.ProtectCommand;
import dev.toka.protect.form.ProtectWindow;
import dev.toka.protect.flag.FlagTable;
import dev.toka.protect.flag.Rules;
import dev.toka.protect.link.Places;
import dev.toka.protect.audit.AuditRecord;
import dev.toka.protect.audit.AuditStore;
import dev.toka.protect.audit.AuditView;
import dev.toka.protect.listener.AuditListener;
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
    private LogWriter<LogRecord> logWriter;
    private LogWriter<AuditRecord> auditWriter;
    private AuditView auditView;
    private Inspector inspector;
    private dev.toka.protect.listener.FlightListener flight;
    private dev.toka.protect.rollback.Rollback rollback;
    private ProtectWindow windows;
    /** Listeners made by wire(), so a reload can take them down. */
    private final List<Listener> listeners = new java.util.ArrayList<>();
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
            places = new Places(getServer());
            getServer().getPluginManager().registerEvents(this, this);
            wire();
            windows = new ProtectWindow(this);
            getServer().getPluginManager().registerEvents(windows, this);
            command = new ProtectCommand(this);
            var commands = getServer().getCommandMap();
            var existing = commands.getCommand("protect");
            if (existing != null && existing != command) {
                warn("log.commandTaken", ownerOf(existing));
                command = null;
            } else if (!commands.register("tokaProtect", command) || commands.getCommand("protect") != command) {
                error("log.commandFailed");
                command = null;
            }
        } catch (Exception | LinkageError failure) {
            error("log.loadFailed", failure);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    /** Builds everything that depends on config.yml and the stored flags: rules, breaker, logs and listeners. Undone by {@link #unwire()}. */
    private void wire() throws IOException {
        var config = getConfig();
        Path pluginDirectory = getDataFolder().toPath().toAbsolutePath().normalize();
        mode = Mode.parse(config.getString("mode", "full"));
        // Minimal mode never touches another plugin: it has no places to look in, so only world settings and defaults count.
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
        listen(new dev.toka.protect.listener.PlayerListener(config.getBoolean("player.keep-inventory", true), config.getBoolean("player.keep-experience", true), config.getBoolean("player.check-movement", false), config.getBoolean("player.auto-pickup", true)));
        if (mode.enforces()) listen(new WorldListener(this, rules, breaker, enabled));
        if (mode.enforces() && enabled.contains(Feature.FLIGHT))
            listen(flight = new dev.toka.protect.listener.FlightListener(this, rules, breaker, config.getBoolean("flight.kick-illegal", false)));
        if (mode.enforces() && enabled.contains(Feature.RECORD)) startLog(config, pluginDirectory);
        if (mode.enforces() && enabled.contains(Feature.AUDIT)) startAudit(config, pluginDirectory);
    }

    private void listen(Listener listener) {
        listeners.add(listener);
        getServer().getPluginManager().registerEvents(listener, this);
    }

    /** Stops what {@link #wire()} started: listeners, writers, query threads and the log databases. */
    private void unwire() {
        listeners.forEach(HandlerList::unregisterAll);
        listeners.clear();
        if (flight != null) flight.release();
        flight = null;
        if (auditWriter != null) auditWriter.close();
        auditWriter = null;
        if (auditView != null) {
            auditView.close();
            try { auditView.store().close(); } catch (Exception failure) { error("log.auditCloseFailed", failure); }
        }
        auditView = null;
        if (logWriter != null) logWriter.close();
        logWriter = null;
        if (rollback != null) rollback.close();
        rollback = null;
        if (logView != null) {
            logView.close();
            try { logView.store().close(); } catch (Exception failure) { error("log.recordCloseFailed", failure); }
        }
        logView = null;
        inspector = null;
    }

    /** /protect reload: the config, the texts and everything built from them, then the flags are read from storage again. */
    public void reloadSettings() throws IOException {
        unwire();
        reloadConfig();
        languages.clear();
        wire();
        if (command != null) command.refreshNames();
        getServer().getOnlinePlayers().values().forEach(Player::sendCommandData);
    }

    /** The plugin that owns a command, for the log when a name is taken. */
    private static String ownerOf(Command taken) {
        return taken instanceof PluginCommand<?> owned ? owned.getPlugin().getName() : taken.getClass().getName();
    }

    public void info(String key, Object... args) { getLogger().info(text(getServer().getConsoleSender(), key, args)); }
    public void warn(String key, Object... args) { getLogger().warning(text(getServer().getConsoleSender(), key, args)); }
    public void error(String key, Object... args) { getLogger().error(text(getServer().getConsoleSender(), key, args)); }

    /** An error line with its cause attached. */
    public void error(String key, Throwable cause) { getLogger().error(text(getServer().getConsoleSender(), key), cause); }

    /** Opens the log database and starts writing. A failure here only turns the log off; the other features go on. */
    private void startLog(cn.nukkit.utils.Config config, Path pluginDirectory) {
        try {
            Path file = pluginDirectory.resolve(config.getString("log.file", "log/blocks.db")).normalize();
            if (!file.startsWith(pluginDirectory)) throw new IllegalArgumentException("log.file must remain inside the plugin data folder");
            LogStore logStore = new LogStore(file);
            long retention = config.getInt("log.retention-days", 90) * 86_400_000L;
            long maxRows = config.getLong("log.max-rows", 3_000_000L);
            logWriter = new LogWriter<>("log", new LogWriter.Sink<LogRecord>() {
                public void write(List<LogRecord> batch) throws Exception { logStore.insert(batch); }
                public void maintain() throws Exception {
                    long removed = logStore.purge(System.currentTimeMillis() - retention, maxRows);
                    if (removed > 0) info("log.purged", removed);
                }
            }, config.getInt("log.queue-size", 20_000), config.getInt("log.batch-size", 500), config.getInt("log.flush-seconds", 2) * 1000L,
                    config.getInt("log.maintenance-minutes", 30) * 60_000L, failure -> {
                if (breaker.failed(Feature.RECORD, failure)) error("log.recordTripped", failure);
                else warn("log.recordWriteFailed", failure);
            });
            logView = new LogView(this, logStore, config.getInt("log.query-rows", 300), config.getInt("log.collapse-seconds", 60) * 1000L);
            rollback = new dev.toka.protect.rollback.Rollback(this, logStore, config.getInt("rollback.max-rows", 200_000), config.getInt("rollback.max-blocks", 100_000),
                    config.getInt("rollback.blocks-per-tick", 200), config.getInt("rollback.confirm-seconds", 60) * 1000L);
            inspector = new Inspector(logView, null);
            listen(new LogListener(this, rules, logWriter, breaker, config.getInt("log.explosion-block-limit", 2000),
                    new dev.toka.protect.log.TileNbt(new java.util.HashSet<>(config.getStringList("log.tile-data")), config.getInt("log.tile-data-max-bytes", 2048))));
            listen(inspector);
            info("log.recordReady", config.getInt("log.retention-days", 90), dev.toka.protect.log.Num.group(maxRows));
        } catch (Exception | LinkageError failure) {
            error("log.recordStartFailed", failure);
            EnumSet<Feature> without = EnumSet.noneOf(Feature.class);
            without.addAll(enabled);
            without.remove(Feature.RECORD);
            enabled = Collections.unmodifiableSet(without);
        }
    }

    /** Opens the command audit and starts listening. A failure here only turns the audit off. */
    private void startAudit(cn.nukkit.utils.Config config, Path pluginDirectory) {
        try {
            Path file = pluginDirectory.resolve(config.getString("audit.file", "log/commands.db")).normalize();
            if (!file.startsWith(pluginDirectory)) throw new IllegalArgumentException("audit.file must remain inside the plugin data folder");
            AuditStore store = new AuditStore(file);
            long retention = config.getInt("audit.retention-days", 30) * 86_400_000L;
            long maxRows = config.getLong("audit.max-rows", 500_000L);
            auditWriter = new LogWriter<>("audit", new LogWriter.Sink<AuditRecord>() {
                public void write(List<AuditRecord> batch) throws Exception { store.insert(batch); }
                public void maintain() throws Exception { store.purge(System.currentTimeMillis() - retention, maxRows); }
            }, config.getInt("audit.queue-size", 5_000), 200, 2_000L, config.getInt("audit.maintenance-minutes", 30) * 60_000L, failure -> {
                if (breaker.failed(Feature.AUDIT, failure)) error("log.auditTripped", failure);
                else warn("log.auditWriteFailed", failure);
            });
            auditView = new AuditView(this, store, config.getInt("audit.query-rows", 300));
            java.util.Set<String> hidden = new java.util.HashSet<>();
            for (String name : config.getStringList("audit.hidden-commands")) hidden.add(name.toLowerCase(Locale.ROOT).replaceFirst("^/", ""));
            listen(new AuditListener(this, auditWriter, breaker, hidden, config.getInt("audit.max-text", 256),
                    new dev.toka.protect.link.DiscordSender(getServer()), config.getString("audit.discord-channel", "cmd"), config.getBoolean("audit.discord", false)));
            info("log.auditReady", config.getInt("audit.retention-days", 30), dev.toka.protect.log.Num.group(maxRows));
        } catch (Exception | LinkageError failure) {
            error("log.auditStartFailed", failure);
            EnumSet<Feature> without = EnumSet.noneOf(Feature.class);
            without.addAll(enabled);
            without.remove(Feature.AUDIT);
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
        unwire();
        if (store != null) try { store.close(); } catch (IOException failure) { error("log.storeCloseFailed", failure); }
    }

    /**
     * Whether tokaProtect is deciding pvp right now: it is running with the pvp feature on and not tripped. Other plugins that
     * can also settle pvp (tokaLand) step aside while this is true and resume when it is not.
     */
    public static boolean handlesPvp() {
        Main main = instance;
        return main != null && main.enabled.contains(Feature.PVP) && main.breaker.working(Feature.PVP);
    }

    /** Administrators of the rules: the permission, an operator, or the identity administrator. */
    public boolean isAdmin(CommandSender sender) {
        return sender.hasPermission("tokaprotect.admin") || sender.isOp() || sender.hasPermission("toka.identity.admin");
    }

    public ProtectCommand command() { return command; }
    public ProtectWindow windows() { return windows; }
    public LogView logView() { return logView; }
    public dev.toka.protect.rollback.Rollback rollback() { return rollback; }
    public Inspector inspector() { return inspector; }
    public LogWriter<LogRecord> logWriter() { return logWriter; }
    public AuditView auditView() { return auditView; }
    public Mode mode() { return mode; }
    public Breaker breaker() { return breaker; }
    /** The features switched on by the mode and the configuration; one that has tripped is still listed here. */
    public Set<Feature> enabled() { return enabled; }
    public Rules rules() { return rules; }
    public Places places() { return places; }

    /** Saves a changed table (read back before it counts) and switches the live rules to it. */
    public void save(FlagTable next) throws IOException { rules.table(repository.save(next)); }

    public String text(CommandSender sender, String key, Object... args) {
        String tag = sender instanceof Player player && player.getLocale() != null ? player.getLocale().toLanguageTag() : getConfig().getString("language", "zh-TW");
        String selected = tag.startsWith("en") ? "en-US" : "zh-TW";
        return languages.computeIfAbsent(selected, t -> new Messages(getClass().getClassLoader(), getDataFolder().toPath(), t, line -> getLogger().warning(line))).text(key, args);
    }
}
