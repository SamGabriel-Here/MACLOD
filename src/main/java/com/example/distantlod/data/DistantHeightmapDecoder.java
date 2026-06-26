package com.example.distantlod.data;

import com.example.distantlod.DistantLodClient;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.collection.PackedIntegerArray;
import net.minecraft.util.math.MathHelper;

/**
 * Decodes a chunk's saved {@code Heightmaps} NBT tag into a {@link LodChunkData}.
 *
 * Vanilla's saved heightmap stores Y relative to the dimension bottom:
 * {@code stored = surfaceWorldY - bottomY + 1}. A stored value of 0 means
 * "no tracked surface", otherwise the world-space surface height is
 * {@code bottomY + stored - 1}.
 *
 * This does not yet decode actual block colors (that requires walking the
 * section block-state palettes, which is a separate, heavier piece of work).
 * Color is a placeholder height band until that's added.
 */
public final class DistantHeightmapDecoder {

    private DistantHeightmapDecoder() {}

    /** Returns null if the chunk isn't fully generated yet, or has no usable heightmap. */
    public static LodChunkData decode(NbtCompound root, int chunkX, int chunkZ, int worldBottomY, int worldHeight) {
        String status = root.getString("Status");
        if (!status.endsWith("full")) {
            // Partially generated chunks (still mid-worldgen-pipeline) don't have
            // a trustworthy surface yet.
            return null;
        }

        NbtCompound heightmaps = root.getCompound("Heightmaps");
        long[] packed = heightmaps.getLongArray("MOTION_BLOCKING");
        if (packed.length == 0) {
            packed = heightmaps.getLongArray("WORLD_SURFACE");
        }
        if (packed.length == 0) {
            return null;
        }

        int bits = MathHelper.ceilLog2(worldHeight + 1);
        PackedIntegerArray storage = new PackedIntegerArray(bits, 256, packed);

        int size = LodChunkData.SIZE;
        int[] heights = new int[size * size];
        int[] colors = new int[size * size];

        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                // Heightmap.toIndex(x, z) is x + z*16, not x*16 + z.
                int raw = storage.get(z * size + x);
                int height = raw == 0 ? worldBottomY - 1 : worldBottomY + raw - 1;
                if (height < worldBottomY - 1 || height > worldBottomY + worldHeight) {
                    DistantLodClient.LOGGER.warn(
                            "[Distant LOD] distant chunk ({}, {}) col ({}, {}) out-of-range height {} (raw {})",
                            chunkX, chunkZ, x, z, height, raw);
                }
                heights[z * size + x] = height;
                colors[z * size + x] = bandColor(height);
            }
        }

        return new LodChunkData(chunkX, chunkZ, heights, colors);
    }

    /**
     * High-contrast debug height bands, until real block-color decoding exists.
     * These are intentionally artificial so the far LOD ring is easy to spot
     * while testing the disk-read + mesh-cache pipeline.
     */
    private static int bandColor(int y) {
        if (y < 58) {
            return 0xFF00B7FF; // cyan: ocean / low ground
        } else if (y < 85) {
            return 0xFF43FF35; // neon green: low terrain
        } else if (y < 120) {
            return 0xFFFFD23A; // yellow/orange: hills
        } else if (y < 160) {
            return 0xFFFF4FD8; // magenta: high mountains
        } else {
            return 0xFFFFFFFF; // white: peaks
        }
    }
}
