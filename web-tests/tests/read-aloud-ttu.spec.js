// @ts-check
/**
 * Read aloud against the real ッツ Ebook Reader build: the sentence being spoken is kept on
 * screen with ttu's own page turns (so its bookmark and Honmani's reading position move on), in
 * paginated and continuous mode, horizontal and vertical text, across ttu's section changes.
 * The app is a stub that plays the part of the speech engine by sending the protocol's messages.
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

const sentence = (chapter, n) => `第${chapter}章の${n}番目の文はこれです。`;
const paragraph = (chapter, p) => `<p>${[0, 1, 2, 3, 4, 5].map((k) => sentence(chapter, p * 6 + k)).join('')}</p>`;
const chapterOf = (chapter) => Array.from({ length: 25 }, (_, p) => paragraph(chapter, p));
// 25 paragraphs of 6 sentences: about 2,000 characters a chapter, many pages in each.
const BOOK = buildEpub({ title: '読み上げ', chapters: [chapterOf(1), chapterOf(2), chapterOf(3)], raw: true });
// One enormous sentence (no full stop for a thousand characters) to turn the page in the middle of.
const LONG = 'あいうえおかきくけこ、'.repeat(110);
const LONG_BOOK = buildEpub({ title: '長い文', chapters: [[`<p>${LONG}。</p><p>終わりの文。</p>`]], raw: true });

let requestCount = 0;
async function call(page, command, args = {}) {
  const requestId = ++requestCount;
  await page.evaluate(([command, args]) => window.honmaniLibrary[command](args), [command, { requestId, ...args }]);
  const handle = await page.waitForFunction((id) => window.__messages.find((m) => m.requestId === id), requestId, { timeout: 20_000 });
  return handle.jsonValue();
}

async function openBook(page, epub, viewMode, writingMode) {
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
  for (const script of ['ttu-defaults.js', 'library-bridge.js', 'reading-activity.js', 'sentence-follow.js', 'read-aloud.js']) {
    await page.addInitScript({ path: path.join(ASSETS, script) });
  }
  await page.route('**/honmani-books/*', (route) => route.fulfill({ status: 200, body: epub, contentType: 'application/octet-stream' }));
  await page.goto(`${server.url}/manage`);
  const book = await call(page, 'ensure', { ttuId: null, ttuTitle: null, url: '/honmani-books/read.epub', fileName: 'read.epub' });
  await page.goto(`${server.url}/b?id=${book.id}`);
  await expect(page.locator('div.fixed.inset-0.text-7xl')).toHaveCount(0, { timeout: 10_000 });
  await page.waitForFunction(() => document.querySelector('.book-content')?.textContent.length > 10, null, { timeout: 10_000 });
  return book;
}

let gen = 0;
async function ask(page, type, extra = {}) {
  const id = ++gen;
  const before = await page.evaluate(() => window.__messages.length);
  await page.evaluate((message) => window.__listener({ data: JSON.stringify(message) }), { type: `readAloud.${type}`, gen: id, count: 10, ...extra });
  const handle = await page.waitForFunction(
    ([before, id]) => window.__messages.slice(before).find((m) => m.gen === id),
    [before, id],
    { timeout: 30_000 },
  );
  return handle.jsonValue();
}

/** Plays the speech engine for one sentence: says "now speaking", waits until the mark is on screen. */
async function speak(page, index, offset = 0) {
  await page.evaluate(
    ([gen, index, offset]) => window.__listener({ data: JSON.stringify({ type: 'readAloud.mark', gen, index, offset }) }),
    [gen, index, offset],
  );
  await page.waitForFunction(
    () => {
      const highlight = CSS.highlights.get('honmani-read-aloud');
      return highlight && window.honmaniFollow.startOnScreen([...highlight][0]);
    },
    null,
    { timeout: 20_000 },
  );
}

const markedText = (page) => page.evaluate(() => [...CSS.highlights.get('honmani-read-aloud')].map((range) => range.toString().replace(/\s/g, '')));
const positions = (page) => page.evaluate(() => window.__messages.filter((m) => m.type === 'reader.position').map((m) => m.characters));

