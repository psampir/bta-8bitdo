# Logo

Original artwork for 8BitDo Controller Support.

| File | Use |
|---|---|
| `icon.png` | Primary icon (identical to `icon-dark.png`), used in the README and on Modrinth |
| `icon-dark.png` | 512×512, dark slate background — recommended: reads on light *and* dark UIs |
| `icon-white.png` | 512×512, white background, closest to the supplied reference |
| `icon-transparent.png` | 512×512, RGBA with a transparent background |
| `preview-64-*.png` | 64×64 previews used to check legibility at icon size |

## Design

Two lines of type, stacked and centred: **BTA** in grey metal with a bevel, hairline
cracks and a grey extrusion, and **8BitDo** in white with a heavy black outline and a dark
extrusion. Both sit on a black outline so they stay readable on any background.

- **Fonts:** [Anton](https://fonts.google.com/specimen/Anton) for BTA and
  [Bungee](https://fonts.google.com/specimen/Bungee) for 8BitDo, both under the
  SIL Open Font License. The font files are *not* committed; download them into a
  directory and point the generator at it:

  ```fish
  mkdir -p /tmp/opencode/fonts
  curl -L -o /tmp/opencode/fonts/Anton-Regular.ttf \
      https://github.com/google/fonts/raw/main/ofl/anton/Anton-Regular.ttf
  curl -L -o /tmp/opencode/fonts/Bungee-Regular.ttf \
      https://github.com/google/fonts/raw/main/ofl/bungee/Bungee-Regular.ttf
  python3 docs/logo/generate.py --fonts /tmp/opencode/fonts
  ```

  The generator renders at 1024×1024 and downsamples to 512, and picks the largest font
  size that still fits the margins.

- **Not the official logo.** This is an original approximation: the 8BitDo wordmark is
  typeset in Bungee (a caps-only display face, so the lower-case letters read as caps) and
  given its own metal/3D treatment. No official 8BitDo or Better than Adventure! artwork,
  wordmark or font is used.

- **Backgrounds:** white is flat; the dark variant is a procedural slate gradient with
  value noise, a soft top light and a vignette; the transparent variant has no background
  at all.

## Disclaimer

Not affiliated with 8BitDo or the Better than Adventure! team. "8BitDo" and "Better than
Adventure!" are used descriptively; all trademarks belong to their respective owners.
