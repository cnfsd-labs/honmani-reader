# Device testing

Format: do -> expect. Run what a change touches; the Release pass for every release.

## Getting the APK

- Latest green **Android** workflow run -> Artifacts -> **honmani-test-apks**: `app-internal.apk`
  (release code; use for checks and timing) and `app-debug.apk` (WebView inspectable from
  `chrome://inspect`). Each run signs with a new key: uninstall the old build first (clears dictionaries).
- Timing: `adb logcat -s Honmani.lookup` prints each lookup's duration.
- **Reader looks wrong** (blank page): open the book, wait 5 s, back to the library, Settings -> **Share
  diagnostics**, send to cnfsd.labs@gmail.com or an issue. Nothing is sent automatically.
- Test files: `docs/samples/tebukuro.epub` (also on first start; Settings -> Sample books has eight
  more; no text = app bug), any DRM-free Japanese EPUB, an Aozora `.txt` (unzip the ルビあり zip).

## Release pass (fresh install of the release build, ~10 min)

- First start (uninstall first; a blocked import worker only shows with nothing cached): open the sample
  from the empty library -> text within seconds.
- Tap words (conjugated, furigana); add an EPUB and an Aozora `.txt`; tap an EPUB in the file manager and
  Chrome downloads -> Honmani under "Open with".
- Vertical/horizontal; rotate; dark mode; volume down/up = next/previous page (volume in the library);
  with the panel open a volume press only closes it.
- Long press a word (own long press, no native selection): word marked while held, panel opens on
  release, no blue handles or Copy toolbar, panel has "Copy word" / "Copy sentence"; moving first cancels.
- AnkiDroid card; Words -> export -> "Anki package", import in desktop Anki (audio in the chosen deck).
- Force-stop, reopen -> books, dictionaries, position kept.

## Reader settings

- **Immersive** (off): bars hidden in a book (`dumpsys window | grep ITYPE_STATUS_BAR` -> `visible=false`),
  shown in library/tabs/Settings; top-edge swipe shows them briefly over the page; position does not move
  (bar show, switch, rotate, reopen); menu, panel, volume, Back work; hidden again after leaving the app.
- **E-ink** (off): page black on white in any theme (`getComputedStyle(document.body).backgroundColor`,
  `.book-content` via DevTools socket), no animations/shadows, flat grey word marks, lookup mark black
  with white text; panel opens. Library keeps colours. Off restores the theme exactly
  (`localStorage.theme` unchanged; Auto follows system).
- **Custom CSS** (empty): field with byte counter (max 20 KB), Save/Clear/Cancel; mismatched braces or
  quotes, too much text -> error, Save off. `p { line-height: 3 }` -> `.book-content p` changes, ttu menu
  and footer not; position kept; lookup works. Empty -> no `<style id="honmani-custom-css">`. Clear
  recovers from broken CSS. `url()` makes no network request (local server behind `adb reverse`:
  background, content, border-image, @font-face).
- **Edge taps** (off): outer 15 % turns the page (vertical: left = next; horizontal reverse); with the
  panel open the first edge tap only closes it; links, footer, top bar, long press, middle unchanged. Off
  -> edge taps look up. "Width of the page edges" (10/15/25 %: just outside looks up, just inside turns);
  "Both edges turn forward" (either edge -> next page).
- **Pictures** (cover/illustration, both directions): tap -> full screen on black; pinch and double-tap
  zoom (second double tap = fit), drag stops at edges; Back/close ("Close picture") -> same page. Text
  beside it still looks up; picture tap never turns a page or looks up; tiny inline pictures and
  pictures in links do nothing new.
- **Lookup panel height**: short/long word, kanji, non-word -> top edge stays ~40 %, content scrolls.
  Drag handle/header up -> ~88 %; tap handle toggles (TalkBack Expand/Collapse); drag down shrinks, then
  closes. New tap opens small; back/kanji keep size. Landscape usable.
