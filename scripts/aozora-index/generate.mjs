#!/usr/bin/env node
// Builds docs/aozora/index.json, the catalogue of free books for Honmani, from the Aozora Bunko
// work list (CC BY 4.0). No dependencies. See README.md.
//
//   node scripts/aozora-index/generate.mjs [--out docs/aozora/index.json] [--zip local-list.zip]
//                                          [--scores scores.jsonl]
import { mkdtemp, readFile, writeFile, mkdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, dirname, resolve } from 'node:path';
import { inflateRawSync } from 'node:zlib';
import { fileURLToPath } from 'node:url';

const LIST_URL = 'https://www.aozora.gr.jp/index_pages/list_person_all_extended_utf8.zip';
const FILE_URL = /^https:\/\/www\.aozora\.gr\.jp\/cards\/(\d{6})\/files\/([^/]+\.(?:zip|txt))$/i;
const VERSION = 1;

const args = process.argv.slice(2);
const opt = (name) => { const i = args.indexOf(name); return i >= 0 ? args[i + 1] : undefined; };
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const outPath = resolve(opt('--out') ?? join(root, 'docs', 'aozora', 'index.json'));

/** Returns the bytes of the first entry of a ZIP (stored or deflated). Reads the central directory. */
export function firstZipEntry(buf) {
  let eocd = -1;
  for (let i = buf.length - 22; i >= Math.max(0, buf.length - 65557); i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error('not a ZIP file');
  const cd = buf.readUInt32LE(eocd + 16);
  if (buf.readUInt32LE(cd) !== 0x02014b50) throw new Error('bad ZIP central directory');
  const method = buf.readUInt16LE(cd + 10);
  const size = buf.readUInt32LE(cd + 20);
  const local = buf.readUInt32LE(cd + 42);
  if (buf.readUInt32LE(local) !== 0x04034b50) throw new Error('bad ZIP local header');
  const start = local + 30 + buf.readUInt16LE(local + 26) + buf.readUInt16LE(local + 28);
  const data = buf.subarray(start, start + size);
  if (method === 0) return data;
  if (method === 8) return inflateRawSync(data);
  throw new Error(`unsupported ZIP method ${method}`);
}

/**
 * Difficulty scores from the grader's JSON-lines file (see README.md): work ID -> score. The last line
 * of an ID wins; lines without an ID (the `meta` lines), broken lines and scores that are not a
 * whole number above 0 (failed gradings) are skipped.
 */
export function parseScores(text) {
  const scores = new Map();
  for (const line of text.split('\n')) {
    if (!line.trim()) continue;
    let row;
    try { row = JSON.parse(line); } catch { continue; }
    if (!row || typeof row.id !== 'string') continue;
    scores.set(row.id, Number.isInteger(row.score) && row.score > 0 && !row.error ? row.score : 0);
  }
  return scores;
}

/** RFC 4180 CSV parser (quoted fields may contain commas, quotes and line breaks). */
export function parseCsv(text) {
  const rows = [];
  let row = [], field = '', quoted = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quoted) {
      if (c === '"') { if (text[i + 1] === '"') { field += '"'; i++; } else quoted = false; }
      else field += c;
    } else if (c === '"') quoted = true;
    else if (c === ',') { row.push(field); field = ''; }
    else if (c === '\n' || c === '\r') {
      if (c === '\r' && text[i + 1] === '\n') i++;
      row.push(field); field = ''; rows.push(row); row = [];
    } else field += c;
  }
  if (field !== '' || row.length) { row.push(field); rows.push(row); }
  return rows;
}

// Kanji form + kana form: n = new (modern), o = old. Anything else gets "".
const SCRIPTS = { '新字新仮名': 'nn', '新字旧仮名': 'no', '旧字新仮名': 'on', '旧字旧仮名': 'oo' };

const toHiragana = (s) => s.replace(/[ァ-ヶ]/g, (c) => String.fromCharCode(c.charCodeAt(0) - 0x60));
const cmp = (a, b) => (a < b ? -1 : a > b ? 1 : 0);

