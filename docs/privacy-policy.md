# Privacy policy for Honmani

*Last updated: 2026-11-09.*

Honmani is a free reader for Japanese books, published by CNFSD Labs. It has no account, no ads,
no analytics and no tracking. CNFSD Labs does not receive any data from the app.

## What stays on your phone

Your books, imported dictionaries, saved words, flashcard reviews, recent searches, reading statistics and settings are stored only
on your phone, in Honmani's own app storage. Uninstalling the app deletes them.

If you pair an audiobook with a book (reader menu, Audiobook), Honmani plays the audio file you chose
where it is (it keeps no copy), and keeps a copy of the subtitle file and where you stopped. These stay
on your phone, are not part of Android's backup, and are deleted when you remove the audiobook or the book. If you
export an audiobook, Honmani writes the audio and subtitles only into the folder you choose and sends
them nowhere.

If you have turned on Android's backup to your Google account (or move to a new phone with
Android's transfer), Android includes Honmani's settings (such as the accent colour and the Anki
deck) and a copy Honmani keeps of its own backup file (see below: your saved words with their
sentences, your flashcard reviews, reading progress, statistics, bookmarks and highlights, the titles of your books
and the addresses and user names of your book servers). On a new phone Honmani
offers to restore it. Books, dictionaries, passwords and keys are never part of it.

Settings → Backup writes your settings, reading progress, statistics, saved words
and flashcard reviews to a file in a place you choose, such as your phone's storage or a cloud storage app you picked.
Honmani sends it nowhere itself. The file holds no passwords or keys; it does hold your saved
words with their sentences, your bookmarks and highlights, the titles of your books and the addresses and user names
of your book servers, so keep it where only you can read it.

Settings → Backup → Sync folder: each phone writes one file with your progress, saved words (with their sentences),
flashcard reviews, reading time, bookmarks and highlights (no passwords or keys) into a folder you choose, and reads the
files of your other phones from it. The file is not encrypted. Honmani makes no network connection for this; a sync app
(Syncthing, Nextcloud, Google Drive and the like) that you point at that folder can move the files off your phone.

Exporting your library writes copies of your book files only into the folder you choose.

## When Honmani connects to the internet

Honmani only connects to the internet for these things, and only to these places:

