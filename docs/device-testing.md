# Device testing

Format: what to do -> expected. Tick off what a change touches; the "Release pass" is for every release.

## Getting the APK

1. Latest green run of the **Android** workflow (GitHub, Actions) -> Artifacts -> **honmani-test-apks**.
2. `app-internal.apk` ("Honmani internal", release code: use it for the checks and timing);
   `app-debug.apk` (debuggable, WebView inspectable from `chrome://inspect`).
3. Each CI run signs with a new throwaway key: uninstall the old build first (this clears its data,
   including dictionaries).

Timing: `adb logcat -s Honmani.lookup` prints each lookup's duration.

**Reader looks wrong** (blank page, say): open the book, wait 5 s, back to the library, Settings ->
**Share diagnostics**, send the text to cnfsd.labs@gmail.com or attach it to an issue (device and
WebView versions, console messages, layout of the last book; nothing is sent automatically).

## Test files

- `docs/samples/tebukuro.epub` (新美南吉, with furigana; also offered on first start, Settings -> Sample
  books has eight more). Known well-formed: no text means an app bug.
- Any DRM-free Japanese EPUB, or an Aozora Bunko `.txt` (unzip the "テキストファイル(ルビあり)" zip).

## Release pass (fresh install of the release build, ~10 min)

- First start (uninstall first, or clear data; a blocked import worker only shows with nothing cached):
  open the sample from the empty library -> text within a few seconds.
- Tap words (conjugated, furigana); add your own EPUB and an Aozora `.txt`; tap an EPUB in the file
  manager and in Chrome downloads -> Honmani offered under "Open with".
- Vertical and horizontal; rotate; dark mode; volume down/up = next/previous page (changes volume in
  the library); with the panel open a volume press only closes it, the next turns the page.
- Long press a word (own long press, no native selection): the word is marked while held, the lookup
  panel opens on release, no blue handles or Copy toolbar, panel offers "Copy word" / "Copy sentence";
  moving the finger first cancels.
- AnkiDroid card; Words -> export -> "Anki package", import the .apkg in desktop Anki (audio in the
  chosen deck).
- Force-stop and reopen -> books, dictionaries and position kept.

## Reader settings

- **Immersive mode** (off by default): on -> status and navigation bars hidden while a book is open
  (`dumpsys window | grep ITYPE_STATUS_BAR` shows `visible=false`), shown in library, tabs, Settings and
  reader settings; swipe from the top edge shows them briefly over the page; position does not move
  (ttu counter and bookmark unchanged on bar show, switch change, rotate, reopen); reader menu, panel,
  volume and Back work; bars hidden again after leaving and returning.
- **E-ink mode** (off): page black on white in any theme (`getComputedStyle(document.body).backgroundColor`,
  `.book-content` colour via the DevTools socket), no animations/transitions/shadows, word marks flat
  greys, lookup mark black with white text; panel still opens. Library keeps its colours. Off restores
  the earlier theme exactly (`localStorage.theme` same before/during/after; Auto still follows system).
- **Custom CSS** (empty): row opens a field with byte counter (max 20 KB), Save/Clear/Cancel; mismatched
  braces/quotes or too much text -> error, Save off. `p { line-height: 3 }` -> `.book-content p`
  gets it, ttu menu and footer do not; position kept after reload; lookup still works. Empty -> no
  `<style id="honmani-custom-css">`. Clear in the native screen recovers from broken CSS. `url()` in
  the CSS makes no network request (check with a local server behind `adb reverse`: background,
  content, border-image, @font-face).
- **Edge taps** (off): on -> tap in the outer 15 % turns the page (vertical: left = next, right =
  previous; horizontal reverse); with the panel open the first edge tap only closes it; links, footer,
  top bar, long press and page middle unchanged. Off -> edge taps look up. On also shows "Width of the
  page edges" (Narrow 10 %, Medium 15 %, Wide 25 %: just outside looks up, just inside turns) and
  "Both edges turn forward" (off: either edge -> next page, both text directions).
- **Pictures** (cover or illustration, both directions): tap opens it full screen on black; pinch and
  double-tap zoom (second double tap back to fit), drag stops at the edges; Back or close button
  (TalkBack "Close picture") returns to the same page. Text beside it still looks up; the picture tap
  never turns a page (also with edge taps) or looks up; tiny inline pictures and pictures in links do
  nothing new.
