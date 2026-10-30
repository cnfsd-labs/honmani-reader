// @ts-check
/**
 * The library bridge against the real ッツ Ebook Reader build: Honmani's native library hands
 * book files to ttu (served at /honmani-books/ like the app's BookPathHandler), opens them by id,
 * reads reading progress and removes books.
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

const BOOKS = {
  'neko.epub': buildEpub({
    title: '吾輩は猫である',
    paragraphs: ['吾輩は猫である。名前はまだ無い。', 'どこで生れたかとんと見当がつかぬ。'],
  }),
  'long.epub': buildEpub({
    title: '長い本',
    paragraphs: Array.from({ length: 300 }, (_, i) => `第${i + 1}段落。${'吾輩は猫である。'.repeat(20)}`),
  }),
  // A web novel's chapter as one paragraph, longer than many pages.
  'one-paragraph.epub': buildEpub({
    title: '一段落の本',
    paragraphs: [Array.from({ length: 400 }, (_, i) => `これは${i + 1}番目の文です。`).join('')],
  }),
  // Lines split by <br> in one block, and words in their own elements with a space between.
  'lines.epub': buildEpub({
    title: '行の本',
    raw: true,
    paragraphs: ['<div>東京<br/>都庁へ行く。<span>猫</span> <span>犬</span></div>'],
  }),
  // Furigana on every word: many text nodes per page.
  'ruby.epub': buildEpub({
    title: 'ルビの本',
    paragraphs: Array.from({ length: 120 }, (_, i) => `第${i + 1}段落。` + '<ruby>吾輩<rt>わがはい</rt></ruby>は<ruby>猫<rt>ねこ</rt></ruby>である。'.repeat(25)),
  }),
  // Scripts a book brings along, in the ways that ran in ttu before the reader's policy.
  'scripts.epub': buildEpub({
    title: 'スクリプトの本',
    raw: true,
    paragraphs: [
      '<p>本文はここ。</p>',
      '<details open="open" ontoggle="window.__ran=(window.__ran||[]).concat(\'ontoggle\')"><summary>a</summary>b</details>',
      '<iframe srcdoc="&lt;script&gt;parent.__ran=(parent.__ran||[]).concat(\'iframe\')&lt;/script&gt;"></iframe>',
      '<video src="missing.mp4" onerror="window.__ran=(window.__ran||[]).concat(\'onerror\')"></video>',
      '<img src="missing.png" onerror="window.__ran=(window.__ran||[]).concat(\'img\')"/>',
      '<svg xmlns="http://www.w3.org/2000/svg" onload="window.__ran=(window.__ran||[]).concat(\'svg\')"><rect width="1" height="1"/></svg>',
      '<script>window.__ran=(window.__ran||[]).concat(\'script\')</script>',
    ],
  }),
  'broken.epub': Buffer.from('PK this is not really a zip'),
};
/** text.js comes first, as in DOCUMENT_START_SCRIPTS: the other scripts read what it defines. */
test.beforeEach(async ({ page }) => {
  await page.addInitScript({ path: path.join(ASSETS, 'text.js') });
});

/** The sample books that come with the app. */
const SAMPLES = path.join(__dirname, '../../app/src/main/assets/samples');
for (const name of fs.readdirSync(SAMPLES)) BOOKS[`sample-${name}`] = fs.readFileSync(path.join(SAMPLES, name));

/** Registers the init scripts as ReaderWebView does and serves library files. */
async function setUp(page) {
  await page.addInitScript(() => {
    window.__messages = [];
    window.honmani = { postMessage: (message) => window.__messages.push(JSON.parse(message)) };
  });
  await page.addInitScript({ path: path.join(ASSETS, 'ttu-defaults.js') });
  await page.addInitScript({ path: path.join(ASSETS, 'library-bridge.js') });
  await page.route('**/honmani-books/*', (route) => {
    const name = new URL(route.request().url()).pathname.split('/').pop();
    const body = BOOKS[name];
    if (body) route.fulfill({ status: 200, body, contentType: 'application/octet-stream' });
    else route.fulfill({ status: 404, body: '' });
  });
}

let requestCount = 0;

/** Calls a bridge command and waits for its answer. */
async function call(page, command, args = {}) {
  const requestId = ++requestCount;
  await page.evaluate(([command, args]) => window.honmaniLibrary[command](args), [command, { requestId, ...args }]);
  const handle = await page.waitForFunction(
    (requestId) => window.__messages.find((m) => m.requestId === requestId),
    requestId,
    { timeout: 20_000 },
  );
  return handle.jsonValue();
}

async function ensure(page, fileName, ttuId = null, ttuTitle = null) {
  return call(page, 'ensure', { ttuId, ttuTitle, url: `/honmani-books/${fileName}`, fileName });
}

async function progress(page) {
  const count = await page.evaluate(() => window.__messages.length);
  await page.evaluate(() => window.honmaniLibrary.progress());
  const handle = await page.waitForFunction(
    (count) => window.__messages.slice(count).find((m) => m.type === 'library.progress'),
    count,
  );
  return (await handle.jsonValue()).books;
}

test('a new book is imported through ttu and opens by id', async ({ page }) => {
  await setUp(page);
  await page.goto(`${server.url}/manage`);

  const reply = await ensure(page, 'neko.epub');
  expect(reply).toMatchObject({ type: 'library.ready', title: '吾輩は猫である' });
  expect(typeof reply.id).toBe('number');

  // Opened inside the running page: a full load kept ttu's book list on screen meanwhile.
  await page.evaluate(() => { window.__samePage = true; });
  await page.evaluate((ttuId) => window.honmaniLibrary.open({ ttuId }), reply.id);
  await page.waitForURL(new RegExp(`/b\\?id=${reply.id}$`));
  await expect(page.locator('.book-content')).toContainText('名前はまだ無い');
  expect(await page.evaluate(() => window.__samePage)).toBe(true);
});

