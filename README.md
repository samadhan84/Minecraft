# VishuCraft

An open-world voxel sandbox game for Android (building and exploring with blocks), written from scratch in
Kotlin and OpenGL ES 3.0. It has no third-party game engine and no image assets: every texture is generated
procedurally in code when the game starts.

## Features

- **Infinite procedural world**: chunks (16×128×16) stream in around you on background threads.
- **Biomes**: plains, forests (oak, birch, dark oak), jungles, savannas (acacia), deserts, snowy taigas (spruce),
  mountains with snow caps, oceans and beaches with sugar cane.
- **Underground**: tunnel caves, deep caverns, deepslate layer, granite/diorite/andesite/tuff pockets, and coal, copper, iron,
  gold, lapis, redstone, diamond and emerald ores.
- **195 blocks**: every stone variant, 6 wood types (logs, planks, leaves), 16-colour wool, concrete, terracotta and stained
  glass, mineral blocks, nether and end blocks, prismarine, sea lanterns, crafting table, furnace, chest, TNT, hay, melons,
  jack o'lanterns, torches, mushrooms, cobwebs, slabs and more.
- **Tools**: swords, pickaxes, axes, shovels and hoes in wood, stone, iron, gold, diamond and netherite. The right tool mines
  its blocks much faster. Hoes till farmland and shovels make dirt paths. Also flint and steel, a bucket and a water bucket.
- **Enchanted gear** (ready-made, no enchanting table): enchanted diamond and netherite swords, pickaxes, axes, shovels and
  hoes with a purple glint. Efficiency V tools mine almost instantly.
- **Redstone**: dust (power fades over 15 blocks and climbs steps), redstone torches (inverters and clocks), levers, stone
  buttons, redstone blocks, redstone lamps, pistons and sticky pistons (push up to 12 blocks), and TNT that explodes with a
  chain reaction.
- **Rendering**: face culling, smooth sky lighting with ambient occlusion, cut-out foliage, translucent water and ice, distance fog,
  frustum culling, day/night cycle with sun, moon, sunset tint and drifting clouds, underwater fog.
- **Gameplay**: walking with collision, auto-jump up single steps, swimming, creative flight, mining with crack
  animation (time depends on block hardness), block placing, selection outline.
- **Touch controls**: floating joystick, drag to look, tap to place, touch-and-hold to mine, jump/fly buttons, 9-slot hotbar and a
  creative inventory with isometric block icons.
- **Saving**: modified chunks, player position, time of day and hotbar are saved automatically. You can create worlds from a seed.

## Controls

| Action | Touch |
| --- | --- |
| Move | Left-side joystick |
| Look | Drag anywhere on the world |
| Place block / use item / flip lever / press button | Tap |
| Mine block | Touch and hold |
| Jump / swim / fly up | ▲ |
| Fly down | ▼ (while flying) |
| Toggle flight | FLY |
| Pick block or tool | Tap a hotbar slot, or ••• for the tabbed inventory |
| Pause menu | II or the Back button |

## Building

Requirements: JDK 17+ and the Android SDK (API 35).

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Every push is built by GitHub Actions (`.github/workflows/android.yml`). The resulting debug and release APKs are uploaded as the
`VishuCraft-apk` workflow artifact, so you can download and sideload them without building locally.

Minimum Android version: 7.0 (API 24) with OpenGL ES 3.0.

## Code layout

```
app/src/main/java/com/vishucraft/game/
├── MenuActivity.kt        title screen, new world / seed dialog
├── GameActivity.kt        GL surface, HUD, touch handling, pause menu
├── engine/
│   ├── Game.kt            simulation: chunk streaming, mining, placing, time of day, saving
│   ├── Player.kt          movement, collision, swimming, flight
│   ├── Raycast.kt         voxel DDA for block targeting
│   └── GameInput.kt       thread-safe touch state
├── world/
│   ├── Blocks.kt          block and texture-tile registry
│   ├── Items.kt           tools, enchanted gear, buckets
│   ├── Redstone.kt        redstone power, pistons, TNT
│   ├── Chunk.kt           block storage
│   ├── World.kt           chunk map, background generation, persistence
│   ├── TerrainGenerator.kt heightmap, biomes, caves, ores, trees
│   └── Noise.kt           seeded Perlin noise
├── render/
│   ├── GameRenderer.kt    GLSurfaceView renderer / game loop
│   ├── WorldRenderer.kt   chunk meshes, sky, clouds, selection, cracks
│   ├── ChunkMesher.kt     face culling, lighting and AO meshing
│   ├── TextureAtlas.kt    procedural pixel-art textures
│   └── GlUtil.kt          shaders, buffers, VAOs
└── ui/                    joystick, buttons, hotbar, inventory, block icons
```
