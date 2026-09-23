# Future work: real seek-preview thumbnails

Status as of 0.9.10: the player's seek/scrub overlay shows a text-only
timestamp readout (`target / duration`) plus the existing seek bar — no
per-frame images. This is deliberate, not a placeholder oversight.

## What was tried and reverted

0.9.9 added on-device frame extraction via `MediaMetadataRetriever`
(one reused retriever per playing item, single-threaded serialized
dispatcher, byte-sized LRU cache, 2.5s per-frame timeout, VOD-only). It
worked in principle but was rejected after real-TV testing: on actual
Android TV hardware/network conditions, the preview boxes sat visibly
empty for too long before a frame appeared, which read as broken rather
than smooth. It was fully removed in 0.9.10 (`SeekPreviewThumbnailProvider`
deleted, no `MediaMetadataRetriever` usage remains in the player).

## Why local extraction is the wrong long-term approach

Both IBO (the reference app; decompiled and confirmed to use only a stock
`SeekBar` with no wired trick-play mechanism) and Xtream's standard
`player_api.php` schema (no sprite-sheet/VTT-thumb-track/preview-image
fields) offer no server-provided preview data. On-device extraction is the
only local fallback, but it competes with the single hardware video
decoder most Android TV boxes have and depends on the same network path
that's already streaming the main video — exactly the resource that must
not be interrupted for the feature to feel smooth. That tension is
structural, not a bug to be tuned away.

## Recommended design: server-generated trick-play thumbnails

Flix Town's own backend (flixtown.panelsandapps.com) is the right place to
solve this once, offline, rather than repeatedly on weak client hardware —
but it currently has no video-processing capability (no FFmpeg, job queue,
or blob storage) and doesn't hold the source video files, so this is new
infrastructure, not a config change.

- **Trigger:** generate previews once per movie/episode (not per viewing),
  e.g. on first playback request or via a backfill job.
- **Pipeline:** FFmpeg extracts a frame every 10–15s from the source
  stream, resized to ~240×135 (TV-appropriate, not full resolution),
  encoded as WebP or JPEG — either as individually named files or packed
  into a sprite sheet.
- **Storage:** cached server-side (disk or object storage) keyed by
  content id, served over HTTPS.
- **API:** a small endpoint (e.g. `get_preview_index` alongside the
  existing Xtream-proxy/TMDB endpoints) returning the interval and either
  a sprite-sheet URL + tile layout or a list of per-timestamp thumbnail
  URLs for a given stream/episode id.
- **Client:** the app downloads only that small preview index + images
  (never decodes video for this), caches them on disk keyed by content id,
  and renders them in the seek overlay using the same fallback hierarchy
  already built into the player (real frame → lightweight placeholder →
  today's timestamp-only card) so a title with no generated previews yet
  degrades to the current clean text-only UI instead of breaking.

No implementation of this is included in 0.9.10 — this file exists so the
design isn't re-litigated from scratch next time it's picked up, and
because building it touches the backend/panel repo, which is outside this
Android repo's scope.