test("a book's own scripts don't run in the reader", async ({ page }) => {
  await setUp(page);
  await page.goto(`${server.url}/manage`);

  const reply = await ensure(page, 'scripts.epub');
  await page.evaluate((ttuId) => window.honmaniLibrary.open({ ttuId }), reply.id);
  await page.waitForURL(new RegExp(`/b\\?id=${reply.id}$`));
  await expect(page.locator('.book-content')).toContainText('本文はここ');
  await page.waitForTimeout(1_000);
  expect(await page.evaluate(() => window.__ran ?? [])).toEqual([]);
});

test('a book ttu already holds is not imported again', async ({ page }) => {
  await setUp(page);
  await page.goto(`${server.url}/manage`);
  const first = await ensure(page, 'neko.epub');

  await page.reload();
  await page.evaluate(() => {
    window.__puts = 0;
    const put = IDBObjectStore.prototype.put;
    IDBObjectStore.prototype.put = function (...args) {
      if (this.name === 'data') window.__puts++;
      return put.apply(this, args);
    };
  });
  const again = await ensure(page, 'neko.epub', first.id, first.title);

  expect(again).toMatchObject({ type: 'library.ready', id: first.id, title: first.title });
  expect(await page.evaluate(() => window.__puts)).toBe(0);
});

test('a stale id (ttu reset, or another book under that id) imports the book again', async ({ page }) => {
  await setUp(page);
  await page.goto(`${server.url}/manage`);
  const neko = await ensure(page, 'neko.epub');

  const reply = await ensure(page, 'long.epub', neko.id, '長い本');

  expect(reply).toMatchObject({ type: 'library.ready', title: '長い本' });
  expect(reply.id).not.toBe(neko.id);
});

test('a book ttu cannot read is reported as failed', async ({ page }) => {
  await setUp(page);
  await page.goto(`${server.url}/manage`);

  const reply = await ensure(page, 'broken.epub');

  expect(reply.type).toBe('library.failed');
  expect(reply.message).toContain('Error importing');
});

test('a missing book file is reported as failed', async ({ page }) => {
  await setUp(page);
  await page.goto(`${server.url}/manage`);

  const reply = await ensure(page, 'gone.epub');

  expect(reply).toMatchObject({ type: 'library.failed' });
  expect(reply.message).toContain('404');
});

test('reading progress comes from ttu’s bookmarks', async ({ page }) => {
  await setUp(page);
  await page.goto(`${server.url}/manage`);
  const book = await ensure(page, 'long.epub');
  expect(await progress(page)).toEqual([]);

  await page.goto(`${server.url}/b?id=${book.id}`);
  await expect(page.locator('div.fixed.inset-0.text-7xl')).toHaveCount(0, { timeout: 5_000 });
  for (let i = 0; i < 5; i++) {
    await page.keyboard.press('ArrowDown');
    await page.waitForTimeout(100);
  }
  // ttu saves its automatic bookmark shortly after a page turn.
  await expect.poll(async () => (await progress(page)).find((b) => b.id === book.id)?.progress ?? 0).toBeGreaterThan(0);
  const [{ progress: fraction, characters }] = await progress(page);
  expect(fraction).toBeLessThan(1);
  // The characters read up to the position, for the reading statistics.
  expect(characters).toBeGreaterThan(0);
});

test('navigate rejects targets that leave the reader origin', async ({ page }) => {
  await setUp(page);
  await page.goto(`${server.url}/manage`);
  await page.evaluate(() => window.honmaniLibrary.navigate({ path: '/settings' }));
  await page.waitForURL(/\/settings$/);
  for (const path of ['/\\example.com/x', '/\\/example.com/x', '//example.com/x', '/\t/example.com/x', 'https://example.com/']) {
    await page.evaluate((path) => window.honmaniLibrary.navigate({ path }), path);
    await page.waitForTimeout(200);
    expect(page.url().startsWith(server.url)).toBe(true);
    expect(new URL(page.url()).pathname).toBe('/settings');
  }
});

test('other ttu pages open inside the running page, without a page load', async ({ page }) => {
  await setUp(page);
  await page.goto(`${server.url}/manage`);
  await page.evaluate(() => (window.__samePage = true));

  await page.evaluate(() => window.honmaniLibrary.navigate({ path: '/settings' }));
  await page.waitForURL(/\/settings$/);
  await expect(page.locator('h2', { hasText: 'Theme' })).toBeVisible();
  expect(await page.evaluate(() => window.__samePage)).toBe(true);

  // Only paths on the reader's origin.
  await page.evaluate(() => window.honmaniLibrary.navigate({ path: '//example.com/x' }));
  await page.waitForTimeout(300);
  expect(new URL(page.url()).pathname).toBe('/settings');
});