- **Lookup panel height**: short word, long word, kanji, non-word -> top edge stays at ~40 % of the
  screen, content scrolls inside. Drag handle/header up -> ~88 %; tap handle toggles (TalkBack
  "Expand"/"Collapse"); drag down shrinks, then closes. A new tap opens small; back/kanji keep the
  size. Landscape usable.
- **Reader bottom menu**: tap the thin strip under the text -> sheet: Contents, reading slider, Add
  bookmark, Bookmarks, Highlights, Search in book, Reader settings, Words from this session, Show
  progress (switch, page reloads), Close book. Strip easy to hit with the gesture bar; edge page turns
  still work. A long swipe up (~1/4 screen, mostly straight) opens it too, in paged horizontal and
  vertical text; not a short, diagonal or page-turn swipe, not with the panel open; in continuous
  horizontal text it scrolls instead (vertical continuous opens it). Long press and tap unchanged.

## Lookup panel

- **Don't count**: tap a never-looked-up word -> undo arrow "Don't count" beside the speaker (at the
  largest font a 5-character match, e.g. 食べなかった shortened once, is not cut). Press -> panel
  closes, the word is not in Words. On a saved word: lookup count, Learned mark, due card and progress
  go back as they were. Same sentence within the hour changes nothing, no button. A starred or
  Anki-added word is kept. Taps inside a definition and "Edit and look up" change nothing.
- **Nested lookups** (book, search, shared text): tap a Japanese character in an example sentence
  (Jitendex "More meanings") or a cross-reference -> that entry replaces the panel, back arrow shows.
  Nest twice, Back (arrow or system) twice -> each step returns one entry, the first is as it was; once
  more closes. English text does nothing. Nested words are not in Words, no progress.
- **Ignore word**: panel ⋮ beside the star -> "Ignore word" (panel stays, shows "Stop ignoring"); in
  Words expand a row -> "Ignore word" (snackbar Undo). Ignored: no tint (Learning or grey "+ New"), out
  of Learning/Learned/All, card out of the review queue, not in book-sheet N%/M/"Words to learn". Words
  ⋮ -> "Ignored words" lists them; "Stop ignoring" restores the earlier state (Learning, Known, card).
  Lookup still works; backup keeps it.

## Word marks and known words

- **Tell Honmani what you already know** (Words ⋮, Settings, or the card atop Words -> Learning while
  under 50 Known words; "Not now" hides it for good): marks as Known the top 500 to 5000 words (needs a
  frequency dictionary, e.g. Jiten), a text/CSV word list (file or pasted; one word per line, optional
  reading after tab or comma, `#` lines ignored, over 5 MB refused), or an Anki deck. Each shows a
  preview (matched, new, already known, not found) before "Mark as known". "Earlier steps": Undo removes
  what the step added and moves back what it moved from Learning, but keeps words looked up, starred,
  moved or changed since. Check: top 1000 -> Known filter and a book's "% known" rise; Undo -> fall;
  change one word first -> it stays.
