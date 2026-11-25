// @ts-check
/**
 * Books other readers forgive, imported into the real ッツ Ebook Reader build (patch 0007).
 *
 * Needs the ttu build produced by `./gradlew :app:buildTtu` (or set TTU_DIR).
 */
const fs = require('node:fs');
const path = require('node:path');
const { test, expect } = require('@playwright/test');
const { startTtuServer } = require('../support/ttu-server');
const { rawEpub, page: xhtml } = require('../support/epub');

const ASSETS = path.join(__dirname, '../../app/src/main/assets/honmani');
const TTU_DIR = process.env.TTU_DIR ?? path.join(__dirname, '../../app/build/generated/ttu/ttu');
const hasBuild = fs.existsSync(path.join(TTU_DIR, 'index.html'));

if (process.env.CI && !hasBuild) {
  throw new Error(`ttu build not found at ${TTU_DIR}; run ./gradlew :app:buildTtu first`);
}
test.skip(!hasBuild, `ttu build not found at ${TTU_DIR}; run ./gradlew :app:buildTtu`);

let server;
test.beforeAll(async () => {
  server = await startTtuServer(TTU_DIR);
});
test.afterAll(() => server?.close());

const ONE = '<p>吾輩は猫である。</p>';
const TWO = '<p>名前はまだ無い。</p>';
const BOTH = '吾輩は猫である。 名前はまだ無い。';
const BOTH_CHAPTERS = ['第一章', '第二章'];

/** Two chapters under `hrefs` in the package document and the contents, `names` in the zip. */
const twoChapters = (hrefs, names = hrefs, rest = {}) =>
  rawEpub({
    title: '猫',
    items: [
      { id: 'a', href: hrefs[0] },
      { id: 'b', href: hrefs[1] },
    ],
    toc: [
      [hrefs[0], '第一章'],
      [hrefs[1], '第二章'],
    ],
    files: { [names[0]]: xhtml(ONE), [names[1]]: xhtml(TWO) },
    ...rest,
  });

/** Imports a book through the library bridge; its row in ttu's database. */
async function importBook(page, book) {
  await page.addInitScript(() => {
    window.__messages = [];
    window.honmani = { postMessage: (message) => window.__messages.push(JSON.parse(message)) };
  });
  for (const script of ['text.js', 'ttu-defaults.js', 'library-bridge.js']) {
    await page.addInitScript({ path: path.join(ASSETS, script) });
  }
  await page.route('**/honmani-books/*', (route) => route.fulfill({ status: 200, body: book }));
  await page.goto(`${server.url}/manage`);
  await page.evaluate(() =>
    window.honmaniLibrary.ensure({
      requestId: 1,
      ttuId: null,
      ttuTitle: null,
      url: '/honmani-books/book.epub',
      fileName: 'book.epub',
    }),
  );
  const reply = await page
    .waitForFunction(() => window.__messages.find((m) => m.requestId === 1), null, { timeout: 20_000 })
    .then((handle) => handle.jsonValue());
  expect(reply.type, reply.message).toBe('library.ready');
  const row = await page.evaluate(
    (id) =>
      new Promise((resolve) => {
        const request = indexedDB.open('books');
        request.onsuccess = () => {
          const get = request.result.transaction('data').objectStore('data').get(id);
          get.onsuccess = () => {
            const content = document.createElement('div');
            content.innerHTML = get.result.elementHtml;
            resolve({
              text: content.textContent.replace(/\s+/g, ' ').trim(),
              characters: get.result.characters,
              chapters: get.result.sections.map((section) => section.label).filter(Boolean),
            });
          };
        };
      }),
    reply.id,
  );
  return { id: reply.id, ...row };
}

async function open(page, id) {
  await page.evaluate((id) => window.honmaniLibrary.open({ ttuId: id }), id);
  await page.waitForSelector('.book-content', { timeout: 20_000 });
}

