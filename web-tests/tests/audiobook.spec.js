// @ts-check
/**
 * Audiobook read-along, the page's half (audiobook.js with highlights.js and sentence-follow.js):
 * marking the passage the app says is being heard and reaching it across sections. The app plays
 * the audio and aligns the subtitles, so here it is a stub that sends the messages by hand.
 */
const path = require('node:path');
const { test, expect } = require('@playwright/test');

const ASSETS = path.join(__dirname, '../../app/src/main/assets/honmani');

async function openBook(page, body, { storage = {}, css = '' } = {}) {
  await page.addInitScript((storage) => {
    for (const [key, value] of Object.entries(storage)) localStorage.setItem(key, value);
    window.__messages = [];
    window.__listeners = [];
    window.honmani = {
      postMessage: (message) => window.__messages.push(JSON.parse(message)),
      addEventListener: (type, listener) => window.__listeners.push(listener),
    };
  }, storage);
  for (const script of ['text.js', 'highlights.js', 'sentence-follow.js', 'audiobook.js']) {
    await page.addInitScript({ path: path.join(ASSETS, script) });
  }
  await page.route('**/*', (route) =>
    route.fulfill({
      contentType: 'text/html',
      body: `<!doctype html><meta charset="utf-8"><style>body { font-family: IPAGothic; font-size: 20px; ${css} }</style><body><div class="book-content">${body}</div>`,
    }),
  );
  await page.goto('http://localhost:9/b?id=1');
  await expect.poll(() => page.evaluate(() => window.__messages.some((m) => m.type === 'highlights.request'))).toBe(true);
}

const send = (page, message) => page.evaluate((message) => window.__listeners.forEach((listener) => listener({ data: JSON.stringify(message) })), message);
const sections = (page, list) => send(page, { type: 'highlights', bookId: 1, sections: list, total: 99, items: [] });
const marked = (page) =>
  page.evaluate(() => [...(CSS.highlights.get('honmani-read-aloud') ?? [])].map((range) => range.toString()));

const TWO_SECTIONS =
  '<div id="ttu-a"><p>吾輩は猫である。</p><p>名前はまだ無い。</p></div><div id="ttu-b"><p>どこで生れたか。</p></div>';
// a: 吾輩は猫である (7) + 名前はまだ無い (7) = 14; b starts at 14.
const A_B = [['ttu-a', 0], ['ttu-b', 14]];

test('a passage is marked across paragraphs, in book positions', async ({ page }) => {
  await openBook(page, TWO_SECTIONS);
  await sections(page, A_B);
  await send(page, { type: 'audiobook.mark', gen: 1, start: 3, end: 10 });
  await expect.poll(() => marked(page)).toEqual(['猫である。名前は']);
  await send(page, { type: 'audiobook.mark', gen: 2, start: 14, end: 21 });
  await expect.poll(() => marked(page)).toEqual(['どこで生れたか。']);
});

test('a passage in the second section is found when both are in the page (continuous mode)', async ({ page }) => {
  await openBook(page, TWO_SECTIONS);
  await sections(page, A_B);
  await send(page, { type: 'audiobook.mark', gen: 1, start: 16, end: 18 });
  await expect.poll(() => marked(page)).toEqual(['で生']);
});

test('clear removes the mark', async ({ page }) => {
  await openBook(page, TWO_SECTIONS);
  await sections(page, A_B);
  await send(page, { type: 'audiobook.mark', gen: 1, start: 0, end: 3 });
  await expect.poll(() => marked(page)).toEqual(['吾輩は']);
  await send(page, { type: 'audiobook.clear' });
  await expect.poll(() => marked(page)).toEqual([]);
});

test('a passage outside the page that no page turn reaches is reported lost', async ({ page }) => {
  // Only section a is in the page, and no reader turns the page: the passage is in b.
  await openBook(page, '<div id="ttu-a"><p>吾輩は猫である。</p><p>名前はまだ無い。</p></div>');
  await sections(page, A_B);
  await send(page, { type: 'audiobook.mark', gen: 7, start: 16, end: 18 });
  await expect.poll(() => page.evaluate(() => window.__messages.find((m) => m.type === 'audiobook.lost'))).toEqual({ type: 'audiobook.lost', gen: 7 });
  expect(await marked(page)).toEqual([]);
});

test('a newer mark replaces one still on its way', async ({ page }) => {
  await openBook(page, TWO_SECTIONS);
  await sections(page, A_B);
  await send(page, { type: 'audiobook.mark', gen: 1, start: 0, end: 3 });
  await send(page, { type: 'audiobook.mark', gen: 2, start: 7, end: 10 });
  await expect.poll(() => marked(page)).toEqual(['名前は']);
});

test('a section turned to by the keyboard is followed', async ({ page }) => {
  // A stand-in for ttu: ArrowDown swaps section a for b in the page.
  await openBook(page, '<div id="ttu-a"><p>吾輩は猫である。</p><p>名前はまだ無い。</p></div>');
  await page.evaluate(() => {
    window.addEventListener('keydown', (event) => {
      if (event.code !== 'ArrowDown') return;
      document.querySelector('.book-content').innerHTML = '<div id="ttu-b"><p>どこで生れたか。</p></div>';
    });
  });
  await sections(page, A_B);
  await send(page, { type: 'audiobook.mark', gen: 3, start: 14, end: 18 });
  await expect.poll(() => marked(page)).toEqual(['どこで生']);
  expect(await page.evaluate(() => window.__messages.some((m) => m.type === 'audiobook.lost'))).toBe(false);
});

test('a scroll by hand detaches: the mark moves on, and follow.attach or clear follows again', async ({ page }) => {
  await openBook(page, TWO_SECTIONS, { storage: { viewMode: 'continuous' }, css: 'height: 5000px;' });
  await sections(page, A_B);
  const detached = () => page.evaluate(() => window.honmaniFollow.detached());
  const scrollByHand = async () => {
    await page.waitForTimeout(700); // past the time our own scrolling may still cause scroll events
    await page.evaluate(() => window.scrollTo(0, window.scrollY + 1000));
    await expect.poll(detached).toBe(true);
  };

  await send(page, { type: 'audiobook.mark', gen: 1, start: 0, end: 3 });
  await expect.poll(() => marked(page)).toEqual(['吾輩は']);
  expect(await detached()).toBe(false);
  await scrollByHand();
  expect(await page.evaluate(() => window.__messages.filter((m) => m.type === 'follow.detached').length)).toBe(1);

  const scrolled = await page.evaluate(() => window.scrollY);
  await send(page, { type: 'audiobook.mark', gen: 2, start: 7, end: 10 });
  await expect.poll(() => marked(page)).toEqual(['名前は']);
  expect(await page.evaluate(() => window.scrollY)).toBe(scrolled);

  await send(page, { type: 'follow.attach' });
  await expect.poll(detached).toBe(false);

  await send(page, { type: 'audiobook.mark', gen: 3, start: 0, end: 3 });
  await expect.poll(() => marked(page)).toEqual(['吾輩は']);
  await scrollByHand();
  await send(page, { type: 'audiobook.clear' });
  await expect.poll(detached).toBe(false);
});
