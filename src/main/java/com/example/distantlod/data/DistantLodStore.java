package com.example.distantlod.data;

import com.example.distantlod.DistantLodClient;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Caches {@link LodChunkData} read directly from disk (see
 * {@link DistantChunkSource}) for chunks the client has never had loaded.
 * Reads happen on a small background thread pool so disk I/O and NBT parsing
 * never stall the render thread; {@link #getCached} is a non-blocking lookup
 * the renderer can call every frame.
 *
 * Singleplayer only - there is no save folder to read for a remote server.
 */
public final class DistantLodStore {

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, daemonThreadFactory());

    private static final Map<Long, LodChunkData> CACHE = new ConcurrentHashMap<>();
    private static final Set<Long> UNAVAILABLE = ConcurrentHashMap.newKeySet();
    private static final Set<Long> PENDING = ConcurrentHashMap.newKeySet();
    private static final AtomicLong EPOCH = new AtomicLong();

    /** Source for the dimension currently being rendered; swapped on world/dimension change. */
    private static volatile DistantChunkSource source;
    private static volatile World sourceWorld;

    private DistantLodStore() {}

    private static ThreadFactory daemonThreadFactory() {
        AtomicInteger n = new AtomicInteger();
        return runnable -> {
            Thread t = new Thread(runnable, "distantlod-region-reader-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    public static LodChunkData getCached(long key) {
        return CACHE.get(key);
    }

    /** Kicks off an async disk read for this chunk if one isn't already cached/pending/known-missing. */
    public static boolean requestLoad(World world, Path saveRoot, int chunkX, int chunkZ) {
        DistantChunkSource src = sourceFor(world, saveRoot);
        long requestEpoch = EPOCH.get();
        long key = ChunkPos.toLong(chunkX, chunkZ);
        if (CACHE.containsKey(key) || UNAVAILABLE.contains(key) || !PENDING.add(key)) {
            return false;
        }

        int worldBottomY = world.getBottomY();
        int worldHeight = world.getHeight();

        EXECUTOR.submit(() -> {
            try {
                NbtCompound nbt = src.readChunkNbt(chunkX, chunkZ);
                LodChunkData data = nbt == null ? null
                        : DistantHeightmapDecoder.decode(nbt, chunkX, chunkZ, worldBottomY, worldHeight);
                if (isCurrentRequest(requestEpoch, world)) {
                    if (data != null) {
                        CACHE.put(key, data);
                    } else {
                        UNAVAILABLE.add(key);
                    }
                }
            } catch (Exception e) {
                if (isCurrentRequest(requestEpoch, world)) {
                    DistantLodClient.LOGGER.warn("[Distant LOD] Failed reading chunk ({}, {}) from disk", chunkX, chunkZ, e);
                    UNAVAILABLE.add(key);
                }
            } finally {
                PENDING.remove(key);
            }
        });
        return true;
    }

    private static boolean isCurrentRequest(long requestEpoch, World world) {
        return EPOCH.get() == requestEpoch && sourceWorld == world;
    }

    /** Only the overworld is supported for now; returns null for other dimensions. */
    private static synchronized DistantChunkSource sourceFor(World world, Path saveRoot) {
        if (source != null && sourceWorld == world) {
            return source;
        }
        if (sourceWorld != world) {
            // Dimension or world changed - old handles point at the wrong region folder.
            if (source != null) {
                source.close();
            }
            CACHE.clear();
            UNAVAILABLE.clear();
            PENDING.clear();
            EPOCH.incrementAndGet();
        }
        Path regionDir = saveRoot.resolve("region");
        source = new DistantChunkSource(regionDir);
        sourceWorld = world;
        return source;
    }

    /** Called on disconnect so stale data/handles from this world don't leak into the next. */
    public static void clear() {
        EPOCH.incrementAndGet();
        if (source != null) {
            source.close();
        }
        source = null;
        sourceWorld = null;
        CACHE.clear();
        UNAVAILABLE.clear();
        PENDING.clear();
    }
}
