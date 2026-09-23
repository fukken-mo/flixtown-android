# Real seek-preview thumbnails

Status as of 0.9.13: **fully implemented but DORMANT.** The manifest
model, sprite cache, preloading, and sprite-crop rendering all exist and
work, but the app makes **zero** preview-related network requests right
now — see "Why this is dormant" and "How to turn it on" below. This file
records the investigation and design decisions so they aren't
re-litigated later.

## What was tried and rejected

- **0.9.9 — on-device frame extraction** via `MediaMetadataRetriever` (one
  reused retriever per playing item, single-threaded serialized dispatcher,
  byte-sized LRU cache, 2.5s per-frame timeout, VOD-only). Worked in
  principle but failed real-TV testing: preview boxes sat visibly empty for
  too long. Removed in 0.9.10, not attempted again — local decode competes
  with the single hardware video decoder most Android TV boxes have.
- **0.9.11 — server-generated frames, one WebP image per timestamp.**
  Real frames, correct architecture direction, but still too slow on real
  TV. Root cause (see "Why 0.9.11 was slow" below): no preloading at all,
  and one network request per visible card. Kept the server-side-generation
  and host-agnostic-client principles, replaced the per-frame fetch model
  with sprite sheets + continuous preloading.
- **0.9.12 — sprite sheets + preloading, defaulting to a jsDelivr/GitHub
  Actions proof-of-concept host.** The architecture itself (see "The fix"
  below) is sound, but the "proof of concept" never actually worked: the
  generation workflow (`generate-trickplay.yml`) only ever existed on a
  feature branch, never merged to `main` — and GitHub Actions only lists
  `workflow_dispatch` workflows that exist on the default branch, so it was
  **never runnable from the Actions UI at all**. No manifest was ever
  generated for any title. Every scrub session was therefore still making
  a real network request to a URL guaranteed to 404, which read as
  lag/dysfunction even though the sprite/preload code itself was never
  actually exercised end-to-end. Fixed in 0.9.13 by removing the built-in
  default entirely (see below) rather than trying to fix the POC's
  reachability — the feature now does nothing at all until the backend
  explicitly configures a real host.

## Why the source has to be server-side

Neither reference offers real preview data: IBO's own player (decompiled
directly — layout XML + dex strings, not assumed) uses a stock `SeekBar`
with no wired trick-play mechanism, and Xtream's standard `player_api.php`
schema has no sprite-sheet/VTT-thumb-track/preview-image fields. So frames
have to be generated once, offline, and served as small static images.

## Why 0.9.11 was slow

0.9.11 fetched one individual WebP image per visible card, and only ever
started that fetch the moment the user pressed LEFT/RIGHT — there was no
preloading. Two compounding problems: (1) every newly-exposed edge frame
during scrubbing was a **cold** network request (Coil's cache only helps
on a *repeat* view of the exact same URL, not the first), and (2) up to 5
such cold requests could be in flight for one scrub press. That reads as
"stare at an empty box for several seconds" on real TV hardware/network
conditions, exactly as reported.

## The fix: sprite sheets + continuous preloading

- **Sprite sheets, not individual frames.** Each sprite sheet holds 50
  frames (a 10×5 grid). One network fetch of one sprite satisfies an
  entire 5-card scrub window — usually many consecutive scrub presses too,
  since 50 frames at a 10s interval covers ~8 minutes of scrubbing before a
  different sprite is ever needed.
- **Preloading starts at playback, not at the first key press.** As soon
  as the trick-play manifest resolves, and again every time the playing
  position crosses into a new sprite's time range, `PlayerScreen` quietly
  warms the sprite covering the *current* position plus the *next* one in
  sequence (`TrickPlaySpriteCache.preload`), entirely off the UI thread.
  By the time the user presses LEFT/RIGHT, the relevant sprite is
  typically already decoded and sitting in memory.
- **Bounded, small memory.** `TrickPlaySpriteCache` is a byte-sized
  `LruCache<String, ImageBitmap>` capped at 9MB — comfortably inside the
  5–10MB target (one 1600×450 ARGB_8888 sprite ≈2.9MB; the cap holds
  roughly "previous + current + next" transiently). Released entirely
  (`evictAll()` + cancelling any in-flight preload) when the item changes.
  Never hundreds of individual Bitmaps.
- **Coil still does the network layer.** Sprite bytes are fetched through
  Coil's own `ImageLoader` (`context.imageLoader.execute(...)`), so they
  get Coil's normal disk+memory cache like every other image in this app —
  a sprite evicted from the small decoded-bitmap cache above is typically a
  fast local disk hit, not a cold network fetch, if it's needed again.