- **Reader bottom menu**: tap the strip under the text -> Contents, slider, Add bookmark, Bookmarks,
  Highlights, Search in book, Reader settings, Words from this session, Show progress (page reloads),
  Close book. Strip easy to hit with the gesture bar; edge turns still work. Long swipe up (~1/4 screen,
  straight) opens it in paged text; not short/diagonal/page-turn swipes, not with the panel open;
  continuous horizontal scrolls instead (vertical continuous opens it).

## Lookup panel

- **Don't count**: tap a new word -> undo arrow beside the speaker (largest font: a 5-character match
  not cut). Press -> panel closes, word not in Words. On a saved word: lookup count, Learned mark, due
  card, progress go back. Same sentence within the hour: no button. Starred/Anki-added kept. Taps inside
  a definition and "Edit and look up" change nothing.
- **Nested lookups**: tap a Japanese character in an example sentence or cross-reference -> that entry
  replaces the panel, back arrow. Nest twice, Back twice -> one entry each, first as it was; once more
  closes. English text does nothing. Not in Words, no progress.
- **Ignore word**: panel ⋮ -> "Ignore word" (shows "Stop ignoring"); Words row -> "Ignore word" (Undo).
  Ignored: no tint, out of Learning/Learned/All, card out of the queue, not in book-sheet N%/M/"Words to
  learn". Words ⋮ -> "Ignored words"; "Stop ignoring" restores state. Lookup works; backup keeps it.

## Word marks and known words

- **"Tell Honmani what you already know"** (Words ⋮, Settings, or the Learning card while under 50
  Known; "Not now" hides it): top 500-5000 words (needs a frequency dictionary, e.g. Jiten), a text/CSV
  list (one word per line, optional reading after tab/comma, `#` ignored, over 5 MB refused) or an Anki
  deck -> preview (matched, new, already known, not found) before "Mark as known". "Earlier steps" Undo
  removes what the step added, moves back what it moved, keeps words changed since. Top 1000 -> Known
  filter and book "% known" rise; Undo -> fall.
- **Quick quiz** (~45 words; I know it / I don't, Undo last) -> "You seem to know about N words" +
  slider; Continue -> preview, nothing marked before "Mark as known". "Don't know" never marked, "know"
  always. Know up to a point: estimate near it; batch "Quick quiz: about N words", Undo removes it.
- **Mark words**: "Learning" (default): look up, turn a page -> word and forms (食べた, 食べない) tinted;
  "Learned" removes it. Spelling variants are one word (当たり前 looked up -> 当り前 tinted, one unbroken
  mark). "+ New": grey on never-met words; read once, gone. "Off": none. Dark pages: stronger tints.
  First marks after install/dictionary change take seconds, then fade in within ~1 s; a turned page shows
  them at once.
- Optional: hide furigana over known words (on reopening; lookup brings it back); tint sentences with
  exactly one unknown word.
- **Mark words due for review** (on): due-today card -> amber tint (blue if the accent is amber/yellow;
  WebView draws no underlines in highlights). Off + reopen -> Learning tint; answered -> Learning tint.
- **Book sheet** of a graded book: "You know N% of its words; M are new to you": N counts only Known
  (new user 0 %), Learning is neither. Mark Known -> N rises; "Words to learn before reading" lists the
  M, most used first. Difficulty and cover badge unchanged.

## Anki

- Words -> Learning "Check against Anki": forgets the "in Anki" mark of cards deleted in AnkiDroid, marks
  words whose card exists; only the chosen "Card type" note type and saved words. "Choose a deck"
  (also Settings -> Anki): saved words found in a deck's word field become learned, every word on its
  notes Known (also never looked up); confirm shows both numbers. Sentence decks match almost nothing.
- Settings -> Anki -> Card type "Honmani" (default) or "Sentence": adding a word creates "Honmani
  Sentence": front = source sentence without mark on the word, back = word, reading, meanings (no
  sentence: the word). "Check against Anki" uses it; export "Anki package" follows; the text file is
  always Honmani type. "My own": mapping fills the user's note type; no field added, template untouched.
