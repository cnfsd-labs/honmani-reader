# Device testing

## Getting the APK

1. Open the latest green run of the **Android** workflow on GitHub (repository → Actions →
   Android → newest run with a green check).
2. Under **Artifacts**, download **honmani-test-apks**. It is a zip with two APKs:
   - `app-internal.apk` ("Honmani internal"): release code, use this for the checks below and
     for timing.
   - `app-debug.apk` ("Honmani debug"): debuggable; the reader WebView can be inspected from
     `chrome://inspect` on a computer.
3. Copy the APK to the phone and open it; allow installing from that app when Android asks.

Each CI run signs with a different throwaway key: uninstall the previous build before
installing a newer one (this also clears its data, including imported dictionaries).

Useful for timing: with the phone connected over USB,
`adb logcat -s Honmani.lookup` prints how long each lookup takes.

**If something in the reader looks wrong** (e.g. a blank page): open the book, wait 5 seconds,
go back to the library, then Settings → **Share diagnostics** and send the text (to
cnfsd.labs@gmail.com, or attach it to a GitHub issue). It contains the
device and WebView versions, the reader's console messages and how the last book was laid out
(settings, fonts, positions, the first 40 characters). Nothing is sent automatically.

## Test files

- **Sample book:** [`docs/samples/tebukuro.epub`](samples/tebukuro.epub), 新美南吉's 手袋を買いに with
  furigana, converted from Aozora Bunko (also built into the app: the first-start screen offers
  it; Settings → Sample books has eight more, built by `scripts/sample-books/`). It is known to be well-formed, so if it shows no text the
  problem is in the app, not the file.
- Any other DRM-free Japanese EPUB, or an Aozora Bunko `.txt` file (from a work's page on
  aozora.gr.jp, the zip under "テキストファイル(ルビあり)"; unzip it first).
- JMdict for Yomitan: `JMdict_english.zip` from
  https://github.com/yomidevs/jmdict-yomitan/releases/latest
- Jitendex for Yomitan: `jitendex-yomitan.zip` from https://jitendex.org

## Before a release

A quick pass over what users hit first, on a fresh install of the release build (10 minutes):
first start with the sample book; tap a few words (conjugated forms, furigana); add your own EPUB
and an Aozora `.txt`; vertical and horizontal mode; rotate; back to the library; dark mode;
add a card to AnkiDroid; Words → export (arrow up) → "Anki package" and import the .apkg in Anki on a
computer (cards with audio in the chosen deck); force-stop and reopen (books, dictionaries,
position kept).

## Back up and restore

Settings → Back up and restore → Back up…, save the file (to Downloads, say). Uninstall Honmani,
install it again, get a dictionary, then Restore… and pick the file: accent colour, reader
settings, Words (with Learning/Known progress), Progress (level, streak, calendar, milestones,
skill) and Statistics come back. Add one of the books again (the same file, or from the book
server): it shows its old progress, opens at the old page and has its statistics. Restoring the
same file again changes nothing.
With progress sync signed in: read on in KOReader after the backup, then restore and add the
book: it opens at KOReader's page ("Continued at …"), not at the backup's.

## Reading progress sync (KOReader)

With KOReader on an e-reader (or Calibre-Web Automated, whose own KOReader plugin "CWA Progress
Sync" the e-reader needs; in Honmani its plain address and Calibre-Web login are enough) signed in
to the same sync server:
put the same EPUB file on both. In Honmani: Settings → Reading progress sync, sign in, read a few
pages, go back to the library. In KOReader, open the book: it offers Honmani's position (top of
the page Honmani showed). Read on in KOReader, then open the book in Honmani: it says "Continued
at …% from …" and shows the page KOReader was on. Books converted from Aozora text files only
match if both devices have the same EPUB.

Away from the server (mobile data with a home server, or flight mode): read a few pages in
Honmani, go back to the library, then reconnect (or restart Honmani on the home Wi-Fi). Without
opening the book again, KOReader then offers Honmani's last page. Going back: read further in
Honmani offline, open the book in KOReader at its older place and close it, reconnect, open the
book in Honmani: it asks "Where to continue?"; "Stay at …" keeps Honmani's page and KOReader
offers it next time, "Go to …" jumps back.

## Word marks and known words

Reader settings → Mark words. "Learning" (the default): look up a word, turn a page or two and
come back: the word and its other forms (食べた, 食べない) have a light accent tint; after
"Learned" in Words it loses it. Other spellings of it count as the same word: after looking up
当たり前, 当り前 has the tint too (with or without furigana, one unbroken mark). "+ New" adds a grey tint on words never met in your reading
(at first nearly everything, particles too); words read once lose it the next time they come up. "Off" shows none. The first marks after opening Honmani take a
few seconds (the dictionaries are read) and fade in; after that, a turned page shows its marks at
once. Between two chapters there is no empty page (銀河鉄道の夜 had four). Book sheet, after some reading in graded books: "You
know about N% of its words; about M are new to you", higher for easy books.

## Known limitations (no need to report)

- Diagnostics show "Failed to register a ServiceWorker": ttu tries to install its offline cache,
  which Honmani doesn't need (the reader is in the app). Harmless.
- ttu's Google Drive / OneDrive sync and backup downloads do not work.
- Dictionary images (pictures in some entries) are not shown.
- Choosing one of ttu's web fonts in its settings brings back a 10-second wait when opening
  books (those fonts are not bundled).
