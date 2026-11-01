# Profiles upgrade

## Branch and current status

Branch `codex/profiles` starts at `b53a5b70f7d32e7346bc7acbd31a7a8fabc07221`.
It includes the watched-episode badges and one-time customer-notice changes.
Production identity, version defaults, signing and panel settings are unchanged.

This first commit contains the validated profile metadata model, its unit tests,
and 28 optimized avatars supplied by Mo. The model is deliberately not wired to
Home, sign-in, progress or sync yet. No usable profiles APK is being delivered.
No live backend changes have been made. Kids filtering is not implemented.

## Approved artwork

Use the uploaded funny-character PNG pack instead of the generated 3D avatars.
All 28 images are bundled as 384px transparent WebP files with stable IDs
`avatar_01` through `avatar_28`. The original archive and vector sources are not
bundled. `catalog.json` records source PNG checksums for traceability.
Avatar choice never determines whether a profile is a Kids profile.

## Product defaults

- Up to six profiles per account, each with a name and a premade avatar.
- One immutable `default` profile ID, initially named My profile. It can be renamed
  and have its avatar changed but cannot be deleted or converted into a Kids profile.
- Existing watched flags, My List and Continue Watching belong to that default.
- Additional profiles get opaque UUIDs. Renames and avatar changes must not change
  the ID or history. Deleting and recreating a name creates a different profile.
- Names support Arabic and other Unicode text, up to 24 code points. Control and
  direction-override characters are rejected; ordinary language direction is supported.
- Profile metadata must be scoped to the authenticated account, never a global list.

## Required implementation before a test APK

1. Extend the authenticated sync backend to persist profiles and scope progress,
   watched flags and My List by profile. Preserve the old app/default-profile
   protocol so older installed clients cannot overwrite a non-default profile.
   Server authorization derives account ownership from the session, never a client
   account ID. Reject another account's profile IDs. Use revisions for concurrent
   edits and tombstones for deleted profiles to prevent resurrection.
2. Migrate existing local and remote history to default without copying it to other
   profiles. Test upgrade and downgrade behavior. Do not clear existing history.
3. Add the TV profile picker and name/avatar editor, with D-pad focus, Back behavior,
   clear selected state and readable names. Add a Switch profile entry. Never switch
   profiles under a running player or apply a late response to the new profile.
4. Keep playback and network work off the main thread, preserve the existing sync
   cadence, and test account A -> B -> A plus profile A -> B -> A.
5. Implement parental PIN management and expiry-safe parent sessions. A parent PIN
   must not be stored as plaintext. Parent approval is required to leave Kids mode,
   select an adult profile, edit restrictions or remove a restricted profile.
6. Confirm the actual catalog's age-certification/category metadata before enabling
   Kids mode. Use an explicitly approved kids catalog initially. Hide unknown titles.
   Never infer suitability from Animation, star ratings or an avatar. Filter Home,
   search, categories, recommendations, actor pages, Details, direct playback and
   autoplay. Restrict customer requests and notices that could reveal adult titles.
   Enforce policy at the service boundary as well as in the UI. Fail closed when
   policy is unavailable and explain the temporary unavailability.
7. Test real TV D-pad flows, layout at 720p/1080p, large names, offline use, multiple
   devices, deleted-profile late writes, process death during migration, old/new
   clients together and unchanged player performance. Re-run sync and notices checks.

## Remaining notification limitation

The prior notice-once fix is once per account on each installation. It is not a
cloud-wide receipt. If notices must appear only once across all TVs, add account
receipts to the backend and migrate local acknowledgements. Do not label the current
per-installation behavior as cross-device once-only delivery.

## Review and release

Codex implements functionality and APIs. Claude can review the exact completed diff
and TV design after a working Preview exists. No direct Claude communication is
established here. No production build, version bump, merge or publishing is part of
this foundation commit. Provide a compatible backend patch and installation steps
before enabling profiles, then validate migration on Mo's devices before release.