- Both Honmani types end in `FuriganaPlain` (`飛[と]び 散[ち]る`, for `{{furigana:FuriganaPlain}}`), shown
  by no template. The first card to an older type adds it without asking (once per run; if refused, the
  card goes in without). Changing a note type moves `scm`: next AnkiWeb sync is one-way, sync all devices
  first. Importing an `.apkg` over an older type makes "Honmani Sentence+". Settings -> Anki -> Sentence
  ends: end characters for new cards, sentence highlights and tint; old cards unchanged.

## Dictionary search and radicals

- Words -> "Search dictionaries": results while typing: 食べる, たべる, taberu, neko, "cat" (猫 first);
  saved words in "In your words". Tap a result -> entry, query joins "Recent searches". Right after a
  dictionary install a note says English search is getting ready (~1 min); Japanese/romaji work at once.
  Launcher long-press "Search" opens Words with the field focused.
- Radicals: 部 at the end of the field -> sheet by stroke count. 木 + 日 -> kanji with both (東, 杳, ...),
  dead ends dimmed, "Reset" clears. Tap a kanji -> inserted at the cursor, sheet closes, search runs.
  Largest font on a narrow phone: nothing clipped; TalkBack "Radical 木, 4 strokes", checkboxes.

## Review

- Look up words in a book; next day Words shows "Review · n due" and an icon badge. Front = sentence with
  the word marked; tap = answer; Forgot / Got it (or swipe); Undo; Back mid-session keeps answers.
- ⋮: Bury until tomorrow, Mark known, Don't review, Review now, Edit meaning, Correct furigana (field per
  kanji part, kana only, Reset; also in the panel ⋮; changes only Honmani's built furigana, never the
  book), Add/Edit note (max 500 chars; back of the card; empty removes; survives re-lookup, remove and
  take back, restore; Words shows it), Look up, Open in book (at the sentence; Back -> Words), Not a
  word, Remove.
- Chips other than Due (This book, A book..., Starred, All learning) review early, ignore the limit.
- "Reviews per day" (50/100 default/200/No limit): 120 due, 100 chosen -> Words and badge say 100,
  session asks 100, most overdue first, 20 carry over; after 30 answers 70 left (new words use "New
  words a day"); an Again returns today. 200/No limit shows 120. Book due tint follows the same cards.
- Settings -> Reading -> "Day starts at" (Midnight, 1-6 am): with 4 am, 02:00 reading/reviews count for
  the day before (statistics, goal, streak, new words a day, reviews today, due); applies from the
  change.
- Stability >= 365 days retires a card (never due; badge/tint ignore it); a lookup after it is overdue
  lapses it, back next day.
- Reading counts: a due word read moves later (once a day), a lookup makes it due; closing the book shows
  how many due words you passed.
- Book sheet -> "Words to learn before reading" -> "Learn n words": with the book's sentences, most used
  first; words in Learning not listed; "Top 20" ticks the 20 most used.
- Largest font: chips wrap uncut, buttons and menu usable.
- Progress: reviews today, review time, retention, words in review. Statistics -> "Export for Refold
  (CSV)": only reading not exported before (today's included; Refold adds rows up, so a second same-day
  export has only new minutes); "Export everything again" only for a new account.

## Search in the book

Reader menu -> Search in book: a repeated word -> matches in bold with surroundings and place (%), in
reading order; furigana not matched. Tap a later match -> opens there, yellow briefly (also far down a
long paragraph; vertical/horizontal, paged/continuous). Back -> earlier page. Check speed in a long book;
keyboard does not cover the list.

## Read aloud

Needs a Japanese voice (Android Settings, Text-to-speech) and audible media volume. Reader menu -> Read
aloud, on a page with furigana.