// The app keeps the book list covered until the settings are drawn (book-shown.js).
test('opening the settings reports them drawn once per visit, after the book list is gone', async ({ page }) => {
  await setUp(page);
  await page.addInitScript({ path: path.join(ASSETS, 'book-shown.js') });
  await page.addInitScript(() => {
    const post = window.honmani.postMessage;
    window.honmani.postMessage = (message) => {
      const parsed = JSON.parse(message);
      if (parsed.type === 'pageShown') {
        parsed.settings = !!Array.from(document.querySelectorAll('h2')).find((h) => h.textContent.includes('Theme'));
        parsed.bookList = !!document.querySelector('input[type=file][accept^="application/epub+zip"]');
      }
      post(JSON.stringify(parsed));
    };
  });
  await page.goto(`${server.url}/manage`);
  const shown = () => page.evaluate(() => window.__messages.filter((m) => m.type === 'pageShown'));
  await page.waitForTimeout(300);
  expect(await shown()).toEqual([]);

  await page.evaluate(() => window.honmaniLibrary.navigate({ path: '/settings' }));
  const message = { type: 'pageShown', path: '/settings', settings: true, bookList: false };
  await expect.poll(shown).toEqual([message]);
  await page.waitForTimeout(300);
  expect(await shown()).toHaveLength(1);

  await page.goBack();
  await page.waitForURL(/\/manage$/);
  await page.evaluate(() => window.honmaniLibrary.navigate({ path: '/settings' }));
  await expect.poll(shown).toEqual([message, message]);
});

test('touches and keys in a book are reported as reading activity, at most every 15 s', async ({ page }) => {
  await setUp(page);
  await page.addInitScript({ path: path.join(ASSETS, 'reading-activity.js') });
  await page.goto(`${server.url}/manage`);
  const book = await ensure(page, 'long.epub');
  const activity = () => page.evaluate(() => window.__messages.filter((m) => m.type === 'reader.activity').length);
  await page.keyboard.press('ArrowDown');
  expect(await activity()).toBe(0); // not on the book page

  await page.goto(`${server.url}/b?id=${book.id}`);
  await expect(page.locator('div.fixed.inset-0.text-7xl')).toHaveCount(0, { timeout: 5_000 });
  await page.keyboard.press('ArrowDown');
  await page.mouse.click(200, 300);
  await page.keyboard.press('ArrowDown');
  expect(await activity()).toBe(1);
});

test('every page turn in a book reports the new reading position', async ({ page }) => {
  await setUp(page);
  await page.addInitScript({ path: path.join(ASSETS, 'reading-activity.js') });
  await page.goto(`${server.url}/manage`);
  const book = await ensure(page, 'long.epub');
  await page.goto(`${server.url}/b?id=${book.id}`);
  await expect(page.locator('div.fixed.inset-0.text-7xl')).toHaveCount(0, { timeout: 5_000 });
  const positions = () => page.evaluate(() => window.__messages.filter((m) => m.type === 'reader.position'));

  for (let turn = 1; turn <= 3; turn++) {
    await page.keyboard.press('ArrowDown');
    await expect.poll(async () => (await positions()).length).toBeGreaterThanOrEqual(turn);
  }
  const reported = await positions();
  expect(reported.every((m) => m.id === book.id && Number.isInteger(m.characters))).toBe(true);
  expect(reported.at(-1).characters).toBeGreaterThan(0);
  // Where in the book, for the stretches read: moves forward with the pages.
  expect(reported.every((m) => typeof m.progress === 'number' && m.progress >= 0 && m.progress <= 1)).toBe(true);
  expect(reported.at(-1).progress).toBeGreaterThan(0);
  expect(reported.at(-1).progress).toBeCloseTo((await progress(page))[0].progress, 5);
  // The same number ttu keeps in its bookmark.
  expect((await progress(page))[0].characters).toBe(reported.at(-1).characters);
});

// ttu counts characters by text node; a paragraph longer than a page used to keep the count
// (and the bookmark) on the same number page after page.
test('the character count moves on every page, also within a paragraph longer than a page', async ({ page }) => {
  await setUp(page);
  await page.addInitScript({ path: path.join(ASSETS, 'reading-activity.js') });
  await page.goto(`${server.url}/manage`);
  const book = await ensure(page, 'one-paragraph.epub');
  await page.goto(`${server.url}/b?id=${book.id}`);
  await expect(page.locator('div.fixed.inset-0.text-7xl')).toHaveCount(0, { timeout: 5_000 });
  const positions = () => page.evaluate(() => window.__messages.filter((m) => m.type === 'reader.position').map((m) => m.characters));

  for (let turn = 1; turn <= 4; turn++) {
    await page.keyboard.press('ArrowDown');
    await expect.poll(async () => (await positions()).length).toBeGreaterThanOrEqual(turn);
  }
  const counts = await positions();
  expect(counts[0]).toBeGreaterThan(0);
  for (let i = 1; i < counts.length; i++) expect(counts[i]).toBeGreaterThan(counts[i - 1]);
});

// A book reopened far into a long chapter (its last page, say) used to stay covered for the
// full 6 s: book-shown.js looked for text on screen only among the chapter's first text nodes.
test('a book reopened far into a chapter is reported shown right away', async ({ page }) => {
  await setUp(page);
  await page.addInitScript({ path: path.join(ASSETS, 'book-shown.js') });
  await page.goto(`${server.url}/manage`);
  const book = await ensure(page, 'ruby.epub');
  await page.evaluate((id) => window.honmaniLibrary.open({ ttuId: id }), book.id);
  await page.waitForURL(/\/b\?id=/);
  await expect(page.locator('div.fixed.inset-0.text-7xl')).toHaveCount(0, { timeout: 5_000 });
  for (let i = 0; i < 25; i++) {
    await page.keyboard.press('ArrowDown');
    await page.waitForTimeout(60);
  }
  await expect.poll(async () => (await progress(page))[0]?.progress ?? 0).toBeGreaterThan(0.1);
  await page.goBack();
  await page.waitForURL(/\/manage$/);

  await page.evaluate(() => (window.__messages.length = 0));
  const start = Date.now();
  await page.evaluate((id) => window.honmaniLibrary.open({ ttuId: id }), book.id);
  await expect.poll(() => page.evaluate(() => window.__messages.filter((m) => m.type === 'bookShown').length), { timeout: 3_000 }).toBe(1);
  expect(Date.now() - start).toBeLessThan(2_500);
});

