// @ts-check
/**
 * ttu puts a book's HTML into its page as it is. The reader's Content-Security-Policy (ReaderPolicy
 * in the app, `support/ttu-server.js` here) keeps a book's own code from running: an image's
 * error handler, an inline script, a click handler and a javascript: link that is tapped all stay
 * inert.
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

const FLAG = "window.__ran=(window.__ran||[]).concat('%s')";
const flag = (name) => FLAG.replace('%s', name);

const hostile = {
  name: 'hostile.epub',
  mimeType: 'application/epub+zip',
  buffer: buildEpub({
    title: '危険な本',
    raw: true,
    paragraphs: [
      '<p>本文はここ。</p>',
      `<img src="x" onerror="${flag('onerror')}"/>`,
      `<script>${flag('script')}</script>`,
      `<p><span id="handler" onclick="${flag('onclick')}">押す</span></p>`,
      `<p><a id="evil" href="javascript:${flag('javascript-link')}">危険なリンク</a></p>`,
    ],
  }),
};

test('a book with an image error handler, a script, a click handler and a javascript: link stays inert', async ({ page }) => {
  await page.addInitScript({ path: path.join(ASSETS, 'text.js') });

  await page.goto(`${server.url}/manage`);
  await page.locator('input[type=file][accept*="epub"]').first().setInputFiles(hostile);
  await page.getByText('危険な本').first().click();
  await page.waitForURL(/\/b\?id=\d+/);
  await expect(page.locator('.book-content')).toContainText('本文はここ');
  const link = page.locator('.book-content a#evil');
  await expect(link).toHaveCount(1);
  const route = (url) => new URL(url).pathname + new URL(url).search;
  const address = route(page.url());

  // ttu's loading spinner may still cover the page (the broken image), so the taps are dispatched.
  await link.dispatchEvent('click');
  await page.locator('.book-content #handler').dispatchEvent('click');
  await page.waitForTimeout(1_000);

  expect(await page.evaluate(() => window.__ran ?? [])).toEqual([]);
  // ttu turns the link's address into a fragment: at most a jump inside the page.
  expect(route(page.url())).toBe(address);
  await expect(page.locator('.book-content')).toContainText('本文はここ');
});
