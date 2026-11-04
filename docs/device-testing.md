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
  furigana (also built in: the first-start screen offers it, Settings → Sample books has eight
  more). It is known to be well-formed, so if it shows no text the problem is in the app.
- Any other DRM-free Japanese EPUB, or an Aozora Bunko `.txt` file (from a work's page on
  aozora.gr.jp, the zip under "テキストファイル(ルビあり)"; unzip it first).

## Before a release

A quick pass over what users hit first, on a fresh install of the release build (10 minutes):
first start with the sample book; tap a few words (conjugated forms, furigana); add your own EPUB
and an Aozora `.txt`; tap an EPUB in the phone's file manager and in Chrome's downloads (is
Honmani offered under "Open with"?); vertical and horizontal mode; volume down/up turn to the next/previous
page in a book (and change the volume in the library); rotate; back to the library; dark mode;
add a card to AnkiDroid; Words → export (arrow up) → "Anki package" and import the .apkg in Anki on a
computer (cards with audio in the chosen deck); force-stop and reopen (books, dictionaries,
position kept).

Do the first-start part on a **fresh install** (uninstall first, or clear the app's data): open
the sample book from the empty library and check that the text shows within a few seconds. Policies
and storage that only break with nothing cached (a blocked import worker, say) don't show on a
phone that has opened a book before.

Long-press a word: the lookup panel opens, no blue selection handles or Copy toolbar stay, and the
panel offers "Copy word" and "Copy sentence". With the panel open a volume press only closes it;
the next one turns the page.

Immersive mode (Reader settings, off by default): with the switch on, the status and navigation bars
are hidden while a book is open (`dumpsys window | grep ITYPE_STATUS_BAR` shows `visible=false`) and
back on the library, other tabs, Settings and the reader settings; a swipe from the top edge shows them
for a moment, over the page. The book fills the screen, and its position doesn't move (ttu's counter and the
bookmark stay the same when the bars show, when the switch changes, after rotating and on reopening); the reader menu, the lookup panel, the volume buttons
and Back work as before, and the bars are hidden again after leaving the app and coming back.

E-ink mode (Reader settings, off by default): with the switch on, a book page is black on white whatever
the theme (check `getComputedStyle(document.body).backgroundColor` and the colour of `.book-content`
through the DevTools socket), has no animations, transitions or shadows, and shows the word marks
and the lookup mark as flat greys (the lookup mark black with white text); the lookup panel still opens for
a tap on a word. The library keeps its colours. Turning the switch off brings the earlier theme back
exactly (`localStorage.theme` is the same before, during and after; the Auto theme still follows the system).

Custom CSS (Reader settings, "Custom CSS", empty by default): the row opens a multi-line field with a byte
counter (at most 20 KB), Save, Clear and Cancel; mismatched braces or quotes and too much text show an
error and Save stays off. With `p { line-height: 3 }` saved, the book's paragraphs get the new line height
(`getComputedStyle(document.querySelector('.book-content p')).lineHeight` over the DevTools socket) and
ttu's menu and the footer do not; the reading position stays the same after the reload; a word still opens
the lookup panel. Empty means no `<style id="honmani-custom-css">` in the page at all. Broken CSS never
makes the book unusable: the reader settings screen is native and Clear removes it. A `url()` in the CSS
makes no network request (checked on the 7 Pro with a local server behind `adb reverse`: background, content, border-image and @font-face url() all stayed silent).

