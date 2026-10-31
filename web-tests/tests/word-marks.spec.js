// @ts-check
const path = require('node:path');
const { test, expect } = require('@playwright/test');

const ASSETS = path.join(__dirname, '../../app/src/main/assets/honmani');

/** Opens a ttu-like book page (/b) with `body` inside .book-content and returns the marks requests. */
async function openBook(page, body) {
  await page.addInitScript({ path: path.join(ASSETS, 'text.js') });
  await page.addInitScript(() => {
    window.__messages = [];
    window.honmani = { postMessage: (message) => window.__messages.push(JSON.parse(message)), addEventListener: () => {} };
  });
  await page.addInitScript({ path: path.join(ASSETS, 'word-marks.js') });
  await page.route('**/*', (route) =>
    route.fulfill({
      contentType: 'text/html',
      body: `<!doctype html><meta charset="utf-8"><body style="font-family: IPAGothic"><div class="book-content">${body}</div>`,
    }),
  );
  await page.goto('http://localhost:9/b?id=1');
  await expect.poll(() => page.evaluate(() => window.__messages.length)).toBeGreaterThan(0);
  return page.evaluate(() => window.__messages.filter((m) => m.type === 'marks.request').flatMap((m) => m.blocks.map((b) => b.text)));
}

test('a line break in the source is a space: it does not split a word in the marks request', async ({ page }) => {
  const texts = await openBook(page, '<p>彼は東京\nに住む。</p><p style="white-space: pre-line">雨が\n降る。</p>');
  expect(texts).toEqual(['彼は東京 に住む。', '雨が\n降る。']);
});
