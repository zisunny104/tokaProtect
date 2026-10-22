package dev.toka.protect.storage;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.toka.protect.flag.Flag;
import dev.toka.protect.flag.FlagTable;
import dev.toka.protect.flag.Layer;
import dev.toka.protect.flag.Setting;
import dev.toka.storage.DocumentStore;
import java.io.IOException;
import java.util.*;

/** The flag table as one document. A save is read back before it counts, and an unreadable file is an error, never a quiet reset. */
public final class FlagRepository {
    private static final String KEY = "flags";
    private final DocumentStore store;

    public FlagRepository(DocumentStore store) { this.store = Objects.requireNonNull(store); }

    public FlagTable load() throws IOException {
        Optional<JsonObject> document = store.load(KEY);
        if (document.isEmpty()) return FlagTable.empty();
        JsonObject root = document.get();
        if (!root.has("format") || root.get("format").getAsInt() != 1) throw new IOException("Unsupported flags format");
        Map<Layer, Map<Flag, Setting>> settings = new HashMap<>();
        try {
            for (var item : root.getAsJsonArray("settings")) {
                JsonObject row = item.getAsJsonObject();
                Layer layer = new Layer(Layer.Kind.valueOf(row.get("kind").getAsString()), row.get("id").getAsString());
                Flag flag = Flag.byId(row.get("flag").getAsString()).orElseThrow(() -> new IOException("Unknown flag: " + row.get("flag").getAsString()));
                Setting setting = new Setting(Setting.Value.valueOf(row.get("value").getAsString()), row.get("enforced").getAsBoolean());
                settings.computeIfAbsent(layer, l -> new EnumMap<>(Flag.class)).put(flag, setting);
            }
        } catch (RuntimeException bad) {
            throw new IOException("Unreadable flags", bad);
        }
        return new FlagTable(settings);
    }

    public synchronized FlagTable save(FlagTable table) throws IOException {
        JsonArray rows = new JsonArray();
        table.all().entrySet().stream().sorted(Comparator.comparing((Map.Entry<Layer, ?> e) -> e.getKey().kind()).thenComparing(e -> e.getKey().id())).forEach(layer ->
                layer.getValue().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                    JsonObject row = new JsonObject();
                    row.addProperty("kind", layer.getKey().kind().name());
                    row.addProperty("id", layer.getKey().id());
                    row.addProperty("flag", entry.getKey().id());
                    row.addProperty("value", entry.getValue().value().name());
                    row.addProperty("enforced", entry.getValue().enforced());
                    rows.add(row);
                }));
        JsonObject root = new JsonObject();
        root.addProperty("format", 1);
        root.add("settings", rows);
        store.save(KEY, root);
        FlagTable readBack = load();
        if (!readBack.all().equals(table.all())) throw new IOException("Flag readback mismatch");
        return readBack;
    }
}
