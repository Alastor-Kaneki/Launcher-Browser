# Cosmos Double Doors

Native C++ mod for **Pocket Cosmos** (a LeviLaunchroid fork, package
`org.levimc.launcher`) that opens and closes paired doors and trapdoors
together in Minecraft Bedrock on Android `arm64-v8a`.

- Author: Alastor Kaneki
- Version: 1.0.0
- Package: `cosmos-double-doors.levipack`
- Target: Pocket Cosmos 1.5.27-1.1.2.1, Minecraft Bedrock 26.5x (built for 26.52.3)

## Status: read this first

| Stage | Result |
| --- | --- |
| Compiles for arm64-v8a with NDK 28.2.13676358 (the launcher's own NDK) | ✅ |
| Host unit tests: state parsing and pair detection (25 tests) | ✅ pass |
| ARM64 tests under qemu: the real hook and sync code against a fake game built with the same toolchain (19 tests) | ✅ pass |
| `.levipack` layout, manifest, config and ELF validation | ✅ pass |
| Checked against a real `libminecraftpe.so` 26.52.3 | ❌ **not done**: no game binary was available |
| Tested in game on a device | ❌ **not done** |

Passing the build and the tests does not prove the mod works in the game.
Some of the design is confirmed by its sources: Pocket Cosmos' mod loader, the
preloader SDK, the block-state format and LeviLamina's 26.51 class headers.
Two parts are unconfirmed until the mod runs against 26.52.3:

1. Whether the 26.52.3 class layout still matches the 26.51 headers. The mod
   checks this when it starts. If the layout differs, it logs why and does
   nothing.
2. The final in-game behaviour.

Use `tools/inspect_libminecraftpe.py` to check (1) offline. Use the
[in-game test checklist](#in-game-acceptance-checklist) to check (2).

## How it works

```
player taps a door
  └─ DoorBlock::_useDoor(BlockPlayerInteractEvent&)      ← hooked (GlossHook via SDK)
       1. read the door's state (both halves)
       2. call the original vanilla handler               ← sound, animation, permissions
       3. read the state again; if it didn't change → stop (iron door, cancelled, adventure mode…)
       4. find the partner (logic/PairFinder)
       5. if the partner isn't already in that state → call the original
          vanilla handler on the partner (recursion guard set)
```

- **Locating hook targets without offsets.** Minecraft's Android library keeps
  C++ RTTI. The mod finds the vtables of `DoorBlock` (`9DoorBlock`) and
  `TrapDoorBlock` (`13TrapDoorBlock`) by their type names. In LeviLamina's 26.51
  headers, `_useDoor` and `_useTrapDoor` are the only virtuals these classes
  add, so each one is the last slot of its vtable. Before installing any hook,
  the mod checks that:
  - both vtables have the same size;
  - each is exactly one slot longer than `BlockType`'s;
  - the two functions are different;
  - the launcher SDK's own resolver (`pl::memory::resolveVtableFunction`) agrees.

  The mod uses no hardcoded addresses, offsets or byte signatures.
- **Reading blocks.** The mod gets block states from the block's NBT
  serialisation compound (`name` plus `states`, the format used in world
  saves). It finds that compound by scanning for a valid libc++ `std::map`.
  It finds the block's `BlockType*` with the pointer the game passes to the
  hook. It finds blocks next to the clicked one by calling the event's own
  `getBlock()` virtual with a different position. It only reads the event
  after checking the event's RTTI type and the shape of its vtable.
- **No unsafe memory access.** Every pointer read goes through
  `process_vm_readv`, or a pipe if that call is blocked. A wrong layout guess
  becomes a failed read and the mod stops acting, instead of crashing.
  Android's tagged heap pointers (`0xb4…`) are handled.
- **Only where the world is authoritative.** The mod acts only when the
  interacting player is a `ServerPlayer`, which happens in local worlds and in
  worlds you host. On Realms and remote servers the client never runs the
  server's door logic. There the mod does nothing and the server decides.
- **Vanilla behaviour is kept.** The partner is toggled by Minecraft's own
  handler. Each door therefore plays its own normal sound once and animates
  normally, and its collision, block states, network sync, iron-door rules and
  game-mode permissions all stay vanilla. A recursion guard limits each click
  to at most one call on each door, so there are no loops and no repeated
  sounds.

### Pairing rules

The rules use Java facing semantics. The Bedrock-to-Java conversion was checked
against GeyserMC's block mappings.

| Kind | Strict (default) | Relaxed (`requireMatchingOrientation: false`) |
| --- | --- | --- |
| Door | Same facing, opposite hinge, partner on the door's free edge (left hinge → clockwise side) | Same or opposite facing, any hinge, either side along the door plane |
| Trapdoor | Partner on the free edge, facing back, same half (top/bottom) | Any horizontal neighbour on the same half |

Additional rules:

- Doors pair only when both lower halves are at the same Y.
- A door never pairs with a trapdoor.
- Walls and air between the two doors are skipped, up to `maxLinkDistance`.
- An incompatible door between them stops the search.
- Material families: `copper` in the name means copper (any oxidation or wax
  level), `iron` means iron, and everything else is wood, including future
  wood types.

## Configuration

The config file is `config/config.json` inside the installed mod folder. The
launcher can also edit it, using `config.schema.json`. Changes take effect the
next time Minecraft starts.

| Option | Default | Meaning |
| --- | --- | --- |
| `enabled` | `true` | Turns the whole mod on or off. |
| `doubleDoors` | `true` | Pairs doors. |
| `doubleTrapdoors` | `true` | Pairs trapdoors. |
| `requireMatchingBlockType` | `true` | Links only doors of the same kind. |
| `allowDifferentVariants` | `true` | When kinds must match, any wood links with any wood and any copper with any copper. When `false`, the block IDs must be identical. |
| `requireMatchingOrientation` | `true` | Requires a mirrored pair, as described in the pairing rules above. |
| `redstoneSync` | `false` | See the limitation below. |
| `maxLinkDistance` | `1` | How far away a partner can be, from 1 to 4 blocks. |
| `verboseLogging` | `false` | Logs every pairing decision. |

### Known limitation: redstone sync

In 26.5x, doors and trapdoors react to redstone through
`_onRedstoneUpdate(BlockRedstoneUpdateEvent&)`. That function is not virtual,
and the game dispatches it through its PubSub event system, so the mod can't
locate it from RTTI the way it locates the use handlers. I haven't verified a
way to hook it, so this build includes **no redstone hook**.

Redstone behaviour is therefore always vanilla: each door reacts only to its
own signal, and redstone toggles are never mirrored. Setting
`redstoneSync: true` only logs a warning that the feature isn't available. Mod
logic never moves iron doors, because the vanilla handler refuses player
interaction with them.

## Installation

1. Download `cosmos-double-doors.levipack` from the latest successful
   **Cosmos Double Doors** workflow run, in the
   `cosmos-double-doors-levipack` artifact.
2. In Pocket Cosmos, open **Mods → Import** and pick the file. `.levipack` and
   `.zip` both work.
3. Make sure the mod is enabled for your Minecraft 26.5x profile. The manifest
   declares `"minecraft_versions": ["1.26.5*", "26.5*"]`. The launcher skips the
   mod on any other version and shows "Skipped incompatible mod".
4. Launch Minecraft and open a local world. It can be Survival or Creative and
   needs no cheats or experiments.

## Building

Requirements:

- CMake 3.22 or newer
- Ninja
- A host C++20 compiler
- Android NDK. Use 28.2.13676358 to match Pocket Cosmos; r26 or newer works.
- Python 3 and `zip`
- Optional: `qemu-user-static`, for the ARM64 tests

Build command:

```bash
NDK=/path/to/android-ndk mods/cosmos-double-doors/scripts/build.sh
```

The script does the following:

1. Builds the host config generator and runs the logic tests.
2. Builds `libcosmos_double_doors.so` for `arm64-v8a` with `c++_shared`, as the
   launcher does.
3. Runs the ARM64 tests under qemu.
4. Strips the library and writes
   `dist/cosmos-double-doors.levipack` (an unstripped copy goes in
   `dist/symbols/`).
5. Validates the result.

The preloader SDK is fetched at Pocket Cosmos' pinned commit `5d108da`. To use
a local checkout instead, set `PRELOADER_ANDROID_ROOT`.

### Package layout

```
cosmos-double-doors.levipack
├── manifest.json               type preload-native, entry libcosmos_double_doors.so
├── libcosmos_double_doors.so   AArch64, 16 KB-aligned, exports PLGetModRegistration
├── README.txt
└── config/
    ├── config.json
    └── config.schema.json
```

## Pre-flight check against your own game library

Get `lib/arm64-v8a/libminecraftpe.so` from your own Minecraft APK, then run:

```bash
python3 mods/cosmos-double-doors/tools/inspect_libminecraftpe.py libminecraftpe.so
```

The script runs the same RTTI discovery as the mod, without the game. It
handles RELA, RELR and Android APS2 relocations, and prints the hook slots and
whether every structural check passes.

## In-game acceptance checklist

Run these in a local Survival world, then in a Creative world. Watch the logs
while testing with `adb logcat -s "Cosmos Double Doors" Preloader`.

1. The log shows `Hooks installed`. If it shows `not supported`, go to
   troubleshooting.
2. Place two oak doors side by side as a mirrored pair. Opening either door
   opens both, and closing either closes both, from both the upper and lower
   halves.
3. A single door works normally.
4. An oak door next to a copper door doesn't link. A spruce door next to an oak
   door links while `allowDifferentVariants` is on.
5. Two doors with the same hinge side don't link in strict mode.
6. Break one door of a pair, then use the other: it opens and closes normally.
7. Two trapdoors facing each other on the same half open together. Set
   `doubleTrapdoors: false` and they work independently.
8. Iron doors still need redstone. A button opens only the door it powers.
9. Each door makes one sound per interaction, with no spam, and clicking
   rapidly never desyncs a pair.
10. On a Realm or remote server: no change and no errors.

## Troubleshooting

| Symptom | Cause / fix |
| --- | --- |
| Launcher says "Skipped incompatible mod" | Your Minecraft version doesn't match `minecraft_versions`. This is intentional. Only change the manifest after `inspect_libminecraftpe.py` reports COMPATIBLE. |
| Log: `RTTI: no unique primary vtable for 9DoorBlock` | This Minecraft build was compiled differently and the mod can't use it. The mod stays inactive and the game is unaffected. Report the version. |
| Log: `Unsupported game layout: …` | The door classes changed shape in this version. The mod stays inactive. |
| Log: `Double doors disabled for this session: …` | A runtime safety check failed, for example on the event type or block layout. The mod stopped acting for this session. Send the full log. |
| Log: `Interaction by 11LocalPlayer is not server-authoritative` | You are on a server or Realm. This is expected: the server owns door logic there. |
| Doors open alone in a local world | Check `enabled` and `doubleDoors`. Make sure the pair is mirrored, with opposite hinges, or set `requireMatchingOrientation: false`. Turn on `verboseLogging` to see the decision. |
| Copper and wood doors don't link | Set `requireMatchingBlockType: false`. |
| Redstone opens only one door | This is the known limitation described above. |
| Config edits have no effect | Restart Minecraft. The config is read at startup. |
| Mod not listed after import | The archive must contain `manifest.json` at its root. Run `scripts/validate_levipack.py`. |

## Source layout

```
src/CosmosDoubleDoors.*     launcher integration: PL_REGISTER_MOD lifecycle, config load, fail-safe enable
src/config/                 typed config + schema, host generator
src/logic/                  door model, Bedrock state → facing conversion, pair detection (pure C++)
src/memory/                 fault-tolerant reads, module segments, RTTI vtable discovery
src/game/                   event/libc++ ABI mirrors, block state reader, hook-target resolution
src/sync/                   hooks + world interaction (read → vanilla toggle → partner toggle)
tests/                      host logic tests; ARM64 FakeGame end-to-end tests
tools/                      offline libminecraftpe.so inspector
scripts/                    build/package, .levipack validator
```