const REFERENCES = {
  encoded: [['a%201.xhtml', '%E4%BA%8C.xhtml'], ['a 1.xhtml', '二.xhtml']],
  'Japanese, unencoded': [['第一章.xhtml', '第二章.xhtml']],
  'in another case': [['Text/A.xhtml', 'b.XHTML'], ['text/a.xhtml', 'b.xhtml']],
  'with backslashes': [['text\\a.xhtml', 'text\\b.xhtml'], ['text/a.xhtml', 'text/b.xhtml']],
  'with dots': [['./a.xhtml', 'x/../b.xhtml'], ['a.xhtml', 'b.xhtml']],
  'in another Unicode form': [['が.xhtml', 'ぎ.xhtml'], ['が.xhtml', 'ぎ.xhtml']],
  'that contain each other': [['11.xhtml', '1.xhtml']],
};
for (const [kind, [hrefs, names]] of Object.entries(REFERENCES)) {
  test(`references ${kind} lead to the chapters, also from the contents`, async ({ page }) => {
    const book = await importBook(page, twoChapters(hrefs, names));
    expect(book.text).toBe(BOTH);
    expect(book.chapters).toEqual(BOTH_CHAPTERS);
  });
}

test('what is missing from the zip or no page is left out, the rest of the book opens', async ({ page }) => {
  const book = await importBook(
    page,
    twoChapters(['a.xhtml', 'b.xhtml'], undefined, {
      items: [
        { id: 'font', href: 'x.otf', type: 'application/vnd.ms-opentype' },
        { id: 'a', href: 'a.xhtml' },
        { id: 'gone', href: 'gone.xhtml' },
        { id: 'zero', href: 'zero.xhtml' },
        { id: 'blank', href: 'blank.xhtml' },
        { id: 'b', href: 'b.xhtml' },
      ],
      spine: ['a', 'gone', 'nowhere', 'zero', 'blank', 'b'],
      files: {
        'a.xhtml': xhtml(ONE),
        'zero.xhtml': '',
        'blank.xhtml': '<html xmlns="http://www.w3.org/1999/xhtml"><head><title>x</title></head><body/></html>',
        'b.xhtml': xhtml(TWO),
      },
    }),
  );
  expect(book.text).toBe(BOTH);
  expect(book.characters).toBe(14);
  await open(page, book.id);
});

test('a page whose script or title closes itself keeps its text', async ({ page }) => {
  const book = await importBook(
    page,
    twoChapters(['a.xhtml', 'b.xhtml'], undefined, {
      files: { 'a.xhtml': xhtml(ONE, '<title/><script src="a.js"/>'), 'b.xhtml': xhtml(TWO, '<style/>') },
    }),
  );
  expect(book.text).toBe(BOTH);
});

test('package documents with their own prefix, one item or other media types are read', async ({ page }) => {
  const book = await importBook(
    page,
    rawEpub({
      title: '猫',
      items: [{ id: 'a', href: 'a.html', type: 'text/x-oeb1-document' }],
      files: { 'a.html': xhtml(ONE) },
      opf: (xml) =>
        xml
          .replace('xmlns="http', 'xmlns:opf="http')
          .replace(/<(\/?)(package|metadata|manifest|item|spine|itemref)\b/g, '<$1opf:$2'),
    }),
  );
  expect(book.text).toBe('吾輩は猫である。');
});

test('a Shift_JIS page is read as Windows wrote it', async ({ page }) => {
  const text = [0x87, 0x40, 0x94, 0x4c, 0x81, 0x60]; // ①猫～
  const body = [...Buffer.from('<?xml version="1.0" encoding="Shift_JIS"?><html><body><p>'), ...text, ...Buffer.from('</p></body></html>')];
  const book = await importBook(
    page,
    rawEpub({ title: '猫', items: [{ id: 'a', href: 'a.xhtml' }], files: { 'a.xhtml': body } }),
  );
  expect(book.text).toBe('①猫～');
});

/** An SVG picture of a size of its own, so tests see which one is shown. */
const picture = (width) => `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="20"><rect width="9" height="9"/></svg>`;