- **Dictionaries and reader fonts** you choose to download, and a check for dictionary
  updates when the app starts (for dictionaries you imported yourself that offer updates, the check goes to the
  update address the dictionary names, which can be any https address): GitHub (github.com, raw.githubusercontent.com), Hugging Face
  (huggingface.co) and Jiten (api.jiten.moe), always over an encrypted connection (https).
  These sites see an ordinary download request (your IP address and the app's name); Honmani
  sends no personal data. Their own privacy
  policies apply.
- **Example sentences**, only if you switch them on in Settings: one file (about 4 MB) from
  GitHub (github.com), over https, with the same request details as above. The sentences are
  looked up on your phone; nothing about the words you look up is sent.
- **Free books (Aozora Bunko), only if you open "Free books" and tap "Load the list"**: Honmani
  asks cnfsd-labs.github.io (GitHub Pages) for the list of public-domain works (about 2.5 MB,
  less compressed), and, when you tap "Add to library" on a book, downloads that book's text
  from www.aozora.gr.jp. Both over https. Nothing is sent but the request itself (the address
  asked for and the app's name; no account, no identifier, nothing about your reading or
  searches: the search runs on your phone). Neither host is contacted before you tap, and
  the list is kept on your phone until you refresh it. Their own privacy policies apply.
- **Book servers you add yourself** (OPDS catalogues such as Calibre-Web). Honmani connects only
  to the addresses you enter, when the app starts if automatic import is on (it is by default for a new server) and when you open
  the catalogue; a search you type in a server's catalogue is sent to that server. If you give a user name and password, the password is stored
  encrypted on your phone and sent only to that server.
  At an unencrypted address (`http://`) anyone on the same network can read a password that
  is sent, and on another Wi-Fi the address can belong to someone else's device. There Honmani
  sends the login only after the server has asked for one, and asks again on every new
  network; on a hotspot with a login page (as in cafés and hotels) it sends none. A device
  that asks on purpose still gets it, so use `https://` or a VPN away from home. The same
  applies to reading progress sync.
- **Reading progress sync, only if you sign in** (Settings → Books and sync) to a
  KOReader sync server of your choice: for EPUB books, Honmani sends that server an identifier
  computed from the book file (not its title or text), your reading position and percentage,
  the name of your phone model and a random id Honmani made for this phone (so the server can
  tell your devices apart), and reads back the position saved by your other devices. You can
  also turn this on from a book server's settings, with that server's address and login.
  For KOReader's sync servers Honmani keeps only an MD5 hash of your password, encrypted on
  your phone, as KOReader does. Calibre-Web Automated checks your Calibre-Web password
  itself: for it, the password is stored encrypted on your phone and sent only to that server.
  Signing out deletes the stored login.
  The server's operator's privacy policy applies.
- **Google Cloud text-to-speech, only if you turn it on** and enter your own Google Cloud API
  key (stored encrypted on your phone, sent only to Google): to make audio for Anki cards, and to say a word when you tap its speaker in a lookup or on a
  flashcard, Honmani sends the word or example sentence and your key to Google
  (texttospeech.googleapis.com), over an encrypted connection. Google's privacy policy applies. The default voice is your
  phone's own, which works offline. Sound that a flashcard plays by itself (the "Play audio by itself" setting) always uses
  the phone's voice and never reaches Google.
- **Audiobook made with a Google voice, only if you start it for a book** (reader menu, Audiobook, "Make one
  with a Google voice (costs money)"; needs your own Google Cloud API key): Honmani sends the book's text,
  sentence by sentence (with the readings written out in place of furigana), and your key to Google
  (texttospeech.googleapis.com), over an encrypted connection, until the audiobook is done or you stop it.
  Google charges the account of your key per character sent; Honmani shows an estimate and keeps your
  spending cap, but cannot see your usage or free allowance. The audio stays on your phone, is kept out of
  Android's backup and is deleted with the book or when you delete it. Google's privacy policy applies.
- **WaniKani, only when you start it** (Settings or Words → Tell Honmani what you already know → WaniKani):
  Honmani sends the read-only API token you pasted to WaniKani (api.wanikani.com), over an encrypted connection, and
  reads back which vocabulary words (and, if you ask, kanji) you have at Guru (stage 5) or above, to mark them as known. The token
  is used for that one run only and is not stored, backed up or logged. WaniKani's privacy policy applies.

The book reader itself never loads anything from the internet.

Settings → Help and about → Network activity lists the latest requests Honmani made (when, what for, the server's
name and how it went). The list is kept in memory on your phone only: it holds no addresses,
logins or keys, is not sent anywhere unless you share the diagnostics yourself, and is gone when
the app stops.

## Other apps

- **AnkiDroid:** if you add words to Anki, Honmani hands them to the AnkiDroid app on your phone,
  after you allow it. Nothing leaves your phone for this.
- **Files for Anki on a computer:** saving your words as an Anki package or text file writes it
  where you choose in Android's save dialog. Honmani sends it nowhere.
- **Export for Refold:** the reading time per day and book (with the book titles) is written to a CSV file
  and Android's share menu opens. You choose who receives it, if anyone. Honmani sends it nowhere.
- **Share diagnostics:** only when you tap it in Settings, Honmani prepares a short technical
  report (app and Android version, the reader's log, why the app last crashed or was closed by
  Android, layout details of the last opened book, which can include short excerpts of its text)
  and opens Android's share menu. You choose who receives it, if anyone. The record of the last
  crash stays in the app's storage on your phone.

## Children

Honmani is not directed at children under 13.

## Changes

If this policy changes, the new version will be published here with a new date.

## Contact

CNFSD Labs – cnfsd.labs@gmail.com

Source code: https://github.com/cnfsd-labs/honmani-reader (GPL-3.0-or-later)