- Bar (close, previous, play/pause centred, next, speed); first sentence marked grey, then spoken; the mark
  follows the sentence, in a long one the words; visible beside word marks, highlights, in e-ink.
- Ruby words are read as their furigana.
- Volume buttons change the volume (no page turn) while the bar is up. A swipe or tap-zone turn detaches:
  speech and mark go on, the page stays, "Follow" (above the bar) brings it back; closing the bar resets.
- Page end: the book turns itself (a sentence crossing the break turns when the rest is off the page);
  paged/continuous, both directions. Close and reopen: on the page reached.
- Pause/play resumes at the sentence start; next/previous move a sentence, also paused. Speed
  0.75/1/1.25/1.5, kept per book. Fits at 360 dp and the largest font; TalkBack reads each button.
- Tap a word: pauses while the panel is open, resumes from the sentence start on close; the panel's word
  audio never plays over the voice.
- Call, other player, Home pauses it; the screen stays on only while reading.
- No Japanese voice: bar says so, offers "Voice settings". Book end: it stops and says so.
- Rotate, change font, close the book while reading: no crash, no voice left.
- Reader menu -> Pronunciations: add "text in the book" + "read as (kana)", Save; Read aloud speaks the
  kana (logcat tag ReadAloud, "speak" line); delete shows Undo; removing the book removes its entries;
  backup restore brings them back (a restored phone keeps its own reading).

## Audiobook

Needs an EPUB, an audio file and an SRT/WebVTT matching the text; use a silent recording (media volume
0). Reader menu -> Audiobook: pick audio, then subtitles.

- Bar, "Matching the subtitles to the book..." briefly, audio plays, the heard subtitle is marked; pages
  turn at page end (both directions, paged/continuous) and count as reading: with progress sync the
  position reaches the server (a far jump does not).
- Subtitles not in the book change nothing (mark stays); under half found -> the bar says so.
- Same bar order, volume buttons and swipe-away/Follow as read aloud.
- Next/previous move one subtitle (previous first restarts the current), also paused. Speed
  0.75/1/1.25/1.5, kept.
- Pause, close the app: audio paused, screen no longer kept on, Play continues. Call/other player
  pauses; a word tap pauses while the panel is open.
- Read aloud and the audiobook stop each other.
- Remove: pairing, subtitle copy, position gone. Delete the audio file, play -> "audio is gone", "Change
  files". Unplayable audio, empty subtitles, non-EPUB -> readable message, no crash.

## Bookmarks and the reading slider

Reader menu top: slider with live label ("42% · 10,350 / 24,847"), Add bookmark, Bookmarks. Paged and
continuous, vertical and horizontal, EPUB and TXT:
- Drag: label follows, nothing moves until release; then reload there and "Jumped to N%" with "Back to
  where you were" (exact earlier page).
- Add bookmark: name prefilled from the text, editable; place = current page. List in reading order; tap
  jumps, pencil renames, bin deletes (Undo). Removing the book removes them.
- Backup then restore on a fresh install with the same book: bookmarks there; twice adds nothing.
- Progress sync (EPUB): after a slider/bookmark jump nothing is sent until a page is turned; the other
  device is not asked about a backward jump.
- Long press and tap still look up after a jump.

## Highlights

Needs an EPUB and "Look up selected text" on. Light, dark, sepia, black; vertical/horizontal;
paged/continuous; with/without furigana:
- Own long press on a word (no blue selection): marked while held, panel on release; moving first
  cancels. Panel: Selection / Sentence / Paragraph (Sentence chosen) with its text in one line, and
  "Highlight" with four colours; one tap saves, closes, "Highlight saved" (Undo). A sentence ends at
  。！？ or a line break and keeps a closing 」; a paragraph can span pages; furigana is no part of either.
