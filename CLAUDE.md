# Honmani – notes for Claude

Android reader for Japanese books with a tap-to-look-up pop-up dictionary, saved words, Anki
cards, reading statistics, OPDS book servers and KOReader progress sync. ッツ Ebook Reader (ttu)
renders the books in a WebView; everything around it is Honmani's own Kotlin + Compose code.
Published by CNFSD Labs under GPL-3.0-or-later. `README.md` is the overview,
`docs/device-testing.md` the phone checklist.

**Product principle:** works without setup: install, get a dictionary with one tap, open a book,
tap words. Anything configurable has defaults and stays optional.

**Maintainer's notes:** current state, open tasks, devices and publishing are in the private
repository `cnfsd-labs/notes`: `CLAUDE.md` (shared by all projects), then `honmani/CLAUDE.md`.
Read both first when you have access
(`gh api repos/cnfsd-labs/notes/contents/<path> --jq .content | base64 -d`). This
repository is public: never copy those notes, personal details or discussions into it.

## Decisions (don't relitigate without new facts)

- **Licences.** Honmani is GPL-3.0-or-later, "© 2026 CNFSD Labs"; no personal names in the app,
  docs or commits (author "CNFSD Labs"). Code or data may be copied from GPL-3.0+, LGPL-3.0,
  Apache-2.0, MPL-2.0, MIT, BSD, ISC, 0BSD or CC0 projects; not GPL-2.0-only, "non-commercial" or
  unlicensed. AGPL-3.0 (e.g. Readest): ask the maintainer first. When copying: keep the notice in
  the file, name the source (repository, path, commit), add it to the credits in `AboutScreen`
  (and its licence text if new), mention it in the commit message.
