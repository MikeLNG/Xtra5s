# Xtra5s (experimental sibling of Xtra5)

Personal fork of official Xtra (crackededed/Xtra) with custom features, started from Xtra5
(prime385/Xtra5) 1.48.1 so Xtra5 itself stays untouched. Xtra5s is its own app
(`com.prime385.xtra5s`, app name "Xtra5s") and installs next to Xtra5. Never push to
prime385/Xtra5 from here. Work happens on the `custom-xtra5` branch (the default branch).

## Working rules

- **Always confirm the plan first.** Before writing or changing any code, describe the planned
  changes (what, where, and behaviour) and wait for the owner's explicit go-ahead.
- Once a new version is built (and the plan was approved), publish it right away: merge it into
  `custom-xtra5` (which publishes the release) without waiting for the owner to test. Also send
  the APK and `xtra5s-Custom-Changelog.txt`.
- Never open pull requests to, or push to, the official Xtra repository.

## Keeping upstream merges clean

- Put custom logic in `app/src/main/java/com/github/andreyasadchy/xtra/custom/`.
- Keep edits to upstream files to small, clearly marked hooks (`// Xtra5 custom`) or blocks
  appended at the end of a file. Custom strings go in `res/values/xtra5_strings.xml`.
- Commits must be authored as `prime385`: the build uses the newest non-prime385 commit to know
  which official Xtra version it is based on.

## Safety checks (run by the release workflow before anything is pushed or published)

- `.github/xtra5s-hooks.sh` checks that every hook in official files (lines marked
  `Xtra5 custom` / `Xtra5s custom`, or calling into the custom package) is still there, using
  the counts in `.github/xtra5s-hooks.txt`. After adding or removing hooks on purpose, run
  `.github/xtra5s-hooks.sh --update` and commit the list.
- `.github/xtra5s-merge.py` does the daily merge: if official changes clash with Xtra5s
  changes, it takes official Xtra's code and puts the Xtra5s change back on top, per clashing
  spot, when the two changed different lines (or official only lightly edited / added lines
  next to the Xtra5s change). If official rewrote, moved or deleted the code a change plugs
  into, it aborts and nothing is published (resolve by hand). Re-applied files are noted in
  that build's changelog entry.
- `app/src/test/.../custom/Xtra5sLogicTest.kt` tests the custom features' logic
  (`./gradlew testDebugUnitTest`). Keep custom logic that can be tested free of Android calls
  at load time, and add tests for new logic.

## Versioning and releases

- Version is `MAJOR.FEATURE.FIX`, set by `val xtra5Version` at the end of `app/build.gradle.kts`
  (versionCode = MAJOR*10000 + FEATURE*100 + FIX). New features bump FEATURE.
- Every new version needs an entry at the top of `XTRA5_CHANGELOG.txt` (same format as the
  existing entries: a `========================================` separator line, then
  `Xtra5s X.Y.Z (date)`, `Based on official Xtra <sha>`, then New / Changed / Fixed /
  Behind the scenes sections). Send the full history as `xtra5s-Custom-Changelog.txt` (always this exact name, so the owner
  can save over the same local file) alongside every APK sent to the owner.
- Keep `XTRA5_SUMMARY.txt` up to date when a version adds or substantially changes a major
  feature. It is a feature list for a prospective user: grouped bullet points, a sentence or two
  each (sub-points for complex features), benefits rather than internals (no build/merge/email
  details), no minor fixes. Send it as `xtra5s-Custom-Changelog_summary.txt` alongside every APK sent to the owner.
- `.github/workflows/xtra5.yml` merges official Xtra daily, bumps FIX, builds and publishes a
  release (tag `xtra5s-vX.Y.Z`) with the APK and `xtra5s-Custom-Changelog.txt` (full history; the release description is
  that version's entry; it writes the entry itself for automatic builds);
  the in-app updater installs releases from this repo.