- **Quick quiz** (~45 words over the frequency ranks; I know it / I don't, Undo last answer) -> "You
  seem to know about N words" with a slider; Continue shows the preview, nothing marked before "Mark as
  known". "Don't know" answers are never marked, "know" answers always. Answer "know" to a point, then
  "don't": the estimate lands near it; batch shows as "Quick quiz: about N words", Undo removes it.
- **Mark words** (Reader settings): "Learning" (default): look up a word, turn a page -> it and its
  forms (食べた, 食べない) have a light accent tint; "Learned" in Words removes it. Spelling variants are
  one word: after 当たり前, 当り前 is tinted too (one unbroken mark, with or without furigana). "+ New"
  adds a grey tint on never-met words (at first nearly everything); read once, it goes. "Off": none.
  Dark pages (black, grey, dark system) use stronger tints. First marks after install or a dictionary
  change take a few seconds (word list built once), then fade in within ~1 s of opening; a turned page
  shows them at once.
- Optional: hide furigana over known words (applies on reopening; a lookup brings it back); tint
  sentences with exactly one unknown word.
- **Mark words due for review** (on): a word with a card due today gets an amber tint instead (blue when
  the accent is amber/yellow; WebView draws no underlines in highlights). Check on a light and a dark
  page; off + reopen -> Learning tint; after answering the review -> Learning tint again.
- **Book sheet of a graded book**: "You know N% of its words; M are new to you". N counts only Known
  words (new user: 0 %); Learning words are neither known nor new. Mark a word Known -> N rises; "Words
  to learn before reading" lists the M new words, most used first. Difficulty and cover badge unchanged.

## Anki

- Words -> Learning: "Check against Anki" (beside "Add all N to Anki") forgets the "in Anki" mark of
  cards deleted in AnkiDroid and marks words whose card exists. Only looks at Honmani's own note type of
  the chosen "Card type" and at saved words. "Choose a deck" (also Settings -> Anki) marks saved words
  found in a deck's word field as learned and every word on its notes as Known, also never-looked-up
  ones; the confirm text shows both numbers. Decks of whole sentences match almost nothing.
- Settings -> Anki -> Card type "Honmani" (default) or "Sentence": adding a word (panel, Learning, "Add
  all N") creates the note type "Honmani Sentence": front = the whole source sentence with no mark on the
  word, back = word, reading, meanings (no stored sentence: the word). "Check against Anki" then uses
  that type. Export "Anki package" follows the choice; the text file always uses the Honmani type.
- "My own" card type: the field mapping fills a note type the user already has; no field added, template
  untouched.
- Both Honmani note types end in `FuriganaPlain` (`飛[と]び 散[ち]る`, for `{{furigana:FuriganaPlain}}`),
  shown by no template. The first card to an older note type adds the field without asking (once per
  run; if AnkiDroid refuses, the card goes in without it). Changing a note type moves the collection's
  `scm`, so the next AnkiWeb sync goes one way: sync all devices first. Importing an `.apkg` over an older
  type leaves it and makes "Honmani Sentence+". Settings -> Anki -> Sentence ends: sentence-end
  characters for new cards, sentence highlights and the sentence tint; old cards unchanged.

## Dictionary search and radicals

- Words -> "Search dictionaries": results while typing. 食べる, たべる, taberu, neko, "cat" (English: 猫
  first); saved words in "In your words". Tap a result -> entry, query joins "Recent searches". Right
  after a dictionary install a note says English search is getting ready (~1 min); Japanese and romaji
  work at once. Launcher long-press "Search" opens Words with the field focused.
- Radical search: button 部 at the end of the field -> sheet of radicals by stroke count. 木 + 日 ->
  kanji containing both (東, 杳, ...); dead-end radicals dimmed; "Reset" clears. Tap a kanji -> inserted
  at the cursor, sheet closes, search runs. Check at "Largest" font on a narrow phone (nothing clipped)
  and TalkBack ("Radical 木, 4 strokes", checkboxes). Handwriting = keyboard (Gboard).

## Review

- Look up words in a book; next day Words shows "Review · n due" and the icon a badge. Front = sentence
  with the word marked; tap shows the answer; Forgot / Got it (or swipe left/right); Undo takes back the
  last answer. Back mid-session keeps the answers.
- ⋮: Bury until tomorrow, Mark known, Don't review, Review now, Edit meaning, Correct furigana (field
  per kanji part, kana only, Reset; same entry in the panel ⋮; changes only Honmani's built furigana:
  panel headword, card reading, new Anki cards, never the book), Add/Edit note (max 500 chars, several
  lines; back of the card under the meaning; empty removes it; survives re-lookup, remove and take
  back, backup restore; Words shows it), Look up, Open in book (opens at the sentence; Back returns to
  Words), Not a word, Remove.
- Chips other than Due (This book, A book..., Starred, All learning) review early and ignore the limit.
- Settings -> Flashcards -> "Reviews per day" (50 / 100 default / 200 / No limit): 120 due with 100 ->
  Words and badge say 100, session asks 100, most overdue first, 20 carry over. After 30 answers 70 are
  left (new words use "New words a day"); an Again returns today regardless. 200 / No limit shows all
  120. The in-book due tint follows the same cards.
- Settings -> Reading -> "Day starts at" (Midnight default, 1-6 am): with 4 am, reading and reviews at
  02:00 count for the day before (statistics, goal, streak, new words a day, reviews today, due);
  applies from the change, recorded days keep their date.
- Stability >= 365 days retires the card for good (never due; badge and tint ignore it); looking it up
  in a book after it is overdue lapses it, back the next day.
- Reading counts: a due word read in a book moves later (once a day), a lookup makes it due; closing the
  book shows how many due words you passed.
- Book sheet -> "Words to learn before reading" -> "Learn n words": come with the book's sentences,
  most used first; words already in Learning are not listed; "Top 20" ticks the 20 most used.
- Largest font: chips wrap uncut, answer buttons and menu usable.
- Progress shows reviews today, review time, retention, words in review. Progress -> Statistics ->
  "Export for Refold (CSV)" offers a file holding only reading not exported before (today's so far
  included; Refold adds rows up, so a second same-day export has only the new minutes); "Export
  everything again" repeats all, only for a new account.

## Search in the book

Reader menu -> Search in book: a word occurring several times -> each match bold with surroundings and
place (%), in reading order; furigana not matched. Tap a later match -> opens there, marked yellow
briefly (also far down a long paragraph; vertical/horizontal, paged/continuous). Back returns to the
earlier page. Check speed in a long book and that the keyboard does not cover the list.

## Read aloud

Needs a Japanese voice (Android Settings, Text-to-speech); media volume audible. Reader menu -> Read
aloud, on a page with furigana.

- Bar at the bottom (previous, play/pause, next, speed, close); first sentence marked grey, then spoken;
  the mark follows the sentence, inside a long one the words; visible next to word marks and
  highlights and in e-ink mode.
- A ruby word is read as its furigana.
- End of page: the book turns itself (a sentence crossing the break turns when the rest is off the page);
  paged and continuous, both directions. Close and reopen: on the page reached.
- Pause/play resumes from the sentence start; next/previous move one sentence, also paused. Speed cycles
  0.75/1/1.25/1.5 and is kept per book. Fits at 360 dp and the largest font; TalkBack reads each button.
- Tap a word: pauses while the panel is open, resumes from the sentence start on close; the panel's word
  audio never plays over the voice.
- A call, other player or Home pauses it; screen stays on only while reading.
- No Japanese voice: the bar says so and offers "Voice settings". End of book: it stops and says so.
- Rotate, change font, close the book while reading: no crash, no voice left over.
- Reader menu -> Pronunciations: add "text in the book" and "read as (kana)", Save; Read aloud speaks the
  kana (logcat tag ReadAloud, "speak" line); delete shows Undo; removing the book removes its entries;
  backup restore brings them back (a restored phone keeps its own reading).

## Audiobook

Needs an EPUB, an audio file and an SRT/WebVTT matching the book's text. Use a silent recording (media
volume stays 0). Reader menu -> Audiobook: choose the audio, then the subtitles.

