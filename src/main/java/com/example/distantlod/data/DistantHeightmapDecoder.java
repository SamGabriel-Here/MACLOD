package com.example.distantlod.data;

import com.example.distantlod.DistantLodClient;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.collection.PackedIntegerArray;
import net.minecraft.util.math.MathHelper;

/**
 * Decodes a chunk's saved {@code Heightmaps} NBT tag into a {@link LodChunkData}.
 *
 * Vanilla's heightmap storage already records *absolute* world Y (+1) per
 * column - see {@code Heightmap.trackUpdate}, which calls {@code set(x, z, y + 1)}
 * with the block's real world Y, not a chunk-relative offset. That means no
 * per-dimension bottom-Y correction is needed when reading it back: a stored
 * value of 0 means "no surface tracked in this column", and any other value
 * minus 1 is directly the surface block's world Y.
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
                int height = raw - 1;
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

    /** Rough height-banded placeholder color, until real block-color decoding exists. */
    private static int bandColor(int y) {
        if (y < 60) {
            return 0xFF1E5FA8; // water / low ground
        } else if (y < 90) {
            return 0xFF3F8F3A; // plains/forest
        } else if (y < 130) {
            return 0xFF8A7A52; // hills
        } else {
            return 0xFFD8D8D8; // mountains
        }
    }
}
