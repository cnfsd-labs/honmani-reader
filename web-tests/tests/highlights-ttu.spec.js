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

// A paragraph that continues onto the next page (and the next): its highlight is one range over all of it.
const LONG_SENTENCE = 'これは長い文章です。'; // 9 counted characters
const LONG_PARAGRAPH = LONG_SENTENCE.repeat(400);
const LONG_BOOK = buildEpub({ title: '長い段落', chapters: [[`<p>前置き。</p><p>${LONG_PARAGRAPH}</p><p>後書き。</p>`]], raw: true });

for (const writingMode of ['horizontal-tb', 'vertical-rl']) {
  test(`the paragraph extent of a long press covers a paragraph over several pages (paginated, ${writingMode})`, async ({ page }) => {
    await page.addInitScript({ path: path.join(ASSETS, 'text.js') });
    await page.addInitScript(
      (writingMode) => {
        localStorage.setItem('viewMode', 'paginated');
        localStorage.setItem('writingMode', writingMode);
        window.__messages = [];
        window.honmani = {
          postMessage: (message) => window.__messages.push(JSON.parse(message)),
          addEventListener: (type, listener) => (window.__listener = listener),
        };
      },
      writingMode,
    );
    for (const script of ['ttu-defaults.js', 'library-bridge.js', 'highlights.js', 'tap-bridge.js']) {
      await page.addInitScript({ path: path.join(ASSETS, script) });
    }
    await page.route('**/honmani-books/*', (route) => route.fulfill({ status: 200, body: LONG_BOOK, contentType: 'application/octet-stream' }));
    await page.goto(`${server.url}/manage`);
    const book = await call(page, 'ensure', { ttuId: null, ttuTitle: null, url: '/honmani-books/long.epub', fileName: 'long.epub' });
    await page.goto(`${server.url}/b?id=${book.id}`);
    await expect(page.locator('div.fixed.inset-0.text-7xl')).toHaveCount(0, { timeout: 10_000 });
    await page.waitForFunction(() => window.__messages.some((m) => m.type === 'highlights.request'));

    // The app answers the request with the book's table (no highlight yet).
    const total = 3 + 400 * 9 + 3;
    const answer = (items) =>
      page.evaluate(
        ([total, items, bookId]) =>
          window.__listener({ data: JSON.stringify({ type: 'highlights', bookId, sections: [['ttu-ch1', 0]], total, items }) }),
        [total, items, book.id],
      );
    await answer([]);

    // A long press on the first character of the long paragraph.
    await page.evaluate(() => {
      const walker = document.createTreeWalker(document.querySelector('.book-content'), NodeFilter.SHOW_TEXT);
      for (let node = walker.nextNode(); node; node = walker.nextNode()) {
        if (!node.data.startsWith('これは長い')) continue;
        const range = document.createRange();
        range.setStart(node, 0);
        range.setEnd(node, 1);
        getSelection().removeAllRanges();
        getSelection().addRange(range);
        return;
      }
      throw new Error('no long paragraph');
    });
    await page.waitForFunction(() => window.__messages.some((m) => m.type === 'lookup'), null, { timeout: 5000 });
    const { highlight } = await page.evaluate(() => window.__messages.find((m) => m.type === 'lookup'));
    expect(highlight.word).toMatchObject({ start: 3, end: 4 });
    expect(highlight.sentence).toMatchObject({ start: 3, end: 12 });
    expect(highlight.paragraph).toMatchObject({ start: 3, end: 3 + 400 * 9 });

    await answer([{ id: 1, start: highlight.paragraph.start, end: highlight.paragraph.end, color: 0 }]);
    await expect.poll(() => page.evaluate(() => (CSS.highlights.get('honmani-hl-0')?.size ?? 0))).toBe(1);
    const spread = await page.evaluate(() => {
      const range = [...CSS.highlights.get('honmani-hl-0')][0];
      const rects = [...range.getClientRects()].filter((r) => r.width > 0);
      const xs = rects.flatMap((r) => [r.left, r.right]);
      const ys = rects.flatMap((r) => [r.top, r.bottom]);
      return {
        text: range.toString().length,
        // How far past the screen the marked text reaches, along whichever axis the pages run.
        beyond: Math.max(Math.max(...xs) - innerWidth, -Math.min(...xs), Math.max(...ys) - innerHeight, -Math.min(...ys)),
      };
    });
    // The last full stop is not a counted character, so the range ends before it.
    expect(spread.text).toBe(400 * LONG_SENTENCE.length - 1);
    // The text runs on past the first page.
    expect(spread.beyond).toBeGreaterThan(0);
  });
}
