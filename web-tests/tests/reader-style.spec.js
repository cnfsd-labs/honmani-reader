// @ts-check
/**
 * reader-style.js on the real ッツ Ebook Reader build, served with the app's Content-Security-Policy:
 * e-ink mode (honmaniEInk).
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

/** The scripts in ReaderWebView's order (text.js first); `settings` are stored before they run, each page load. */
async function setUp(page, settings = {}) {
  await page.addInitScript(
    ({ settings, first }) => {
      window.__messages = window.__messages ?? [];
      window.honmani = { postMessage: (message) => window.__messages.push(JSON.parse(message)), onmessage: null };
      // Only the first load seeds them: later changes (the test's own) stay.
      if (first && !sessionStorage.getItem('seeded')) {
        sessionStorage.setItem('seeded', '1');
        for (const [key, value] of Object.entries(settings)) localStorage.setItem(key, value);
      }
    },
    { settings, first: true },
  );
  for (const script of ['text.js', 'ttu-defaults.js', 'tap-bridge.js', 'word-marks.js', 'reader-style.js']) {
    await page.addInitScript({ path: path.join(ASSETS, script) });
  }
}

const book = {
  name: 'neko.epub',
  mimeType: 'application/epub+zip',
  buffer: buildEpub({
    title: '吾輩は猫である',
    paragraphs: ['吾輩は<ruby>猫<rt>ねこ</rt></ruby>である。名前はまだ無い。', 'どこで生れたかとんと見当がつかぬ。'],
  }),
};

async function openBook(page) {
  await page.goto(`${server.url}/manage`);
  await page.locator('input[type=file][accept*="epub"]').first().setInputFiles(book);
  await page.getByText('吾輩は猫である').first().click();
  await page.waitForURL(/\/b\?id=\d+/);
  await expect(page.locator('div.fixed.inset-0.text-7xl')).toHaveCount(0, { timeout: 3_000 });
  await expect(page.locator('.book-content p').first()).toBeVisible();
}

/** Stores [values] and loads the book page again, as the reader settings screen does. */
async function reloadWith(page, values) {
  await page.evaluate((values) => {
    for (const [key, value] of Object.entries(values)) {
      if (value === null) localStorage.removeItem(key);
      else localStorage.setItem(key, value);
    }
  }, values);
  await page.reload();
  await expect(page.locator('.book-content p').first()).toBeVisible();
}

const style = (page, selector, property) =>
  page.evaluate(([selector, property]) => getComputedStyle(document.querySelector(selector))[property], [selector, property]);