test('every picture is the one its page means', async ({ page }) => {
  const image = (src) => `<p><img src="${src}" alt=""/></p>`;
  const data = `data:image/svg+xml;base64,${Buffer.from(picture(26)).toString('base64')}`;
  const book = await importBook(
    page,
    rawEpub({
      title: '絵',
      items: [
        { id: 'a', href: 'a.xhtml' },
        { id: 'i11', href: '11.svg', type: 'image/svg+xml' },
        { id: 'i1', href: '1.svg', type: 'image/svg+xml' },
        { id: 'encoded', href: '%E7%B5%B5%203.svg', type: 'image/svg+xml' },
        { id: 'type', href: 'typed.svg', type: 'application/octet-stream' },
      ],
      spine: ['a'],
      files: {
        'a.xhtml': xhtml(ONE + ['1.svg', '11.svg', '絵 3.svg', 'typed.svg', 'unlisted.svg', data].map(image).join('')),
        '1.svg': picture(21),
        '11.svg': picture(22),
        '絵 3.svg': picture(23),
        'typed.svg': picture(24),
        'unlisted.svg': picture(25),
      },
    }),
  );
  await open(page, book.id);
  await expect
    .poll(() => page.evaluate(() => [...document.querySelectorAll('.book-content img')].map((img) => img.naturalWidth)))
    .toEqual([21, 22, 23, 24, 25, 26]);
});

test('a book whose CSS has a bracket too many opens, with its other rules', async ({ page }) => {
  const book = await importBook(
    page,
    twoChapters(['a.xhtml', 'b.xhtml'], undefined, {
      items: [
        { id: 'a', href: 'a.xhtml' },
        { id: 'b', href: 'b.xhtml' },
        { id: 'css', href: 'style.css', type: 'text/css' },
      ],
      spine: ['a', 'b'],
      files: {
        'a.xhtml': xhtml('<p class="x">吾輩は猫である。</p>'),
        'b.xhtml': xhtml(TWO),
        'style.css': 'h1 { color: red; } }\n p.y { color: blue; }\n.x { letter-spacing: 3px; }',
      },
    }),
  );
  await open(page, book.id);
  await expect(page.locator('.book-content p.x')).toHaveCSS('letter-spacing', '3px');
});

const LINE = 'これは本文の一行で、吾輩は猫である。名前はまだ無い。どこで生れたかとんと見当がつかぬ。';
/** A novel of `lines` lines in one block. */
const oneBlock = (lines) =>
  rawEpub({
    title: '塊',
    items: [{ id: 'a', href: 'a.xhtml' }],
    files: { 'a.xhtml': xhtml(`<div>${Array(lines).fill(LINE).join('<br/>')}</div>`) },
  });

for (const writingMode of ['vertical-rl', 'horizontal-tb']) {
  test(`a novel in one block of lines opens soon (${writingMode})`, async ({ page }) => {
    await page.addInitScript((mode) => localStorage.setItem('writingMode', mode), writingMode);
    const book = await importBook(page, oneBlock(2800));
    expect(book.text.length).toBe(LINE.length * 2800);
    const start = Date.now();
    await open(page, book.id);
    await page.waitForFunction(() => document.querySelector('.book-content')?.textContent.length > 100_000);
    await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    // 120,000 characters in one block took half a minute.
    expect(Date.now() - start).toBeLessThan(12_000);
  });

  test(`a novel in one block of lines looks as its book says (${writingMode})`, async ({ page }) => {
    await page.addInitScript((mode) => {
      localStorage.setItem('writingMode', mode);
      localStorage.setItem('viewMode', 'continuous');
    }, writingMode);
    const book = await importBook(page, oneBlock(300));
    await open(page, book.id);
    const lines = await page.evaluate(() => {
      const walker = document.createTreeWalker(document.querySelector('.book-content'), NodeFilter.SHOW_TEXT);
      const starts = [];
      for (let node; (node = walker.nextNode()); ) {
        if (!node.data.startsWith('これは本文')) continue;
        const range = document.createRange();
        range.setStart(node, 0);
        range.setEnd(node, 1);
        const box = range.getBoundingClientRect();
        starts.push([box.left, box.top]);
      }
      return {
        count: starts.length,
        steps: [...new Set(starts.slice(1).map(([left, top], i) => `${Math.round(left - starts[i][0])} ${Math.round(top - starts[i][1])}`))],
      };
    });
    expect(lines.count).toBe(300);
    // No empty line and no indent where the import starts a new part.
    expect(lines.steps).toHaveLength(1);
  });
}
