#!/usr/bin/env python3
"""Builds the JIS X 0213 kanji table that turns Aozora gaiji notes (※［＃「…」、第3水準1-15-62］)
into characters, from the kJIS0213 field of Unicode's Unihan database.

Usage (run once; the output is committed, nothing is built at build time):

  mkdir /tmp/unihan && cd /tmp/unihan
  curl -O https://www.unicode.org/Public/UCD/latest/ucd/Unihan.zip    # used: Unicode 18.0.0
  unzip Unihan.zip Unihan_OtherMappings.txt
  python3 -I scripts/jisx0213/build_jisx0213.py /tmp/unihan/Unihan_OtherMappings.txt \
      app/src/main/resources/app/honmani/reader/data/library/jisx0213.bin

kJIS0213 gives "plane,row,cell" for every kanji of JIS X 0213 (plane 1 = 第3水準 and the JIS X
0208 kanji, plane 2 = 第4水準). It agrees with the J3-/J4- values of kIRG_JSource (hex row and
cell, each +0x20) wherever both exist, and covers more kanji.

Format: one ASCII header line ending in "\\n" (source and version), a big-endian u16 with the number
of plane-1 entries, then u32 big-endian records, plane 1 first, each plane sorted:
(((row - 1) * 94 + cell - 1) << 18) | code point. Every code point is below 0x40000.

Unihan data: Copyright © 1991-2026 Unicode, Inc., Unicode License v3,
https://www.unicode.org/license.txt
"""
import re
import struct
import sys

SOURCE = "https://www.unicode.org/Public/UCD/latest/ucd/Unihan.zip"


def main(source, output):
    version = None
    planes = {1: {}, 2: {}}
    with open(source, encoding="utf-8") as lines:
        for line in lines:
            found = re.match(r"# Unicode Version (\S+)", line)
            if found:
                version = found.group(1)
            if "\tkJIS0213\t" not in line:
                continue
            code, _, value = line.rstrip("\n").split("\t")
            plane, row, cell = (int(part) for part in value.split(","))
            point = int(code[2:], 16)
            assert plane in planes and 1 <= row <= 94 and 1 <= cell <= 94 and point < 0x40000, line
            index = (row - 1) * 94 + cell - 1
            assert index not in planes[plane], line
            planes[plane][index] = point
    assert version, "no Unicode version in the source"
    header = f"JIS X 0213 kanji from Unihan kJIS0213, Unicode {version}, {SOURCE}, Unicode License v3\n"
    with open(output, "wb") as out:
        out.write(header.encode("ascii"))
        out.write(struct.pack(">H", len(planes[1])))
        for plane in (1, 2):
            for index in sorted(planes[plane]):
                out.write(struct.pack(">I", (index << 18) | planes[plane][index]))
    print(f"Unicode {version}: {len(planes[1])} + {len(planes[2])} kanji")


if __name__ == "__main__":
    main(*sys.argv[1:])
