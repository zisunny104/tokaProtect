package dev.toka.protect.storage;

import com.google.gson.JsonObject;
import dev.toka.protect.flag.*;
import dev.toka.storage.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.IOException;
import static org.junit.Assert.*;

public class FlagRepositoryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void settingsSurviveAnyBackend() throws Exception {
        for (Backend backend : Backend.values()) {
            var path = temporary.newFolder(backend.name()).toPath();
            var table = FlagTable.empty()
                    .with(Layer.world("survival"), Flag.EXPLOSIONS, new Setting(Setting.Value.ALLOW, false))
                    .with(Layer.region("11111111-1111-1111-1111-111111111111"), Flag.PVP, new Setting(Setting.Value.DENY, true));
            try (var store = DocumentStore.open(new StoreConfig(backend, path, 1))) {
                assertTrue(new FlagRepository(store).load().all().isEmpty());
                new FlagRepository(store).save(table);
            }
            try (var store = DocumentStore.open(new StoreConfig(backend, path, 1))) {
                assertEquals(table.all(), new FlagRepository(store).load().all());
            }
        }
    }

    @Test public void anUnknownFlagIsAnErrorNotASilentLoss() throws Exception {
        try (var store = DocumentStore.open(new StoreConfig(Backend.JSON, temporary.newFolder().toPath(), 1))) {
            var row = new JsonObject();
            row.addProperty("kind", "WORLD");
            row.addProperty("id", "w");
            row.addProperty("flag", "no-such-flag");
            row.addProperty("value", "DENY");
            row.addProperty("enforced", false);
            var rows = new com.google.gson.JsonArray();
            rows.add(row);
            var root = new JsonObject();
            root.addProperty("format", 1);
            root.add("settings", rows);
            store.save("flags", root);
            assertThrows(IOException.class, () -> new FlagRepository(store).load());
        }
    }

    @Test public void aFutureFormatIsRefused() throws Exception {
        try (var store = DocumentStore.open(new StoreConfig(Backend.JSON, temporary.newFolder().toPath(), 1))) {
            var root = new JsonObject();
            root.addProperty("format", 2);
            store.save("flags", root);
            assertThrows(IOException.class, () -> new FlagRepository(store).load());
        }
    }
}
