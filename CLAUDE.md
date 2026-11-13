# Honmani – notes for Claude

Android reader for Japanese books: tap-to-look-up pop-up dictionary, saved words, Anki cards, reading
statistics, OPDS book servers, KOReader progress sync. ッツ Ebook Reader (ttu) renders books in a
WebView; everything around it is Kotlin + Compose. Published by CNFSD Labs, GPL-3.0-or-later.
`README.md` = overview, `docs/device-testing.md` = phone checklist.

**Product principle:** works without setup (install, one-tap dictionary, open a book, tap words);
anything configurable has defaults and stays optional.

**Maintainer's notes** (state, open tasks, devices, publishing) are in the private repository
`cnfsd-labs/notes`: `CLAUDE.md`, then `honmani/CLAUDE.md`; read both first when you have access
(`gh api repos/cnfsd-labs/notes/contents/<path> --jq .content | base64 -d`). This repository is
public: never copy those notes, personal details or discussions into it.

## Decisions (don't relitigate without new facts)

- **Licences.** GPL-3.0-or-later, "© 2026 CNFSD Labs"; no personal names in app, docs or commits
  (author "CNFSD Labs"). May copy from GPL-3.0+, LGPL-3.0, Apache-2.0, MPL-2.0, MIT, BSD, ISC, 0BSD,
  CC0; not GPL-2.0-only, "non-commercial" or unlicensed; AGPL-3.0: ask first. When copying: keep the
  notice, name the source (repo, path, commit), add it to the credits in `AboutScreen` (and the licence
  text if new), mention it in the commit message.