- **ttu** is a pinned submodule built by `:app:buildTtu` with `third_party/patches/` applied.
  Keep patches minimal; prefer scripts injected from `app/src/main/assets/honmani/`. Patches 0001
  and 0003 are proposed upstream (ttu-ttu/ebook-reader#549, #548): drop them once merged.
- **Reader origin** `https://appassets.androidplatform.net` (`WebViewAssetLoader`) must never
  change: ttu's library lives in its IndexedDB. The WebView blocks network loads.
- **Network use** (dictionaries, fonts, book servers, progress sync, Google voices) is listed in
  `docs/privacy-policy.md`, which must change with any new network use.
- **Library:** Honmani owns the book files (app storage, named by SHA-256); ttu's IndexedDB is a
  rebuildable cache fed through ttu's own import input (`library-bridge.js`).
- **Database:** one Room database, **schema frozen** (version 1 in `app/schemas`). A change needs
  a new version, a migration in `HonmaniDatabase.MIGRATIONS`, a test in `DatabaseMigrationTest` and
  the exported JSON committed. No destructive fallback. Manual DI in `AppContainer`, no Hilt.
- **Android backup** keeps the `settings` preferences and `files/auto-backup/` (`AutoBackup`:
  the Settings backup zip, rewritten at most every 6 h when the app goes to the background,
  dropped over 10 MB; offered once on a new install and not overwritten until answered).
  Everything tied to this phone (first start, encrypted keys, AnkiDroid ids, sync account) is in
  `device` preferences, never backed up (`BackupRulesTest`).
- **Application ID** `app.honmani.reader` is permanent. Play bundles come from the "Release
  bundle" workflow (tag `release/*`; run number = version code); other builds stay unsigned or
  debug-signed.
- **Versions:** AGP 9.4 with built-in Kotlin (no `org.jetbrains.kotlin.android`), Gradle 9.8,
  Kotlin 2.4.21, KSP 2.3, compileSdk 37, targetSdk 36 (raise deliberately). Kotlin and KSP move
  together.
- **Reader settings:** ttu's localStorage is the only store, so Honmani's screen and ttu's own
  settings page never disagree. The theme follows the system until the reader picks one
  (`honmaniAutoTheme`).
- **Lookup** is tap-based, scan length 20, longest match first, except that an entry the book's
  furigana contradicts ranks below the entries it agrees with (誰が with だれ is 誰, not 誰が
  たが); deinflection rules are Honmani's own (`lookup/Deinflector`). Glossaries are always stored deflated (`GlossaryPacker`).
- **Saved words** are recorded on every lookup, ranked by a decayed score (half-life 14 days),
  never deleted for age. "Learned", not "Known". Anki cards are suggested, never pushed.
- **Spelling variants** (`SpellingVariants`): forms with kanji, two characters or more, that one
  dictionary entry (Yomitan sequence) lists together count as one word for marks, book coverage
  and word progress; single kanji are left out (生 is also うぶ, under 初). The vocabulary
  estimate and grading keep exact forms (fitted on them). Cached in `files/spelling-variants.txt`
  per dictionary set.
- **Anki:** own client for AnkiDroid's content provider (not the LGPL library); note type and
  deck "Honmani"; `.apkg` (schema 11, fixed note type id, guid from the word) or a text file for
  Anki on a computer. The Google Cloud key is Keystore-encrypted and only sent in
  `X-Goog-Api-Key`.
- **Credentials and servers:** OPDS 1.x only, own parser and `HttpURLConnection` client.
  Passwords are AES-GCM with a Keystore key (not androidx security-crypto). A login only goes to
  the server's own origin; redirects are followed by hand. Plain http and user CAs are allowed for
  home servers, with a warning. Over http a login only goes after the server's 401, per server and
  per Android network, and never on a captive-portal hotspot (`data/net/`).
- **Progress sync:** KOReader's protocol, EPUB only, document id = KOReader's partial MD5,
  exact positions (`EpubPositions` follows ttu's character counting). Calibre-Web Automated wants
  its web password as HTTP Basic at `<address>/kosync`. No account creation in Honmani (Play would
  require account deletion; the protocol has none).

## Build, test, CI

- Prerequisites: JDK 21, Android SDK 37, Node 24 + pnpm 12, `git submodule update --init`.
- `./gradlew lintDebug testDebugUnitTest assembleRelease assembleInternal assembleDebug`, then
  `scripts/check-apk.sh <apk>`.
- `cd web-tests && npm ci && npx playwright install --with-deps chromium && npx playwright test`
  (needs `:app:buildTtu` output, or `TTU_DIR=...`). Playwright has no Chromium build for Ubuntu
  26.04 yet: set `PLAYWRIGHT_HOST_PLATFORM_OVERRIDE=ubuntu24.04-x64` for install and run.
- Unit tests merge the app's assets, so even `testDebugUnitTest` runs `:app:buildTtu` (Node and
  pnpm needed; `-x :app:buildTtu` fails).
- Lookup against a real dictionary: `scripts/lookup-coverage/export_terms.py <honmani.db>` (from a
  debug build), then `LookupCoverageCheck` taps through the sample books and writes
  `app/build/lookup-coverage/report.txt` (skipped without the export, so CI skips it).
- CI (`.github/workflows/android.yml`) runs all of this on every push and cancels the running
  build: don't push while someone waits for an APK.
- **Test APKs:** artifact `honmani-test-apks` of a green run → `app-internal.apk`. Every run signs
  with a new key, so the old build must be uninstalled first (deletes dictionaries). Give the
  direct link `…/actions/runs/<run>/artifacts/<id>`. Local builds use the local debug key.
- Cloud sandbox (Claude Code on the web): Google Maven is blocked, so CI is the only Android
  compiler. Pure-Kotlin code can be tested in a throwaway JVM Gradle project; ttu and Playwright
  run with Node 24 (`~/node24`) and the preinstalled Chromium. Linux's default CJK font has broken
  vertical metrics: tests use `IPAGothic`.

## Lessons learned (each cost a CI or device round trip)

- Changing an interface: update **every** implementation, including test fakes
  (`grep -rn ": InterfaceName"`; Room DAOs and `DictionaryImportScheduler` have fakes).
- Unit tests compile against `android.jar`: no `com.sun.net.httpserver` (tests use a small
  `ServerSocket` server, `HeaderServer`). JUnit rejects test methods with a return value: a
  `= runBlocking { … }` test must end in a statement.
- Android's ICU regex is stricter than the JVM's: always escape `{` and `}`; parse bridge
  messages as JSON, not with regexes.
- Lint fails on a literal BOM in source: write such characters as escapes.
- Assert the part of a UI state a test is about, not the whole object.
- Room in-memory test DBs need `allowMainThreadQueries()` for raw queries. `MigrationTestHelper`
  fails on Windows (path separators); it passes on Linux CI.
- AAPT drops asset directories starting with `_` (ttu's `_app/`): overridden in
  `app/build.gradle.kts`, guarded by `check-apk.sh`.
- `git apply` inside this repo silently skips paths for a work dir under it: `buildTtu` sets
  `GIT_CEILING_DIRECTORIES`. Kotlin block comments nest: never write `/*` inside KDoc.
- Android WebView differs from desktop Chromium (e.g. blank vertical pages without an explicit
  height). Reproduce in Chromium by forcing the reported condition, fix, add a regression test.
  Test with realistic books (paragraphs longer than a screen, mobile emulation, DPR 4).
  `Range.getClientRects()` gives inline boxes, not lines.
- Chromium's back skips history entries added without a tap in the page: history steps go
  through `history.go` in the page, not `WebView.goBackOrForward`.
- ttu cancels clicks on `<ruby>`: the tap bridge listens in the capture phase.
- WebView methods must run on the main thread: never call `BookAdder.add` / `TtuLibrary` inside
  `withContext(IO)`.
- ttu reads TXT as UTF-8 or Shift_JIS only and drops furigana: `TextEncoding` converts text to
  UTF-8 and Aozora texts become EPUBs (`AozoraText`, `AozoraEpub`); the hash stays the original
  file's.
- ttu's Popper popovers land off screen in the WebView: `help-text.js` pins them to the bottom.
  Ask testers where on the screen a WebView bug happens.
- Drag offsets: update plain state synchronously in the drag callback, not in a coroutine per
  move.
- A continuation resumed from callbacks on other threads must resume at most once
  (`AtomicBoolean`). Catch exceptions from other apps' content providers.
- Android 13+ shows no notifications without `POST_NOTIFICATIONS`, even a foreground service's;
  an Android 12 phone proves nothing. Ask when the work starts (`rememberWithNotifications`).
- ttu reserves some font families (`reservedFontNames`) without their files: downloaded fonts use
  their own family ("Honmani Klee One"), or books wait for ttu's 30 s font timeout.

## Conventions

- Commit messages: imperative subject, body explains why, end with the session's attribution
  lines. No model names in commits or code otherwise.
- Tests accompany behaviour changes (JVM unit tests, Robolectric for Room, Playwright for
  injected JS and ttu).
- Strings in `res/values/strings.xml` (escape apostrophes as `\'`); user-facing errors are
  readable sentences.
- Work goes to `main` (`git push origin HEAD:main`); no PRs unless asked.
- Explain changes in plain terms; device testing is the real check. Ask for "Share diagnostics"
  when the reader misbehaves.
