package com.fossiles.fossilescorebackend.infrastructure.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Parte la cantidad pendiente de una línea de OP en tareas del tamaño
 * configurado por producto ({@code units_per_task}). Default 2.
 */
public final class TaskQuantityChunker {

    public static final int DEFAULT_UNITS_PER_TASK = 2;

    private TaskQuantityChunker() {
    }

    public static int resolveUnitsPerTask(Integer configured) {
        if (configured == null || configured < 1) {
            return DEFAULT_UNITS_PER_TASK;
        }
        return configured;
    }

    public static List<Integer> splitQuantity(int remaining, int unitsPerTask) {
        List<Integer> chunks = new ArrayList<>();
        int left = Math.max(remaining, 0);
        int size = resolveUnitsPerTask(unitsPerTask);
        while (left > 0) {
            int chunk = Math.min(size, left);
            chunks.add(chunk);
            left -= chunk;
        }
        return chunks;
    }
}
