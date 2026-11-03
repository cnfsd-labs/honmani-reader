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

Edge taps (Reader settings, off by default): with the switch on, a tap in the outer 15 % at the left or
right of a paginated page turns it (vertical text: left = next, right = previous; horizontal: the
reverse); with the panel open the first edge tap only closes it; a tap on a link, the footer or the top
bar, a long press and the middle of the page work as before; with the switch off edge taps look up.

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
Bury until tomorrow, Mark known, Don't review, Review now, Edit meaning, Look up, Open in book (the
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
Contents (ttu's table of contents opens), Search in book, Reader settings, Words from this session, Show progress
(a switch; the page reloads and the counter appears or disappears) and Close book (back to the
book list). A tap on the text still looks a word up. The tap on the bottom edge no longer hides the
progress counter. Check that the strip is easy to hit with the gesture bar and that page turns at
the left and right edges still work.

## Search in the book

Reader menu, Search in book: type a word that occurs several times. The list shows each match in
bold with the text around it and its place in the book (%), in reading order; furigana is not part
of the text matched. Tap a later match: the book opens there and the match is marked yellow for a
moment (also when it is further down a long paragraph, in vertical and horizontal text, paginated
and continuous). Back from the search returns to the page you were on. Check a search in a long book
for speed and that the keyboard does not cover the list.

## Known limitations (no need to report)

- Diagnostics show "Failed to register a ServiceWorker": ttu tries to install its offline cache,
  which Honmani doesn't need (the reader is in the app). Harmless.
- ttu's Google Drive / OneDrive sync and backup downloads do not work.
- Dictionary images (pictures in some entries) are not shown.
- Choosing one of ttu's web fonts in its settings brings back a 10-second wait when opening
  books (those fonts are not bundled).