export function buildIndex(csvText, generated, scores = new Map()) {
  const rows = parseCsv(csvText.replace(/^﻿/, ''));
  const header = rows.shift();
  const col = (name) => {
    const i = header.indexOf(name);
    if (i < 0) throw new Error(`column missing in the work list: ${name}`);
    return i;
  };
  const c = Object.fromEntries([
    '作品ID', '作品名', '作品名読み', '姓', '名', '姓読み', '名読み', '文字遣い種別', '公開日',
    '作品著作権フラグ', '人物著作権フラグ', 'テキストファイルURL', '役割フラグ',
  ].map((n) => [n, col(n)]));

  const works = new Map();
  const seen = new Set();
  const notFree = new Set();
  for (const r of rows) {
    if (r.length !== header.length) continue;
    const id = r[c['作品ID']];
    seen.add(id);
    // Only works whose copyright flag is なし (the card says free) are listed. The person flag is
    // checked too, so a living translator or editor never slips through.
    if (r[c['作品著作権フラグ']] !== 'なし' || r[c['人物著作権フラグ']] !== 'なし') notFree.add(id);
  }
  for (const r of rows) {
    if (r.length !== header.length) continue;
    const id = r[c['作品ID']];
    // A work has one row per person: take the first 著者 row.
    if (notFree.has(id) || works.has(id) || r[c['役割フラグ']] !== '著者') continue;
    const m = FILE_URL.exec(r[c['テキストファイルURL']]);
    if (!m) continue; // no text file, or hosted elsewhere
    works.set(id, {
      id,
      title: r[c['作品名']],
      titleReading: r[c['作品名読み']],
      author: r[c['姓']] + r[c['名']],
      authorReading: r[c['姓読み']] + r[c['名読み']],
      script: SCRIPTS[r[c['文字遣い種別']]] ?? '',
      published: r[c['公開日']],
      file: `${m[1]}/${m[2]}`,
    });
  }
  const entries = [...works.values()].sort((a, b) =>
    cmp(toHiragana(a.authorReading), toHiragana(b.authorReading)) ||
    cmp(a.author, b.author) ||
    cmp(toHiragana(a.titleReading), toHiragana(b.titleReading)) ||
    cmp(a.id, b.id));
  // Authors are listed once; works point to them by position (works are sorted, so the
  // positions never decrease).
  const authors = [];
  const authorIndex = new Map();
  const list = entries.map((w) => {
    const key = `${w.author}\u0000${w.authorReading}`;
    if (!authorIndex.has(key)) { authorIndex.set(key, authors.length); authors.push([w.author, w.authorReading]); }
    const entry = { i: w.id, t: w.title, r: w.titleReading, a: authorIndex.get(key), s: w.script, p: w.published, u: w.file };
    const d = scores.get(w.id);
    if (d > 0) entry.d = d; // difficulty 1-100, left out when the work is not graded
    return entry;
  });
  return {
    index: {
      version: VERSION,
      generated,
      source: 'Aozora Bunko work list (https://www.aozora.gr.jp/index_pages/person_all_extended.html), CC BY 4.0; the texts are public domain',
      count: list.length,
      authors,
      works: list,
    },
    stats: { rows: rows.length, workIds: seen.size, notFree: notFree.size, kept: list.length, authors: authors.length, graded: list.filter((w) => w.d).length },
  };
}

async function main() {
  const tmp = await mkdtemp(join(tmpdir(), 'aozora-index-'));
  try {
    let zip;
    const local = opt('--zip');
    if (local) zip = await readFile(local);
    else {
      const res = await fetch(LIST_URL);
      if (!res.ok) throw new Error(`download failed: HTTP ${res.status}`);
      zip = Buffer.from(await res.arrayBuffer());
      await writeFile(join(tmp, 'list.zip'), zip); // kept only for inspection, removed below
    }
    const csv = firstZipEntry(zip).toString('utf8');
    const scoresPath = opt('--scores');
    const scores = scoresPath ? parseScores(await readFile(scoresPath, 'utf8')) : undefined;
    const { index, stats } = buildIndex(csv, new Date().toISOString().slice(0, 10), scores);
    await mkdir(dirname(outPath), { recursive: true });
    // One work per line keeps diffs readable and the file small.
    const head = JSON.stringify({ ...index, authors: undefined, works: undefined }).slice(0, -1);
    const authors = index.authors.map((a) => JSON.stringify(a)).join(',\n');
    const body = index.works.map((w) => JSON.stringify(w)).join(',\n');
    await writeFile(outPath, `${head},"authors":[\n${authors}\n],"works":[\n${body}\n]}\n`);
    console.log(`wrote ${outPath}: ${index.count} works`, stats);
  } finally {
    await rm(tmp, { recursive: true, force: true });
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await main();