test('resetting progress makes ttu open the book at the start', async ({ page }) => {
  await setUp(page);
  await page.goto(`${server.url}/manage`);
  const book = await ensure(page, 'long.epub');
  await page.goto(`${server.url}/b?id=${book.id}`);
  await expect(page.locator('div.fixed.inset-0.text-7xl')).toHaveCount(0, { timeout: 5_000 });
  for (let i = 0; i < 5; i++) {
    await page.keyboard.press('ArrowDown');
    await page.waitForTimeout(100);
  }
  await expect.poll(async () => (await progress(page)).length).toBe(1);
  // The app resets from the library, never while ttu shows the book.
  await page.goto(`${server.url}/manage`);

  expect(await call(page, 'resetProgress', { ttuId: book.id })).toMatchObject({ type: 'library.reset' });
  expect(await progress(page)).toEqual([]);

  await page.goto(`${server.url}/b?id=${book.id}`);
  await expect(page.locator('div.fixed.inset-0.text-7xl')).toHaveCount(0, { timeout: 5_000 });
  await expect(page.locator('.book-content p').first()).toBeInViewport();
});

test('a position set from a backup keeps the time it was read, for progress sync to compare', async ({ page }) => {
  await setUp(page);
  await page.goto(`${server.url}/manage`);
  const book = await ensure(page, 'long.epub');

  await call(page, 'setBookmark', { ttuId: book.id, characters: 1234, progress: 0.1, modified: 1_700_000_000_000 });
  expect(await call(page, 'bookmark', { ttuId: book.id })).toMatchObject({ characters: 1234, modified: 1_700_000_000_000 });

  // From progress sync: no time given, so now.
  const before = Date.now();
  await call(page, 'setBookmark', { ttuId: book.id, characters: 2000, progress: 0.2 });
  const synced = await call(page, 'bookmark', { ttuId: book.id });
  expect(synced.characters).toBe(2000);
  expect(synced.modified).toBeGreaterThanOrEqual(before);
});

test('removing a book deletes it from ttu', async ({ page }) => {
  await setUp(page);
  await page.goto(`${server.url}/manage`);
  const book = await ensure(page, 'neko.epub');

  expect(await call(page, 'remove', { ttuId: book.id })).toMatchObject({ type: 'library.removed' });

  const ids = await page.evaluate(
    () =>
      new Promise((resolve) => {
        const request = indexedDB.open('books');
        request.onsuccess = () => {
          const all = request.result.transaction('data').objectStore('data').getAllKeys();
          all.onsuccess = () => resolve(all.result);
        };
      }),
  );
  expect(ids).not.toContain(book.id);
});

test('the bridge never creates ttu’s database itself', async ({ page }) => {
  await setUp(page);
  // Any page on the origin gets the scripts; this one does not load ttu.
  await page.goto(`${server.url}/does-not-exist.txt`);

  expect(await progress(page)).toEqual([]);
  const names = await page.evaluate(async () => (await indexedDB.databases()).map((d) => d.name));
  expect(names).not.toContain('books');
});

test('the book page reports its background and shows the reading position as a bar', async ({ page }) => {
  await setUp(page);
  await page.addInitScript(() => {
    window.localStorage.setItem('theme', 'ecru-theme');
    window.localStorage.setItem('honmaniAccent', JSON.stringify(['#e91e63', '#ffffff']));
  });
  await page.addInitScript({ path: path.join(ASSETS, 'reading-activity.js') });
  await page.addInitScript({ path: path.join(ASSETS, 'book-frame.js') });
  await page.goto(`${server.url}/manage`);
  const book = await ensure(page, 'long.epub');
  await page.goto(`${server.url}/b?id=${book.id}`);
  await expect(page.locator('div.fixed.inset-0.text-7xl')).toHaveCount(0, { timeout: 5_000 });

  const backgrounds = () => page.evaluate(() => window.__messages.filter((m) => m.type === 'reader.background').map((m) => m.color));
  await expect.poll(backgrounds).toContain('#f7f6eb');

  const bar = page.locator('#honmani-progress');
  for (let turn = 1; turn <= 3; turn++) await page.keyboard.press('ArrowDown');
  await expect.poll(async () => (await bar.boundingBox())?.width ?? 0).toBeGreaterThan(0);
  const box = await bar.boundingBox();
  const viewport = page.viewportSize();
  // At the bottom, in the accent colour, as far as the reading position.
  expect(box.y + box.height).toBeCloseTo(viewport.height, 0);
  expect(await bar.evaluate((element) => getComputedStyle(element).backgroundColor)).toBe('rgb(233, 30, 99)');
  const reported = await page.evaluate(() => window.__messages.filter((m) => m.type === 'reader.position').at(-1).progress);
  await expect.poll(async () => (await bar.boundingBox()).width / viewport.width).toBeCloseTo(reported, 2);
  // Vertical books fill from the right, the way their pages turn.
  const vertical = await page.evaluate(() => getComputedStyle(document.documentElement).writingMode.startsWith('vertical-rl'));
  if (vertical) expect(box.x + box.width).toBeCloseTo(viewport.width, 0);
  else expect(box.x).toBeCloseTo(0, 0);
});

