package dev.toka.protect.log;

import cn.nukkit.block.Block;
import cn.nukkit.blockentity.BlockEntity;
import cn.nukkit.level.Level;
import cn.nukkit.math.Vector3;
import cn.nukkit.nbt.NBTIO;
import cn.nukkit.nbt.tag.CompoundTag;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.Set;

/**
 * Keeps the tile data of a few small kinds of block (signs, heads, banners; the kinds come from the configuration, by the
 * core's own tile id) so a rollback can put the text back. Containers are not kept: their contents are large and restoring
 * them next to the items that already dropped would duplicate items.
 */
public final class TileNbt {
    private final Set<String> kinds;
    private final int maxBytes;

    public TileNbt(Set<String> kinds, int maxBytes) {
        this.kinds = Set.copyOf(kinds);
        this.maxBytes = maxBytes;
    }

    /** The tile data at this block in a storable form, or null when it has none, is not a configured kind or is too big. */
    public byte[] capture(Block block) {
        if (kinds.isEmpty() || block.getLevel() == null) return null;
        try {
            BlockEntity tile = block.getLevel().getBlockEntity(new Vector3(block.getFloorX(), block.getFloorY(), block.getFloorZ()));
            if (tile == null || !kinds.contains(tile.getSaveId())) return null;
            tile.saveNBT();
            byte[] bytes = NBTIO.write(tile.namedTag, ByteOrder.LITTLE_ENDIAN);
            return bytes.length <= maxBytes ? bytes : null;
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** Puts saved tile data back at the spot, replacing whatever tile is there. Silent when the data is missing or unreadable. */
    public static void restore(Level level, int x, int y, int z, byte[] data) {
        if (data == null) return;
        try {
            CompoundTag tag = NBTIO.read(data, ByteOrder.LITTLE_ENDIAN);
            var existing = level.getBlockEntity(new Vector3(x, y, z));
            if (existing != null) existing.close();
            var chunk = level.getChunk(x >> 4, z >> 4, true);
            tag.putInt("x", x).putInt("y", y).putInt("z", z);
            BlockEntity.createBlockEntity(tag.getString("id"), chunk, tag);
        } catch (IOException | RuntimeException unreadable) {
            // The block itself is back; only its text is lost.
        }
    }
}