test.describe('e-ink mode', () => {
  test('off by default: no style, the theme is ttu\'s', async ({ page }) => {
    await setUp(page, { theme: 'black-theme' });
    await openBook(page);
    expect(await page.locator('#honmani-eink-style').count()).toBe(0);
    expect(await style(page, 'body', 'backgroundColor')).toBe('rgb(0, 0, 0)');
  });

  test('on: black on white, no motion, no shadows; the stored theme is untouched, and off again restores it', async ({ page }) => {
    await setUp(page, { theme: 'black-theme' });
    await openBook(page);
    const dark = {
      background: await style(page, 'body', 'backgroundColor'),
      text: await style(page, '.book-content', 'color'),
    };
    expect(dark.background).toBe('rgb(0, 0, 0)');

    await reloadWith(page, { honmaniEInk: '1' });
    expect(await page.locator('#honmani-eink-style').count()).toBe(1);
    expect(await style(page, 'body', 'backgroundColor')).toBe('rgb(255, 255, 255)');
    expect(await style(page, 'html', 'backgroundColor')).toMatch(/rgb\(255, 255, 255\)|rgba\(0, 0, 0, 0\)/);
    expect(await style(page, '.book-content', 'color')).toBe('rgb(0, 0, 0)');
    expect(await style(page, '.book-content p', 'color')).toBe('rgb(0, 0, 0)');
    // ttu's footer (the counter) is black too, not the dark theme's grey.
    expect(await page.evaluate(() => getComputedStyle(document.querySelector('[style^="color:"]:not(.book-content)')).color)).toBe('rgb(0, 0, 0)');
    // No motion or shadows anywhere on the page.
    expect(
      await page.evaluate(() =>
        [...document.querySelectorAll('body, body *')]
          .map((e) => getComputedStyle(e))
          .filter((s) => s.transitionDuration.split(',').some((d) => d.trim() !== '0s') || s.animationName !== 'none' || s.textShadow !== 'none' || s.boxShadow !== 'none')
          .length,
      ),
    ).toBe(0);
    expect(await style(page, 'html', 'scrollBehavior')).toBe('auto');
    // The theme setting itself is not touched.
    expect(await page.evaluate(() => localStorage.getItem('theme'))).toBe('black-theme');

    await reloadWith(page, { honmaniEInk: null });
    expect(await page.locator('#honmani-eink-style').count()).toBe(0);
    expect(await style(page, 'body', 'backgroundColor')).toBe(dark.background);
    expect(await style(page, '.book-content', 'color')).toBe(dark.text);
    expect(await page.evaluate(() => localStorage.getItem('theme'))).toBe('black-theme');
  });

  test('on: marks are greys as solid backgrounds, and the lookup mark is inverted', async ({ page }) => {
    await setUp(page, { honmaniEInk: '1', theme: 'light-theme' });
    await openBook(page);
    const colors = await page.evaluate(() => {
      const range = document.createRange();
      range.selectNodeContents(document.querySelector('.book-content p'));
      const out = {};
      for (const name of ['honmani-learning', 'honmani-new', 'honmani-due', 'honmani-one-unknown', 'honmani-known-furigana', 'honmani-search-hit', 'honmani-lookup']) {
        CSS.highlights.set(name, new Highlight(range));
        const s = getComputedStyle(document.querySelector('.book-content p'), `::highlight(${name})`);
        out[name] = [s.backgroundColor, s.color];
      }
      return out;
    });
    const grey = /^rgb\((\d+), \1, \1\)$/;
    for (const name of ['honmani-learning', 'honmani-new', 'honmani-due', 'honmani-one-unknown', 'honmani-search-hit']) {
      expect(colors[name][0], name).toMatch(grey);
    }
    // Four different levels, all dark enough to tell from the page's white and light enough to read black on.
    const levels = ['honmani-learning', 'honmani-new', 'honmani-due', 'honmani-one-unknown'].map((n) => Number(/\d+/.exec(colors[n][0])[0]));
    expect(new Set(levels).size).toBe(4);
    for (const level of levels) expect(level).toBeLessThan(250);
    for (const level of levels) expect(level).toBeGreaterThan(120);
    expect(colors['honmani-lookup']).toEqual(['rgb(0, 0, 0)', 'rgb(255, 255, 255)']);
  });

  test('on: a tap on a word still looks it up', async ({ page }) => {
    await setUp(page, { honmaniEInk: '1' });
    await openBook(page);
    const point = await page.evaluate(() => {
      const node = document.querySelector('.book-content p').lastChild;
      const range = document.createRange();
      range.setStart(node, 2);
      range.setEnd(node, 3);
      const rect = range.getBoundingClientRect();
      return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
    });
    await page.mouse.click(point.x, point.y);
    expect((await page.evaluate(() => window.__messages)).map((m) => m.type)).toContain('lookup');
  });

  test('on: only the reader is changed; the library page keeps its colours', async ({ page }) => {
    await setUp(page, { honmaniEInk: '1', theme: 'black-theme' });
    await page.goto(`${server.url}/manage`);
    expect(await page.locator('#honmani-eink-style').count()).toBe(1);
    expect(await style(page, 'body', 'backgroundColor')).not.toBe('rgb(255, 255, 255)');
  });
});