test('a book element with the progress bar id is left alone; the bar is the script\'s own', async ({ page }) => {
  await setUp(page);
  await page.addInitScript(() => {
    window.addEventListener('DOMContentLoaded', () => {
      document.body.insertAdjacentHTML('beforeend', '<div id="honmani-progress" data-book="1"></div>');
    });
  });
  await page.addInitScript({ path: path.join(ASSETS, 'reading-activity.js') });
  await page.addInitScript({ path: path.join(ASSETS, 'book-frame.js') });
  await page.goto(`${server.url}/manage`);
  const book = await ensure(page, 'long.epub');
  await page.goto(`${server.url}/b?id=${book.id}`);
  await expect(page.locator('div.fixed.inset-0.text-7xl')).toHaveCount(0, { timeout: 5_000 });

  for (let turn = 1; turn <= 3; turn++) await page.keyboard.press('ArrowDown');
  const bar = page.locator('#honmani-progress:not([data-book])');
  await expect.poll(async () => (await bar.boundingBox())?.width ?? 0).toBeGreaterThan(0);
  expect(await page.locator('#honmani-progress[data-book]').evaluate((e) => e.getAttribute('style'))).toBeNull();
});

test('the progress bar stays off when the reader turned it off', async ({ page }) => {
  await setUp(page);
  await page.addInitScript(() => window.localStorage.setItem('honmaniProgressBar', '0'));
  await page.addInitScript({ path: path.join(ASSETS, 'reading-activity.js') });
  await page.addInitScript({ path: path.join(ASSETS, 'book-frame.js') });
  await page.goto(`${server.url}/manage`);
  const book = await ensure(page, 'long.epub');
  await page.goto(`${server.url}/b?id=${book.id}`);
  await expect(page.locator('div.fixed.inset-0.text-7xl')).toHaveCount(0, { timeout: 5_000 });

  for (let turn = 1; turn <= 3; turn++) await page.keyboard.press('ArrowDown');
  await expect.poll(() => page.evaluate(() => window.__messages.some((m) => m.type === 'reader.position'))).toBe(true);
  await expect(page.locator('#honmani-progress')).toHaveCount(0);
});

/** The bridge object with replies, as WebView's: the app's answers go to its message listeners. */
async function withReplies(page) {
  await page.addInitScript(() => {
    const listeners = [];
    window.honmani.addEventListener = (type, listener) => listeners.push(listener);
    window.__reply = (message) => listeners.forEach((listener) => listener({ data: JSON.stringify(message) }));
  });
}

/** Opens `name` with word marks on, in `mode` (null: the default), and waits for the book. */
async function openWithMarks(page, name, mode) {
  await setUp(page);
  await withReplies(page);
  if (mode) await page.addInitScript((mode) => window.localStorage.setItem('honmaniWordMarks', mode), mode);
  await page.addInitScript({ path: path.join(ASSETS, 'word-marks.js') });
  await page.goto(`${server.url}/manage`);
  const book = await ensure(page, name);
  await page.goto(`${server.url}/b?id=${book.id}`);
  await expect(page.locator('div.fixed.inset-0.text-7xl')).toHaveCount(0, { timeout: 5_000 });
}

const markRequests = (page) => page.evaluate(() => window.__messages.filter((m) => m.type === 'marks.request'));

/** The text each word-mark highlight covers. */
const marked = (page) =>
  page.evaluate(() =>
    Object.fromEntries(
      ['honmani-learning', 'honmani-new'].map((name) => [name, [...(CSS.highlights.get(name) ?? [])].map((range) => range.toString())]),
    ),
  );

test('word marks: the page asks about the paragraphs on screen and marks what the app answers', async ({ page }) => {
  await openWithMarks(page, 'neko.epub', null);

  await expect.poll(async () => (await markRequests(page)).flatMap((m) => m.blocks.map((b) => b.text))).toContain('吾輩は猫である。名前はまだ無い。');
  const request = (await markRequests(page)).find((m) => m.blocks.some((b) => b.text.startsWith('吾輩')));
  expect(request.mode).toBe('learning');
  const block = request.blocks.find((b) => b.text.startsWith('吾輩'));
  // New marks fade in: they start in their own highlights, then join the others.
  const fresh = await page.evaluate(([requestId, id]) => {
    window.__reply({ type: 'marks', requestId, color: '#e91e63', blocks: [{ id, marks: [[0, 2, 0], [3, 1, 1]] }] });
    const texts = (name) => [...(CSS.highlights.get(name) ?? [])].map((range) => range.toString());
    return { learning: texts('honmani-learning-fresh'), new: texts('honmani-new-fresh'), settled: texts('honmani-learning') };
  }, [request.requestId, block.id]);
  expect(fresh).toEqual({ learning: ['吾輩'], new: ['猫'], settled: [] });
  await expect.poll(() => marked(page)).toEqual({ 'honmani-learning': ['吾輩'], 'honmani-new': ['猫'] });
  expect(await page.evaluate(() => CSS.highlights.get('honmani-learning-fresh').size)).toBe(0);

  // The reader's words changed (a lookup): the paragraphs on screen are asked about again, and
  // the marks stay until the answer, so unchanged ones don't flash.
  const before = (await markRequests(page)).length;
  await page.evaluate(() => window.__reply({ type: 'marks.reset' }));
  expect(await marked(page)).toEqual({ 'honmani-learning': ['吾輩'], 'honmani-new': ['猫'] });
  await expect.poll(async () => (await markRequests(page)).length).toBeGreaterThan(before);
  const again = (await markRequests(page)).slice(before).find((m) => m.blocks.some((b) => b.id === block.id));

  // 吾輩 stays as it was, 猫 is no longer new, 名前 is newly marked and only it fades in.
  const after = await page.evaluate(([requestId, id]) => {
    window.__reply({ type: 'marks', requestId, color: '#e91e63', blocks: [{ id, marks: [[0, 2, 0], [8, 2, 1]] }] });
    const texts = (name) => [...(CSS.highlights.get(name) ?? [])].map((range) => range.toString());
    return { learning: texts('honmani-learning'), freshLearning: texts('honmani-learning-fresh'), new: texts('honmani-new'), freshNew: texts('honmani-new-fresh') };
  }, [again.requestId, block.id]);
  expect(after).toEqual({ learning: ['吾輩'], freshLearning: [], new: [], freshNew: ['名前'] });
  await expect.poll(() => marked(page)).toEqual({ 'honmani-learning': ['吾輩'], 'honmani-new': ['名前'] });
});

