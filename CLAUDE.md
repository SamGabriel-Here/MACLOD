# Claude Code Handoff — MACLOD

Codex checked the current WIP after commit `95915bf`.

## Current status

The current uncommitted work is a real step toward Distant-Horizons-style behavior:

- `DistantChunkSource` reads `.mca` region files directly from the singleplayer save folder.
- `DistantHeightmapDecoder` decodes saved chunk heightmaps into `LodChunkData`.
- `DistantLodStore` asynchronously requests/caches disk-read chunks.
- `LodRenderer` now tries to render cached distant chunks beyond vanilla-loaded terrain.

This is no longer just the near loaded-chunk debug overlay. It is the first disk-backed far-terrain prototype.

## Build validation

Codex ran:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew --no-daemon build
```

Result:

```text
BUILD SUCCESSFUL
```

The WIP jar contains these new classes:

- `DistantChunkSource.class`
- `DistantHeightmapDecoder.class`
- `DistantLodStore.class`

A validated jar was copied to:

```text
/Users/samgabriel/Documents/Codex/2026-06-26/distant-horizons-mod-for-macos/outputs/distant-lod-0.1.0-disk-lod-wip.jar
```

## Important: do not forget new files

Before pushing, make sure these untracked files are added:

```bash
git add src/main/java/com/example/distantlod/data/DistantChunkSource.java \
        src/main/java/com/example/distantlod/data/DistantHeightmapDecoder.java \
        src/main/java/com/example/distantlod/data/DistantLodStore.java
```

Current WIP also modifies:

- `ChunkLodCapture.java`
- `ChunkLodStore.java`
- `LodRenderer.java`

## Codex safety patch already applied

Codex made a small guardrail patch on top of the WIP:

1. `DistantLodStore`
   - added an epoch guard so async disk-read results from an old world/dimension are discarded after disconnect/world switch.
   - clears pending/cache/unavailable state on source changes.

2. `DistantChunkSource`
   - synchronizes reads per `RegionFile` handle.
   - adds a `closed` flag so background tasks cannot resurrect region-file handles after disconnect.

3. `LodRenderer`
   - uses `World.OVERWORLD.equals(world.getRegistryKey())` instead of reference comparison.
   - makes distant read radius adapt to vanilla render distance.
   - avoids doing distant rendering if the user's vanilla render distance is already at/above the prototype cap.

## Next test

Run:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew runClient
```

Then:

1. Open a singleplayer overworld.
2. Use render distance around 12–20 chunks.
3. Move/look around after terrain has generated.
4. Expected: height-banded far chunks may appear beyond vanilla-loaded terrain after async disk reads complete.

## Known limitations / next fixes

- This is singleplayer-overworld only.
- It reads already-generated chunks from disk; it does not generate new far terrain yet.
- Colors are height-band placeholders, not real surface block colors.
- Renderer still emits quads every frame via Tessellator. Good for proof of concept, not scalable.
- No proper LOD mesh cache/VBO yet.
- No quadtree/frustum-culling section system yet.
- No disk cache format separate from Minecraft region files yet.
- README is stale and still describes the old four-wall test. Update before pushing if possible.

## Architectural reference from Distant Horizons

Use the GitLab repo only as an architecture reference, not as copy-paste source:

https://gitlab.com/distant-horizons-team/distant-horizons

Relevant DH concepts to mimic gradually:

- `FullDataSource`-style persistent terrain data
- `ColumnRenderSource`-style render-column representation
- background transformer from saved/generated data to render data
- render sections / quadtree
- VBO-backed render buffers
- frustum culling
- distance fade / fog integration

MACLOD should stay simpler for now. The next clean milestone is:

> Convert cached `LodChunkData` into reusable mesh buffers instead of rebuilding all quads every frame.

