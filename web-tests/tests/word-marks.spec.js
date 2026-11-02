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

/** The ranges of the due highlight, and the page's mark style. */
const dueMarked = (page) => page.evaluate(() => [...(CSS.highlights.get('honmani-due') ?? [])].map((range) => range.toString()));
const markRequest = (page) => page.evaluate(() => window.__messages.find((m) => m.type === 'marks.request'));

/** Answers the first marks request with `marks` for its first block. */
async function answer(page, marks) {
  await page.evaluate((marks) => {
    const request = window.__messages.find((m) => m.type === 'marks.request');
    window.__listener({ data: JSON.stringify({ type: 'marks', requestId: request.requestId, color: '#ffc400', blocks: [{ id: request.blocks[0].id, marks }] }) });
  }, marks);
}

/** A screenshot of the paragraph. */
const picture = (page) => page.locator('p').first().screenshot({ animations: 'disabled' });

test('a word due for review gets a dotted underline over its tint', async ({ page }) => {
  await openBook(page, '<p style="font-size: 32px">彼は東京に住む。</p>');
  const request = await markRequest(page);
  expect(request.dueMarks).toBe(true);

  // 東京 is learning and due: both marks lie on it.
  await answer(page, [[2, 2, 0], [2, 2, 4]]);
  await expect.poll(() => dueMarked(page)).toEqual(['東京']);
  await page.waitForTimeout(400);
  const css = await page.evaluate(() => document.getElementById('honmani-marks-style').textContent);
  expect(css).toMatch(/::highlight\(honmani-due\) \{ text-decoration-line: underline; text-decoration-style: dotted;/);
  expect(await page.evaluate(() => [...(CSS.highlights.get('honmani-learning') ?? [])].map((range) => range.toString()))).toEqual(['東京']);
});

for (const [name, background, color, red] of [['light', '#ffffff', '#000000', 153], ['dark', '#000000', '#ffffff', 255]]) {
  test(`the due underline is drawn on a ${name} page`, async ({ page }) => {
    await openBook(page, '<p style="font-size: 32px; margin: 20px">彼は東京に住む。</p>');
    await page.evaluate(([background, color]) => {
      document.body.style.background = background;
      document.body.style.color = color;
    }, [background, color]);
    const before = await picture(page);
    await answer(page, [[2, 2, 4]]);
    await expect.poll(() => dueMarked(page)).toEqual(['東京']);
    await page.waitForTimeout(400);
    // The underline changes pixels of the paragraph (it is drawn, not just set).
    expect((await picture(page)).equals(before)).toBe(false);
    // The accent as it is on a dark page, darker on a light one.
    const css = await page.evaluate(() => document.getElementById('honmani-marks-style').textContent);
    expect(Number(/::highlight\(honmani-due\) \{[^}]*rgba\((\d+),/.exec(css)[1])).toBe(red);
  });
}

test('with the due switch off the page neither asks for nor draws a due mark', async ({ page }) => {
  await page.addInitScript(() => window.localStorage.setItem('honmaniMarkDue', '0'));
  await openBook(page, '<p>彼は東京に住む。</p>');
  expect((await markRequest(page)).dueMarks).toBe(false);
  // Even if the app answered with one, it is not drawn.
  await answer(page, [[2, 2, 0], [2, 2, 4]]);
  await expect.poll(() => page.evaluate(() => [...(CSS.highlights.get('honmani-learning') ?? [])].length)).toBe(1);
  expect(await dueMarked(page)).toEqual([]);
});