test('word marks are stronger on a dark page, so they show at low screen brightness', async ({ page }) => {
  await openWithMarks(page, 'neko.epub', 'new');
  await expect.poll(async () => (await markRequests(page)).flatMap((m) => m.blocks.map((b) => b.text))).toContain('吾輩は猫である。名前はまだ無い。');
  const request = (await markRequests(page)).find((m) => m.blocks.some((b) => b.text.startsWith('吾輩')));
  const block = request.blocks.find((b) => b.text.startsWith('吾輩'));
  const answer = (requestId) =>
    page.evaluate(
      ([requestId, id]) => window.__reply({ type: 'marks', requestId, color: '#e91e63', blocks: [{ id, marks: [[0, 2, 0], [3, 1, 1]] }] }),
      [requestId, block.id],
    );
  const tints = () => page.evaluate(() => document.getElementById('honmani-marks-style').textContent.split('\n').slice(0, 2));

  await page.evaluate(() => (document.body.style.backgroundColor = 'rgb(255, 255, 255)'));
  await answer(request.requestId);
  await expect.poll(tints).toEqual([
    '::highlight(honmani-learning) { background-color: rgba(233, 30, 99, 0.280); }',
    '::highlight(honmani-new) { background-color: rgba(128, 128, 128, 0.200); }',
  ]);

  // A dark theme: the next answer brings the stronger tints.
  await page.evaluate(() => (document.body.style.backgroundColor = 'rgb(0, 0, 0)'));
  const before = (await markRequests(page)).length;
  await page.evaluate(() => window.__reply({ type: 'marks.reset' }));
  await expect.poll(async () => (await markRequests(page)).length).toBeGreaterThan(before);
  const again = (await markRequests(page)).slice(before).find((m) => m.blocks.some((b) => b.id === block.id));
  await answer(again.requestId);
  await expect.poll(tints).toEqual([
    '::highlight(honmani-learning) { background-color: rgba(233, 30, 99, 0.450); }',
    '::highlight(honmani-new) { background-color: rgba(128, 128, 128, 0.450); }',
  ]);
});

test('a word just looked up is marked as being learned at once, before the app answers again', async ({ page }) => {
  await openWithMarks(page, 'neko.epub', 'new');
  await expect.poll(async () => (await markRequests(page)).flatMap((m) => m.blocks.map((b) => b.text))).toContain('吾輩は猫である。名前はまだ無い。');
  const request = (await markRequests(page)).find((m) => m.blocks.some((b) => b.text.startsWith('吾輩')));
  const block = request.blocks.find((b) => b.text.startsWith('吾輩'));
  await page.evaluate(
    ([requestId, id]) => window.__reply({ type: 'marks', requestId, color: '#e91e63', blocks: [{ id, marks: [[3, 1, 1]] }] }),
    [request.requestId, block.id],
  );
  await expect.poll(() => marked(page)).toEqual({ 'honmani-learning': [], 'honmani-new': ['猫'] });

  // tap-bridge.js announces the lookup's mark over 猫.
  await page.evaluate(() => {
    const walker = document.createTreeWalker(document.querySelector('.book-content'), NodeFilter.SHOW_TEXT);
    let node = walker.nextNode();
    while (node && !node.data.includes('吾輩は猫')) node = walker.nextNode();
    const range = document.createRange();
    const at = node.data.indexOf('猫');
    range.setStart(node, at);
    range.setEnd(node, at + 1);
    window.dispatchEvent(new CustomEvent('honmani-looked-up', { detail: { ranges: [range], color: '#e91e63' } }));
  });
  expect(await marked(page)).toEqual({ 'honmani-learning': ['猫'], 'honmani-new': [] });
});

test('a word just looked up keeps its mark through answers asked for before the lookup counted', async ({ page }) => {
  await openWithMarks(page, 'neko.epub', null);
  await expect.poll(async () => (await markRequests(page)).flatMap((m) => m.blocks.map((b) => b.text))).toContain('吾輩は猫である。名前はまだ無い。');
  const request = (await markRequests(page)).find((m) => m.blocks.some((b) => b.text.startsWith('吾輩')));
  const block = request.blocks.find((b) => b.text.startsWith('吾輩'));

  // The answer to a request sent before the tap arrives after the lookup's mark, without 猫.
  await page.evaluate(() => {
    const walker = document.createTreeWalker(document.querySelector('.book-content'), NodeFilter.SHOW_TEXT);
    let node = walker.nextNode();
    while (node && !node.data.includes('吾輩は猫')) node = walker.nextNode();
    const range = document.createRange();
    const at = node.data.indexOf('猫');
    range.setStart(node, at);
    range.setEnd(node, at + 1);
    window.dispatchEvent(new CustomEvent('honmani-looked-up', { detail: { ranges: [range], color: '#e91e63' } }));
  });
  await page.evaluate(
    ([requestId, id]) => window.__reply({ type: 'marks', requestId, color: '#e91e63', blocks: [{ id, marks: [[0, 2, 0]] }] }),
    [request.requestId, block.id],
  );
  // In any order: 猫's mark came first.
  await expect.poll(async () => (await marked(page))['honmani-learning'].sort()).toEqual(['吾輩', '猫']);

  // After the lookup's reset the app's answer decides: here 猫 is no longer being learned.
  const before = (await markRequests(page)).length;
  await page.evaluate(() => window.__reply({ type: 'marks.reset' }));
  await expect.poll(async () => (await markRequests(page)).length).toBeGreaterThan(before);
  const again = (await markRequests(page)).slice(before).find((m) => m.blocks.some((b) => b.id === block.id));
  await page.evaluate(
    ([requestId, id]) => window.__reply({ type: 'marks', requestId, color: '#e91e63', blocks: [{ id, marks: [[0, 2, 0]] }] }),
    [again.requestId, block.id],
  );
  await expect.poll(() => marked(page)).toEqual({ 'honmani-learning': ['吾輩'], 'honmani-new': [] });
});

