#!/usr/bin/env bash
#
# Regenerates the launcher icons from logo.png.
#
# The source is a white line-art glyph on a black rounded plate, with transparency
# outside that plate. That last detail is the trap: the obvious pipeline
#
#     magick logo.png -alpha off -colorspace gray -alpha copy ...
#
# turns the transparent surround white when it drops the alpha channel, so the
# following luminance-to-alpha step makes the *plate silhouette* opaque and the glyph
# transparent. The launcher then shows a solid white square. Flattening onto black
# first is what avoids it.
#
# Usage: tools/make-icons.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE="$ROOT/logo.png"
RES="$ROOT/app/src/main/res"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

command -v magick >/dev/null 2>&1 || {
  echo "ImageMagick 7 (magick) is required" >&2
  exit 1
}
[[ -f "$SOURCE" ]] || { echo "no logo.png at $SOURCE" >&2; exit 1; }

echo "==> extracting the glyph"
# Flatten onto black, then use the resulting luminance as the opacity of a pure white
# image. Trimming afterwards gives a tight bounding box to scale from.
magick "$SOURCE" -background black -alpha remove -alpha off -colorspace gray "$TMP/mask.png"
magick -size 2000x2000 xc:white "$TMP/mask.png" -alpha off \
  -compose CopyOpacity -composite PNG32:"$TMP/glyph_full.png"
magick "$TMP/glyph_full.png" -trim +repage PNG32:"$TMP/glyph.png"

# density  legacy-px  adaptive-px
DENSITIES=(
  "mdpi 48 108"
  "hdpi 72 162"
  "xhdpi 96 216"
  "xxhdpi 144 324"
  "xxxhdpi 192 432"
)

for entry in "${DENSITIES[@]}"; do
  read -r density legacy adaptive <<< "$entry"
  mkdir -p "$RES/mipmap-$density" "$RES/drawable-$density"

  # Adaptive layers are 108dp but only the middle 72dp is guaranteed visible, so the
  # glyph is placed at 52% of the canvas to stay clear of every mask shape.
  inner=$(( adaptive * 52 / 100 ))
  magick "$TMP/glyph.png" -resize "${inner}x${inner}" -background none -gravity center \
    -extent "${adaptive}x${adaptive}" PNG32:"$RES/drawable-$density/ic_launcher_foreground.png"

  # Android 13+ themed icons tint this layer, so it is the same silhouette.
  cp "$RES/drawable-$density/ic_launcher_foreground.png" \
     "$RES/drawable-$density/ic_launcher_monochrome.png"

  # Legacy icons are drawn whole and unmasked, so they keep the logo's own plate.
  magick "$SOURCE" -background black -alpha remove -resize "${legacy}x${legacy}" \
    PNG32:"$RES/mipmap-$density/ic_launcher.png"
  magick "$SOURCE" -background black -alpha remove -resize "${legacy}x${legacy}" \
    \( -size "${legacy}x${legacy}" xc:none -fill white \
       -draw "circle $((legacy/2)),$((legacy/2)) $((legacy/2)),0" \) \
    -alpha set -compose DstIn -composite \
    PNG32:"$RES/mipmap-$density/ic_launcher_round.png"

  echo "    $density"
done

echo "==> verifying the adaptive icon is not a blank square"
# A correct foreground is mostly transparent line art. A fully opaque layer means the
# alpha extraction inverted, which is the failure this script exists to prevent.
opacity="$(magick "$RES/drawable-xxxhdpi/ic_launcher_foreground.png" \
  -alpha extract -format '%[fx:mean]' info:)"
awk -v value="$opacity" 'BEGIN {
  if (value > 0.6 || value < 0.02) {
    printf "  error: foreground opacity is %.3f, expected line art\n", value > "/dev/stderr"
    exit 1
  }
  printf "    foreground opacity %.3f, looks like line art\n", value
}'

echo "==> done"
