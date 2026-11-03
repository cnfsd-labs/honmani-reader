// @ts-check
/**
 * In-book search, the page's half (book-search.js) against the real ッツ Ebook Reader build: the
 * app saves the bookmark of a hit and calls honmaniSearch.reload(); the reloaded page must open at
 * the hit, in both view modes and writing directions, and mark it for a moment.
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

/** Three chapters of numbered paragraphs; the word 鍵穴 is in paragraph 80 of the last one only. */
const PARAGRAPHS = 120;
const paragraph = (chapter, i) =>
  `第${chapter}章${i}段落。` + 'これは検索のための長い文章です。'.repeat(6) + (chapter === 3 && i === 80 ? '扉の鍵穴から光が漏れる。' : '');
const chapters = [1, 2, 3].map((chapter) => Array.from({ length: PARAGRAPHS }, (_, i) => paragraph(chapter, i + 1)));
const BOOK = buildEpub({ title: '検索の本', chapters });
const letters = (text) => text.replace(/[^\p{L}\p{N}]/gu, '').length;
const TOTAL = letters(chapters.flat().join(''));
const BEFORE_HIT = letters(chapters[0].join('') + chapters[1].join('') + chapters[2].slice(0, 79).join('') + paragraph(3, 80).split('鍵穴')[0]);

let requestCount = 0;
async function call(page, command, args = {}) {
  const requestId = ++requestCount;
  await page.evaluate(([command, args]) => window.honmaniLibrary[command](args), [command, { requestId, ...args }]);
  const handle = await page.waitForFunction((id) => window.__messages.find((m) => m.requestId === id), requestId, { timeout: 20_000 });
  return handle.jsonValue();
}

async function setUp(page, settings) {
  await page.addInitScript({ path: path.join(ASSETS, 'text.js') });
  await page.addInitScript((settings) => {
    for (const [key, value] of Object.entries(settings)) localStorage.setItem(key, value);
    window.__messages = [];
    window.honmani = { postMessage: (message) => window.__messages.push(JSON.parse(message)) };
  }, settings);
  for (const script of ['ttu-defaults.js', 'library-bridge.js', 'book-search.js']) {
    await page.addInitScript({ path: path.join(ASSETS, script) });
  }
  await page.route('**/honmani-books/*', (route) => route.fulfill({ status: 200, body: BOOK, contentType: 'application/octet-stream' }));
}

for (const viewMode of ['paginated', 'continuous']) {
  for (const writingMode of ['horizontal-tb', 'vertical-rl']) {
    test(`a hit opens and is marked (${viewMode}, ${writingMode})`, async ({ page }) => {
      await setUp(page, { viewMode, writingMode });
      await page.goto(`${server.url}/manage`);
      const book = await call(page, 'ensure', { ttuId: null, ttuTitle: null, url: '/honmani-books/search.epub', fileName: 'search.epub' });
      await page.goto(`${server.url}/b?id=${book.id}`);
      await expect(page.locator('div.fixed.inset-0.text-7xl')).toHaveCount(0, { timeout: 10_000 });
      // The start of the book: the hit is not on screen.
      await expect(page.getByText('鍵穴')).not.toBeInViewport();

      await call(page, 'setBookmark', { ttuId: book.id, characters: BEFORE_HIT, progress: BEFORE_HIT / TOTAL });
      await page.evaluate(() => window.honmaniSearch.reload({ before: '扉の', match: '鍵穴' }));

      // After the reload the hit is marked, and the marked text is on screen. (waitForFunction
      // survives the navigation; it keeps what it saw while the mark was there.)
      const handle = await page.waitForFunction(
        () => {
          const highlight = window.CSS && CSS.highlights && CSS.highlights.get('honmani-search-hit');
          if (!highlight) return null;
          const [range] = Array.from(highlight);
          const rect = range.getBoundingClientRect();
          return { text: range.toString(), inside: rect.right > 0 && rect.bottom > 0 && rect.left < innerWidth && rect.top < innerHeight };
        },
        undefined,
        { timeout: 20_000, polling: 'raf' },
      );
      expect(await handle.jsonValue()).toEqual({ text: '鍵穴', inside: true });

      // The mark goes away by itself.
      await expect.poll(() => page.evaluate(() => CSS.highlights.has('honmani-search-hit')), { timeout: 6_000 }).toBe(false);
    });
  }
}

test('markOnScreen finds nothing for text that is not on screen', async ({ page }) => {
  await setUp(page, { viewMode: 'paginated', writingMode: 'horizontal-tb' });
  await page.goto(`${server.url}/manage`);
  const book = await call(page, 'ensure', { ttuId: null, ttuTitle: null, url: '/honmani-books/search.epub', fileName: 'search.epub' });
  await page.goto(`${server.url}/b?id=${book.id}`);
  await expect(page.locator('div.fixed.inset-0.text-7xl')).toHaveCount(0, { timeout: 10_000 });

  expect(await page.evaluate(() => window.honmaniSearch.markOnScreen({ before: '', match: '鍵穴' }))).toBe(false);
  expect(await page.evaluate(() => window.honmaniSearch.markOnScreen({ before: '', match: '第1章1段落' }))).toBe(true);
});
