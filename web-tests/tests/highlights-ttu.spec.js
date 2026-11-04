// @ts-check
/**
 * Highlights (highlights.js) against the real ッツ Ebook Reader build: the section ids and the
 * character counting the app's table (EpubPositions.sectionStarts) relies on must match ttu's own
 * DOM, in both view modes and writing directions, also with furigana (ruby text is not counted).
 *
 * Needs the ttu build produced by `./gradlew :app:buildTtu` (or set TTU_DIR).
 */
const fs = require('node:fs');
const path = require('node:path');
const { test, expect } = require('@playwright/test');
const { startTtuServer } = require('../support/ttu-server');
const { buildEpub } = require('../support/epub');

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

/** Counted characters of text without markup: ttu's counting, close enough for kanji and kana. */
const letters = (text) => text.replace(/[^\p{L}\p{N}]/gu, '').length;
const filler = 'これは長い文章です。'.repeat(8);
const chapter1 = Array.from({ length: 30 }, (_, i) => `<p>第一章${i}。${filler}</p>`);
// The target: a ruby word and plain text after it.
const RUBY_PARAGRAPH = '<p>扉の<ruby>鍵穴<rt>かぎあな</rt></ruby>から光が漏れる。</p>';
const chapter2 = [...Array.from({ length: 20 }, (_, i) => `<p>第二章${i}。${filler}</p>`), RUBY_PARAGRAPH];
const chapter3 = Array.from({ length: 30 }, (_, i) => `<p>第三章${i}。${filler}</p>`);
const strip = (xhtml) => xhtml.replace(/<rt>.*?<\/rt>/g, '').replace(/<[^>]*>/g, '');
const CH2_START = letters(strip(chapter1.join('')));
const BEFORE_TARGET = letters(strip(chapter2.slice(0, 20).join('')) + '扉の');
const TARGET_START = CH2_START + BEFORE_TARGET;
const TARGET = '鍵穴から光';
const TARGET_END = TARGET_START + letters(TARGET);
const BOOK = buildEpub({ title: '印の本', chapters: [chapter1, chapter2, chapter3], raw: true });

let requestCount = 0;
async function call(page, command, args = {}) {
  const requestId = ++requestCount;
  await page.evaluate(([command, args]) => window.honmaniLibrary[command](args), [command, { requestId, ...args }]);
  const handle = await page.waitForFunction((id) => window.__messages.find((m) => m.requestId === id), requestId, { timeout: 20_000 });
  return handle.jsonValue();
}

for (const viewMode of ['paginated', 'continuous']) {
  for (const writingMode of ['horizontal-tb', 'vertical-rl']) {
    test(`a highlight over furigana lands on the same text (${viewMode}, ${writingMode})`, async ({ page }) => {
      await page.addInitScript({ path: path.join(ASSETS, 'text.js') });
      await page.addInitScript(
        ([viewMode, writingMode]) => {
          localStorage.setItem('viewMode', viewMode);
          localStorage.setItem('writingMode', writingMode);
          window.__messages = [];
          window.honmani = {
            postMessage: (message) => window.__messages.push(JSON.parse(message)),
            addEventListener: (type, listener) => (window.__listener = listener),
          };
        },
        [viewMode, writingMode],
      );
      for (const script of ['ttu-defaults.js', 'library-bridge.js', 'highlights.js']) {
        await page.addInitScript({ path: path.join(ASSETS, script) });
      }
      await page.route('**/honmani-books/*', (route) => route.fulfill({ status: 200, body: BOOK, contentType: 'application/octet-stream' }));
      await page.goto(`${server.url}/manage`);
      const book = await call(page, 'ensure', { ttuId: null, ttuTitle: null, url: '/honmani-books/marks.epub', fileName: 'marks.epub' });
      const total = letters(strip(chapter1.join('') + chapter2.join('') + chapter3.join('')));
      // Open in the second chapter (paginated shows only that one).
      await call(page, 'setBookmark', { ttuId: book.id, characters: TARGET_START, progress: TARGET_START / total });
      await page.goto(`${server.url}/b?id=${book.id}`);
      await expect(page.locator('div.fixed.inset-0.text-7xl')).toHaveCount(0, { timeout: 10_000 });
      await page.waitForFunction(() => window.__messages.some((m) => m.type === 'highlights.request'));

      const sectionIds = await page.evaluate(() => [...document.querySelectorAll('[id^="ttu-"]')].map((e) => e.id));
      expect(sectionIds).toContain('ttu-ch2');

      const sections = [['ttu-ch1', 0], ['ttu-ch2', CH2_START], ['ttu-ch3', CH2_START + letters(strip(chapter2.join('')))]];
      await page.evaluate(
        ([sections, total, start, end, bookId]) =>
          window.__listener({ data: JSON.stringify({ type: 'highlights', bookId, sections, total, items: [{ id: 7, start, end, color: 1 }] }) }),
        [sections, total, TARGET_START, TARGET_END, book.id],
      );
      await expect
        .poll(() => page.evaluate(() => [...(CSS.highlights.get('honmani-hl-1') ?? [])].map((range) => range.toString())), { timeout: 10_000 })
        .toEqual(['鍵穴かぎあなから光']); // the furigana in between is part of the range, not counted

      // The page's own measurement of the same text agrees with the table.
      const measured = await page.evaluate(() => {
        const range = [...CSS.highlights.get('honmani-hl-1')][0];
        return window.honmaniHighlights.measure(range);
      });
      expect(measured).toMatchObject({ start: TARGET_START, end: TARGET_END });
    });
  }
}