Pictures: in a book with a picture (a cover page or an illustration, horizontal and vertical), a tap on
it opens it full screen on black, with the cover and a big photo both readable; pinch and double tap zoom
in (a second double tap goes back to fit), a drag moves it only as far as its edges, and Back or the
close button (TalkBack: "Close picture") return to the same page. A tap on text beside the picture still
opens the lookup, a tap on the picture never turns a page (also with edge taps on) or opens a lookup, and
a tiny inline picture (a character's) and a picture in a link do nothing new.

Edge taps (Reader settings, off by default): with the switch on, a tap in the outer 15 % at the left or
right of a paginated page turns it (vertical text: left = next, right = previous; horizontal: the
reverse); with the panel open the first edge tap only closes it; a tap on a link, the footer or the top
bar, a long press and the middle of the page work as before; with the switch off edge taps look up.
With the switch on the settings also show "Width of the page edges" (Narrow 10 %, Medium 15 % as above,
Wide 25 %: a tap just outside the zone looks up, just inside turns) and "Both edges turn forward" (off by
default): either edge goes to the next page, in vertical and in horizontal text.

Lookup panel height: tap a short word and a long one, a kanji and a non-word: the panel's top edge
stays at the same place (about 40% of the screen), the content scrolls inside. Drag the handle or
header up: it grows to about 88%; tap the handle: it toggles (TalkBack: "Expand"/"Collapse");
drag down: it shrinks, then closes. A new tap opens it small again; back, kanji and longer/shorter
keep its size. Rotated to landscape the panel stays usable.

Don't count: tap a word you have not looked up before: the panel's top row shows the undo arrow "Don't
count" beside the speaker, and at the largest font a match of 5 characters (e.g. 食べなかった shortened once) is not cut. Press it: the panel closes and the word is not in Words (the count there is as before).
Tap a saved word: the same button; pressing it leaves the word and its older lookups as they were (lookup
count back, a Learned mark back, a due review card back as it was, the word's progress back). Looking up
again within the hour in the same sentence changes nothing, so there is no button. A word starred or added
to Anki from the panel is kept. Taps on words and kanji inside a definition, and "Edit and look up", save
and change nothing.
Nested lookups: in a panel (book, search, shared text), tap a Japanese character inside an example
sentence (Jitendex: "More meanings") or a cross-reference: that word's entry (longest match from the
character, inflected forms too) replaces the panel and a back arrow appears. Tap a Japanese word there
too, then Back (arrow or system) twice: each step returns one entry, the first entry is as it was; Back
once more closes the panel. Taps on English text do nothing. Nested words are not in Words, no progress.

## Back up and restore

Settings → Back up and restore → Back up…, save the file (to Downloads, say). Uninstall Honmani,
install it again, get a dictionary, then Restore… and pick the file: accent colour, reader
settings, Words (with Learning/Known progress), Progress (level, streak, calendar, milestones,
skill) and Statistics come back. Add one of the books again (the same file, or from the book
server): it shows its old progress, opens at the old page and has its statistics. Restoring the
same file again changes nothing. Review cards come back with their due dates (answer a review
after the backup, restore: the card is back at the backup's date), and Progress → Overview shows
the review tiles (reviews today, review time, retention, words in review).
With progress sync signed in: read on in KOReader after the backup, then restore and add the
book: it opens at KOReader's page ("Continued at …"), not at the backup's.

Without a backup file: Honmani keeps one of its own (rewritten when it goes to the background,
at most every 6 hours), which Android's backup to the Google account or a phone-to-phone
transfer takes along. On the new phone, the first start asks "Restore from your previous
phone?" (with the date the backup was made); Restore brings back the same as a restore from a
file, and Android has already put the settings back.

Android's backup can be tried without a Google account over USB, through the debug-only local
transport (the phone's own backup setting is switched off by default; undo it afterwards):

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

## Anki

Words → Learning: "Check against Anki" (beside "Add all N to Anki") forgets the "in Anki" mark of
cards deleted in AnkiDroid and marks words whose card exists there. It only looks at Honmani's
own note type of the chosen "Card type" and at words already saved. "Choose a deck" (also in Settings → Anki) marks saved
words found in a deck's word field as learned; decks of whole sentences match almost nothing.

Settings → Anki → Card type: "Honmani" (default) or "Sentence". With "Sentence", adding a word
(lookup panel, Words → Learning, "Add all N to Anki") creates the note type "Honmani Sentence" in
AnkiDroid: the front is the whole sentence the word came from with no mark on the word, the back
the word, its reading and meanings (a word without a stored sentence shows the word instead).
"Check against Anki" then looks at that note type. Words → export → "Anki package" uses the same
choice; the text file always uses the Honmani note type.

Both note types end in `FuriganaPlain`, the reading in Anki's notation (`飛[と]び 散[ち]る`, for the
`furigana:` filter). A note type from an older Honmani is left alone and its cards go in without
the field; Settings → Anki then shows "Add the FuriganaPlain field", which warns first: changing a
note type moves the collection's schema stamp (`scm`), so the next AnkiWeb sync has to go one way.
Importing an `.apkg` over an older type leaves it as it is and makes a second one ("Honmani
Sentence+"). Settings → Anki → Sentence ends: the characters that end a sentence on new cards, in
sentence highlights and in the sentence tint; old cards are not changed.

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

Sync status: with sync signed in, Settings → Reading progress sync shows "Last synced … ago"
(or "Not synced yet"). Turn the server off (or leave the network) and turn a page or open a
book: Settings says "Last attempt failed …: server unreachable" (other reasons: wrong login,
HTTP status), and in that book the reader menu (tap the bottom strip) starts with "Progress sync
failed: …"; other books show no hint. When the server is back, the next sync clears both.

## Word marks and known words

Reader settings → Mark words. "Learning" (the default): look up a word, turn a page or two and
come back: the word and its other forms (食べた, 食べない) have a light accent tint; after
"Learned" in Words it loses it. Other spellings of it count as the same word: after looking up
当たり前, 当り前 has the tint too (with or without furigana, one unbroken mark). "+ New" adds a grey
tint on words never met in your reading (at first nearly everything, particles too); words read
once lose it the next time they come up. "Off" shows none. On a dark page (black, grey or dark
system theme) both tints are stronger, so they still show at low screen brightness. The first
marks after a new install or a dictionary change take a few seconds (the word list is built
once); after that they fade in within about a second of opening a book, and a turned page shows
its marks at once.

Optional, in the same settings: hide the furigana over words you know (takes effect when the book
is opened again; a lookup brings it back), and tint sentences with exactly one unknown word.
Reader settings → "Mark words due for review" (on by default): a word whose flashcard is due today has
an amber tint instead of the Learning one (blue when the accent is amber or yellow, as the default is;
Android's WebView draws no underlines in highlights). Check one word on a light and on a dark page;
turn the switch off and open the book again: Learning tint. After the word is answered in a review (not
due any more) it gets the Learning tint back.

Book sheet of a graded book: "You know N% of its words; M are new to you". N counts only words that
are Known in your own word list (the ones the book text marks Known), so a new user sees 0%; words in
Learning are not known and not counted as new. Mark a word Known in Words and N rises; "Words to
learn before reading" lists the M new words, most used first. The difficulty and the cover badge do not change.

Ignore word: in the lookup panel, ⋮ beside the star → "Ignore word" (the panel stays; the word shows
"Stop ignoring"); in Words, expand a row → "Ignore word" (snackbar with Undo). An ignored word has no
tint in the book (neither Learning nor grey "+ New"), is out of Words → Learning/Learned/All, its card
is out of the review queue, and it is not in the book sheet's N%, M or "Words to learn". Words ⋮ →
"Ignored words" lists them; "Stop ignoring" brings back its earlier state (Learning, Known, card). A
lookup of it still works. Backup and restore keep it.

## Dictionary search

Words → "Search dictionaries": results appear while you type. Try 食べる, たべる, taberu, neko and
"cat" (English: 猫 first); saved words show in their own "In your words" section. A tap on a result
opens the entry and adds the query to "Recent searches". Right after a dictionary is installed a
note says English search is getting ready (about a minute); Japanese and romaji work at once.
Long-press the launcher icon: "Search" opens Words with the field focused.

Radical search: the button 部 ("Search by radical") at the end of the field opens a sheet with the
radicals by stroke count. Pick 木 and 日: the kanji list shows what contains both (東, 杳, ...), radicals
that lead nowhere are dimmed, "Reset" clears the picks. A tap on a kanji inserts it at the cursor
of the field and closes the sheet; the search runs as for typed text. Check it at font size
"Largest" on a narrow phone (nothing clipped) and with TalkBack (radicals read "Radical 木, 4
strokes" and as checkboxes). Handwriting is the keyboard's job (Gboard's Japanese handwriting layout).

## Review

Add a few words to Learning (look them up in a book). The next day Words shows "Review · n due" and
the Words icon a badge. Start: the front shows the sentence with the word marked, a tap shows the
answer, Forgot / Got it (or swipe left/right) moves on, Undo takes the last answer back. ⋮ offers
Bury until tomorrow, Mark known, Don't review, Review now, Edit meaning, Correct furigana (a field per
kanji part, kana only, Reset; the same entry is in the lookup panel's ⋮; it changes only the furigana
Honmani builds: the panel headword, the card's reading and new Anki cards, never the book), Add note / Edit note (up to 500
characters, several lines; on the back of the card only, under the meaning; saving it empty removes it; it stays
when the word is looked up again, removed and taken back, or restored from a backup; Words shows it when a word is
opened), Look up, Open in book (the
book opens at the sentence; Back returns to Words), Not a word and Remove. Back mid-session keeps the
answers given. Chips other than Due (This book, A book…, Starred, All learning) review words early.
Settings → Flashcards → "Reviews per day" (50 / 100 default / 200 / No limit): with 120 cards due and
100 chosen, Words and the badge say 100 due and the session asks 100, the most overdue first; the
other 20 carry over to tomorrow. A card answered today counts, so after 30 answers 70 are left (new
words count against "New words a day" instead); an Again comes back today whatever the limit. The
chips other than Due ignore the limit. Switching to 200 or No limit shows all 120 again. The
due tint in the book follows the same cards. Settings → Reading → "Day starts at" (Midnight default, 1 am … 6 am): with 4 am chosen,
reading and reviews at 02:00 count for the day before (statistics, goal, streak, "New words a day", reviews today, what is due); the
setting applies from the moment it is changed, days already recorded keep their date. At the largest font size the chips wrap, none is cut.
A card whose memory has reached a year (stability 365 days or more) leaves the queue for good: it is never due,
the badge and tint ignore it; looking such a word up in a book after it is overdue lapses it and it comes back the next day.
Reading counts too: a due word read in a book moves later (once a day), a lookup in the book makes it
due; closing the book shows how many due words you passed. Book sheet → "Words to learn before reading" →
"Learn n words": they come with their sentences from the book, the ones used soonest first.
Words already in Learning are not listed; "Top 20" ticks the 20 most used. At the largest font size the answer buttons
and the menu stay usable. Progress shows reviews today, review time, retention and words in review;
Progress → Statistics → "Export for Refold (CSV)" offers a file to share. It holds only reading that
was not exported before, today's so far included; Refold adds every imported row up, so a second
export the same day holds just the minutes read since ("Export everything again" repeats all, only for a new account).

## Reader bottom menu

In a book, tap the bottom edge of the page (the thin strip under the text). A sheet opens with
Contents (ttu's table of contents opens), a reading slider, Add bookmark, Bookmarks, Highlights, Search in book, Reader settings, Words from this session, Show progress
(a switch; the page reloads and the counter appears or disappears) and Close book (back to the
book list). A tap on the text still looks a word up. The tap on the bottom edge no longer hides the
progress counter. Check that the strip is easy to hit with the gesture bar and that page turns at
the left and right edges still work.

A long swipe up (about a quarter of the screen, mostly straight) from anywhere on the page opens the
same menu. Check: paged mode, horizontal and vertical text: it opens; a short swipe up, a diagonal
swipe and a normal page-turn swipe do not; with the lookup panel open a swipe does nothing; in
continuous mode with horizontal text a swipe up scrolls the book and the menu stays closed (vertical
text in continuous mode opens it); a long press on text still looks up and a tap still behaves as before.

## Search in the book

Reader menu, Search in book: type a word that occurs several times. The list shows each match in
bold with the text around it and its place in the book (%), in reading order; furigana is not part
of the text matched. Tap a later match: the book opens there and the match is marked yellow for a
moment (also when it is further down a long paragraph, in vertical and horizontal text, paginated
and continuous). Back from the search returns to the page you were on. Check a search in a long book
for speed and that the keyboard does not cover the list.

## Read aloud

Needs a Japanese voice (Android Settings, Text-to-speech output). Keep the media volume where you
can hear it, then reader menu, Read aloud, on a page of a book with furigana.

- The bar appears at the bottom (previous, play/pause, next, speed, close) and the first sentence on
  the page is marked in grey over the text, then said aloud. The mark follows the sentence, and
  inside a long sentence it follows the words. It is visible next to word marks and highlights, and in
  e-ink mode.
- A ruby word is read as its furigana (hiragana above the kanji), not as the kanji.
- At the end of the page the book turns by itself (a sentence that crosses the page break turns when the part
  still to read is off the page), in paginated and continuous mode, horizontal and vertical. Close the
  book and open it again: it is on the page the reading had reached.
- Pause, then play: it goes on from the start of that sentence. Next and previous move one sentence,
  also while paused. The speed button cycles 0.75, 1, 1.25, 1.5 and the book keeps the speed on the next
  start. Everything fits at 360 dp wide and the largest font; TalkBack reads each button.
- Tap a word: reading pauses while the panel is open, and goes on from the start of that sentence when
  it closes. The word's audio in the panel never plays over the reader's voice.
- A call, another player or the Home button pauses it. The screen stays on only while it reads.
- Without a Japanese voice the bar says so and offers "Voice settings".
- At the end of the book it stops and says so in the bar.
- Rotate the phone, change the font and close the book while it reads: no crash, no voice left over.

## Bookmarks and the reading slider

Reader menu, at the top: a slider with a live label ("42% · 10,350 / 24,847"), then Add bookmark and
Bookmarks. Check, in paged and continuous mode, vertical and horizontal text, EPUB and TXT:
- Drag the slider: the label follows the finger and nothing moves until you let go; then the book
  reloads at that place and a "Jumped to N%" message offers "Back to where you were", which returns to
  the exact page you were on.
- Add bookmark: the name is prefilled from the first characters at the place and can be edited; the
  place is the page you were on. Bookmarks lists them in reading order with name and %; a tap jumps,
  the pencil renames, the bin deletes and Undo brings it back. Removing the book removes its bookmarks.
- Backup, then restore on a fresh install with the same book: the bookmarks are there; restoring twice
  adds nothing.
- With progress sync on (EPUB): after a slider or bookmark jump nothing is sent to the server until a
  page is turned there; the other device is not asked about a backward jump.
- A long press and a tap on text still look a word up after a jump.

## Highlights

Needs an EPUB and "Look up selected text" on (Reader settings). Check on light, dark, sepia and black
themes, vertical and horizontal text, paged and continuous mode, with and without furigana:
- Long press a word (Android's handles are hidden on purpose, so the selection is one word or a few
  characters): the lookup panel shows Selection / Sentence / Paragraph (Sentence chosen) with the
  text of the choice in one line, and "Highlight" with four colours. One tap saves what is chosen,
  closes the panel and shows "Highlight saved" with Undo. A sentence ends at 。！？ or a line break and keeps a
  closing 」; a paragraph can run over several pages (marks on both pages after a turn); furigana is no part of either.
- A new highlight that overlaps older ones in the book replaces them (any colour); Undo brings them back.
- Close and reopen the book, change the font size, rotate: the highlight stays on the same text.
- Tap inside a highlight: the word lookup opens as always, with the colours (the current one ticked)
  and "Remove highlight"; another colour changes it, Remove deletes it and offers Undo.
- Reader menu, Highlights: the passage, a colour dot and the place; a tap jumps there ("Back to where
  you were" returns), the bin removes with Undo. Removing the book removes its highlights.
- Backup, then restore on a fresh install with the same book: the highlights are there.
- Selection lookup off: no new highlights can be made, existing ones are still drawn, listed and
  removable. A TXT book offers no Highlight.
- E-ink mode: four solid light greys (220, 200, 180, 160). A word mark is translucent black over them,
  so inside a highlight it shows darker; the lookup mark and a search hit are solid and cover it.
- A word mark, the lookup mark and a search hit lie over a highlight, they do not hide the whole text.

## Known limitations (no need to report)

- Diagnostics show "Failed to register a ServiceWorker": ttu tries to install its offline cache,
  which Honmani doesn't need (the reader is in the app). Harmless.
- ttu's Google Drive / OneDrive sync and backup downloads do not work.
- Dictionary images (pictures in some entries) are not shown.
- Choosing one of ttu's web fonts in its settings brings back a 10-second wait when opening
  books (those fonts are not bundled).
