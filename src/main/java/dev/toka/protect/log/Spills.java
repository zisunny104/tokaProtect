package dev.toka.protect.log;

import cn.nukkit.item.Item;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Containers that were just broken and the stacks that fell out of them, kept for a couple of minutes so a pickup nearby can be
 * tied to the container it came from. Used on the server thread only; it holds few entries and an empty tracker answers at once,
 * so ordinary pickups cost nothing.
 */
public final class Spills {
    public record Spill(String world, int x, int y, int z, long time, List<Item> left) {}
    public record Match(Spill spill, int count) {}

    private static final int MAX = 64;
    private static final int RANGE = 8;
    private final long windowMillis;
    private final ArrayDeque<Spill> recent = new ArrayDeque<>();

    public Spills(long windowMillis) { this.windowMillis = windowMillis; }

    public void add(String world, int x, int y, int z, long now, List<Item> stacks) {
        if (stacks.isEmpty()) return;
        List<Item> copies = new ArrayList<>(stacks.size());
        for (Item stack : stacks) copies.add(stack.clone());
        recent.addLast(new Spill(world, x, y, z, now, copies));
        while (recent.size() > MAX) recent.removeFirst();
    }

    /** True when nothing is being tracked right now. */
    public boolean isEmpty(long now) {
        while (!recent.isEmpty() && now - recent.peekFirst().time() > windowMillis) recent.removeFirst();
        return recent.isEmpty();
    }

    /** The spill a picked-up stack belongs to and how many of it, or null. The matched amount is no longer open for later pickups. */
    public Match take(String world, double x, double y, double z, Item picked, long now) {
        if (isEmpty(now)) return null;
        for (Spill spill : recent) {
            if (!spill.world().equals(world) || Math.abs(spill.x() - x) > RANGE || Math.abs(spill.y() - y) > RANGE || Math.abs(spill.z() - z) > RANGE) continue;
            for (var it = spill.left().iterator(); it.hasNext(); ) {
                Item open = it.next();
                if (!open.equals(picked, true, true)) continue;
                int n = Math.min(open.getCount(), picked.getCount());
                if (n <= 0) continue;
                open.setCount(open.getCount() - n);
                if (open.getCount() <= 0) it.remove();
                return new Match(spill, n);
            }
        }
        return null;
    }
}
