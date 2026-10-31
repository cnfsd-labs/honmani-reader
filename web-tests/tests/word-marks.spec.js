// @ts-check
const path = require('node:path');
const { test, expect } = require('@playwright/test');

const ASSETS = path.join(__dirname, '../../app/src/main/assets/honmani');

/** Opens a ttu-like book page (/b) with `body` inside .book-content and returns the marks requests. */
async function openBook(page, body) {
  await page.addInitScript({ path: path.join(ASSETS, 'text.js') });
  await page.addInitScript(() => {
    window.__messages = [];
    window.honmani = { postMessage: (message) => window.__messages.push(JSON.parse(message)), addEventListener: (type, listener) => (window.__listener = listener) };
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

test('a gaiji picture is one placeholder character: no word runs across it, and marks land after it', async ({ page }) => {
  const gif = 'data:image/gif;base64,R0lGODlhAQABAAAAACH5BAEKAAEALAAAAAABAAEAAAICTAEAOw==';
  const texts = await openBook(page, `<p>東<img class="gaiji" src="${gif}" width="24" height="24">京都に行く。</p>`);
  expect(texts).toEqual(['東\uFFFC京都に行く。']);

  const id = await page.evaluate(() => window.__messages.find((m) => m.type === 'marks.request').blocks[0].id);
  await page.evaluate((id) => {
    const request = window.__messages.find((m) => m.type === 'marks.request');
    window.__listener({ data: JSON.stringify({ type: 'marks', requestId: request.requestId, color: '#336699', blocks: [{ id, marks: [[2, 2, 0]] }] }) });
  }, id);
  const marked = () => page.evaluate(() => [...(CSS.highlights.get('honmani-learning') ?? [])].map((range) => range.toString()));
  await expect.poll(marked).toEqual(['京都']);
});