- **ttu** is a pinned submodule built by `:app:buildTtu` with `third_party/patches/` applied. Keep
  patches minimal; prefer scripts injected from `app/src/main/assets/honmani/`. Patches 0001 and 0003
  are proposed upstream (ttu-ttu/ebook-reader#549, #548): drop them once merged.
- **Reader origin** `https://appassets.androidplatform.net` (`WebViewAssetLoader`) must never change
  (ttu's library lives in its IndexedDB). The WebView blocks network loads. Book HTML is not sanitised
  by ttu: pages carry a Content-Security-Policy (`ReaderPolicy`, mirrored in
  `web-tests/support/ttu-server.js`); links leave the reader only on a tap.
- **Network use** is listed in `docs/privacy-policy.md`, which must change with any new network use.
- **Library:** Honmani owns the book files (app storage, named by SHA-256); ttu's IndexedDB is a
  rebuildable cache fed through ttu's import input (`library-bridge.js`). Watched folder: one, rescanned
  when the library opens, no background work; a book removed from it stays away (`removed_book`).
- **Database:** one Room database, schema version 1 in `app/schemas`, manual DI in `AppContainer`
  (no Hilt). Until the first public release the schema is edited in place (commit the regenerated
  JSON); an install from before a schema change must be uninstalled (identity-hash crash, "Room cannot
  verify the data integrity": dev machines only, since both backup rule files exclude the DB).
  **Freeze the schema from the first Play upload or closed test, not later** (testers update over old
  data). Day columns: `reading_day.day` is `yyyy-MM-dd` text, `read_day.day` an epoch-day number (both local dates; permanent once frozen). Once frozen: a change needs a new version, a migration in `HonmaniDatabase.MIGRATIONS`, a test
  in `DatabaseMigrationTest` and the exported JSON committed. No destructive fallback.
- **Android backup** keeps the `settings` preferences and `files/auto-backup/` (`AutoBackup`: the
  Settings backup zip, rewritten at most every 6 h when the app goes to the background, dropped over
  10 MB, offered once on a new install). Everything tied to this phone (first start, encrypted keys,
  AnkiDroid ids, sync account) is in `device` preferences, never backed up (`BackupRulesTest`). Backed-up setting names and types, the backup file name and the Anki note type ids are permanent (`BackupSettingKeysTest`).
- **Application ID** `app.honmani.reader` is permanent. Play bundles come from the "Release bundle"
  workflow (tag `release/<version>`: version name from the tag, version code = run number × 10 + attempt); other builds stay unsigned or debug-signed.
- **Versions:** AGP 9.5 with built-in Kotlin (no `org.jetbrains.kotlin.android`), Gradle 9.8, Kotlin
  2.4.21, KSP 2.3 (move together), compileSdk 37, targetSdk 36 (raise deliberately).
- **Reader settings:** ttu's localStorage is the only store (Honmani's own as `honmani*` keys); theme
  follows the system until the reader picks one (`honmaniAutoTheme`).
- **Motion and sounds.** Animations use the springs in `ui/theme/Motion.kt` and run only when
  `LocalMotionEnabled` (Appearance "Animations" switch on, not E-ink, Android animator scale above 0): new animations must check it.
  Interface sounds are self-made sine tones (`scripts/sounds`, listed in `AboutScreen`), played by
  `UiSounds`, never while read aloud or audio plays; the Appearance "Sounds" switch (off by default) gates them; they use the media stream (the
  sonification stream is muted by ringer silent/vibrate); haptics follow the system setting.
- **Lookup:** tap-based, scan length 20, longest match first, except an entry the book's furigana
  contradicts ranks below those it agrees with; own deinflection (`lookup/Deinflector`); glossaries
  stored deflated (`GlossaryPacker`).
- **Saved words:** recorded on every tap lookup (a long press records nothing until a panel action saves
  the word), ranked by a decayed score (half-life 14 days), never
  deleted for age. Finished words are "Known" (they include words marked from lists or Anki, never looked up); the tick action is "Mark as learned". Anki cards are suggested, never pushed.
- **Spelling variants** (`SpellingVariants`): kanji forms of 2+ characters listed together by one
  dictionary entry count as one word for marks, coverage and progress; single kanji excluded; the
  vocabulary estimate and grading keep exact forms.
- **Anki:** own client for AnkiDroid's content provider (not the LGPL library). Note types "Honmani"
  (deck "Honmani"), "Honmani Sentence" (`SentenceCard`, `anki_card_style`) and "My own" (`CardMapping.kt`:
  per-field `CardSource` mapping as JSON, presets Lapis/JPMN/generic): fills a user's note type, never
  adds a field or changes its template (that forces a one-way AnkiWeb sync). `.apkg` (schema 11, fixed
  note type ids, guid from the word) or a text file (Honmani type only) for desktop Anki. The Google Cloud key is
  Keystore-encrypted and only sent in `X-Goog-Api-Key`.
- **Credentials and servers:** OPDS 1.x only, own parser and `HttpURLConnection` client. Passwords are
  AES-GCM with a Keystore key (not androidx security-crypto). A login goes only to the server's own
  origin; redirects are followed by hand. Plain http and user CAs are allowed for home servers, with a
  warning; over http a login goes only after the server's 401 (the progress sync client also accepts Komga's 403), per server and Android network, never
  on a captive-portal hotspot (`data/net/`).
- **Progress sync:** KOReader's protocol, EPUB only, document id = KOReader's partial MD5, exact
  positions (`EpubPositions` follows ttu's character counting). Calibre-Web Automated wants its web
  password as HTTP Basic at `<address>/kosync`. No account creation in Honmani (Play would require
  account deletion; the protocol has none).

## Build, test, CI

- Prerequisites: JDK 21, Android SDK 37, Node 24 + pnpm 12, `git submodule update --init`.
- `./gradlew lintDebug testDebugUnitTest assembleRelease assembleInternal assembleDebug`, then
  `scripts/check-apk.sh <apk>`.
- `cd web-tests && npm ci && npx playwright install --with-deps chromium && npx playwright test`
  (needs `:app:buildTtu` output or `TTU_DIR=...`). No Chromium build for Ubuntu 26.04 yet: set
  `PLAYWRIGHT_HOST_PLATFORM_OVERRIDE=ubuntu24.04-x64` for install and run.
- `testDebugUnitTest` also runs `:app:buildTtu` (Node and pnpm needed; `-x :app:buildTtu` fails).
- CI (`.github/workflows/android.yml`) runs all of this on every push (the browser tests as a parallel `browser`
  job, one retry, flaky tests named as warnings; `lintDebug` is the lint gate, release lint-vital is off) and cancels the running build:
  don't push while someone waits for an APK.
- **Test APKs:** artifact `honmani-test-apks` of a green run -> `app-internal.apk`; every run signs with
  a new key (uninstall the old build first). Give the direct link `.../actions/runs/<run>/artifacts/<id>`.
- CI flake: the token cannot rerun jobs, so push a small commit to get a new run.
- Cloud sandbox: Google Maven is blocked, so CI is the only Android compiler. Linux's default CJK font
  has broken vertical metrics: tests use `IPAGothic`.

## Lessons learned (each cost a CI or device round trip)

- Changing an interface: update **every** implementation including test fakes
  (`grep -rn ": InterfaceName"`; Room DAOs and `DictionaryImportScheduler` have fakes).
- Unit tests compile against `android.jar`: no `com.sun.net.httpserver` (use the `ServerSocket`-based
  `HeaderServer`). JUnit rejects test methods with a return value: a `= runBlocking { ... }` test must
  end in a statement. Assert the part of a UI state the test is about, not the whole object.
- Room in-memory test DBs need `allowMainThreadQueries()` for raw queries. `MigrationTestHelper` fails
  on Windows; passes on Linux CI.
- Android's ICU regex is stricter than the JVM's: escape `{` and `}`; parse bridge messages as JSON.
- Lint fails on a literal BOM in source (write escapes). Overriding `ComponentActivity.dispatchKeyEvent`
  is a RestrictedApi error: use `onKeyDown`/`onKeyUp`. Kotlin block comments nest: never write `/*`
  inside KDoc.
- Android WebView differs from desktop Chromium (e.g. blank vertical pages without an explicit height):
  reproduce in Chromium by forcing the reported condition, fix, add a regression test with realistic
  books (paragraphs longer than a screen, mobile emulation, DPR 4). `Range.getClientRects()` gives
  inline boxes, not lines. WebView paints no `text-decoration` or `-webkit-text-stroke` in CSS
  `::highlight()` (background, colour, `text-shadow` work); desktop Chromium does, so check marks on
  the phone.
- History steps go through `history.go` in the page, not `WebView.goBackOrForward` (Chromium's back
  skips entries added without a tap).
- ttu cancels clicks on `<ruby>`: the tap bridge listens in the capture phase.
- WebView methods must run on the main thread: never call `BookAdder.add` / `TtuLibrary` inside
  `withContext(IO)`.
- ttu reads TXT as UTF-8 or Shift_JIS only and drops furigana: `TextEncoding` converts to UTF-8 and
  Aozora texts become EPUBs (`AozoraText`, `AozoraEpub`); the hash stays the original file's.
- ttu's Popper popovers land off screen in the WebView: `help-text.js` pins them to the bottom. ttu
  reserves some font families (`reservedFontNames`) without their files: downloaded fonts use their own
  family ("Honmani Klee One"), or books wait for ttu's 30 s font timeout.
- A long press can't grow into a passage via native selection: the page clears it after 250 ms and the
  WebView sends `touchcancel` once it selects. Honmani uses its own long press (mark while held, a drag
  extends it, lookup on release); highlights take their extent from the lookup panel. Test selection features with more
  than one character.
- A continuation resumed from callbacks on other threads must resume at most once (`AtomicBoolean`).
  Catch exceptions from other apps' content providers.
- Android 13+ shows no notifications without `POST_NOTIFICATIONS`, even a foreground service's; an
  Android 12 phone proves nothing. Ask when the work starts (`rememberWithNotifications`).
- Restored files get a 1970 modified date: read dates from file contents (`exportedAt`). Try backup with
  `bmgr` (`docs/device-testing.md`).

## Conventions

- Commits: imperative subject, body explains why, end with the session's attribution lines. No model
  names in commits or code otherwise.
- Tests accompany behaviour changes (JVM unit tests, Robolectric for Room, Playwright for injected JS
  and ttu).
- Strings in `res/values/strings.xml` (escape apostrophes as `\'`); user-facing errors are readable
  sentences.
- Work goes to `main` (`git push origin HEAD:main`); no PRs unless asked.
