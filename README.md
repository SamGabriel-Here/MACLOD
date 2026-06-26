# Distant LOD

A learning prototype of a Distant Horizons–style far-terrain renderer for
Minecraft, built on **Fabric 1.20.1** and set up to run cleanly on **Apple
Silicon macOS** (M-series).

Current state: it renders real heightmap terrain built from two sources -
captured live chunk data for whatever the client has loaded, and chunks read
**directly off disk** from the singleplayer world save for terrain beyond the
normal horizon. The renderer now uploads per-chunk geometry into Minecraft
`VertexBuffer`s and reuses those meshes between frames, so this is the first
disk-backed + mesh-cached far-terrain prototype rather than just a render
pipeline test.

## Prerequisites (macOS / Apple Silicon)

- **JDK 17, ARM64.** Minecraft 1.20.1 runs on Java 17. Get the native
  Apple-Silicon build (e.g. Azul Zulu/Temurin 17 aarch64) so you are not on
  Rosetta. Verify: `java -version` should mention `17` and `aarch64`.
- A Gradle 8.8 wrapper is already committed (`gradlew`, `gradle/wrapper/`), so
  no separate Gradle install is required.

## Build & run

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew runClient     # launches a dev Minecraft with the mod loaded
```

In-game: open a singleplayer **overworld** world. A small MACLOD debug HUD in
the top-left shows near/far chunk counts, mesh counts, queued disk reads, and
the active vanilla/distant radii. The near ring (chunks the client has actually
loaded) renders immediately as a translucent colored heightfield floating a few
blocks above the real terrain. The distant ring
(read from disk, beyond vanilla's loaded chunks) renders opaque, height-banded
colors, and fills in asynchronously as background reads complete. Disk read
requests are throttled each frame so the game does not queue thousands of
region-file reads at once. It's easiest to see the distant ring clearly by lowering **Video
Settings → Render Distance** to ~2-4 chunks first, so vanilla's own horizon is
small enough to leave a visible gap for the mod to fill.

## Project layout

```
distant-lod/
├── build.gradle, settings.gradle, gradle.properties
├── gradlew, gradle/wrapper/         # committed Gradle 8.8 wrapper
├── src/main/
│   ├── java/com/example/distantlod/
│   │   ├── DistantLodClient.java          # client entrypoint
│   │   ├── render/LodRenderer.java        # cached VertexBuffer renderer for near + distant rings
│   │   └── data/
│   │       ├── LodChunkData.java          # compact per-chunk LOD snapshot
│   │       ├── ChunkLodCapture.java       # near: real heightmap+color from a loaded chunk
│   │       ├── ChunkLodStore.java         # near: cache, kept live via ClientChunkEvents
│   │       ├── DistantChunkSource.java    # distant: reads .mca region files off disk
│   │       ├── DistantHeightmapDecoder.java # distant: decodes saved Heightmaps NBT
│   │       └── DistantLodStore.java       # distant: async cache + background thread pool
│   └── resources/
│       ├── fabric.mod.json
│       └── assets/distantlod/shaders/     # reference GLSL for a future custom-shader path
```

## How the distant ring works

`DistantChunkSource` reuses Minecraft's own `RegionFile`/`NbtIo` classes to
read a chunk's saved NBT straight from the world's `region/*.mca` files,
bypassing the normal client/server chunk-loading protocol entirely. This only
works in **singleplayer** - the integrated server's save folder lives on the
same machine; there is no filesystem access to a remote server's world.

`DistantHeightmapDecoder` then decodes the chunk's saved `Heightmaps` NBT tag
(the same packed-long-array format vanilla itself writes) into a per-column
surface height. It does not yet decode real block colors - that requires
walking the section block-state palettes, a separate and heavier piece of
work - so distant terrain is currently tinted by a height-band placeholder
(blue/green/tan/gray) rather than its real surface block color.

`DistantLodStore` runs those reads on a small background thread pool so disk
I/O and NBT parsing never stall the render thread, with an epoch guard so
stale results from a previous world/dimension are discarded after disconnect.
`LodRenderer` then turns each `LodChunkData` into a cached Minecraft
`VertexBuffer`; the cache is pruned as chunks leave the active LOD radius and
cleared on disconnect.

## Why it runs on macOS

`LodRenderer` draws through Minecraft's own `RenderSystem` and the built-in
`position_color` core shader, which already targets the OpenGL 3.2+ Core
Profile Apple's GL requires: VAO-backed, no immediate mode, and
`DrawMode.QUADS` is triangulated by Minecraft (the driver never sees
`GL_QUADS`). When you move to your own VAO/VBO + GLSL, keep the same rules -
they're enforced strictly on macOS:

- No immediate mode (`glBegin/glEnd`), no fixed-function pipeline.
- Always bind a VAO before drawing.
- Use `GL_TRIANGLES` + an index buffer, never `GL_QUADS`.
- Restore all GL state after your draw (macOS is unforgiving about state leaks).
- Keep GLSL at `#version 150` (GL 3.2) or whatever version MC creates.

The reference shaders in `assets/distantlod/shaders/` show the distance-fade
seam trick for later.

## Known limitations

- Singleplayer overworld only; the Nether/End and multiplayer aren't wired up.
- Reads only **already-generated** terrain from disk; it doesn't generate new
  far terrain that's never been explored.
- Distant terrain color is a height-band placeholder, not the real surface
  block color.
- Meshes are now cached in `VertexBuffer`s, but still at one mesh per chunk;
  there is no region/section batching yet.
- No quadtree/frustum culling, no disk cache format of its own (it just reads
  vanilla's region files directly each time).

## Roadmap

1. **Done:** render hook proven safe on macOS; real heightmap+color capture
   for loaded chunks; disk-backed reads for terrain beyond the loaded horizon;
   per-chunk mesh caching with Minecraft `VertexBuffer`s.
2. **Next:** batch chunks into larger render sections so the renderer draws
   tens of meshes instead of hundreds/thousands of one-chunk meshes.
3. **Later:** real block-color decoding (section palettes) instead of height
   bands; render sections / quadtree + frustum culling; distance-fade blend
   zone using the shaders already in `assets/distantlod/shaders/`.

## Notes

- Client-only mod (`"environment": "client"`), so it can join vanilla servers
  (the distant-disk feature simply does nothing there).
- Version pins are in `gradle.properties`; bump them against
  <https://fabricmc.net/develop> when you upgrade.
- Architectural reference (not copy-paste source) for where this is headed:
  the real Distant Horizons project at
  <https://gitlab.com/distant-horizons-team/distant-horizons>.
