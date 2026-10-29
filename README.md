# Honmani

Honmani?! (本まに, from the Kansai ほんまに, "really?!") is a Japanese ebook reader for Android
with a pop-up dictionary. Open a book, tap any word and see what it means, even when it's
conjugated: 食べさせられなかった leads straight to 食べる.

- Dictionaries with one tap on the first start (English or Japanese definitions), or any
  Yomitan dictionary
- EPUB and text files (Aozora Bunko too), vertical or horizontal text, furigana, reader fonts
- Readings, definitions, word frequency, pitch accent and kanji details for every tap
- Every word you look up is saved with its sentence; send words to Anki (AnkiDroid or an
  `.apkg` for Anki on a computer)
- Reading statistics (time, characters, speed), a reading level and milestones
- Book difficulty from 1 to 100, worked out on the phone from the text
- Your own book server (OPDS, such as Calibre-Web): browse, search, download
- Reading progress sync with KOReader (e-readers, Calibre-Web Automated), to the character
- Back up settings, progress, statistics and saved words to one file, for a new phone; Android's
  own backup takes them along too, and a new install offers to restore them
- No account, no ads, no tracking ([privacy policy](docs/privacy-policy.md))

## Status

In testing before its first release on Google Play (Android 8.0 and later). Test builds come
from CI: the `honmani-test-apks` artifact of a green run of the Android workflow (see
[`docs/device-testing.md`](docs/device-testing.md)).

## Building

Requires JDK 21, the Android SDK (platform 37), and Node.js 24 with pnpm 12 (for ッツ Ebook
Reader). Clone with submodules:

```sh
git clone --recurse-submodules <repo>    # or: git submodule update --init
./gradlew lintDebug testDebugUnitTest assembleRelease
scripts/check-apk.sh app/build/outputs/apk/release/app-release-unsigned.apk
```

Browser tests for the reader's JavaScript run against the ttu build Gradle produces:

```sh
cd web-tests && npm ci && npx playwright install --with-deps chromium && npx playwright test
```

`check-apk.sh` is the release gate CI runs on every push: 16 KB page alignment of native
libraries, a complete bundled reader, and no Play-restricted permissions.

## How it fits together

- **Reader.** [ッツ Ebook Reader](https://github.com/ttu-ttu/ebook-reader) (ttu) is a pinned
  submodule, built with the small patches in `third_party/patches/` (each explained in its
  README) and served offline in a WebView on `https://appassets.androidplatform.net`. Scripts
  in `app/src/main/assets/honmani/` are injected at document start: they report taps, page turns
  and positions to the app, hand books to ttu, and read and write ttu's settings.
- **App.** Kotlin, Jetpack Compose (Material 3), Room, manual dependency injection
  (`AppContainer`). One screen: the library, words, progress and settings tabs are drawn over
  the reader, which stays loaded underneath.
- **Library.** Honmani keeps the book files (named by their SHA-256) and feeds them to ttu, whose
  own storage is only a cache. Text files are converted to UTF-8; Aozora Bunko texts become EPUBs
  with furigana, headings and their notation (`data/library/`).
- **Lookup.** Honmani's own deinflection rules (`lookup/Deinflector`, with a test corpus in
  `app/src/test/resources/`) and a lookup over Yomitan dictionaries imported into Room
  (`data/dictionary/`, definitions stored compressed). The panel draws Yomitan's structured
  content natively.
- **Words and Anki.** Every lookup is saved and ranked by a decayed score (`data/words/`);
  cards go to AnkiDroid through its content provider or into an Anki package (`anki/`). Spellings
  one dictionary entry lists together (当たり前, 当り前) count as one word for word marks and
  progress (`SpellingVariants`).
- **Book servers and sync.** OPDS 1.x catalogues with Basic or Digest login (`data/opds/`),
  and KOReader's sync protocol with exact positions between KOReader's XPointers and ttu's
  character count (`data/sync/`). Passwords are encrypted with an Android Keystore key; over
  plain http a login is only sent once the server asks for it (`data/net/`).
- **Statistics.** Reading time and characters per book and day (`data/stats/`).
- **Backups.** One zip of JSON through the system file picker (`data/backup/`): everything but
  the books, which are named by content hash; a restore merges and keeps what books not in the
  library had until the same file is added again.

## Licence

Honmani is free software under the [GNU General Public License v3.0](LICENSE) or (at your option)
any later version. It comes with no warranty.

It includes [ッツ Ebook Reader](https://github.com/ttu-ttu/ebook-reader) (BSD 3-Clause) and the
libraries listed in the app under Settings → About Honmani. Dictionaries and fonts are downloaded
from their publishers and keep their own licences.
