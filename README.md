# VishuCraft

An open-world voxel sandbox game for Android (building and exploring with blocks), written from scratch in
Kotlin and OpenGL ES 3.0. It has no third-party game engine and no image assets: every texture is generated
procedurally in code when the game starts.

## Features

- **Wi-Fi multiplayer**: choose "Open to Wi-Fi" in the pause menu and friends on the same network pick
  "Join Wi-Fi game" (games are found automatically, or type the host's address). Everyone shares the terrain and
  every block change; the host runs redstone, liquids, farms and mobs, and mobs chase whoever is nearest.
- **Survival and Creative modes** chosen per world, with **multiple save slots** and a **settings** screen
  (look sensitivity, field of view, render distance, volume, button size).
- **Survival**: 36-slot inventory with stacks, items drop and are picked up, tool durability, the right pickaxe tier is
  needed for ores, hunger with food and eating, natural regeneration, armor (leather to netherite, plus enchanted sets),
  and your items drop where you die.
- **Torch light**: torches, lava, glowstone, lamps and lit furnaces light up their surroundings; caves are dark
  until you light them.
- **Sound and music**: every effect (digging per material, footsteps, pickups, eating, explosions, mob voices, rain and
  thunder) is synthesised in code, and the calm background music is composed on the fly.
- **Flowing water and lava**: liquids spread and fall, lava burns and lights caves, water meeting lava makes
  obsidian or cobblestone, and buckets carry either.
- **Weather**: rain, snow in cold biomes, and thunderstorms with lightning flashes.
- **Farming**: till soil, plant wheat, carrots and potatoes, grow saplings into trees, use bone meal, and breed
  cows, sheep and pigs.
- **Structures**: villages (houses with doors and glass panes, farms, a well and villagers), desert temples with a
  trapped treasure room, and underground dungeons with loot chests (sometimes holding enchanted gear).
- **Two more worlds**: light an obsidian frame to reach the fiery **Ember Realm** (lava seas, ember rock, glowing
  clusters, ancient debris), or a quartz frame for the floating **Sky Isles**. A portal home is built where you arrive.
- **New creatures** (original designs): Rattlers (thornwood archers), Crawlers (wall-climbing rock beetles), Night
  Gliders (swooping moth-wings), Cinder Brutes in the Ember Realm, Void Wisps in the Sky Isles, and villagers.
- **Building pieces**: doors and trapdoors for every wood type plus iron, fences and gates for every wood, five kinds of stairs, fences and gates, ladders, glass panes
  and iron bars, all with proper collision.
- **More redstone**: repeaters (1-4 tick delay), observers, daylight sensors, pressure plates, hoppers, rails,
  powered rails and rideable minecarts; doors react to redstone. Bows and arrows too.
- **Beds** in 16 colours: tap to set your respawn point; at night (or in a storm) you sleep until morning.
  Not with monsters nearby, and never in the other worlds (they explode). Bigger village houses have one.
- **Crafting grid**: lay items out yourself in a 2x2 grid (inventory) or 3x3 grid (crafting table); tools, armor,
  doors, stairs and more need the right pattern, other recipes just the right ingredients. A recipe list is there too
  (small recipes anywhere, the rest at a crafting table), a **furnace** that smelts ore,
  sand, clay and food using coal, charcoal or wood, and **chests** with 27 slots. Mobs drop leather, meat, wool and more.

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
- **Mobs**: cows, pigs and sheep wander the grasslands (and run when hit). Zombies chase and hit you at night and in caves,
  and burn in sunlight. **Boomlings**, the game's own exploding stone creatures, sneak up, hiss and blow up.
  Tap a mob to attack it; swords and axes hit harder. You have 10 hearts, regenerate over time, take fall and blast damage,
  and respawn if you die. "Mobs: Peaceful" in the pause menu turns monsters off.
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
| Attack mob / place block / use item / flip lever / press button | Tap |
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

Minimum Android version: 7.0 (API 24) with OpenGL ES 2.0. Runs on phones, tablets and **Android TV**
(it appears on the TV home screen, and every menu can be used with the remote).

## Windows (laptop / PC)

Every push also builds a Windows version (`.github/workflows/windows.yml`), uploaded as the `VishuCraft-windows` artifact:

- **VishuCraft-Setup.exe** – installer. Installs for the current user (no administrator needed), adds a Start-menu entry
  and a desktop shortcut. Java is bundled, nothing else to install.
- **VishuCraft-portable.zip** – unzip anywhere and run `VishuCraft.exe`.

Windows may show "Windows protected your PC" because the installer is not code-signed: click *More info* → *Run anyway*.
Worlds and settings are kept in `%APPDATA%\VishuCraft`. Wi-Fi games work between the PC and phones/TVs on the same network.

Keyboard and mouse: W A S D walk, mouse looks, left click mines / attacks, right click places / uses, Space jumps
(double-tap to fly in Creative), Shift flies down, Ctrl sprints, 1-9 or the wheel pick a hotbar slot, middle click picks
the block you look at, E opens the inventory, Esc pauses, F3 shows FPS, F11 fullscreen, F2 saves a screenshot.

The desktop version shares the whole game engine with Android (`desktop/` only adds the window, menus, input and sound).
Build and run it yourself with JDK 17+ (no Android SDK needed):

```bash
./gradlew -PdesktopOnly :desktop:run
```

### TV remote, gamepad and keyboard

| Action | TV remote | Gamepad | Keyboard |
| --- | --- | --- | --- |
| Walk | Up / Down | Left stick | W A S D |
| Turn / look | Left / Right, Channel + / − | Right stick | Arrows, R / V |
| Place / use / attack | OK | LT | Enter or K |
| Mine | Hold OK | RT | J |
| Jump / fly up | – | A | Space |
| Fly down | – | Left stick click | C or Shift |
| Toggle flight | – | X | F |
| Inventory | Menu | Y | E |
| Hotbar slot | Rewind / Fast-forward | LB / RB | 1-9, Q, Tab |
| Pause | Back | B / Start | Esc |

## Code layout

```
app/src/main/java/com/vishucraft/game/
├── MenuActivity.kt        title screen, new world / seed dialog
├── GameActivity.kt        GL surface, HUD, touch handling, pause menu
├── engine/
│   ├── Game.kt            simulation: chunk streaming, mining, placing, time of day, saving
│   ├── Player.kt          movement, collision, swimming, flight
│   ├── Raycast.kt         voxel DDA for block targeting
│   ├── Mobs.kt            mob spawning, AI, physics, combat
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
│   ├── MobRenderer.kt     animated box models for mobs
│   ├── ChunkMesher.kt     face culling, lighting and AO meshing
│   ├── TextureAtlas.kt    procedural pixel-art textures
│   └── GlUtil.kt          shaders, buffers, VAOs
└── ui/                    joystick, buttons, hotbar, inventory, block icons
```
