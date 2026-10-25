package dev.toka.protect.form;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerFormRespondedEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.element.ElementButtonImageData;
import cn.nukkit.form.element.ElementDivider;
import cn.nukkit.form.element.ElementDropdown;
import cn.nukkit.form.element.ElementHeader;
import cn.nukkit.form.element.ElementInput;
import cn.nukkit.form.element.ElementSlider;
import cn.nukkit.form.element.ElementToggle;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseModal;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowCustom;
import cn.nukkit.form.window.FormWindowModal;
import cn.nukkit.form.window.FormWindowSimple;
import dev.toka.protect.Main;
import dev.toka.protect.command.HelpPages;
import dev.toka.protect.command.ProtectCommand;
import dev.toka.protect.flag.Flag;
import dev.toka.protect.flag.Layer;
import dev.toka.protect.flag.Setting;
import dev.toka.protect.log.LogFormat;
import dev.toka.protect.log.LogStore;
import dev.toka.protect.log.Num;
import dev.toka.protect.log.TimeSpan;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * The windows of /protect for administrators: flags, the block log, rollback and help. Every change is made by running the
 * command, so the windows and the command cannot disagree.
 */
public final class ProtectWindow implements Listener {
    private static final int PER_PAGE = 10;
    private static final String[] LAYERS = {"world", "region", "claim"};
    private static final String[] VALUES = {"allow", "deny", "inherit"};

    private record Entry(Player player, Consumer<PlayerFormRespondedEvent> handler) {}

    private final Main plugin;
    private final Map<FormWindow, Entry> entries = Collections.synchronizedMap(new WeakHashMap<>());

    public ProtectWindow(Main plugin) { this.plugin = plugin; }

    private String t(Player player, String key, Object... args) { return plugin.text(player, key, args); }

    private void show(Player player, FormWindow window, Consumer<PlayerFormRespondedEvent> handler) {
        entries.put(window, new Entry(player, handler));
        player.showFormWindow(window);
    }

    @EventHandler public void onResponse(PlayerFormRespondedEvent event) {
        Entry entry = entries.remove(event.getWindow());
        if (entry != null && entry.player() == event.getPlayer()) entry.handler().accept(event);
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        synchronized (entries) { entries.values().removeIf(e -> e.player() == player); }
    }

    private static ElementButton button(String text, String icon) {
        return icon == null ? new ElementButton(text) : new ElementButton(text, new ElementButtonImageData("path", "textures/ui/" + icon));
    }

    private void menu(Player player, String title, String content, List<ElementButton> buttons, Consumer<Integer> pressed) {
        show(player, new FormWindowSimple(title, content, buttons), event -> {
            if (event.wasClosed() || !(event.getResponse() instanceof FormResponseSimple response)) return;
            pressed.accept(response.getClickedButtonId());
        });
    }

    private boolean admin(Player player) {
        if (plugin.isAdmin(player)) return true;
        player.sendMessage(t(player, "cmd.denied"));
        return false;
    }

    private ProtectCommand command() { return plugin.command(); }

    private void run(Player player, String... args) {
        if (command() != null) command().execute(player, "protect", args);
    }

    private void fail(Player player, Exception failure) { player.sendMessage(t(player, "cmd.failed", command().describe(player, failure))); }

    /** The first window. */
    public void open(Player player) {
        if (!admin(player)) return;
        List<ElementButton> buttons = new ArrayList<>(List.of(button(t(player, "win.flags"), "icon_setting"), button(t(player, "win.log"), "magnifyingGlass"),
                button(t(player, "win.rollback"), "refresh_light"), button(t(player, "win.help"), "icon_book_writable"), button(t(player, "win.close"), null)));
        menu(player, t(player, "win.title"), "", buttons, index -> {
            switch (index) {
                case 0 -> flagTarget(player);
                case 1 -> queryForm(player, false);
                case 2 -> queryForm(player, true);
                case 3 -> help(player);
                default -> { }
            }
        });
    }

    // ---- flags ----

    private void flagTarget(Player player) {
        if (!admin(player)) return;
        List<String> worlds = plugin.getServer().getLevels().values().stream().map(l -> l.getFolderName()).sorted().toList();
        FormWindowCustom window = new FormWindowCustom(t(player, "win.flags"));
        window.addElement(new ElementHeader(t(player, "win.flags")));
        window.addElement(new ElementDropdown(t(player, "win.layerLabel"), List.of(t(player, "win.layerWorld"), t(player, "win.layerRegion"), t(player, "win.layerClaim")), 0));
        window.addElement(new ElementDropdown(t(player, "win.world"), worlds.isEmpty() ? List.of("-") : worlds, Math.max(0, worlds.indexOf(player.getLevel().getFolderName()))));
        window.addElement(new ElementInput(t(player, "win.target"), t(player, "win.targetHint")));
        show(player, window, event -> {
            if (event.wasClosed() || !(event.getResponse() instanceof FormResponseCustom response)) { open(player); return; }
            String kind = LAYERS[response.getDropdownResponse(1).getElementID()];
            String target = kind.equals("world") ? response.getDropdownResponse(2).getElementContent() : String.valueOf(response.getInputResponse(3)).strip();
            try {
                flagValues(player, kind, target, command().layer(kind, target));
            } catch (IllegalArgumentException failure) {
                fail(player, failure);
            }
        });
    }