for (const viewMode of ['paginated', 'continuous']) {
  for (const writingMode of ['horizontal-tb', 'vertical-rl']) {
    test(`the sentence being spoken stays on screen and the saved position moves on (${viewMode}, ${writingMode})`, async ({ page }) => {
      test.setTimeout(120_000);
      await openBook(page, BOOK, viewMode, writingMode);
      const first = await ask(page, 'begin', { count: 10 });
      expect(first.items[0].text).toBe(sentence(1, 0));
      const firstPage = await page.evaluate(() => document.querySelector('.book-content').getBoundingClientRect().left);

      // Speak 40 sentences one after the other, asking for more as the app would.
      const queue = [...first.items];
      let end = first.end;
      const spoken = [];
      for (let n = 0; n < 40; n++) {
        if (queue.length < 4 && !end) {
          const more = await ask(page, 'more', { count: 10 });
          queue.push(...more.items);
          end = more.end;
        }
        const item = queue.shift();
        await speak(page, item.i);
        // The old mark can still be on screen for a moment while the new one is drawn: wait for the text.
        await expect.poll(() => markedText(page), { timeout: 10_000 }).toEqual([item.text]);
        spoken.push(item.text);
      }
      expect(spoken[39]).toBe(sentence(1, 39));
      // The page moved on by itself (turned, or scrolled), through the reader's own path: a normal
      // position report from ttu's bookmark.
      const reported = await positions(page);
      expect(reported.length).toBeGreaterThan(0);
      expect(Math.max(...reported)).toBeGreaterThan(sentence(1, 0).length * 20);
      const nowFirst = await page.evaluate(() => {
        const first = window.honmaniText.firstCharOnScreen(document.querySelector('.book-content'), 'rt, rp', false);
        return first.node.data.slice(first.offset, first.offset + 8);
      });
      expect(nowFirst).not.toBe(sentence(1, 0).slice(0, 8));
      void firstPage;
      // All those turns were our own: the view never counted as taken over by the reader.
      expect(await page.evaluate(() => window.__messages.some((m) => m.type === 'follow.detached'))).toBe(false);
    });
  }
}

const firstOnScreen = (page) =>
  page.evaluate(() => {
    const first = window.honmaniText.firstCharOnScreen(document.querySelector('.book-content'), 'rt, rp', false);
    return first.node.data.slice(first.offset, first.offset + 12);
  });
const detachedMessages = (page) => page.evaluate(() => window.__messages.filter((m) => m.type === 'follow.detached').length);

for (const [viewMode, writingMode] of [['paginated', 'horizontal-tb'], ['paginated', 'vertical-rl'], ['continuous', 'horizontal-tb']]) {
  test(`a page turned by hand stays while the mark moves on, until the reader follows again (${viewMode}, ${writingMode})`, async ({ page }) => {
    test.setTimeout(120_000);
    await openBook(page, BOOK, viewMode, writingMode);
    const { items } = await ask(page, 'begin', { count: 10 });
    await speak(page, items[0].i);
    await expect.poll(() => markedText(page)).toEqual([items[0].text]);
    // Past the time our own turn may still cause scroll events.
    await page.waitForTimeout(700);
    expect(await detachedMessages(page)).toBe(0);

    const before = await firstOnScreen(page);
    if (viewMode === 'paginated') {
      await page.evaluate(() => window.dispatchEvent(new KeyboardEvent('keydown', { code: 'ArrowDown', key: 'ArrowDown', bubbles: true })));
    } else {
      await page.mouse.move(300, 300);
      await page.mouse.wheel(0, 600);
    }
    await expect.poll(() => firstOnScreen(page), { timeout: 10_000 }).not.toBe(before);
    await expect.poll(() => detachedMessages(page), { timeout: 10_000 }).toBe(1);
    const turned = await firstOnScreen(page);

    // The next sentence is back on the page before: the mark goes there, the view does not.
    await page.evaluate(
      ([gen, index]) => window.__listener({ data: JSON.stringify({ type: 'readAloud.mark', gen, index, offset: 0 }) }),
      [gen, items[1].i],
    );
    await expect.poll(() => markedText(page)).toEqual([items[1].text]);
    await page.waitForTimeout(700);
    expect(await firstOnScreen(page)).toBe(turned);
    expect(await detachedMessages(page)).toBe(1);

    // Follow: the app attaches and sends the sentence again, which brings it on screen.
    await page.evaluate(() => window.honmaniFollow.attach());
    await speak(page, items[1].i);
    expect(await page.evaluate(() => window.honmaniFollow.detached())).toBe(false);
  });
}

