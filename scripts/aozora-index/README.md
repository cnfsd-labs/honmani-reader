# Aozora Bunko index

Builds `docs/aozora/index.json`, the catalogue behind the optional "free books" list. GitHub Pages
serves it at `https://cnfsd-labs.github.io/honmani-reader/aozora/index.json` (gzip-compressed on the
wire). The app downloads it on request; nothing is fetched at build time.

## Regenerate

```
node scripts/aozora-index/generate.mjs [--out docs/aozora/index.json] [--zip list.zip] [--scores scores.jsonl]
```

Node 24, no dependencies. The script downloads
`https://www.aozora.gr.jp/index_pages/list_person_all_extended_utf8.zip` into the program's
memory (a fresh temp directory is created and removed; nothing from the download is executed),
reads the one CSV in it with a built-in ZIP reader, and writes the index. `--zip` uses a local copy
instead of downloading. `--scores` adds the difficulty (`d`) from a grader output file, see
"Difficulty scores" below. Commit the result; regenerate a few times a year (the list changes daily).

## What is listed

- One entry per work (the CSV has one row per person and work). The primary author is the first row
  whose role (`役割フラグ`) is `著者`; family and given name are joined without a separator.
- Only works whose work copyright flag (`作品著作権フラグ`) is `なし` and whose person flag
  (`人物著作権フラグ`) is `なし` on every row of the work. Aozora redistributes only works it marks
  free; a translator or editor still in copyright shows up as `あり` and the work is left out.
  Translations are therefore covered by the flag; no extra rule is applied.
- Only works with a text file hosted by Aozora itself, as `https://www.aozora.gr.jp/cards/<card>/files/<file>`
  (`.zip` or `.txt`). Works with only an HTML page or a file on a third-party site are left out.
- Of the 2026-11 list (17,840 works): 488 not free, 8 without a usable text file, 17,344 listed.

## Format (version 1)

```
{"version":1,"generated":"2026-11-07","source":"...","count":17344,
"authors":[ ["愛知敬一","あいちけいいち"], ... ],
"works":[ {"i":"046340","t":"ファラデーの伝","r":"ファラデーのでん","a":0,"s":"nn",
           "p":"2006-12-24","u":"001234/46340_ruby_24806.zip","d":96}, ... ]}
```

| Key | Meaning |
|---|---|
| `version` | Format version (integer). Readers should refuse a higher one. |
| `generated` | Date of generation, `YYYY-MM-DD`. |
| `source` | Human-readable origin and licence. |
| `count` | Number of works. |
| `authors` | Array of `[name, reading]` (reading in kana, as in the work list). Each person once. |
| `works[].i` | Aozora work ID (`作品ID`, six digits, a string). |
| `works[].t`, `.r` | Title and its kana reading (`作品名`, `作品名読み`). |
| `works[].a` | Position in `authors`. |
| `works[].s` | Script: two letters, kanji forms then kana forms, `n` = modern (新字/新仮名), `o` = old (旧字/旧仮名): `nn`, `no`, `on`, `oo`. Empty for the 16 works Aozora files under `その他`. |
| `works[].p` | Publication date on Aozora (`公開日`), `YYYY-MM-DD`. |
| `works[].u` | Text file as `<card>/<file>`. The URL is `https://www.aozora.gr.jp/cards/<card>/files/<file>`. |
| `works[].d` | Optional: approximate difficulty on the app's 1-100 scale (see "Difficulty scores"). Left out when the work is not graded. Readers must treat it as absent. |

The file has one author or work per line (readable diffs). Works are sorted by author reading
(katakana compared as hiragana), then author name, then title reading, then ID, so `a` never
decreases. The same title can appear several times (different scripts or editions): the ID is the
key. Card page of a work: `https://www.aozora.gr.jp/cards/<card>/card<ID without leading zeros>.html`.

## Text files

Checked on 3 works (`789_ruby_5639.zip`, `1567_ruby_4948.zip`, `48222_ruby_59603.zip`) and on the
CSV (`テキストファイル符号化方式`: 19,185 rows ShiftJIS, 10 UTF-8, 307 empty):

- Served as `application/zip`; each ZIP holds one `.txt` (romanised name, e.g. `hashire_merosu.txt`).
  All listed URLs end in `.zip` or `.txt`.
- The text is Shift_JIS (Windows-31J compatible) with CRLF line ends. A few files are UTF-8, so
  detect the encoding rather than assume it.