test('word marks leave furigana out of the text and the marks', async ({ page }) => {
  await openWithMarks(page, 'ruby.epub', 'new');

  await expect.poll(async () => (await markRequests(page)).length).toBeGreaterThan(0);
  const request = (await markRequests(page))[0];
  expect(request.mode).toBe('new');
  const block = request.blocks.find((b) => b.text.startsWith('第1段落。'));
  expect(block.text.startsWith('第1段落。吾輩は猫である。吾輩は')).toBe(true);
  await page.evaluate(
    ([requestId, id]) => window.__reply({ type: 'marks', requestId, blocks: [{ id, marks: [[5, 4, 0]] }] }),
    [request.requestId, block.id],
  );
  // 吾輩は猫 spans a ruby base, plain text and another base: three ranges, no furigana.
  await expect.poll(() => marked(page)).toEqual({ 'honmani-learning': ['吾輩', 'は', '猫'], 'honmani-new': [] });
});

test('word marks ask about a paragraph longer than a request in pieces, and mark all of it', async ({ page }) => {
  await openWithMarks(page, 'one-paragraph.epub', null);
  const paragraph = Array.from({ length: 400 }, (_, i) => `これは${i + 1}番目の文です。`).join('');
  expect(paragraph.length).toBeGreaterThan(4_000);

  const sent = async () => (await markRequests(page)).flatMap((m) => m.blocks.map((b) => ({ ...b, requestId: m.requestId }))).filter((b) => b.text.includes('番目の文'));
  await expect.poll(async () => (await sent()).map((b) => b.text).join('')).toBe(paragraph);
  const pieces = await sent();
  expect(pieces.length).toBe(2);
  // Each piece ends after a sentence, within the limit.
  expect(pieces[0].text.length).toBeLessThanOrEqual(4_000);
  expect(pieces[0].text.endsWith('。')).toBe(true);

  // Marks in both pieces, each at its place in the paragraph.
  const answers = new Map();
  for (const [piece, word] of [[pieces[0], '1番目'], [pieces[1], '400番目']]) {
    if (!answers.has(piece.requestId)) answers.set(piece.requestId, []);
    answers.get(piece.requestId).push({ id: piece.id, marks: [[piece.text.indexOf(word), word.length, 0]] });
  }
  for (const [requestId, blocks] of answers) {
    await page.evaluate(([requestId, blocks]) => window.__reply({ type: 'marks', requestId, blocks }), [requestId, blocks]);
  }
  await expect.poll(async () => (await marked(page))['honmani-learning'].sort()).toEqual(['1番目', '400番目']);
});

test('word marks read a block\'s text as the tap bridge does: a <br> ends a line, spaces stay', async ({ page }) => {
  await openWithMarks(page, 'lines.epub', null);
  const text = '東京\n都庁へ行く。猫 犬';
  await expect.poll(async () => (await markRequests(page)).flatMap((m) => m.blocks.map((b) => b.text))).toContain(text);
  const request = (await markRequests(page)).find((m) => m.blocks.some((b) => b.text === text));
  const block = request.blocks.find((b) => b.text === text);
  await page.evaluate(
    ([requestId, id]) => window.__reply({ type: 'marks', requestId, blocks: [{ id, marks: [[0, 2, 0], [3, 2, 0], [11, 1, 1]] }] }),
    [request.requestId, block.id],
  );
  await expect.poll(() => marked(page)).toEqual({ 'honmani-learning': ['東京', '都庁'], 'honmani-new': ['犬'] });

  // A word looked up after the line break gets its mark at the same place.
  await page.evaluate(() => {
    const walker = document.createTreeWalker(document.querySelector('.book-content'), NodeFilter.SHOW_TEXT);
    let node = walker.nextNode();
    while (node && node.data !== '犬') node = walker.nextNode();
    const range = document.createRange();
    range.selectNodeContents(node);
    window.dispatchEvent(new CustomEvent('honmani-looked-up', { detail: { ranges: [range], color: '#e91e63' } }));
  });
  expect(await marked(page)).toEqual({ 'honmani-learning': ['東京', '都庁', '犬'], 'honmani-new': [] });
});

test('word marks let go of the paragraphs of chapters ttu took out of the page', async ({ page }) => {
  // Which paragraphs the marks watch: a reset observes each of them again.
  await page.addInitScript(() => {
    window.__observed = [];
    const observe = IntersectionObserver.prototype.observe;
    IntersectionObserver.prototype.observe = function (target) {
      window.__observed.push(target);
      return observe.call(this, target);
    };
  });
  await openWithMarks(page, 'sample-ginga.epub', null);
  await expect.poll(async () => (await markRequests(page)).length).toBeGreaterThan(0);

  // Paginated, ttu holds one chapter at a time: on to the next one.
  const first = await chapter(page);
  for (let turn = 0; (await chapter(page)) === first; turn++) {
    expect(turn, 'turns to reach the next chapter').toBeLessThan(200);
    await page.keyboard.press('ArrowDown');
    await page.waitForTimeout(20);
  }
  // The marks' rescan after ttu's change, then a lookup's reset.
  await page.waitForTimeout(500);
  const watched = await page.evaluate(() => {
    window.__observed = [];
    window.__reply({ type: 'marks.reset' });
    return { all: window.__observed.length, detached: window.__observed.filter((element) => !element.isConnected).length };
  });
  expect(watched.all).toBeGreaterThan(0);
  expect(watched.detached).toBe(0);
});

