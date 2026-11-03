// @ts-check
/**
 * A long swipe up on a book page opens the reader menu (reader-menu.js) against the real ッツ
 * Ebook Reader build: a long, straight swipe up in paged mode and in continuous vertical text
 * posts { type: 'reader.menuSwipe' }; short, diagonal and horizontal swipes do not, nor does a
 * swipe in continuous mode with horizontal text, or one while ttu's reader bar is open.
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
  for (const script of ['text.js', 'ttu-defaults.js', 'library-bridge.js', 'reader-menu.js']) {
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

/** A one-finger swipe by (dx, dy) CSS px from the middle of the page, as the touch screen sends it. */
async function swipe(page, dx, dy) {
  const { width, height } = page.viewportSize();
  const x = width / 2 - dx / 2;
  const y = height / 2 - dy / 2;
  const cdp = await page.context().newCDPSession(page);
  const touch = (type, px, py) =>
    cdp.send('Input.dispatchTouchEvent', { type, touchPoints: type === 'touchEnd' ? [] : [{ x: px, y: py }] });
  await touch('touchStart', x, y);
  for (let i = 1; i <= 6; i++) await touch('touchMove', x + (dx * i) / 6, y + (dy * i) / 6);
  await touch('touchEnd', x + dx, y + dy);
  await cdp.detach();
}

const menuMessages = (page) => page.evaluate(() => window.__messages.filter((m) => m.type === 'reader.menuSwipe').length);

for (const [viewMode, writingMode, opens] of [
  ['paginated', 'horizontal-tb', true],
  ['paginated', 'vertical-rl', true],
  ['continuous', 'vertical-rl', true],
  ['continuous', 'horizontal-tb', false],
]) {
  test(`long swipe up (${viewMode}, ${writingMode}): ${opens ? 'opens' : 'does not open'} the menu`, async ({ page }) => {
    await openBook(page, { viewMode, writingMode });
    const height = page.viewportSize().height;
    await swipe(page, 0, -height * 0.5);
    await page.waitForTimeout(300);
    expect(await menuMessages(page)).toBe(opens ? 1 : 0);
  });
}

test('short, diagonal, downward and horizontal swipes do not open the menu', async ({ page }) => {
  await openBook(page, { viewMode: 'paginated', writingMode: 'horizontal-tb' });
  const { width, height } = page.viewportSize();
  await swipe(page, 0, -height * 0.15);
  await swipe(page, -width * 0.6, -height * 0.3);
  await swipe(page, 0, height * 0.5);
  await swipe(page, -width * 0.6, 0);
  await page.waitForTimeout(300);
  expect(await menuMessages(page)).toBe(0);
  // A straight swipe up still works afterwards.
  await swipe(page, width * 0.05, -height * 0.4);
  await expect.poll(() => menuMessages(page)).toBe(1);
});

test('no menu while the reader bar is open', async ({ page }) => {
  await openBook(page, { viewMode: 'paginated', writingMode: 'horizontal-tb' });
  const height = page.viewportSize().height;
  await page.locator('button.fixed.inset-x-0.top-0').click();
  await expect(page.locator('div.fixed.inset-x-0.top-0')).toHaveCount(1);
  await swipe(page, 0, -height * 0.5);
  await page.waitForTimeout(300);
  expect(await menuMessages(page)).toBe(0);
});

test('isMenuSwipe decides on length and straightness', async ({ page }) => {
  await page.addInitScript({ path: path.join(ASSETS, 'reader-menu.js') });
  await page.goto(`${server.url}/manage`);
  const results = await page.evaluate(() => {
    const f = window.honmaniReaderMenu.isMenuSwipe;
    return [f(0, -200, 800), f(0, -199, 800), f(100, -250, 800), f(150, -250, 800), f(0, 300, 800), f(-100, -200, 800)];
  });
  expect(results).toEqual([true, false, true, false, false, true]);
});
