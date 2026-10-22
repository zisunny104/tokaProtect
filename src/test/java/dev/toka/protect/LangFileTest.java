package dev.toka.protect;

import dev.toka.protect.flag.Flag;
import org.junit.Test;
import org.yaml.snakeyaml.Yaml;
import java.io.InputStream;
import java.util.Map;
import java.util.TreeSet;
import static org.junit.Assert.*;

public class LangFileTest {
    private static TreeSet<String> keys(String file) throws Exception {
        try (InputStream in = LangFileTest.class.getResourceAsStream("/lang/" + file)) {
            TreeSet<String> out = new TreeSet<>();
            flatten("", new Yaml().load(in), out);
            return out;
        }
    }

    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Object node, TreeSet<String> out) {
        if (node instanceof Map<?, ?> map) map.forEach((k, v) -> flatten(prefix.isEmpty() ? k.toString() : prefix + "." + k, v, out));
        else out.add(prefix);
    }

    @Test public void bothLanguagesHaveTheSameKeys() throws Exception {
        assertEquals(keys("zh-TW.yml"), keys("en-US.yml"));
    }

    @Test public void everyFlagHasADescription() throws Exception {
        var keys = keys("zh-TW.yml");
        for (Flag flag : Flag.values()) assertTrue(flag.id(), keys.contains("flag." + flag.id()));
    }

    @Test public void everyLoggedActionHasAWord() throws Exception {
        var keys = keys("zh-TW.yml");
        for (dev.toka.protect.log.Action action : dev.toka.protect.log.Action.values()) assertTrue(action.name(), keys.contains(action.langKey()));
    }
}
