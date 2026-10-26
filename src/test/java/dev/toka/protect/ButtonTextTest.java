package dev.toka.protect;

import org.junit.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

import static org.junit.Assert.*;

/** Button labels carry no colour codes; only a leading red code is allowed (close, cancel). */
public class ButtonTextTest {
    private static final String[] BUTTON_KEYS = {
            "win.flags", "win.log", "win.rollback", "win.help", "win.close", "win.cancel", "win.back", "win.prev", "win.next",
            "win.search", "win.rollbackThis", "win.rollbackYes", "help.name.read", "help.name.flags", "help.name.log", "help.name.rollback"};

    private static Object find(Object node, String path) {
        for (String part : path.split("[.]")) node = ((Map<?, ?>) node).get(part);
        return node;
    }

    @Test public void buttonLabelsHaveNoColourCodesExceptLeadingRed() throws Exception {
        for (String file : new String[]{"zh-TW.yml", "en-US.yml"}) {
            try (InputStream in = ButtonTextTest.class.getResourceAsStream("/lang/" + file)) {
                Object root = new Yaml().load(in);
                for (String key : BUTTON_KEYS) {
                    Object value = find(root, key);
                    assertNotNull(file + " " + key, value);
                    String text = value.toString();
                    String rest = text.startsWith("§c") ? text.substring(2) : text;
                    assertFalse(file + " " + key + " has a colour code: " + text, rest.contains("§"));
                }
            }
        }
    }
}
