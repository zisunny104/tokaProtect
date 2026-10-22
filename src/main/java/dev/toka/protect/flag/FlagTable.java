package dev.toka.protect.flag;

import java.util.*;

/**
 * Every setting that is not "inherit", by layer and flag. Immutable: a change builds a new table, so a reader never sees
 * a half-applied edit. It also remembers which flags are set below the world level, which lets the hot paths skip the
 * region and claim lookups for every flag nobody has overridden.
 */
public final class FlagTable {
    private final Map<Layer, Map<Flag, Setting>> settings;
    private final Set<Flag> setBelowWorld;
    private final Map<String, Map<Flag, Boolean>> worldValues;

    public FlagTable(Map<Layer, Map<Flag, Setting>> settings) {
        Map<Layer, Map<Flag, Setting>> copy = new HashMap<>();
        EnumSet<Flag> below = EnumSet.noneOf(Flag.class);
        settings.forEach((layer, flags) -> {
            Map<Flag, Setting> kept = new EnumMap<>(Flag.class);
            flags.forEach((flag, setting) -> { if (setting.value() != Setting.Value.INHERIT) kept.put(flag, setting); });
            if (kept.isEmpty()) return;
            copy.put(layer, Map.copyOf(kept));
            if (layer.kind() != Layer.Kind.WORLD) below.addAll(kept.keySet());
        });
        this.settings = Map.copyOf(copy);
        Map<String, Map<Flag, Boolean>> worlds = new HashMap<>();
        copy.forEach((layer, flags) -> {
            if (layer.kind() != Layer.Kind.WORLD) return;
            Map<Flag, Boolean> values = new EnumMap<>(Flag.class);
            flags.forEach((flag, setting) -> values.put(flag, setting.value() == Setting.Value.ALLOW));
            worlds.put(layer.id(), Collections.unmodifiableMap(values));
        });
        this.worldValues = Map.copyOf(worlds);
        this.setBelowWorld = Collections.unmodifiableSet(below);
    }

    public static FlagTable empty() { return new FlagTable(Map.of()); }

    public Optional<Setting> get(Layer layer, Flag flag) {
        return Optional.ofNullable(settings.getOrDefault(layer, Map.of()).get(flag));
    }

    /** The world's own setting for the flag as a plain boolean, or null when the world sets none. Allocates nothing. */
    public Boolean worldValue(String world, Flag flag) {
        Map<Flag, Boolean> values = worldValues.get(world);
        return values == null ? null : values.get(flag);
    }

    /** True when some region or claim sets this flag, so resolving it needs the position's region and claims. */
    public boolean setBelowWorld(Flag flag) { return setBelowWorld.contains(flag); }

    /** A new table with one setting changed; inherit removes it. */
    public FlagTable with(Layer layer, Flag flag, Setting setting) {
        Map<Layer, Map<Flag, Setting>> next = new HashMap<>();
        settings.forEach((l, flags) -> next.put(l, new EnumMap<>(flags)));
        Map<Flag, Setting> flags = next.computeIfAbsent(layer, l -> new EnumMap<>(Flag.class));
        if (setting.value() == Setting.Value.INHERIT) flags.remove(flag);
        else flags.put(flag, setting);
        return new FlagTable(next);
    }

    /** All settings, for saving and for listing. */
    public Map<Layer, Map<Flag, Setting>> all() { return settings; }
}
