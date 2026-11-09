package com.mdvcraft.mdvnpc.companion;

import java.util.OptionalInt;

/** Busca la altura más cercana en la que una entidad puede estar de pie. Sin Bukkit: testeable. */
public final class SafeSpot {
    /** {@code stand(x, y, z)}: true si los pies pueden estar en (x, y, z). */
    @FunctionalInterface
    public interface Stand {
        boolean test(int x, int y, int z);
    }

    private SafeSpot() {
    }

    /** Y de pie más cercana a {@code y} (primero la misma, luego ±1, ±2… hasta {@code range}). */
    public static OptionalInt nearestY(int x, int y, int z, int range, Stand stand) {
        if (stand.test(x, y, z))
            return OptionalInt.of(y);
        for (int offset = 1; offset <= range; offset++) {
            if (stand.test(x, y - offset, z))
                return OptionalInt.of(y - offset);
            if (stand.test(x, y + offset, z))
                return OptionalInt.of(y + offset);
        }
        return OptionalInt.empty();
    }
}