- A new highlight replaces overlapping ones (any colour); Undo brings them back.
- Reopen, change font size, rotate -> same text highlighted.
- Tap inside one: lookup opens with the colours (current ticked) and "Remove highlight"; another colour
  changes it; Remove deletes (Undo).
- Reader menu -> Highlights: passage, colour dot, place; tap jumps ("Back to where you were"), bin
  removes (Undo). Removing the book removes them. Backup/restore on a fresh install keeps them.
- Selection lookup off: none can be made; existing ones drawn, listed, removable. TXT offers no Highlight.
- E-ink: four solid light greys (220, 200, 180, 160); word mark translucent black over them; lookup mark
  and search hit solid, cover it. Word mark, lookup mark, search hit never hide the text.

## Reading progress sync (KOReader)

Setup: KOReader on an e-reader (or Calibre-Web Automated with its "CWA Progress Sync" plugin; in Honmani
its plain address and Calibre-Web login suffice), same sync server, same EPUB on both. Settings ->
Reading progress sync, sign in, read a few pages, back to the library.
- KOReader offers Honmani's position (top of the shown page). Read on there, open in Honmani ->
  "Continued at ...% from ..." at KOReader's page. Aozora-converted books match only with the same EPUB.
- Offline: read in Honmani, back to the library, reconnect (or restart on home Wi-Fi) -> without
  reopening the book KOReader offers the last page. Conflict: read on offline in Honmani, open in
  KOReader at its older place and close, reconnect, open in Honmani -> "Where to continue?"; "Stay at
  ..." keeps Honmani's page (KOReader offers it next), "Go to ..." jumps back.
- Status: Settings shows "Last synced ... ago" / "Not synced yet". Server off, turn a page -> "Last
  attempt failed ...: server unreachable" (or wrong login, HTTP status); that book's reader menu starts
  with "Progress sync failed: ..."; other books no hint. Server back -> next sync clears both.

## Back up and restore

- Settings -> Back up and restore -> Back up..., save. Uninstall, reinstall, get a dictionary, Restore...
  -> accent colour, reader settings, Words (progress), Progress (level, streak, calendar, milestones,
  skill), Statistics return. Re-add a book: old progress, old page, its statistics. Same file twice
  changes nothing. Review cards keep due dates (answer after the backup, restore -> backup's date);
  Progress -> Overview shows the review tiles.
- Progress sync signed in: read on in KOReader after the backup, restore, add the book -> opens at
  KOReader's page ("Continued at ..."), not the backup's.
- Without a file: Honmani's own backup (rewritten on going to the background, at most every 6 h) rides
  Android's Google backup or phone transfer. New phone's first start: "Restore from your previous
  phone?" (with date); Restore = from file; settings already restored by Android.
- Sync folder (Settings -> Back up and restore): pick a folder, press Home: `honmani-sync-<id>.zip` is
  there (no `.part` file); its JSON has `syncFormat`, `deviceId`, no `settings`/`catalogs`. Remove the
  folder in the file app: "Folder unavailable: choose it again". Default off.
- Android's backup over USB without a Google account (debug-only local transport; the phone's backup
  setting is off by default, undo afterwards):

```sh
adb shell bmgr enable true
adb shell bmgr transport com.android.localtransport/.LocalTransport
# open the app once and send it to the background, then:
adb shell bmgr backupnow app.honmani.reader.debug      # "Success"
adb uninstall app.honmani.reader.debug && adb install app-debug.apk   # restores at install
# the first start offers the restore; check the words and settings
adb shell bmgr transport com.google.android.gms/.backup.BackupTransportService
adb shell bmgr enable false
```

## Known limitations (no need to report)

- Diagnostics show "Failed to register a ServiceWorker" (ttu's offline cache, unneeded).
- ttu's Google Drive / OneDrive sync and backup downloads do not work.
- Dictionary images are not shown.
- ttu's web fonts (chosen in its settings) bring back a 10 s wait on opening books (not bundled).
