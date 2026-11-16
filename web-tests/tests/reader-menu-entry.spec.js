// @ts-check
/**
 * ttu's reader bar gets a "Reader menu" entry (reader-menu.js) against the real ッツ Ebook Reader
 * build: in the More dropdown on a phone and in the row of icons on a wide screen, in horizontal
 * and vertical text. A tap posts { type: 'reader.menu' } and closes the bar; the entry comes back
 * each time ttu draws the dropdown again.
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

const chapters = [[...Array(60).keys()].map((i) => `第${i + 1}段落。` + 'これは滑らかな長い文章です。'.repeat(6))];
const BOOK = buildEpub({ title: '指の本', chapters });

async function openBook(page, settings) {
  await page.addInitScript((settings) => {
    for (const [key, value] of Object.entries(settings)) localStorage.setItem(key, value);
    window.__messages = [];
    window.honmani = { postMessage: (message) => window.__messages.push(JSON.parse(message)) };
  }, settings);
  for (const script of ['text.js', 'ttu-defaults.js', 'library-bridge.js', 'reader-settings.js', 'button-labels.js', 'reader-menu.js']) {
    await page.addInitScript({ path: path.join(ASSETS, script) });
  }
  await page.route('**/honmani-books/*', (route) => route.fulfill({ status: 200, body: BOOK, contentType: 'application/octet-stream' }));
  await page.goto(`${server.url}/manage`);
  await page.evaluate(() => window.honmaniLibrary.ensure({ requestId: 1, ttuId: null, ttuTitle: null, url: '/honmani-books/m.epub', fileName: 'm.epub' }));
  const handle = await page.waitForFunction(() => window.__messages.find((m) => m.requestId === 1), undefined, { timeout: 20_000 });
  const book = await handle.jsonValue();
  await page.goto(`${server.url}/b?id=${book.id}`);
  await expect(page.locator('div.fixed.inset-0.text-7xl')).toHaveCount(0, { timeout: 10_000 });
}


const menuMessages = (page) => page.evaluate(() => window.__messages.filter((m) => m.type === 'reader.menu').length);
const BAR = 'div.fixed.inset-x-0.top-0';
const ENTRY = '[data-honmani-reader-menu]';

async function showBar(page) {
  await page.locator('button.fixed.inset-x-0.top-0').click();
  await expect(page.locator(BAR)).toHaveCount(1);
}

for (const writingMode of ['horizontal-tb', 'vertical-rl']) {
  test(`the More dropdown has a Reader menu entry that opens the menu (${writingMode})`, async ({ page }) => {
    await openBook(page, { viewMode: 'paginated', writingMode });
    for (let round = 1; round <= 2; round++) {
      await showBar(page);
      await page.locator(`${BAR} div.sm\\:hidden [data-popover] .cursor-pointer`).first().click();
      const entry = page.locator(`${BAR} div.sm\\:hidden ${ENTRY}`);
      await expect(entry).toHaveCount(1);
      await expect(entry).toHaveText('Reader menu');
      // First in the list, above ttu's own entries.
      const first = await page.evaluate((selector) => document.querySelector(selector).parentElement.firstElementChild.hasAttribute('data-honmani-reader-menu'), `${BAR} div.sm\\:hidden ${ENTRY}`);
      expect(first).toBe(true);
      await entry.click();
      await expect.poll(() => menuMessages(page)).toBe(round);
      // The bar and the dropdown close; ttu did not act on the tap.
      await expect(page.locator(BAR)).toHaveCount(0);
      await expect(page.locator(ENTRY)).toHaveCount(0);
      expect(new URL(page.url()).pathname).toBe('/b');
    }
  });
}

test('on a wide screen the row of icons has the Reader menu entry', async ({ page }) => {
  await page.setViewportSize({ width: 900, height: 420 });
  await openBook(page, { viewMode: 'paginated', writingMode: 'vertical-rl' });
  await showBar(page);
  const entry = page.locator(`${BAR} div.hidden.sm\\:flex > ${ENTRY}`);
  await expect(entry).toHaveCount(1);
  await expect(entry).toHaveAttribute('aria-label', 'Reader menu');
  await expect(entry.locator('svg')).toHaveCount(1);
  await entry.click();
  await expect.poll(() => menuMessages(page)).toBe(1);
  await expect(page.locator(BAR)).toHaveCount(0);
});

test('the Settings entry still posts reader.settings', async ({ page }) => {
  await openBook(page, { viewMode: 'paginated', writingMode: 'horizontal-tb' });
  await showBar(page);
  await page.locator(`${BAR} div.sm\\:hidden [data-popover] .cursor-pointer`).first().click();
  await page.locator(`${BAR} div.sm\\:hidden [title="Go to Reader Settings"]`).click();
  await expect.poll(() => page.evaluate(() => window.__messages.filter((m) => m.type === 'reader.settings').length)).toBe(1);
  expect(await menuMessages(page)).toBe(0);
});