for (const viewMode of ['paginated', 'continuous']) {
  test(`a page turned by hand detaches while the voice keeps reporting words (${viewMode})`, async ({ page }) => {
    test.setTimeout(120_000);
    await openBook(page, BOOK, viewMode, 'horizontal-tb');
    const { items } = await ask(page, 'begin', { count: 10 });
    await speak(page, items[0].i);
    // A real voice sends a mark for every word, several a second, all through the turn by hand.
    await page.evaluate(
      ([gen, index]) => {
        let offset = 1;
        window.__words = setInterval(() => window.__listener({ data: JSON.stringify({ type: 'readAloud.mark', gen, index, offset: offset++ % 5 }) }), 100);
      },
      [gen, items[0].i],
    );
    await page.waitForTimeout(1000);
    const before = await firstOnScreen(page);
    if (viewMode === 'paginated') {
      await page.evaluate(() => window.dispatchEvent(new KeyboardEvent('keydown', { code: 'ArrowDown', key: 'ArrowDown', bubbles: true })));
    } else {
      await page.mouse.move(300, 300);
      await page.mouse.wheel(0, 900);
    }
    await expect.poll(() => detachedMessages(page), { timeout: 5_000 }).toBe(1);
    await page.waitForTimeout(1000);
    expect(await firstOnScreen(page)).not.toBe(before);
    await page.evaluate(() => clearInterval(window.__words));
  });
}

for (const writingMode of ['horizontal-tb', 'vertical-rl']) {
  test(`a sentence over a page break turns when the part being read is off the page (paginated, ${writingMode})`, async ({ page }) => {
    test.setTimeout(120_000);
    await openBook(page, LONG_BOOK, 'paginated', writingMode);
    const first = await ask(page, 'begin', { count: 2 });
    expect(first.items.length).toBe(2);
    const marked = (index, offset) => speak(page, index, offset);
    await marked(first.items[0].i, 0);
    const start = () => page.evaluate(() => window.honmaniText.firstCharOnScreen(document.querySelector('.book-content'), 'rt, rp', false).offset);
    const before = await start();
    // The sentence start is still on the page, so the page stays while the early part is read.
    await marked(first.items[0].i, 5);
    expect(await start()).toBe(before);
    // The engine reaches a part far on: the page turns, and the part is on it.
    await page.evaluate(
      ([gen, index]) => window.__listener({ data: JSON.stringify({ type: 'readAloud.mark', gen, index, offset: 1000 }) }),
      [gen, first.items[0].i],
    );
    await page.waitForFunction(
      (before) => window.honmaniText.firstCharOnScreen(document.querySelector('.book-content'), 'rt, rp', false).offset > before,
      before,
      { timeout: 20_000 },
    );
    expect(await start()).toBeGreaterThan(before);
    const range = await page.evaluate(() => {
      const [highlight] = CSS.highlights.get('honmani-read-aloud');
      return highlight.toString().length;
    });
    expect(range).toBeGreaterThan(1000);
  });
}

test('paginated mode goes on into the next chapter and says when the book ends', async ({ page }) => {
  test.setTimeout(180_000);
  await openBook(page, BOOK, 'paginated', 'horizontal-tb');
  let reply = await ask(page, 'begin', { count: 10 });
  const all = [...reply.items];
  const chapters = new Set();
  let guard = 0;
  while (guard++ < 200) {
    if (reply.end === 'section') {
      reply = await ask(page, 'advance', { count: 10 });
      if (reply.end === 'book' && reply.items.length === 0) break;
    } else if (reply.end === 'book') {
      break;
    } else {
      reply = await ask(page, 'more', { count: 10 });
    }
    all.push(...reply.items);
    if (reply.items.length) await speak(page, reply.items[reply.items.length - 1].i);
  }
  for (const item of all) chapters.add(item.text.slice(0, 2));
  expect(all.length).toBe(3 * 25 * 6);
  expect(all[0].text).toBe(sentence(1, 0));
  expect(all[all.length - 1].text).toBe(sentence(3, 149));
  // The sentences of chapter 2 came after a section change: they are the section's own text.
  expect(all[150].text).toBe(sentence(2, 0));
  expect(all[300].text).toBe(sentence(3, 0));
  expect(reply.end).toBe('book');
});

test('continuous mode reads through every chapter to the end of the book', async ({ page }) => {
  test.setTimeout(120_000);
  await openBook(page, BOOK, 'continuous', 'horizontal-tb');
  let reply = await ask(page, 'begin', { count: 10 });
  const all = [...reply.items];
  while (!reply.end && all.length < 1000) {
    reply = await ask(page, 'more', { count: 10 });
    all.push(...reply.items);
  }
  expect(reply.end).toBe('book');
  expect(all.length).toBe(3 * 25 * 6);
  expect(all[all.length - 1].text).toBe(sentence(3, 149));
});
