# 8BitDo Controller Support

Makes every 8BitDo controller work with [Better than Adventure!](https://betterthanadventure.net/)
8.0.1's native controller support, without touching the game jar.

- Mod id: `eightbitdo` · package `pl.sampir.bta.eightbitdo` · version `1.0.0+8.0.1`
- Client-side only, Minecraft 8.0.1 (BTA) + Babric / Fabric loader
- Licensed MIT; the bundled mapping data is SDL GameControllerDB data under the zlib license

## The problem

BTA 8.0.1 implements controller support on top of **GLFW's gamepad API**. On start-up,
`net.minecraft.client.input.InputHandler.initGLFW(Minecraft)` reads the SDL
GameControllerDB snapshot shipped inside `bta-v8.0.1.jar` as `/controller_mappings.txt`,
feeds it to `glfwUpdateGamepadMappings()`, then counts the pads GLFW classifies as
gamepads and logs `"{} controllers found!"`.

GLFW only reports `glfwJoystickIsGamepad() == true` when a mapping matches the joystick's
**entire GUID**, and the GUID contains the device's firmware revision. An 8BitDo Ultimate 2
Wireless behind an 8BitDo UM 2 receiver, for example, reports

```
03000000c82d00000b31000000010000
```

while the snapshot only knows that product at revision `0x0114`
(`03000000c82d00000b31000014010000`). No exact match means no gamepad, so BTA logs
`0 controllers found!` and ignores the pad even though the kernel driver (`xpad`) works
fine and other SDL games see it.

## What the mod does

It registers the missing mapping **at runtime**, one line per attached 8BitDo pad, built
from the GUID the device actually reports:

```
03000000c82d00000b31000000010000,8BitDo Ultimate 2,a:b0,b:b1,...,x:b2,y:b3,
```

`glfwUpdateGamepadMappings()` is additive — BTA loading its own database afterwards does
not remove the extra entry — so the pad is already a gamepad when `initGLFW` counts
controllers, and BTA logs `1 controllers found!`.

Three moving parts:

| Class | Role |
|---|---|
| `pl.sampir.bta.eightbitdo.MappingStore` | Parses the 8BitDo slice of SDL GameControllerDB, resolves a mapping for a live GUID (exact GUID → same bus/VID/PID → same VID/PID → XInput fallback). No Minecraft, LWJGL or SLF4J imports. |
| `pl.sampir.bta.eightbitdo.EightBitDoSupport` | Walks joysticks `0..GLFW_JOYSTICK_LAST`, skips non-8BitDo and already-known pads, feeds the resolved line to GLFW, remembers what it registered, and never lets an exception escape into the game. |
| `pl.sampir.bta.eightbitdo.mixin.InputHandlerMixin` | `@Inject` at `HEAD` of `InputHandler.initGLFW` (register before BTA counts) and of `InputHandler.poll` (rescan about once per second for hot-plug). `remap = false`, no refmap — BTA is unobfuscated. |

GUID fields are decoded little-endian the way
`net.minecraft.client.input.glfw.GlfwGamepad` does it: bus in bytes 0-3, vendor id 4-5,
product id 8-9, firmware revision 12-13.

## Why a mod and not a jar patch

Patching `/controller_mappings.txt` inside `bta-v8.0.1.jar` would work for one pad and one
firmware revision, but it means shipping a modified 58 MB game jar, it breaks every time
BTA updates, and it still cannot handle a pad that is plugged in later or a revision the
static file does not contain. A Babric mod keeps the game jar pristine, applies to any
8BitDo pad whose GUID can be derived at runtime, and can be uninstalled by deleting one
file.

## Requirements

- A **Babric BTA 8.0.1 instance** — plain BTA 8.0.1 cannot load mods. Use
  `bta_fabric_instance_8.0.1.zip` from [Turnip-Labs](https://github.com/Turnip-Labs)
  (Fabric loader `0.18.4-bta.11`), either in Prism/MultiMC or by unpacking it and running
  its launcher.
- Java 17 or newer.
- The mod is client-side only: servers do not need it and cannot tell it is installed.

## Install

1. Build the jar (below) or take it from `build/libs/eightbitdo-1.0.0+8.0.1.jar`.
2. Drop it into the instance's `mods` folder:
   `.minecraft/mods/eightbitdo-1.0.0+8.0.1.jar` (Prism: right-click the instance →
   *Folder* → *Minecraft* → `mods`).
3. Start the game with the controller connected. The log should show

   ```
   [8BitDo/]: Registered controller mapping for "8BitDo Ultimate 2" (03000000c82d00000b31000000010000, SAME_BUS_VENDOR_PRODUCT), joystick id 8
   ...
   1 controllers found!
   ```

4. In *Options → Controls* the pad appears as `(UNKNOWN)`; that is expected, see below.

## The `(UNKNOWN)` label

BTA asks its own `ControllerTypeDatabase` for a display name and glyph set. Our pad is not
in that table, so the controller is labelled `(UNKNOWN)`. This is cosmetic: BTA's `UNKNOWN`
controller type already uses the **XBOX layout and the `xbox_360` glyphs**, so buttons,
sticks, triggers and the on-screen prompts all behave like an Xbox pad.

Adding a display name would require a second mixin into `ControllerTypeDatabase`; that is
deliberately not done, because it buys a string and risks a second, unnecessary hook into
BTA internals.

## Hot-plug

`InputHandler.poll()` runs once per client tick, and `tick()` rescans every 20 calls
(~1 s). Plug the pad in (or wake it) while the game is running and BTA picks it up in the
next poll, exactly like a pad it knew about from the start. Mappings already registered
are remembered, so nothing is re-registered or re-logged.

## Build

```fish
set -x JAVA_HOME ~/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta
./gradlew build -Dorg.gradle.java.installations.paths=\
$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-gamma
```

- Gradle 9.3.1 via the wrapper, Fabric Loom 1.15, Kotlin DSL.
- Java 21 runs Gradle, Java 17 compiles the mod (`java = 17` in `gradle/libs.versions.toml`).
  The `-Dorg.gradle.java.installations.paths` flag points Gradle at an existing JDK 17
  instead of downloading one; change it to your own JDK 17 if it differs.
- BTA 8.0.1 comes from `https://downloads.betterthanadventure.net/bta-client/release/v8.0.1/manifest.json`,
  so the first build needs network access (~60 MB) and the Minecraft client jar it lists.
- Output: `build/libs/eightbitdo-1.0.0+8.0.1.jar` (plus a `-sources` jar).

No refmap is generated because the mixin is `remap = false`; BTA ships unobfuscated
classes. Mixin targets are checked by the compiler, so a renamed `initGLFW`/`poll` breaks
the build instead of failing at runtime.

## Verification harness

`tools/verify.fish` compiles `MappingStore` and `EightBitDoSupport` **with no Minecraft on
the classpath** against the same LWJGL 3.3.3 jars BTA uses (taken from PrismLauncher's
libraries), runs `tools/VerifyMappings.java`, and asserts the real pad flips from
`isGamepad=false` to `isGamepad=true`:

```fish
set -x JAVA_HOME ~/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta
tools/verify.fish
```

It prints every joystick GLFW sees, the resolved mapping (`kind`, name, body and complete
mapping line), the gamepad state readback, and calls `registerMissingGamepads()` a second
time to prove nothing is registered twice. It also calls `javap` on the instance's
`bta-v8.0.1.jar` and fails if `InputHandler.initGLFW`/`poll` no longer exist, and — when
`BTA_JAR` points at that jar — feeds BTA's whole bundled database to GLFW afterwards to
prove the extra mapping survives (the order `InputHandler.initGLFW` actually uses).

Exit codes: `0` pass, `1` assertion failed, `2` GLFW failed to start, `3` no 8BitDo pad
attached. Compiled classes go to `tools/build/` and are gitignored.

The harness needs `DISPLAY` or `WAYLAND_DISPLAY` (GLFW initialises a display connection
even though no window is created). SLF4J has no provider on the harness classpath, so the
mod's log lines are silenced there; the harness prints the same information itself.

## Mapping data

`src/main/resources/eightbitdo-mappings.txt` is generated, not hand-written:

```fish
java tools/GenerateMappings.java \
    <SDL_GameControllerDB snapshot>.txt \
    "/path/to/bta-v8.0.1.jar" \
    src/main/resources/eightbitdo-mappings.txt
```

It merges the upstream [SDL_GameControllerDB](https://github.com/mdqinc/SDL_GameControllerDB)
with the snapshot bundled in BTA 8.0.1, keeps only entries whose USB vendor id is `0x2dc8`
(8BitDo) and deduplicates by GUID. That is 210 entries today, 72 of them `platform:Linux`.
A newer snapshot can be regenerated at any time and dropped in without touching code.

Resolution at runtime, in order:

1. exact GUID match;
2. same bus + vendor + product, preferring `platform:Linux`, then the same firmware
   revision;
3. same vendor + product, same preference;
4. a hard-coded standard XInput body, so a brand-new 8BitDo pad whose GUID appears nowhere
   still works.

Only pads whose GUID vendor id is `0x2dc8` are touched; every other controller is left
exactly as BTA/GLFW configured it. Joysticks with fewer than two axes are skipped as well:
the UM 2 receiver, for instance, also exposes its keyboard and mouse HID interfaces as
single-axis "joysticks", and GLFW never accepts those as gamepads. A mapping is only
counted as registered once `glfwJoystickIsGamepad()` actually returns true for the device.

## Limitations

- The mixin hooks BTA internals (`InputHandler.initGLFW` and `InputHandler.poll`). A BTA
  version that renames or restructures them fails fast: the build breaks on the missing
  target, and a runtime mismatch makes Mixin log an injection error. Nothing else in BTA
  is modified.
- Mappings are a snapshot of SDL's database plus BTA's. A pad (or revision) absent from
  both falls back to the generic XInput body, which is right for pads in XInput mode but
  cannot invent bindings for exotic layouts (arcade sticks, N64 pads, paddles).
- Only 8BitDo hardware (vendor `0x2dc8`) is handled. Other 8BitDo-labelled devices that
  report a different vendor id are ignored, and so are the many non-8BitDo pads in the same
  database.
- The on-screen type stays `(UNKNOWN)`; glyphs are the Xbox 360 set.
- The `platform:` field is only used to prefer Linux entries; GLFW itself ignores it, so it
  is stripped from the line handed to GLFW.

## Tested with

- BTA 8.0.1, fabric-loader `0.18.4-bta.11`, LWJGL 3.3.3 (the GLFW native bundled by
  LWJGL reports itself as 3.4.0-dev).
- 8BitDo Ultimate 2 Wireless through an 8BitDo UM 2 Receiver, USB `2dc8:310b`, driver
  `xpad`, GUID `03000000c82d00000b31000000010000`.
- `tools/verify.fish`: the pad goes from `isGamepad=false` to `isGamepad=true`, resolves
  as `SAME_BUS_VENDOR_PRODUCT` to the SDL `8BitDo Ultimate 2` mapping, keeps that mapping
  after BTA's own 594 KB database is loaded, and a second scan registers nothing.

## Credits and license

- Mod code: MIT, see `LICENSE`.
- Controller mapping data: [SDL_GameControllerDB](https://github.com/mdqinc/SDL_GameControllerDB)
  (zlib license, Copyright (c) 1997-2024 Sam Lantinga), merged with the database bundled in
  BTA 8.0.1. The zlib notice is reproduced in `LICENSE` and at the top of
  `eightbitdo-mappings.txt`.
- Project scaffold: [Turnip-Labs/bta-example-mod](https://github.com/Turnip-Labs/bta-example-mod)
  (branch `8.0`).
