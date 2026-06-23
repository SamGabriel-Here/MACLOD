package com.example.distantlod.data;

/**
 * Compact per-chunk LOD snapshot (Week 3-4 target).
 *
 * <p>Instead of keeping full chunk meshes for distant terrain, we keep just
 * enough to draw a recognisable silhouette: the top solid block height per
 * column, and a packed colour per column for biome/block tinting. This is the
 * unit you will capture from loaded chunks and feed to the mesh builder.</p>
 *
 * <p>Arrays are indexed {@code z * SIZE + x}, with x,z in [0, SIZE).</p>
 *
 * @param chunkX      chunk X coordinate (block X >> 4)
 * @param chunkZ      chunk Z coordinate (block Z >> 4)
 * @param heights     top solid block Y for each of the 16x16 columns
 * @param biomeColors packed ARGB tint for each of the 16x16 columns
 */
public record LodChunkData(int chunkX, int chunkZ, int[] heights, int[] biomeColors) {

    /** Columns per chunk edge. */
    public static final int SIZE = 16;

    public LodChunkData {
        if (heights == null || heights.length != SIZE * SIZE) {
            throw new IllegalArgumentException("heights must be " + (SIZE * SIZE) + " entries");
        }
        if (biomeColors == null || biomeColors.length != SIZE * SIZE) {
            throw new IllegalArgumentException("biomeColors must be " + (SIZE * SIZE) + " entries");
        }
    }

    public int height(int x, int z) {
        return heights[z * SIZE + x];
    }

    public int color(int x, int z) {
        return biomeColors[z * SIZE + x];
    }
}
