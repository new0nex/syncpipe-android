# SyncPipe Roadmap

SyncPipe is an unofficial fork based on PipePipe and NewPipe. The project direction is practical: keep the useful PipePipe features, learn from NewPipe's stability work, and add private user-owned sync over time.

## Upstream Strategy

### PipePipe Track

PipePipe is the functional base for SyncPipe.

We keep watching PipePipe for:

- YouTube extraction fixes
- SABR/playback breakage fixes
- login/cookie playback behavior
- SponsorBlock and Return YouTube Dislike behavior
- player and downloader fixes
- Android compatibility updates

### NewPipe Track

NewPipe is the maturity reference.

We watch NewPipe for:

- cleaner architecture and release discipline
- extractor and playback stability fixes
- Android platform compatibility fixes
- database and migration safety
- reproducible release practices
- UI/UX behavior that is less surprising than PipePipe

NewPipe does not officially include SponsorBlock or account-based playback, so SyncPipe should not blindly switch base. The goal is to borrow good engineering ideas while keeping PipePipe's practical feature set.

## Current SyncPipe Additions

Implemented and tested:

- SyncPipe app/package branding
- GitHub release update checks for SyncPipe releases
- player setting for double-tap seeking
- player setting for playback speed controls
- hidden tempo/pitch/skip-silence controls unless enabled by setting
- visible fast-forward controls hidden
- long-press/swipe speed changes disabled
- unified YouTube Takeout import entry
- YouTube watch history import
- YouTube playlist import
- YouTube subscription import
- donation/community/wiki cleanup for the public fork

## YouTube Import Status

The Takeout importer is functional and has passed clean import and re-import testing with a real Takeout archive.

Verified behavior:

- history imports unique videos without duplicate rows
- repeated watches are preserved through repeat counts
- re-importing does not duplicate history
- private/export-only playlists become local playlists
- subscriptions import from the same Takeout ZIP
- re-importing subscriptions keeps existing channels stable

Known polish before calling it public-release complete:

- add clearer final wording for repeated watches versus unique videos
- improve warning text for skipped/unparseable Takeout rows
- document exactly which Takeout files are supported
- add failure messages for wrong ZIPs or missing YouTube data
- test more Takeout language/date formats
- test public/unlisted online playlist bookmarks with more examples
- consider metadata refresh for playlist-only videos that have placeholder titles
- add focused unit tests around parsing and idempotency

## Release And Update Roadmap

Next release work:

- bump SyncPipe version before the first public APK release
- build signed release APKs locally for the first release
- upload ABI APKs to GitHub Releases
- verify update notifications from an older installed build
- keep GitHub Actions debug builds unsigned and short-lived

Later release automation:

- add a manual release build workflow
- decide whether signing remains local or moves to GitHub Actions secrets
- add checksum files to every release
- document signing certificate fingerprint in README

## Beyond PipePipe

The long-term SyncPipe feature is private sync across the same user's devices.

Design direction:

- end-to-end encrypted user data
- server acts only as a pipe for encrypted blobs
- no server-readable watch history, playlists, subscriptions, or settings
- local-first operation when offline
- explicit export/import fallback before network sync exists

Potential sync scope:

- subscriptions
- watch history
- playlists
- playback progress
- app settings
- SponsorBlock/preferences state where appropriate

## Near-Term Priorities

1. Finish release/update polish.
2. Finish YouTube Takeout import polish.
3. Create first signed public release.
4. Audit NewPipe/PipePipe differences that matter for stability.
5. Plan the E2E sync data model before writing server code.