- Bar appears, "Matching the subtitles to the book..." briefly, then audio plays and the subtitle heard
  is marked. Pages turn at the end of a page (both directions, paged and continuous). Turned pages
  count as reading: with progress sync the position reaches the server (a far jump does not).
- Subtitles not in the book change nothing (mark stays until the next found one); fewer than half found
  -> the bar says so.
- Next/previous move one subtitle (previous first restarts the current), also paused. Speed cycles
  0.75/1/1.25/1.5 and is kept.
- Pause, close the app: audio paused, screen no longer kept on, Play later continues. A call or other
  player pauses; a word tap pauses while the panel is open.
- Starting Read aloud stops the audiobook and vice versa.
- Remove: pairing, subtitle copy and position gone. Delete the audio file, play -> "audio is gone" with
  "Change files". Unplayable audio, empty subtitle file, non-EPUB book -> readable message, no crash.

## Bookmarks and the reading slider

Reader menu top: slider with live label ("42% · 10,350 / 24,847"), Add bookmark, Bookmarks. Check paged
and continuous, vertical and horizontal, EPUB and TXT:
- Drag the slider: label follows, nothing moves until release; then the book reloads there and "Jumped
  to N%" offers "Back to where you were" (exact earlier page).
- Add bookmark: name prefilled from the text at the place, editable; place = current page. Bookmarks in
  reading order with name and %; tap jumps, pencil renames, bin deletes with Undo. Removing the book
  removes them.
- Backup then restore on a fresh install with the same book: bookmarks there; twice adds nothing.
- With progress sync (EPUB): after a slider/bookmark jump nothing is sent until a page is turned; the
  other device is not asked about a backward jump.