    private void flagValues(Player player, String kind, String target, Layer layer) {
        if (!admin(player)) return;
        FormWindowCustom window = new FormWindowCustom(t(player, "win.flagsOf", t(player, "layer." + kind, target)));
        window.addElement(new ElementHeader(t(player, "win.flagsHeader")));
        List<Setting> current = new ArrayList<>();
        int[] at = new int[Flag.values().length];
        int index = 1;
        for (Flag flag : Flag.values()) {
            Setting setting = plugin.rules().table().get(layer, flag).orElse(new Setting(Setting.Value.INHERIT, false));
            current.add(setting);
            ElementDropdown dropdown = new ElementDropdown(flag.id(), List.of(t(player, "win.value.allow"), t(player, "win.value.deny"), t(player, "win.value.inherit")),
                    switch (setting.value()) { case ALLOW -> 0; case DENY -> 1; case INHERIT -> 2; });
            dropdown.setTooltip(t(player, "flag." + flag.id()));
            window.addElement(dropdown);
            at[flag.ordinal()] = index++;
        }
        window.addElement(new ElementDivider());
        int enforceAt = index;
        window.addElement(new ElementToggle(t(player, "win.enforce"), false));
        show(player, window, event -> {
            if (event.wasClosed() || !(event.getResponse() instanceof FormResponseCustom response)) { open(player); return; }
            boolean enforce = response.getToggleResponse(enforceAt);
            boolean changed = false;
            for (Flag flag : Flag.values()) {
                int chosen = response.getDropdownResponse(at[flag.ordinal()]).getElementID();
                Setting before = current.get(flag.ordinal());
                int old = switch (before.value()) { case ALLOW -> 0; case DENY -> 1; case INHERIT -> 2; };
                boolean needsEnforce = enforce && chosen != 2 && !before.enforced();
                if (chosen == old && !needsEnforce) continue;
                if (enforce && chosen != 2) run(player, "set", kind, target, flag.id(), VALUES[chosen], "enforce");
                else run(player, "set", kind, target, flag.id(), VALUES[chosen]);
                changed = true;
            }
            if (!changed) player.sendMessage(t(player, "win.noChange"));
            open(player);
        });
    }

    // ---- log and rollback ----

    private void queryForm(Player player, boolean rollback) {
        if (!admin(player)) return;
        if (plugin.logView() == null) { player.sendMessage(t(player, "cmd.failed", t(player, "err.LOG_OFF"))); return; }
        FormWindowCustom window = new FormWindowCustom(t(player, rollback ? "win.rollback" : "win.log"));
        window.addElement(new ElementHeader(t(player, "win.queryHeader")));
        window.addElement(new ElementSlider(t(player, "win.radius"), 0, 64, 1, 5));
        window.addElement(new ElementInput(t(player, "win.time"), "30m, 2h, 7d", "1d"));
        window.addElement(new ElementInput(t(player, "win.player"), t(player, "win.playerHint")));
        show(player, window, event -> {
            if (event.wasClosed() || !(event.getResponse() instanceof FormResponseCustom response)) { open(player); return; }
            try {
                int radius = Math.round(response.getSliderResponse(1));
                long since = System.currentTimeMillis() - TimeSpan.parseMillis(String.valueOf(response.getInputResponse(2)).isBlank() ? "1d" : response.getInputResponse(2));
                String who = String.valueOf(response.getInputResponse(3)).strip();
                String world = player.getLevel().getFolderName();
                LogStore.Query query = who.isEmpty() ? LogStore.Query.at(world, player.getFloorX(), player.getFloorY(), player.getFloorZ(), radius, since, 0, 0)
                        : LogStore.Query.byPlayer(plugin.logView().player(who), since, 0, 0);
                String title = who.isEmpty() ? world + " ±" + radius : who;
                if (rollback) previewRollback(player, query, title); else results(player, query, title);
            } catch (IllegalArgumentException failure) {
                fail(player, failure);
            }
        });
    }