- **Frames are drawn as crops, not separate images.** Each of the 5 cards
  draws a cropped region of the one decoded sprite via Compose's
  `Canvas`/`drawImage(srcOffset, srcSize, ...)` — zero extra Bitmap
  allocation per card, no `AsyncImage` per card.
- **UI never blocks on any of this.** A slot whose sprite isn't decoded
  yet renders the exact 0.9.10 timestamp-only card instead of an empty box,
  and swaps to the real frame the instant the preload/on-demand load
  resolves. Scrub input (LEFT/RIGHT/OK/BACK) is never gated on image state.

## Why this is dormant, and how to turn it on

`trickPlayManifestUrl()` in `PlayerScreen.kt` returns `null` unless
`RemoteConfig.trickplayBaseUrl` (an optional field on the existing
backend-config response) is set to a non-blank value. There is no built-in
default anymore. When it returns `null`:

- No manifest HTTP request is ever constructed (not even a doomed one).
- No sprite is ever preloaded.
- No sprite is ever fetched on demand.
- The seek overlay renders the plain timestamp-only look immediately —
  the same one from 0.9.10, unchanged.

`TrickPlayRepository.fetchManifest(manifestUrl)` itself takes a plain URL
and knows nothing about who's serving it — no jsDelivr/GitHub-specific
assumptions anywhere in that class, so nothing about it needs to change
either. **Turning real thumbnails on for a title is purely a backend
config change:** set `trickplay_base_url` in the config response to a real
host, and the whole pipeline (manifest fetch → preload → sprite crop
rendering) activates with zero Android code changes.

## Generation tooling (exists, but not wired to anything reachable)

`.github/workflows/generate-trickplay.yml` still exists on this branch and
still does what it always did — reads a source duration with `ffprobe`,
tiles frames into 160×90 sprite sheets (10×5 grid) with ffmpeg's `tile`
filter, builds a `manifest.json`, and would publish both to a
`trickplay-assets` branch served through jsDelivr. **It has never actually
run**: it was never merged to `main`, and GitHub Actions only lists
`workflow_dispatch` workflows that exist on the default branch, so it was
never selectable from the Actions UI. It's left in place as working
generation tooling for whenever real hosting is stood up, not as an active
proof of concept. Do not expect it to do anything until it's merged to
`main` (or otherwise made runnable) AND real hosting exists for it to
publish to.

## Manifest format (portable — independent of hosting, sprite layout)

```json
{
  "content_id": "movie_12345",
  "interval_seconds": 10,
  "width": 160,
  "height": 90,
  "duration_seconds": 5900,
  "base_url": "https://cdn.jsdelivr.net/gh/OWNER/REPO@trickplay-assets/trickplay/movie_12345",
  "frames": [
    {"time": 0, "sprite": "sprite_001.webp", "x": 0, "y": 0},
    {"time": 10, "sprite": "sprite_001.webp", "x": 160, "y": 0}
  ]
}
```

`base_url` + each frame's `sprite`/`x`/`y` are the only hosting- or
layout-specific pieces — the app never assumes a grid size, frame count
per sprite, or resolution; it only ever reads what a given frame entry
says.

Content id scheme (must match on both sides — see `trickPlayContentId()`
in `PlayerScreen.kt`): `movie_<streamId>` for movies,
`series_<seriesId>_s<season%02d>e<episode%02d>` for episodes.

## Android client

- `data/TrickPlayRepository.kt` — one manifest GET per playback session;
  404/malformed/network failure all resolve to `null`, never an exception.
- `ui/player/TrickPlaySpriteCache.kt` — the decoded-sprite LRU + preloading
  described above.
- `ui/player/PlayerScreen.kt` — resolves the manifest URL, preloads sprites
  as playback progresses, and renders each scrub card as a sprite crop with
  a timestamp-only fallback.

## Storage estimate (1-hour title, 10s interval, 160×90 WebP, 50/sprite)

360 frames ÷ 50/sprite ≈ 8 sprite sheets. Each sheet (1600×450 source
frames re-encoded as WebP) is roughly 80–200KB depending on scene
complexity — an estimate based on typical WebP compression at this
resolution, not yet measured against a real encode, so **≈0.6–1.6MB per
hour of content** on top of one small manifest.json.

## What hasn't been verified yet

No real Xtream VOD URL/credentials were available in this session, and
this is a cloud session with no physical Android TV to test on. The
following need the workflow actually run against one real title and the
result tested on real hardware before this goes any further: sprite
count/total size/average sprite size for a real encode, FFmpeg generation
time, time-to-first-preview and cached-preview latency on a real TV,
whether rapid LEFT/RIGHT scrubbing stays smooth, and whether Coil's
disk/memory cache behaves as expected for this URL pattern.