- It is Aozora text format: a header (title, author, the `【テキスト中に現れる記号について】` block),
  ruby as `|漢字《かんじ》`, annotations as `［＃…］`, gaiji as `※［＃…］`, and a footer with the
  source book (`底本`), inputter and proofreader (`入力`, `校正`) and `青空文庫作成ファイル：`.
  Honmani's `AozoraText` / `AozoraEpub` already convert this format to an EPUB.
- Aozora asks (but does not require) that the file information stays with the text: keep the
  title, author, base text, inputter notes and the `青空文庫作成ファイル：` line.

## Difficulty scores (one-off grading run)

`app/src/test/java/app/honmani/reader/data/difficulty/AozoraIndexGrader.kt` grades every work of
the index with the app's `Difficulty.measure`, the way an imported Aozora text is graded: download,
unzip, decode, `AozoraText.parse`, `AozoraEpub.write` (temp file), `EpubPositions.texts`. It is a
JUnit class that is skipped unless `HONMANI_GRADER_OUT` is set, so CI is unaffected.

Needs the same two dictionaries as the app's default setup, as Yomitan zips (keep them outside the
repo, e.g. in `~/.cache/honmani-grader/`): Jitendex (`DictionaryCatalog`, `jitendex-yomitan.zip`)
and the Jiten frequency list
(`https://api.jiten.moe/api/frequency-list/download?downloadType=yomitan`).

```
C=~/.cache/honmani-grader
HONMANI_GRADER_OUT=$C/scores.jsonl HONMANI_GRADER_CACHE=$C/texts \
HONMANI_GRADER_TERMS=$C/dict/jitendex-yomitan.zip HONMANI_GRADER_FREQ=$C/dict/jiten-frequency.zip \
./gradlew :app:testDebugUnitTest --tests '*AozoraIndexGrader*' -i
```

| Variable | Meaning |
|---|---|
| `HONMANI_GRADER_OUT` | JSON-lines result file (required; switches the run on). Appended to; ids already in it are skipped, so a stopped run resumes. |
| `HONMANI_GRADER_TERMS`, `_FREQ` | Term dictionary zip and rank-based frequency zip (required). |
| `HONMANI_GRADER_CACHE` | Directory for the downloaded files (required), so a second run needs no network. |
| `HONMANI_GRADER_LIMIT`, `_IDS` | Optional: at most N works, or only these comma-separated work IDs. |
| `HONMANI_GRADER_DELAY_MS` | Pause before each download, default 700. Cached files are not delayed. |
| `HONMANI_GRADER_INDEX` | Index file, default `../docs/aozora/index.json` (the test runs in `app/`). |

Requests are one at a time, with a descriptive User-Agent; HTTP 429/503 are retried with a growing
pause (30 s, then doubling, or the server's `Retry-After`), and the run stops after 5 download
failures in a row (just run it again). When the variable is set, `app/build.gradle.kts` lifts the
10 minute test timeout (12 h), gives the test JVM 3 GB and prints the output; the task never counts
as up to date, so no `--rerun` is needed.

Output: a `{"meta":"lexicon",...}` line per start (version, lexicon size and load time, the
dictionary files with sizes), then one line per work: `id, score, r95, characters, words, version,
lexicon` (the dictionaries and revisions used) and `error` when it failed (score 0; `http404`,
`too_large`, `unreadable`, `too_short`, `error:<exception>`). Works that failed only for network
reasons (`network`, `http429`, `http5xx`) are tried again on the next start, so the last line of an
id counts. Re-grade after `Difficulty.VERSION` changes with a new output file.

`generate.mjs --scores <that file>` puts each work's `score` into the index as `d` (the last line
of an ID wins; failed works, score 0, get no `d`). Adding `d` keeps the format at version 1: older
readers ignore the key. The score is approximate (it depends on the dictionaries and on how the
text was cut into words), so the app labels it that way.

## Credits

The difficulty scores are computed with word frequencies from Jiten (https://jiten.moe, CC BY-SA 4.0)
and Jitendex (https://jitendex.org, CC BY-SA 4.0, Stephen Kraus); the app's About screen and
`THIRD_PARTY_NOTICES.md` credit both.

The work list is CC BY 4.0, "Aozora Bunko work list" (https://www.aozora.gr.jp/index_pages/person_all_extended.html).
Credit it in the app's About screen and in `THIRD_PARTY_NOTICES.md`. The texts are public domain
(rules: https://www.aozora.gr.jp/guide/kijyunn.html). The app's privacy policy
(`docs/privacy-policy.md`) must list the two new network uses: the index on GitHub Pages and the
text files on www.aozora.gr.jp.
