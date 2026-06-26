package com.example.distantlod.data;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.storage.RegionFile;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads chunk NBT directly off disk from a world's region files (.mca),
 * bypassing the normal client/server chunk-loading protocol entirely. This is
 * what makes it possible to see terrain the client has never had loaded - the
 * core trick behind Distant Horizons-style rendering.
 *
 * Only works for singleplayer (the integrated server's save folder is on the
 * same machine); there is no equivalent for a remote multiplayer server, since
 * the client has no filesystem access to it.
 */
public final class DistantChunkSource {

    private final Map<Long, RegionFile> openRegions = new ConcurrentHashMap<>();
    private final Path regionDir;
    private volatile boolean closed;

    public DistantChunkSource(Path regionDir) {
        this.regionDir = regionDir;
    }

    /** Reads and parses one chunk's root NBT tag, or null if it doesn't exist on disk yet. */
    public NbtCompound readChunkNbt(int chunkX, int chunkZ) throws IOException {
        if (closed) {
            return null;
        }

        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        long regionKey = (((long) regionX) << 32) | (regionZ & 0xFFFFFFFFL);

        RegionFile region = openRegions.get(regionKey);
        if (region == null) {
            Path regionPath = regionDir.resolve("r." + regionX + "." + regionZ + ".mca");
            if (!Files.isRegularFile(regionPath)) {
                // Region never generated - nothing to read, and importantly we must
                // not let RegionFile's constructor create it (it will, if asked to
                // open a missing file for write access).
                return null;
            }
            if (closed) {
                return null;
            }
            region = new RegionFile(regionPath, regionDir, false);
            RegionFile existing = openRegions.putIfAbsent(regionKey, region);
            if (existing != null) {
                region.close();
                region = existing;
            }
        }

        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        synchronized (region) {
            if (closed) {
                return null;
            }
            try (DataInputStream in = region.getChunkInputStream(pos)) {
                if (in == null) {
                    return null;
                }
                return NbtIo.read(in);
            }
        }
    }

    public void close() {
        closed = true;
        for (RegionFile region : openRegions.values()) {
            try {
                synchronized (region) {
                    region.close();
                }
            } catch (IOException ignored) {
                // Closing a read-only handle on shutdown; nothing useful to do about it.
            }
        }
        openRegions.clear();
    }
}
