# Aozora Bunko index

Builds `docs/aozora/index.json`, the catalogue behind the optional "free books" list. GitHub Pages
serves it at `https://cnfsd-labs.github.io/honmani-reader/aozora/index.json` (gzip-compressed on the
wire). The app downloads it on request; nothing is fetched at build time.

## Regenerate

```
node scripts/aozora-index/generate.mjs [--out docs/aozora/index.json] [--zip list.zip]
```

Node 24, no dependencies. The script downloads
`https://www.aozora.gr.jp/index_pages/list_person_all_extended_utf8.zip` into the program's
memory (a fresh temp directory is created and removed; nothing from the download is executed),
reads the one CSV in it with a built-in ZIP reader, and writes the index. `--zip` uses a local copy
instead of downloading. Commit the result; regenerate a few times a year (the list changes daily).

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
           "p":"2006-12-24","u":"001234/46340_ruby_24806.zip"}, ... ]}
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

## Credits

The work list is CC BY 4.0, "Aozora Bunko work list" (https://www.aozora.gr.jp/index_pages/person_all_extended.html).
Credit it in the app's About screen and in `THIRD_PARTY_NOTICES.md`. The texts are public domain
(rules: https://www.aozora.gr.jp/guide/kijyunn.html). The app's privacy policy
(`docs/privacy-policy.md`) must list the two new network uses: the index on GitHub Pages and the
text files on www.aozora.gr.jp.
