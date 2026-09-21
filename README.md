# Flix Town

Android TV client for Movies and Series (Chromecast with Google TV, Google TV
Streamer, onn. Google TV, Android TV boxes/TVs), plus its PHP/MySQL control
and activation backend.

## Status: Milestone 1 — foundation, config, auth

This milestone covers only: backend-driven remote config, QR-code device
pairing, manual username/password login, AndroidKeyStore-backed secure
credential storage, an installation UUID, and startup routing (maintenance /
forced update / login / renewal-required / home placeholder). The movie/series
catalog, home screen, and player are **not** part of this milestone.

- `app/` — Android TV client (Kotlin, Jetpack Compose + androidx.tv)
- `backend/` — PHP 8 / MySQL control & activation backend (see
  `backend/DEPLOYMENT.md` for cPanel setup and `backend/SECURITY_CHECKLIST.md`
  for a pre-launch audit checklist)

## Build

Open the Actions tab, choose **Build Flix Town APK**, and download the
`FlixTown-TV-debug` artifact after the green check mark appears.
