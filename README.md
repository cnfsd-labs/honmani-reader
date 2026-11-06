# Honmani

Honmani?! (本まに, from the Kansai ほんまに, "really?!") is a Japanese ebook reader for Android
with a pop-up dictionary. Open a book, tap any word and see what it means, even when it's
conjugated: 食べさせられなかった leads straight to 食べる.

- Dictionaries with one tap on the first start (English or Japanese definitions), or any
  Yomitan dictionary
- EPUB and text files (Aozora Bunko too), vertical or horizontal text, furigana, reader fonts; pages
  turn with a tap, a swipe or the volume buttons. Optional: immersive mode (hides the status and
  navigation bars), e-ink mode (black on white, no animations, grey marks), your own CSS for the book
  text. A tap on a picture (a cover too) opens it full screen with pinch zoom, double-tap zoom and
  panning
- Search in the open book (reader menu): every match with its surroundings and place; a tap opens the
  book there and marks it
- Read aloud (reader menu): the phone's Japanese voice (Settings, Text-to-speech) reads from the page on
  screen, marks the sentence it says, turns the pages by itself and stops when you leave the app;
  play/pause, previous and next sentence and speed (0.5 to 1.5) are in a bar over the page.
  Pronunciations (reader menu): per book, "read this text as these kana" for names the voice gets wrong
- Audiobook (reader menu, EPUB): pair a book with an audio file and a subtitle file (SRT or WebVTT);
  subtitles are matched to the book's text, the sentence being heard is marked and the pages turn by
  themselves. Previous/next move one subtitle, speed 0.5 to 1.5, the place is remembered per book.
  Subtitles not in the book are skipped; if fewer than half are found, the bar asks whether the files fit
- Named bookmarks (reader menu): as many places per book as you like, jump from a list, rename or delete
  (Undo); a slider over the book shows the place as % and characters and jumps on release. After a jump,
  "Back to where you were" returns. Bookmarks are in the backup
- Highlights in four colours (EPUB): long press a word, choose what to mark (selection, sentence, or paragraph; a sentence
  at first, then the one you chose last) and a colour in the lookup panel; a new highlight replaces any it overlaps. They
  show in every theme; a tap on one offers its colours and "Remove highlight" (Undo); the reader menu
  lists them and jumps to one. In the backup. Making them needs "Look up selected text" (existing ones
  stay when it is off)
- Readings, definitions, word frequency, pitch accent and kanji details for every tap; tap a Japanese
  word inside a definition or example sentence to look it up in turn (Back steps out, nothing counted)
- Marks in the book on words you are learning or have not met yet, and a list of the words a book holds
  that you don't know; "Ignore word" sets aside names and wrongly split words (no mark, no card, not
  counted), with a list in Words to undo it
