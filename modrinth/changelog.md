## 1.0.1+8.0.1

Mod Menu metadata only, no gameplay changes.

- **Mod list icon:** the mod now ships a square 256×256 icon (`icon` in `fabric.mod.json`), so Mod Menu shows the project logo instead of the generic placeholder.
- **Website** button in Mod Menu now opens the Modrinth page.
- **Issues** button opens the GitHub issue tracker, and the sources link points at the repository.

## 1.0.0+8.0.1

First release, for Better than Adventure! 8.0.1 (Babric).

**Controller support**

- Registers a GLFW gamepad mapping at runtime for every attached 8BitDo pad, built from the GUID the device reports, before BTA counts controllers — `0 controllers found!` becomes `1 controllers found!` for pads missing from BTA's bundled database or running a different firmware revision.
- 210 8BitDo entries merged from SDL_GameControllerDB and BTA's own database, resolved by exact GUID → same bus/VID/PID → same VID/PID → standard XInput fallback.
- Hot-plug: pads attached or woken after start are picked up within a few frames.

**Button layouts**

- New `Layout` button in BTA's controller setup screen: `AUTO → XBOX 360 → NINTENDO SWITCH → AUTO`.
- The choice is stored per device in `config/eightbitdo.properties` (`guid:`, `name:` and `vid:pid` rules, `auto` to opt out) and applies immediately.
- Glyphs only: input is never remapped.

**Notes**

- Requires a Babric BTA 8.0.1 instance and Java 17+; client-side only.
- Tested with an 8BitDo Ultimate 2 Bluetooth in 2.4G/XInput mode (`2dc8:310b`, GUID `03000000c82d00000b31000000010000`) on 2026-11-16.
- The mod hooks BTA internals, so a future BTA update may need matching changes.