test('word marks ask nothing when the reader turned them off', async ({ page }) => {
  await openWithMarks(page, 'neko.epub', 'off');
  await page.waitForTimeout(500);
  expect(await markRequests(page)).toEqual([]);
});

test('every sample book imports into ttu with its title, and the first one opens', async ({ page }) => {
  await setUp(page);
  await page.goto(`${server.url}/manage`);
  const titles = {};
  for (const name of Object.keys(BOOKS).filter((key) => key.startsWith('sample-'))) {
    const reply = await ensure(page, name);
    expect(reply.type, name).toBe('library.ready');
    titles[name] = reply;
  }
  expect(titles['sample-tebukuro.epub'].title).toBe('手袋を買いに');
  await page.evaluate((ttuId) => window.honmaniLibrary.open({ ttuId }), titles['sample-tebukuro.epub'].id);
  await page.waitForURL(/\/b\?id=/);
  await expect(page.locator('.book-content')).toContainText('寒い冬が北方から');
  await expect(page.locator('.book-content rt').first()).toHaveText('きつね');
});

/** Whether any character of the book is on screen (not just inside the book's box). */
const textOnScreen = (page) =>
  page.evaluate(() => {
    const walker = document.createTreeWalker(document.querySelector('.book-content'), NodeFilter.SHOW_TEXT);
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      if (!node.data.trim() || node.parentElement.closest('rt')) continue;
      const range = document.createRange();
      range.selectNodeContents(node);
      for (const box of range.getClientRects()) {
        if (box.width > 0 && box.right > 0 && box.left < innerWidth && box.bottom > 0 && box.top < innerHeight) return true;
      }
    }
    return false;
  });

/** The heading of the chapter ttu shows (it puts in one chapter at a time). */
const chapter = (page) => page.evaluate(() => document.querySelector('.book-content h2')?.textContent ?? null);

test.describe('chapter ends', () => {
  // The OnePlus 7 Pro's WebView, where 銀河鉄道の夜 showed an empty page between two chapters.
  test.use({ isMobile: true, hasTouch: true, deviceScaleFactor: 3, viewport: { width: 360, height: 718 } });

  test('a chapter\'s blank last lines get no page of their own', async ({ page }) => {
    await setUp(page);
    await page.addInitScript({ path: path.join(ASSETS, 'chapter-end.js') });
    await page.goto(`${server.url}/manage`);
    const book = await ensure(page, 'sample-ginga.epub');
    await page.goto(`${server.url}/b?id=${book.id}`);
    await expect(page.locator('div.fixed.inset-0.text-7xl')).toHaveCount(0, { timeout: 5_000 });

    // Through the book, whose chapters end in blank lines; some of them would start a page.
    const empty = [];
    let hidden = 0;
    let turn = 0;
    while ((await chapter(page)) !== '九、ジョバンニの切符') {
      expect(++turn, 'turns to reach the last chapter').toBeLessThan(400);
      hidden = Math.max(hidden, await page.locator('[data-honmani-blank-end]').count());
      await page.keyboard.press('ArrowDown');
      await page.waitForTimeout(50);
      if (!(await textOnScreen(page))) empty.push({ turn, chapter: await chapter(page) });
    }
    expect(empty).toEqual([]);
    expect(hidden).toBeGreaterThan(0);
  });

  test('word marks ask about the next page before it is turned to', async ({ page }) => {
    await setUp(page);
    await withReplies(page);
    await page.addInitScript({ path: path.join(ASSETS, 'word-marks.js') });
    await page.goto(`${server.url}/manage`);
    const book = await ensure(page, 'sample-ginga.epub');
    await page.goto(`${server.url}/b?id=${book.id}`);
    await expect(page.locator('div.fixed.inset-0.text-7xl')).toHaveCount(0, { timeout: 5_000 });

    const asked = async () => (await markRequests(page)).flatMap((m) => m.blocks.map((b) => b.text));
    for (let turn = 1; turn <= 3; turn++) {
      await expect.poll(async () => (await asked()).length).toBeGreaterThan(0);
      await page.waitForTimeout(300);
      const before = new Set(await asked());
      await page.keyboard.press('ArrowDown');
      // The paragraphs now on screen, as word-marks.js reads them (without furigana).
      const shown = await page.evaluate(() =>
        [...document.querySelectorAll('.book-content p')]
          .filter((p) => [...p.getClientRects()].some((box) => box.right > 0 && box.left < innerWidth && box.bottom > 0 && box.top < innerHeight))
          .map((p) => {
            const walker = document.createTreeWalker(p, NodeFilter.SHOW_TEXT);
            let text = '';
            for (let node = walker.nextNode(); node; node = walker.nextNode()) {
              if (!node.parentElement.closest('rt, rp')) text += node.data;
            }
            return text;
          })
          .filter((text) => text.trim()),
      );
      expect(shown.length, `paragraphs on page ${turn + 1}`).toBeGreaterThan(0);
      expect(shown.filter((text) => !before.has(text.slice(0, 4000))), `page ${turn + 1} asked about before the turn`).toEqual([]);
    }
  });
});