- Long press and tap on text still look up after a jump.

## Highlights

Needs an EPUB and "Look up selected text" on. Check on light, dark, sepia, black; vertical/horizontal;
paged/continuous; with/without furigana:
- Own long press on a word (no blue selection or handles): marked while the finger is down, the lookup
  panel opens on release; moving first cancels. Panel: Selection / Sentence / Paragraph (Sentence
  chosen) with the text in one line, and "Highlight" with four colours; one tap saves, closes the panel,
  "Highlight saved" with Undo. A sentence ends at 。！？ or a line break and keeps a closing 」; a paragraph
  can span pages (marks on both after a turn); furigana is part of neither.
- A new highlight replaces overlapping ones (any colour); Undo brings them back.
- Reopen the book, change font size, rotate -> same text still highlighted.
- Tap inside a highlight: lookup opens as usual with the colours (current ticked) and "Remove highlight";
  another colour changes it; Remove deletes, with Undo.
- Reader menu -> Highlights: passage, colour dot, place; tap jumps ("Back to where you were"), bin
  removes with Undo. Removing the book removes its highlights.
- Backup then restore on a fresh install with the same book: highlights there.
- "Look up selected text" off: none can be made; existing ones still drawn, listed, removable. TXT
  books offer no Highlight.
- E-ink: four solid light greys (220, 200, 180, 160); a word mark is translucent black over them
  (darker inside a highlight); lookup mark and search hit are solid and cover it.
- Word mark, lookup mark and search hit lie over a highlight without hiding the text.

## Reading progress sync (KOReader)

Setup: KOReader on an e-reader (or Calibre-Web Automated with its "CWA Progress Sync" plugin; in Honmani
its plain address and Calibre-Web login suffice) on the same sync server; same EPUB on both. Settings
-> Reading progress sync, sign in, read a few pages, back to the library.
- KOReader opens the book and offers Honmani's position (top of the shown page). Read on in KOReader,
  open in Honmani -> "Continued at ...% from ..." at KOReader's page. Aozora-converted books match only
  with the same EPUB on both.
- Offline (mobile data with a home server, or flight mode): read in Honmani, back to the library,
  reconnect (or restart on home Wi-Fi) -> without reopening the book KOReader offers Honmani's last
  page. Conflict: read further offline in Honmani, open the book in KOReader at its older place and
  close, reconnect, open in Honmani -> "Where to continue?"; "Stay at ..." keeps Honmani's page (KOReader
  offers it next time), "Go to ..." jumps back.
- Status: Settings shows "Last synced ... ago" or "Not synced yet". Server off or network gone, turn a
  page or open a book -> "Last attempt failed ...: server unreachable" (other reasons: wrong login,
  HTTP status); that book's reader menu starts with "Progress sync failed: ..."; other books show no
  hint. Server back -> the next sync clears both.

## Back up and restore

- Settings -> Back up and restore -> Back up..., save the file. Uninstall, reinstall, get a dictionary,
  Restore... -> accent colour, reader settings, Words (Learning/Known progress), Progress (level, streak,
  calendar, milestones, skill) and Statistics return. Re-adding a book (same file or from the server):
  old progress, opens at the old page, has its statistics. Restoring the same file twice changes
  nothing. Review cards return with their due dates (answer after the backup, restore -> back at the
  backup's date); Progress -> Overview shows the review tiles.
- With progress sync signed in: read on in KOReader after the backup, restore, add the book -> opens at
  KOReader's page ("Continued at ..."), not the backup's.
- Without a file: Honmani's own backup (rewritten on going to the background, at most every 6 h) rides
  Android's Google backup or phone-to-phone transfer. The new phone's first start asks "Restore from your
  previous phone?" (with the backup date); Restore = restore from file; Android already restored the
  settings.
- Android's backup without a Google account, over USB with the debug-only local transport (the phone's
  backup setting is off by default; undo afterwards):

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

- Diagnostics show "Failed to register a ServiceWorker": ttu's offline cache, unneeded. Harmless.
- ttu's Google Drive / OneDrive sync and backup downloads do not work.
- Dictionary images are not shown.
- Choosing one of ttu's web fonts in its settings brings back a 10 s wait on opening books (not bundled).
