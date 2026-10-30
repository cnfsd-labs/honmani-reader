// @ts-check
const fs = require('node:fs');
const path = require('node:path');
const { test, expect } = require('@playwright/test');

const SETTINGS = path.join(__dirname, '../../app/src/main/assets/honmani/reader-settings.js');
const FIXTURES = path.join(__dirname, '../fixtures');
// Served over http, not file://: a file:// page can reload in a new renderer before the old one
// handed over its localStorage writes, and then starts without them.
const ORIGIN = 'http://honmani.test';
const FIXTURE = `${ORIGIN}/book.html`;

test.beforeEach(async ({ page }) => {
  await page.route(`${ORIGIN}/**`, (route) => {
    const file = path.join(FIXTURES, new URL(route.request().url()).pathname);
    if (!file.startsWith(FIXTURES) || !fs.existsSync(file) || !fs.statSync(file).isFile()) return route.fulfill({ status: 404 });
    return route.fulfill({ path: file });
  });
  await page.addInitScript({ path: SETTINGS });
  await page.goto(FIXTURE);
  await page.evaluate(() => window.localStorage.clear());
});

const userFonts = (page) => page.evaluate(() => JSON.parse(window.localStorage.getItem('userfonts') || 'null'));

test('downloaded fonts are listed as ttu user fonts served by the app', async ({ page }) => {
  await page.evaluate(() =>
    window.honmaniReaderSettings.fonts({ fonts: [{ name: 'Klee One', fileName: 'KleeOne-Regular.ttf' }] }),
  );

  expect(await userFonts(page)).toEqual([
    { name: 'Klee One', path: '/honmani-fonts/KleeOne-Regular.ttf', fileName: 'KleeOne-Regular.ttf' },
  ]);
});

test("fonts the reader added in ttu are kept; Honmani's list is replaced", async ({ page }) => {
  await page.evaluate(() => {
    window.localStorage.setItem(
      'userfonts',
      JSON.stringify([
        { name: 'My Font', path: '/userfonts/my.woff2', fileName: 'my.woff2' },
        { name: 'Klee One', path: '/honmani-fonts/KleeOne-Regular.ttf', fileName: 'KleeOne-Regular.ttf' },
      ]),
    );
    window.honmaniReaderSettings.fonts({ fonts: [{ name: 'BIZ UDMincho', fileName: 'BIZUDMincho-Regular.ttf' }] });
  });

  expect(await userFonts(page)).toEqual([
    { name: 'My Font', path: '/userfonts/my.woff2', fileName: 'my.woff2' },
    { name: 'BIZ UDMincho', path: '/honmani-fonts/BIZUDMincho-Regular.ttf', fileName: 'BIZUDMincho-Regular.ttf' },
  ]);
});

test('file names that are not plain font files are refused; a broken list is replaced', async ({ page }) => {
  await page.evaluate(() => {
    window.localStorage.setItem('userfonts', '{not json');
    window.honmaniReaderSettings.fonts({
      fonts: [
        { name: 'Bad', fileName: '../books/x.ttf' },
        { name: 'Script', fileName: "a.ttf');}body{display:none" },
        { name: 'Zen Maru Gothic', fileName: 'ZenMaruGothic-Regular.ttf' },
      ],
    });
  });

  expect((await userFonts(page)).map((font) => font.name)).toEqual(['Zen Maru Gothic']);
});

/** Does what ttu's layout does: declares the user fonts in <style id="ttu-userfonts">. */
/** Declares the user fonts as ttu does, once per page (also a page that replaced the one before). */
async function declareUserFonts(page) {
  await page.evaluate(() => {
    if (document.getElementById('ttu-userfonts')) return;
    const fonts = JSON.parse(window.localStorage.getItem('userfonts') || '[]');
    const style = document.createElement('style');
    style.id = 'ttu-userfonts';
    style.textContent = fonts
      .map((font) => `@font-face{font-family: '${font.name}';src: url('${font.path}') format('truetype')}`)
      .join(' ');
    document.head.append(style);
  });
}

const faceStatuses = (page) =>
  page.evaluate(() => Array.from(document.fonts).map((face) => `${face.family.replace(/"/g, '')}: ${face.status}`));

test("ttu's face of the chosen Honmani font starts loading once declared, so ttu needn't wait for its timeout", async ({
  page,
}) => {
  await page.evaluate(() => {
    window.honmaniReaderSettings.fonts({ fonts: [{ name: 'Klee One', fileName: 'KleeOne-Regular.ttf' }] });
    window.localStorage.setItem('fontFamilyGroupOne', 'Klee One');
  });
  await page.reload();

  // One face, ttu's own, requested at once (the file isn't served here, so it ends in an error).
  await expect
    .poll(async () => {
      await declareUserFonts(page);
      return faceStatuses(page);
    }).toEqual([expect.stringMatching(/^Klee One: (loading|loaded|error)$/)]);
});

test('fonts not downloaded by Honmani are left to ttu', async ({ page }) => {
  await page.evaluate(() => {
    window.localStorage.setItem('userfonts', JSON.stringify([{ name: 'Mine', path: '/userfonts/m.ttf', fileName: 'm.ttf' }]));
    window.localStorage.setItem('fontFamilyGroupOne', 'Mine');
  });
  await page.reload();
  // Declared until the face is listed: right after a reload, the old page can still take it.
  await expect
    .poll(async () => {
      await declareUserFonts(page);
      return faceStatuses(page);
    })
    .toEqual(['Mine: unloaded']);
  await page.waitForTimeout(200);

  expect(await faceStatuses(page)).toEqual(['Mine: unloaded']);
});

test('the font preload stops watching the page when ttu never declares the fonts', async ({ page }) => {
  await page.evaluate(() => {
    window.honmaniReaderSettings.fonts({ fonts: [{ name: 'Klee One', fileName: 'KleeOne-Regular.ttf' }] });
    window.localStorage.setItem('fontFamilyGroupOne', 'Klee One');
  });
  // Runs the script again in the page, with the timers and the observers' disconnect watched.
  const timers = await page.evaluate((source) => {
    const timers = [];
    const disconnected = [];
    const realSetTimeout = window.setTimeout;
    window.setTimeout = (callback, ms, ...rest) => {
      timers.push({ callback, ms });
      return realSetTimeout(() => {}, 0);
    };
    const original = MutationObserver.prototype.disconnect;
    MutationObserver.prototype.disconnect = function () {
      disconnected.push(this);
      return original.call(this);
    };
    delete window.honmaniReaderSettings;
    new Function(source)();
    window.setTimeout = realSetTimeout;
    const give = timers.find((timer) => timer.ms >= 10000);
    const before = disconnected.length;
    give.callback();
    MutationObserver.prototype.disconnect = original;
    return { ms: give.ms, disconnectedByTimeout: disconnected.length - before };
  }, fs.readFileSync(SETTINGS, 'utf8'));

  expect(timers.ms).toBeLessThanOrEqual(30000);
  expect(timers.disconnectedByTimeout).toBe(1);
});
