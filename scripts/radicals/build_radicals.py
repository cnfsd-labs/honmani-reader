#!/usr/bin/env python3
"""Builds app/src/main/assets/radicals.txt (the data of the radical search) from EDRDG files.

Usage (run once; the output is committed, nothing is built at build time):

  mkdir /tmp/krad && cd /tmp/krad
  curl -O http://ftp.edrdg.org/pub/Nihongo/kradzip.zip      # RADKFILEX, KRADFILE
  curl -O http://www.edrdg.org/kanjidic/kanjidic2.xml.gz    # stroke counts, frequency
  unzip kradzip.zip radkfilex
  python3 -I scripts/radicals/build_radicals.py /tmp/krad/radkfilex /tmp/krad/kanjidic2.xml.gz \
      app/src/main/assets/radicals.txt

RADKFILEX lists, per radical, the kanji (JIS X 0208 and 0212) that contain it. The output is the
inverse, one line per kanji (what KRADFILE and KRADFILE2 say, which RADKFILEX is built from), so
the app needs no sorting: kanji come ordered by stroke count (KANJIDIC2), then by frequency rank.

Format (UTF-8, every radical and kanji is one BMP character):
  # comment
  r<TAB>radical<TAB>stroke count        one line per radical, in RADKFILEX order
  @<N>                                  the following kanji have N strokes
  <kanji><radical><radical>...          the radicals the kanji is made of

RADKFILEX draws some radicals with a substitute kanji (化 for the person radical, ...). SUBSTITUTES
maps them to the radical's own Unicode form where one exists. EDRDG data: CC BY-SA 4.0,
https://www.edrdg.org/edrdg/licence.html
"""
import gzip
import sys
import xml.etree.ElementTree as ET

SUBSTITUTES = {
    "｜": "丨",  # halfwidth bar -> 丨
    "化": "⺅",  # ⺅ person
    "刈": "⺉",  # ⺉ knife
    "尚": "⺌",  # ⺌ small
    "忙": "⺖",  # ⺖ heart
    "扎": "⺘",  # ⺘ hand
    "汁": "⺡",  # ⺡ water
    "犯": "⺨",  # ⺨ dog
    "艾": "⺾",  # ⺾ grass
    "邦": "⻏",  # ⻏ city (阝 on the right)
    "阡": "⻖",  # ⻖ mound (阝 on the left)
    "老": "⺹",  # ⺹ old
    "杰": "⺣",  # ⺣ fire (灬)
    "礼": "⺭",  # ⺭ spirit (礻)
    "疔": "疒",
    "禹": "禸",
    "初": "⻂",  # ⻂ clothes (衤)
    "買": "罒",
    "滴": "啇",
    "込": "⻌",  # ⻌ walk (辶)
}


def read_radkfilex(path):
    radicals = []  # (glyph, strokes)
    kanji = {}  # kanji -> [glyph]
    glyph = None
    with open(path, encoding="euc-jp") as f:
        for line in f:
            line = line.rstrip("\n")
            if line.startswith("#") or not line:
                continue
            if line.startswith("$"):
                parts = line.split()
                glyph = SUBSTITUTES.get(parts[1], parts[1])
                radicals.append((glyph, int(parts[2])))
            else:
                for k in line:
                    kanji.setdefault(k, []).append(glyph)
    return radicals, kanji


def read_kanjidic2(path):
    info = {}
    with gzip.open(path) as f:
        for _, el in ET.iterparse(f):
            if el.tag != "character":
                continue
            literal = el.findtext("literal")
            strokes = el.findtext("misc/stroke_count")
            freq = el.findtext("misc/freq")
            info[literal] = (int(strokes) if strokes else 0, int(freq) if freq else 99999)
            el.clear()
    return info


def main(radk, kanjidic, out):
    radicals, kanji = read_radkfilex(radk)
    info = read_kanjidic2(kanjidic)
    glyphs = [g for g, _ in radicals]
    assert len(set(glyphs)) == len(glyphs), "duplicate radical glyph after substitution"
    for s in glyphs + list(kanji):
        assert len(s) == 1 and ord(s) < 0x10000, s
    order = sorted(kanji, key=lambda k: (info.get(k, (0, 0))[0] or 99, info.get(k, (0, 99999))[1], k))
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write("# Radical search data. Built by scripts/radicals/build_radicals.py from EDRDG's\n")
        f.write("# RADKFILEX/KRADFILE (radicals of each kanji) and KANJIDIC2 (stroke counts, frequency).\n")
        f.write("# (c) Electronic Dictionary Research and Development Group, CC BY-SA 4.0,\n")
        f.write("# https://www.edrdg.org/edrdg/licence.html\n")
        for g, n in radicals:
            f.write(f"r\t{g}\t{n}\n")
        current = None
        for k in order:
            strokes = info.get(k, (0, 0))[0]
            if strokes != current:
                current = strokes
                f.write(f"@{strokes}\n")
            f.write(k + "".join(kanji[k]) + "\n")
    print(len(radicals), "radicals,", len(order), "kanji")


if __name__ == "__main__":
    main(*sys.argv[1:4])
