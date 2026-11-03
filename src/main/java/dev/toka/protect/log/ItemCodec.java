package dev.toka.protect.log;

import cn.nukkit.item.Item;
import cn.nukkit.nbt.NBTIO;
import java.io.IOException;
import java.nio.ByteOrder;

/** Item stacks as bytes for the item log. An empty slot is null, so nothing is stored for it. */
public final class ItemCodec {
    private ItemCodec() {}

    /** The stack as saved tag bytes, null for an empty slot; a stack bigger than the limit throws, and is then not logged. */
    public static byte[] encode(Item item, int maxBytes) throws IOException {
        if (item == null || item.getId() == 0 || item.getCount() <= 0) return null;
        byte[] bytes = NBTIO.write(NBTIO.putItemHelper(item), ByteOrder.LITTLE_ENDIAN);
        if (bytes.length > maxBytes) throw new IOException("item tag too large: " + bytes.length);
        return bytes;
    }

    /** The stack back from bytes; air for null. */
    public static Item decode(byte[] bytes) throws IOException {
        return bytes == null ? Item.get(0) : NBTIO.getItemHelper(NBTIO.read(bytes, ByteOrder.LITTLE_ENDIAN));
    }

    /** Whether the stack in a slot is exactly this one (same item, data, tags and count); null means empty. */
    public static boolean same(byte[] expected, Item actual) {
        boolean empty = actual == null || actual.getId() == 0 || actual.getCount() <= 0;
        if (expected == null) return empty;
        if (empty) return false;
        try { return decode(expected).equalsExact(actual); }
        catch (IOException | RuntimeException unreadable) { return false; }
    }
}
