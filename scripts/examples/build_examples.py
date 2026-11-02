#!/usr/bin/env python3
"""Builds examples-1.db.gz, the optional example sentences the app downloads (Settings → Example
sentences). Run by hand when the data changes; the output goes to a GitHub release, never into
the repository.

Usage (the Tatoeba exports are untrusted data: keep them in their own directory, run with -I):

  mkdir /tmp/tatoeba && cd /tmp/tatoeba
  curl -O https://downloads.tatoeba.org/exports/jpn_sentences.tsv.bz2   # per-language exports
  curl -O https://downloads.tatoeba.org/exports/eng_sentences.tsv.bz2
  curl -O https://downloads.tatoeba.org/exports/jpn_indices.tar.bz2
  python3 -I scripts/examples/build_examples.py /tmp/tatoeba /tmp/examples-out/examples-1.db.gz

jpn_indices lists, per Japanese sentence, the id of its English translation and the headwords of
the Tanaka corpus ("B line"), such as `は 二十歳(はたち){２０歳} になる[01]{になりました}`: the
headword runs up to the first of `( [ { ~ |`, an optional `(reading)` follows, and `~` marks a
checked good example. Per (headword, reading) the 5 best sentences are kept: checked ones first,
then the shorter Japanese. Tatoeba sentences are CC BY 2.0 FR (https://tatoeba.org).

Output (SQLite, vacuumed, gzip):
  sentence(id, ja, en)                           the sentences that at least one headword keeps
  word(word, reading, sentence_id, rank)         WITHOUT ROWID; reading '' when the line has none;
                                                 rank 0..4 is the order within the headword
  meta(key, value)                               source, source date, licence
"""
import bz2
import datetime
import gzip
import os
import re
import sqlite3
import sys
import tarfile
import tempfile
from collections import defaultdict

PER_WORD = 5
STOP = re.compile(r"[(\[{~|]")
READING = re.compile(r"\(([^)]*)\)")


def tsv(path):
    with bz2.open(path, "rt", encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 3:
                yield parts


def index_lines(path):
    with tarfile.open(path) as t:
        member = next(m for m in t.getmembers() if m.isfile())
        for line in t.extractfile(member).read().decode("utf-8").splitlines():
            parts = line.split("\t")
            if len(parts) >= 3:
                yield parts


def tokens(b_line):
    """Yields (headword, reading, checked) for each token of a Tanaka B line."""
    for token in b_line.split():
        word = STOP.split(token, 1)[0]
        if not word:
            continue
        m = READING.search(token)
        yield word, m.group(1) if m else "", "~" in token


def build(src, out_gz, source_date):
    jpn = {int(p[0]): p[2] for p in tsv(os.path.join(src, "jpn_sentences.tsv.bz2"))}
    eng = {int(p[0]): p[2] for p in tsv(os.path.join(src, "eng_sentences.tsv.bz2"))}
    pairs = {}
    for sid, eid, b_line in index_lines(os.path.join(src, "jpn_indices.tar.bz2")):
        sid, eid = int(sid), int(eid)
        if sid in jpn and eid in eng:
            pairs[sid] = (jpn[sid], eng[eid], b_line)
    by_word = defaultdict(set)
    for sid, (ja, _, b_line) in pairs.items():
        for word, reading, checked in tokens(b_line):
            by_word[(word, reading)].add((0 if checked else 1, len(ja), sid))
    # A sentence can list a headword twice, once checked: the better entry wins above (set of
    # tuples keeps both), so keep each sentence once per headword.
    kept = {}
    for key, entries in by_word.items():
        seen = set()
        ranked = []
        for entry in sorted(entries):
            if entry[2] not in seen:
                seen.add(entry[2])
                ranked.append(entry[2])
        kept[key] = ranked[:PER_WORD]
    used = {sid for sids in kept.values() for sid in sids}

    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, "examples.db")
        db = sqlite3.connect(path)
        db.execute("create table sentence(id integer primary key, ja text not null, en text not null)")
        db.execute(
            "create table word(word text not null, reading text not null, sentence_id integer not null,"
            " rank integer not null, primary key(word, reading, sentence_id)) without rowid"
        )
        db.execute("create table meta(key text primary key, value text not null)")
        db.executemany(
            "insert into sentence values(?,?,?)", ((sid, pairs[sid][0], pairs[sid][1]) for sid in sorted(used))
        )
        db.executemany(
            "insert into word values(?,?,?,?)",
            ((w, r, sid, rank) for (w, r), sids in sorted(kept.items()) for rank, sid in enumerate(sids)),
        )
        db.executemany(
            "insert into meta values(?,?)",
            [
                ("source", "Tatoeba (https://tatoeba.org), Japanese sentences with English translations"),
                ("source_date", source_date),
                ("licence", "CC BY 2.0 FR"),
                ("licence_url", "https://creativecommons.org/licenses/by/2.0/fr/"),
            ],
        )
        db.commit()
        db.execute("vacuum")
        db.close()
        size = os.path.getsize(path)
        with open(path, "rb") as f, open(out_gz, "wb") as raw:
            # mtime=0 and no file name: the same input gives the same bytes (and checksum).
            with gzip.GzipFile(filename="", mode="wb", fileobj=raw, compresslevel=9, mtime=0) as g:
                while chunk := f.read(1 << 20):
                    g.write(chunk)
    print(f"sentences {len(used)}, headwords {len(kept)} (word rows {sum(len(v) for v in kept.values())})")
    print(f"unpacked {size} bytes, gz {os.path.getsize(out_gz)} bytes -> {out_gz}")


if __name__ == "__main__":
    if len(sys.argv) not in (3, 4):
        sys.exit(__doc__)
    date = sys.argv[3] if len(sys.argv) == 4 else datetime.date.today().isoformat()
    build(sys.argv[1], sys.argv[2], date)
