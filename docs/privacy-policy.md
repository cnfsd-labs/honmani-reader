# Privacy policy for Honmani

*Last updated: 2026-10-22.*

Honmani is a free reader for Japanese books, published by CNFSD Labs. It has no account, no ads,
no analytics and no tracking. CNFSD Labs does not receive any data from the app.

## What stays on your phone

Your books, imported dictionaries, saved words, reading statistics and settings are stored only
on your phone, in Honmani's own app storage. Uninstalling the app deletes them.

If you have turned on Android's backup to your Google account (or move to a new phone with
Android's transfer), Android includes Honmani's settings (such as the accent colour and the Anki
deck) and a copy Honmani keeps of its own backup file (see below: your saved words with their
sentences, reading progress, statistics and the titles of your books). On a new phone Honmani
offers to restore it. Books, dictionaries, passwords and keys are never part of it.

Settings → Back up and restore writes your settings, reading progress, statistics and saved words
to a file in a place you choose, such as your phone's storage or a cloud storage app you picked.
Honmani sends it nowhere itself. The file holds no passwords or keys; it does hold your saved
words with their sentences and the titles of your books, so keep it where only you can read it.

## When Honmani connects to the internet

Honmani only connects to the internet for these things, and only to these places:

- **Dictionaries and reader fonts** you choose to download, and a check for dictionary
  updates when the app starts: GitHub (github.com, raw.githubusercontent.com), Hugging Face
  (huggingface.co) and Jiten (api.jiten.moe). These sites see an ordinary download request
  (your IP address and the app's name); Honmani sends no personal data. Their own privacy
  policies apply.
- **Book servers you add yourself** (OPDS catalogues such as Calibre-Web). Honmani connects only
  to the addresses you enter. If you give a user name and password, the password is stored
  encrypted on your phone and sent only to that server.
  At an unencrypted address (`http://`) anyone on the same network can read a password that
  is sent, and on another Wi-Fi the address can belong to someone else's device. There Honmani
  sends the login only after the server has asked for one, and asks again on every new
  network; on a hotspot with a login page (as in cafés and hotels) it sends none. A device
  that asks on purpose still gets it, so use `https://` or a VPN away from home. The same
  applies to reading progress sync.
- **Reading progress sync, only if you sign in** (Settings → Reading progress sync) to a
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
  key: to make audio for Anki cards, and to say a word when you tap its speaker in a lookup,
  Honmani sends the word or example sentence and your key to Google
  (texttospeech.googleapis.com). Google's privacy policy applies. The default voice is your
  phone's own, which works offline.

The book reader itself never loads anything from the internet.

## Other apps

- **AnkiDroid:** if you add words to Anki, Honmani hands them to the AnkiDroid app on your phone,
  after you allow it. Nothing leaves your phone for this.
- **Files for Anki on a computer:** saving your words as an Anki package or text file writes it
  where you choose in Android's save dialog. Honmani sends it nowhere.
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