    private void results(Player player, LogStore.Query query, String title) {
        player.sendMessage(t(player, "log.searching"));
        plugin.logView().lines(query, lines -> {
            if (!player.isOnline()) return;
            if (lines == null) { player.sendMessage(t(player, "cmd.failed", t(player, "win.queryFailed"))); return; }
            page(player, query, title, lines, "", 1);
        });
    }

    private void page(Player player, LogStore.Query query, String title, List<LogFormat.Line> all, String needle, int requested) {
        var view = plugin.logView();
        if (view == null) return;
        String lower = needle.toLowerCase(Locale.ROOT);
        List<String> texts = new ArrayList<>();
        for (LogFormat.Line line : all) {
            String text = view.format(player, line);
            if (lower.isEmpty() || cn.nukkit.utils.TextFormat.clean(text).toLowerCase(Locale.ROOT).contains(lower)) texts.add(text);
        }
        int pages = Math.max(1, (texts.size() + PER_PAGE - 1) / PER_PAGE);
        int current = Math.min(Math.max(requested, 1), pages);
        String content = all.isEmpty() ? t(player, "log.none", title) : texts.isEmpty() ? t(player, "win.noMatch", needle)
                : String.join("\n", texts.subList((current - 1) * PER_PAGE, Math.min(texts.size(), current * PER_PAGE)));
        List<ElementButton> buttons = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        buttons.add(button(t(player, "win.back"), "arrow_left")); actions.add(() -> open(player));
        buttons.add(button(t(player, "win.search"), "magnifyingGlass")); actions.add(() -> search(player, query, title, all, needle));
        buttons.add(button(t(player, "win.rollbackThis"), "refresh_light")); actions.add(() -> previewRollback(player, query, title));
        if (current > 1) { buttons.add(button(t(player, "win.prev"), null)); actions.add(() -> page(player, query, title, all, needle, current - 1)); }
        if (current < pages) { buttons.add(button(t(player, "win.next"), null)); actions.add(() -> page(player, query, title, all, needle, current + 1)); }
        String heading = t(player, "win.resultTitle", title, current, pages, Num.group(texts.size()));
        menu(player, heading, content, buttons, index -> actions.get(index).run());
    }

    private void search(Player player, LogStore.Query query, String title, List<LogFormat.Line> all, String needle) {
        FormWindowCustom window = new FormWindowCustom(t(player, "win.searchTitle"));
        window.addElement(new ElementHeader(t(player, "win.searchTitle")));
        window.addElement(new ElementInput(t(player, "win.query"), t(player, "win.queryHint"), needle));
        show(player, window, event -> {
            if (event.wasClosed() || !(event.getResponse() instanceof FormResponseCustom response)) { page(player, query, title, all, needle, 1); return; }
            String text = response.getInputResponse(1) == null ? "" : response.getInputResponse(1).strip();
            page(player, query, title, all, text.length() > 64 ? text.substring(0, 64) : text, 1);
        });
    }

    private void previewRollback(Player player, LogStore.Query query, String title) {
        if (!admin(player)) return;
        var rollback = plugin.rollback();
        if (rollback == null) { player.sendMessage(t(player, "cmd.failed", t(player, "err.LOG_OFF"))); return; }
        try {
            rollback.preview(player, query, title, count -> confirmRollback(player, title, count));
        } catch (IllegalArgumentException failure) {
            fail(player, failure);
        }
    }

    private void confirmRollback(Player player, String title, int count) {
        if (!player.isOnline() || !admin(player)) return;
        show(player, new FormWindowModal(t(player, "win.rollbackTitle", title), t(player, "win.rollbackBody", Num.group(count)), t(player, "win.rollbackYes"), t(player, "win.cancel")), event -> {
            if (!event.wasClosed() && event.getResponse() instanceof FormResponseModal response && response.getClickedButtonId() == 0) run(player, "rollback", "confirm");
            else run(player, "rollback", "cancel");
        });
    }

    // ---- help ----

    private void help(Player player) {
        List<HelpPages.Section> sections = ProtectCommand.SECTIONS;
        List<ElementButton> buttons = new ArrayList<>();
        buttons.add(button(t(player, "win.back"), "arrow_left"));
        sections.forEach(s -> buttons.add(button(t(player, "help.name." + s.id()), null)));
        menu(player, t(player, "win.help"), "", buttons, index -> {
            if (index == 0) { open(player); return; }
            HelpPages.Section section = sections.get(index - 1);
            List<String> lines = new ArrayList<>();
            section.keys().forEach(key -> lines.add(t(player, "help." + key)));
            menu(player, t(player, "help.name." + section.id()), String.join("\n", lines), List.of(button(t(player, "win.back"), "arrow_left")), back -> help(player));
        });
    }
}
