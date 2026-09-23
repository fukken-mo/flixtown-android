# Real seek-preview thumbnails

Status as of 0.9.11: implemented, using server-generated (not on-device)
trick-play thumbnails. This file records the investigation and design
decisions so they aren't re-litigated later.

## What was tried and rejected

0.9.9 added on-device frame extraction via `MediaMetadataRetriever` (one
reused retriever per playing item, single-threaded serialized dispatcher,
byte-sized LRU cache, 2.5s per-frame timeout, VOD-only). It worked in
principle but failed real-TV testing: preview boxes sat visibly empty for
too long before a frame appeared. Removed entirely in 0.9.10 and not
attempted again — local decode competes with the single hardware video
decoder most Android TV boxes have and the same network path already
streaming the main video, which is structural, not a tuning problem.

## Why the source has to be server-side

Neither reference offers real preview data: IBO's own player (decompiled
directly — layout XML + dex strings, not assumed) uses a stock `SeekBar`
with no wired trick-play mechanism, and Xtream's standard `player_api.php`
schema has no sprite-sheet/VTT-thumb-track/preview-image fields. So frames
have to be generated once, offline, and served as small static images.

## Where generation actually runs, and why

The obvious first candidate — running FFmpeg directly on Flix Town's own
panel host (flixtown.panelsandapps.com) — could not be verified. This
session has no shell/SSH access to that host, no confirmation of its
hosting tier (shared cPanel vs. VPS), and no git repo for its PHP source
(this project's own history packages it as a `flixtown-cpanel-backend.zip`
for manual upload, not a deployed-from-git app), so claiming FFmpeg runs
there would have been a guess, which was explicitly ruled out.

Instead, generation runs on **GitHub Actions** (`.github/workflows/
generate-trickplay.yml`) — infrastructure already proven to work for this
exact repo (it's what builds every release APK). An admin manually runs it
per title (Actions → "Generate Trick-Play Thumbnails" → Run workflow) with
the content id and the Xtream VOD URL:

1. `ffprobe` reads the source duration.
2. `ffmpeg -vf "fps=1/10,scale=240:135"` extracts one 240×135 WebP frame
   every 10 seconds directly from the remote URL — no full download, no
   re-encode of the whole file.
3. A small inline Python step builds `manifest.json` (interval, duration,
   and a `{timestampSeconds: imageUrl}` map).
4. Both are committed to a dedicated `trickplay-assets` branch (never the
   app's default branch) under `trickplay/<content_id>/`.

## Where the files are served from

`trickplay-assets` is a plain branch in this same GitHub repo, served
through **jsDelivr's free GitHub CDN**
(`cdn.jsdelivr.net/gh/<owner>/<repo>@trickplay-assets/...`) — a globally
cached HTTPS CDN that needs nothing from Flix Town's own hosting. The
manifest itself carries full absolute URLs (per the "don't make the app
guess URLs" requirement), so the Android client never constructs a
thumbnail path itself.

**This is a stopgap, stated honestly:** committing binary images to a git
branch grows repo size over time and jsDelivr's cache/rate limits are fine
for this traffic pattern but not unlimited. If/when the real panel
infrastructure is confirmed (VPS with shell access, object storage, a
proper `get_preview_index` endpoint), only the generation workflow's
publish step and the manifest's URLs need to change — the Android client
already just follows whatever URLs the manifest gives it, so no app
changes would be required to move storage later.

## Manifest format

```json
{
  "contentId": "movie_12345",
  "interval": 10,
  "width": 240,
  "height": 135,
  "durationMs": 5900000,
  "frames": {
    "0": "https://cdn.jsdelivr.net/gh/OWNER/REPO@trickplay-assets/trickplay/movie_12345/000000.webp",
    "10": ".../000010.webp"
  }
}
```

Content id scheme (must match on both sides — see `trickPlayContentId()`
in `PlayerScreen.kt`): `movie_<streamId>` for movies,
`series_<seriesId>_s<season%02d>e<episode%02d>` for episodes.

## Android client

`TrickPlayRepository.fetchManifest(contentId)` (`data/
TrickPlayRepository.kt`) does one GET per playback session; a 404 (not
generated yet), malformed JSON, or any network failure resolves to `null`
rather than throwing — the player never depends on this succeeding.
`PlayerScreen`'s seek overlay renders each of the 5 filmstrip slots via
Coil's `AsyncImage` (the same image-loading path already used for
posters/cast photos, so caching/background decoding come for free — no
custom bitmap cache needed) when the manifest has that bucket, and falls
back per-slot to the existing timestamp-only card otherwise.

## Storage estimate (1-hour title, 10s interval, 240×135 WebP)

360 frames × roughly 3–8KB per frame at this tiny resolution/quality ≈
**2–4MB per hour of content** — not measured against a real encode yet
(no Xtream VOD URL was available in this session to run the workflow
against), stated as an estimate based on typical WebP compression ratios
at this size, not a guarantee.