- Every tapped word is saved with its sentence ("Don't count" in the panel takes it back; a long press
  to select text saves nothing); send words to Anki
  (AnkiDroid or an `.apkg` for Anki on a computer). Cards use Honmani's own note type, a sentence-first
  variant, or your own note type (Lapis, Marvin's Mining Note or any other): you choose which field gets
  what, and Honmani never adds fields to it or changes its template
- Search the dictionaries in Japanese, romaji or English (Words, or the launcher shortcut "Search"); the
  button 部 finds a kanji by its parts (radical search). Handwriting is left to the keyboard (Gboard's
  Japanese handwriting layout works in the field)
- Flashcard review of the words you are learning, with the sentence you met them in: two buttons
  (Forgot / Got it), a memory model (FSRS) that also counts words you read in books, new words ordered
  by the book you are reading, a daily review limit (50, 100, 200 or none), words due today tinted in the
  book, cards known for a year retired, and a book's words learned before you read it (book sheet)
- Reading statistics (time, characters, speed), a reading level and milestones, a day that can start
  later than midnight (Settings, Goal and time); reading time per day and book can be exported as a CSV
  for the Refold app (Progress, Statistics)
- Book difficulty from 1 to 100, worked out on the phone from the text
- Your own book server (OPDS, such as Calibre-Web): browse, search, download. A folder on the phone: add
  it once, or watch it (Settings, Books and sync) and its new books join when the library opens
- Accent colours, or a random one every day or every launch (Settings, Appearance)
- Reading progress sync with KOReader (e-readers, Calibre-Web Automated), to the character, with the
  last sync and error shown in Settings
- Back up settings, progress, statistics, saved words and flashcard reviews to one file, for a new
  phone; Android's own backup takes them along too, and a new install offers to restore them
- No account, no ads, no tracking ([privacy policy](docs/privacy-policy.md))

## Status

In testing before its first release on Google Play (Android 8.0 and later). Test builds come from CI:
the `honmani-test-apks` artifact of a green run of the Android workflow (see
[`docs/device-testing.md`](docs/device-testing.md)).

## Building

Requires JDK 21, the Android SDK (platform 37), and Node.js 24 with pnpm 12 (for ッツ Ebook Reader).

```sh
git clone --recurse-submodules <repo>    # or: git submodule update --init
./gradlew lintDebug testDebugUnitTest assembleRelease
scripts/check-apk.sh app/build/outputs/apk/release/app-release-unsigned.apk
```

Browser tests for the reader's JavaScript run against the ttu build Gradle produces:

```sh
cd web-tests && npm ci && npx playwright install --with-deps chromium && npx playwright test
```

`check-apk.sh` is the release gate CI runs on every push: 16 KB page alignment of native libraries, a
complete bundled reader, and no Play-restricted permissions.

## How it fits together

- **Reader.** [ッツ Ebook Reader](https://github.com/ttu-ttu/ebook-reader) (ttu) is a pinned submodule,
  built with the small patches in `third_party/patches/` (each explained in its README) and served
  offline in a WebView on `https://appassets.androidplatform.net`. Scripts in
  `app/src/main/assets/honmani/` are injected at document start: they report taps, page turns and
  positions to the app, hand books to ttu, and read and write ttu's settings. ttu shows a book's HTML
  as it is, so pages carry a Content-Security-Policy that keeps scripts in books from running
  (`ReaderPolicy`), and links leave the reader only on a tap.
- **App.** Kotlin, Jetpack Compose (Material 3), Room, manual dependency injection (`AppContainer`).
  One screen: library, words, progress and settings are drawn over the reader, which stays loaded
  underneath.
- **Library.** Honmani keeps the book files (named by SHA-256) and feeds them to ttu, whose storage is
  only a cache. Text files are converted to UTF-8; Aozora Bunko texts become EPUBs with furigana,
  headings and notation (`data/library/`).
- **Lookup.** Honmani's own deinflection rules (`lookup/Deinflector`, test corpus in
  `app/src/test/resources/`) over Yomitan dictionaries imported into Room (`data/dictionary/`,
  definitions stored compressed). The panel draws Yomitan's structured content natively. Optional
  Tatoeba example sentences (`data/examples/`) show as a collapsed "Examples (n)" row.
- **Words and Anki.** Every tapped word is saved and ranked by a decayed score (`data/words/`); cards go to
  AnkiDroid through its content provider or into an Anki package (`anki/`). Spellings one dictionary
  entry lists together (当たり前, 当り前) count as one word for marks and progress (`SpellingVariants`).
- **Book servers and sync.** OPDS 1.x catalogues with Basic or Digest login (`data/opds/`), and
  KOReader's sync protocol with exact positions between KOReader's XPointers and ttu's character count
  (`data/sync/`). Passwords are encrypted with an Android Keystore key; over plain http a login is sent
  only once the server asks (`data/net/`). Plain http and certificates from the user's own CA are
  allowed for these home servers only; the public services Honmani downloads from need https with a
  public certificate (`res/xml/network_security_config.xml`).
- **Statistics.** Reading time and characters per book and day (`data/stats/`).
- **Backups.** One zip of JSON through the system file picker (`data/backup/`): everything but the
  books, which are named by content hash (review cards, logs and read days ride on their word, matched
  by spelling and reading); a restore merges and keeps what books not in the library had until the same
  file is added again.
- **Sync folder.** Settings -> Backup -> Sync folder: each phone writes its own
  `honmani-sync-<id>.zip` (backup format plus edit times and deletion markers, no passwords) to a folder
  you pick when the app goes to the background, and merges the other phones' files on its next start
  (`SyncFolder`, `SyncMerge`).

## Licence

Honmani is free software under the [GNU General Public License v3.0](LICENSE) or (at your option) any
later version. It comes with no warranty.

It includes [ッツ Ebook Reader](https://github.com/ttu-ttu/ebook-reader) (BSD 3-Clause) and the
libraries listed in the app under Settings → Help and about → About Honmani. Dictionaries and fonts are downloaded from
their publishers and keep their own licences. The radical search ships EDRDG's RADKFILE, KRADFILE and
KANJIDIC2 data (© Electronic Dictionary Research and Development Group,
[CC BY-SA 4.0](https://www.edrdg.org/edrdg/licence.html)), converted by
`scripts/radicals/build_radicals.py`. The optional example sentences (Settings → Reading → Example sentences,
off until switched on) are from [Tatoeba](https://tatoeba.org) (Japanese sentences with English
translations, [CC BY 2.0 FR](https://creativecommons.org/licenses/by/2.0/fr/)), packed by
`scripts/examples/build_examples.py` and downloaded from this repository's GitHub releases only when
the reader turns them on: the app's only other download besides dictionaries and fonts.
