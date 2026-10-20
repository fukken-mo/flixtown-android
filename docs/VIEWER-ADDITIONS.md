# Viewer additions (Preview only)

Based on the approved single-row episode controls at 8c68bf9.
No production package/version/signing or live backend changes.

- Player Options: Up from the action row; Down returns to Play.
- Episodes: fetch the existing authenticated series-details endpoint only on demand,
  then choose season/episode. Playback retains its paused/playing state. The chosen
  episode updates Continue Watching immediately. The player snapshot stays bounded
  to 200 episodes on each side, including after a jump to a distant season. Existing
  queue-boundary limitations remain; this does not implement infinite autoplay.
- Speed: 0.75, 1, 1.25, 1.5; saved on this TV.
- Subtitles: the existing subtitle picker now remembers enabled/language choice.
  Options adds four sizes and four appearances. Fixed safe-area positioning is
  unchanged. Original track style remains the default. Bitmap captions may retain
  their encoded appearance; the player cannot recolor their pixels.
- Watched: movie/episode in Player Options; hold OK on a movie's Watch button or
  an episode card in Details. Episode cards display a check. Completion records a
  watched flag too. Manual toggles do not erase saved progress or My List.
- Request-ready notices: evaluated on opening/refreshing My Requests, using that
  customer's existing history and per-request state, including requested seasons.
  Notices are shown once on availability, not on approval. No push service, polling,
  startup dependency, new permission, or backend deployment is required. Notices
  are not delivered while the app is closed or outside My Requests.

Watched flags and request notice history are personal data removed on account
switch; playback preferences remain device settings, as before. No new credential
storage or external admin API is added.

Tests: ViewerOptionsTest, existing EpisodeNavigationTest/unit suite, extended
720p/API28 and 1080p/API31 episode controls flow, existing Requests flow plus
availability-notice and repeat-visit checks. Consult the exact CI run for results.
