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
 * Color comes from {@link DistantBlockColorDecoder}'s real per-column surface
 * block lookup where that succeeds; the height band in {@link #bandColor} is
 * only a fallback for columns it can't resolve (e.g. a missing section).
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

        DistantBlockColorDecoder.decorateColors(root, heights, colors);

        return new LodChunkData(chunkX, chunkZ, heights, colors);
    }

    /**
     * Debug height coloring, until real block-color decoding exists. The
     * previous version used flat bands across wide height ranges (e.g. one
     * solid color for all of y=85..120), so ordinary hills - whose real
     * height varies a lot within one band - visually collapsed into a single
     * uniform sheet with zero shading. Interpolating continuously between
     * anchor colors means adjacent columns at different heights always look
     * at least a little different, so slopes actually read as terrain.
     */
    private static final int[] HEIGHTS = {-64, 58, 85, 120, 160, 200, 320};
    private static final int[] COLORS = {
            0xFF0D3B66, // deep water
            0xFF1E90AA, // shallow water / shoreline
            0xFF3FAA35, // lowland green
            0xFFC9A227, // hills (tan/gold)
            0xFFA85C2E, // highlands (rust/brown)
            0xFFD8D8D8, // peaks (light gray)
            0xFFFFFFFF, // snow caps
    };

    private static int bandColor(int y) {
        if (y <= HEIGHTS[0]) {
            return COLORS[0];
        }
        for (int i = 1; i < HEIGHTS.length; i++) {
            if (y <= HEIGHTS[i]) {
                float t = (float) (y - HEIGHTS[i - 1]) / (HEIGHTS[i] - HEIGHTS[i - 1]);
                return lerpColor(COLORS[i - 1], COLORS[i], t);
            }
        }
        return COLORS[COLORS.length - 1];
    }

    private static int lerpColor(int from, int to, float t) {
        int r = lerpChannel(from, to, t, 16);
        int g = lerpChannel(from, to, t, 8);
        int b = lerpChannel(from, to, t, 0);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int lerpChannel(int from, int to, float t, int shift) {
        int a = (from >> shift) & 0xFF;
        int b = (to >> shift) & 0xFF;
        return a + Math.round((b - a) * t);
    }
}
