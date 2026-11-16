# Logo

| File | Purpose |
|---|---|
| `bta-source.png` | The original artwork supplied by the project author: 127×122 RGBA, transparent background |
| `icon.png` | 512×512 RGBA project icon, derived from `bta-source.png` |
| `preview-64.png` | 64×64 copy used to check legibility at icon size |

## How `icon.png` is made

The source is trimmed of its transparent margins, upscaled to fit 460×460 with a Lanczos
filter plus a mild unsharp mask, and centred on a 512×512 transparent canvas:

```fish
magick docs/logo/bta-source.png -background none -trim +repage \
    -filter Lanczos -resize 460x460 -unsharp 0x1+0.6+0.02 \
    -background none -gravity center -extent 512x512 \
    -strip -define png:compression-level=9 docs/logo/icon.png

magick docs/logo/icon.png -filter Lanczos -resize 64x64 \
    -strip -define png:compression-level=9 docs/logo/preview-64.png
```

The background stays transparent on purpose: the black outlines keep the lockup readable on
both light and dark UIs. `icon.png` is 245 012 bytes, inside Modrinth's 256 KiB icon limit.

## Disclaimer

Not affiliated with 8BitDo or the Better than Adventure! team. "8BitDo" and "Better than
Adventure!" are used descriptively; all trademarks belong to their respective owners.
