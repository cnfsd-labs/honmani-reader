// @ts-check
/**
 * End-to-end: the real ッツ Ebook Reader build, served like the app serves it, with Honmani's
 * document-start scripts injected. Imports an EPUB through ttu's own file input, opens it and
 * taps a word.
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
/** text.js comes first, as in DOCUMENT_START_SCRIPTS: the other scripts read what it defines. */
test.beforeEach(async ({ page }) => {
  await page.addInitScript({ path: path.join(ASSETS, 'text.js') });
});

test.beforeAll(async () => {
  server = await startTtuServer(TTU_DIR);
});
test.afterAll(() => server?.close());

/** Registers init scripts in the same order as ReaderWebView. `settings` simulate user choices. */
async function setUp(page, settings = {}) {
  await page.addInitScript((settings) => {
    for (const [key, value] of Object.entries(settings)) localStorage.setItem(key, value);
    window.__messages = [];
    window.honmani = { postMessage: (message) => window.__messages.push(JSON.parse(message)), onmessage: null };
  }, settings);
  await page.addInitScript({ path: path.join(ASSETS, 'ttu-defaults.js') });
  await page.addInitScript({ path: path.join(ASSETS, 'tap-bridge.js') });
  await page.addInitScript({ path: path.join(ASSETS, 'keep-position.js') });
  await page.addInitScript({ path: path.join(ASSETS, 'library-bridge.js') });
  await page.addInitScript({ path: path.join(ASSETS, 'help-text.js') });
  await page.addInitScript({ path: path.join(ASSETS, 'button-labels.js') });
}

const nekoEpub = {
  name: 'neko.epub',
  mimeType: 'application/epub+zip',
  buffer: buildEpub({
    title: '吾輩は猫である',
    paragraphs: ['吾輩は<ruby>猫<rt>ねこ</rt></ruby>である。名前はまだ無い。', 'どこで生れたかとんと見当がつかぬ。'],
  }),
};

async function importAndOpen(page, file = nekoEpub, title = '吾輩は猫である') {
  await page.goto(`${server.url}/manage`);
  await page.locator('input[type=file][accept*="epub"]').first().setInputFiles(file);
  await page.getByText(title).first().click();
  await page.waitForURL(/\/b\?id=\d+/);
  // ttu covers the page with a spinner until the book is laid out.
  await expect(page.locator('div.fixed.inset-0.text-7xl')).toHaveCount(0, { timeout: 3_000 });
}

async function tapChar(page, char) {
  const point = await page.evaluate((char) => {
    const walker = document.createTreeWalker(document.querySelector('.book-content'), NodeFilter.SHOW_TEXT);
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      if (node.parentElement.closest('rt')) continue;
      const index = node.data.indexOf(char);
      if (index < 0) continue;
      const range = document.createRange();
      range.setStart(node, index);
      range.setEnd(node, index + 1);
      const rect = Array.from(range.getClientRects()).find((r) => r.width > 0 && r.height > 0);
      return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
    }
    throw new Error(`"${char}" not found`);
  }, char);
  await page.mouse.click(point.x, point.y);
}

test('defaults to system fonts so the book shows without waiting for web fonts', async ({ page }) => {
  await setUp(page);
  await importAndOpen(page);

  expect(await page.evaluate(() => localStorage.getItem('fontFamilyGroupOne'))).toBe('serif');
  expect(await page.evaluate(() => localStorage.getItem('fontFamilyGroupTwo'))).toBe('sans-serif');
});

test('defaults to larger text and an instant auto bookmark, and keeps what the user set', async ({ page }) => {
  await setUp(page);
  await page.goto(`${server.url}/settings`);
  expect(await page.evaluate(() => localStorage.getItem('fontSize'))).toBe('28');
  expect(await page.evaluate(() => localStorage.getItem('autoBookmarkTime'))).toBe('0');
  expect(await page.evaluate(() => localStorage.getItem('hideSpoilerImage'))).toBe('0');
  await expect(page.locator('section:has(h2:has-text("Font Size")) input')).toHaveValue('28');

  await page.evaluate(() => localStorage.setItem('fontSize', '22'));
  await page.reload();
  expect(await page.evaluate(() => localStorage.getItem('fontSize'))).toBe('22');
});

for (const [stored, expected] of [['1', '0'], ['5', '5']]) {
  test(`a stored bookmark delay of ${stored} s becomes ${expected} s (1 s was Honmani's earlier default)`, async ({ page }) => {
    await setUp(page, { autoBookmarkTime: stored });
    await page.goto(`${server.url}/settings`);
    expect(await page.evaluate(() => localStorage.getItem('autoBookmarkTime'))).toBe(expected);
  });
}

// Honmani's own reader settings screen reads and writes ttu's settings (reader-settings.js).
test('the app reads and writes the reader settings, and "Like the app" follows the system', async ({ browser }) => {
  const context = await browser.newContext({ colorScheme: 'dark' });
  const page = await context.newPage();
  await setUp(page);
  await page.addInitScript({ path: path.join(ASSETS, 'reader-settings.js') });
  await page.goto(`${server.url}/manage`);
  const read = async () => {
    await page.evaluate(() => window.honmaniReaderSettings.read({ requestId: 1 }));
    return page.evaluate(() => window.__messages.filter((m) => m.type === 'library.settings').at(-1));
  };
  const first = await read();
  expect(first.requestId).toBe(1);
  expect(first.settings).toMatchObject({ theme: 'auto', fontSize: '28', fontFamilyGroupOne: 'serif', writingMode: null });

  await page.evaluate(() =>
    window.honmaniReaderSettings.write({ settings: { theme: 'ecru-theme', fontSize: '34', writingMode: 'horizontal-tb', unknownKey: 'x' } }),
  );
  await Promise.all([
    page.waitForEvent('load'),
    page.evaluate(() => setTimeout(() => window.honmaniReaderSettings.reload(), 0)),
  ]);
  expect((await read()).settings).toMatchObject({ theme: 'ecru-theme', fontSize: '34', writingMode: 'horizontal-tb' });
  expect(await page.evaluate(() => localStorage.getItem('unknownKey'))).toBeNull();
  // ttu itself uses them after the reload.
  await page.goto(`${server.url}/settings`);
  await expect(page.locator('section:has(h2:has-text("Font Size")) input')).toHaveValue('34');

  // Back to following the system: dark here, so the black theme, until the user picks one.
  await page.evaluate(() => window.honmaniReaderSettings.write({ settings: { theme: 'auto' } }));
  expect(await page.evaluate(() => [localStorage.getItem('theme'), localStorage.getItem('honmaniAutoTheme')])).toEqual(['black-theme', 'black-theme']);
  expect((await read()).settings.theme).toBe('auto');
  await context.close();
});

test('the reader bar keeps the table of contents; its Settings entry opens the app\'s screen', async ({ page }) => {
  await setUp(page, { ...userFont, writingMode: 'horizontal-tb' });
  await page.addInitScript({ path: path.join(ASSETS, 'reader-settings.js') });
  await importAndOpen(page);
  // ttu shows its bar after a tap on the strip at the top of the page.
  await page.mouse.click(page.viewportSize().width / 2, 10);
  await expect(page.locator('[title="Open Table of Contents"]')).toBeVisible();
  for (const title of ['Complete Book', 'Toggle Fullscreen', 'Go to Statistics', 'Go to Book Manager', 'Open Custom Point Actions']) {
    for (const element of await page.locator(`[title="${title}"]`).all()) await expect(element).toBeHidden();
  }
  // On a phone the bar's other entries sit in a menu.
  await page.locator('div.flex[class~="sm:hidden"] > *').first().click();
  for (const title of ['Go to Statistics', 'Go to Book Manager']) {
    for (const element of await page.locator(`[title="${title}"]`).all()) await expect(element).toBeHidden();
  }
  await expect(page.locator('[title="Jump to Position"]:visible')).toHaveCount(1);
  await page.locator('[title="Go to Reader Settings"]:visible').first().click();
  await expect.poll(() => page.evaluate(() => window.__messages.filter((m) => m.type === 'reader.settings').length)).toBe(1);
  expect(new URL(page.url()).pathname).toBe('/b');
});

test('a page turn saves the reading position right away, without a bookmark button', async ({ page }) => {
  await setUp(page, { ...userFont, writingMode: 'horizontal-tb', viewMode: 'paginated' });
  const paragraphs = Array.from({ length: 40 }, (_, i) => `第${i + 1}段落。吾輩は猫である。名前はまだ無い。どこで生れたかとんと見当がつかぬ。`);
  await importAndOpen(page, { ...nekoEpub, name: 'long.epub', buffer: buildEpub({ title: '長い本', paragraphs }) }, '長い本');
  const saved = () =>
    page.evaluate(
      () =>
        new Promise((resolve, reject) => {
          const request = indexedDB.open('books');
          request.onerror = () => reject(request.error);
          request.onsuccess = () => {
            const all = request.result.transaction('bookmark').objectStore('bookmark').getAll();
            all.onsuccess = () => resolve(all.result.map((bookmark) => bookmark.exploredCharCount ?? 0));
          };
        }),
    );
  await page.waitForTimeout(500);
  const before = await saved();

  await page.keyboard.press('ArrowRight');
  await expect.poll(async () => (await saved())[0] ?? 0, { timeout: 500 }).toBeGreaterThan(before[0] ?? 0);
  await expect(page.locator('[title="Create Bookmark"]')).toBeHidden();
  // No bookmark marker on the page either: with every page saved, it would be on every page.
  await page.waitForTimeout(300);
  expect(await page.locator('div.fixed.h-3.w-3.opacity-25').count()).toBe(0);
});

test('the settings have no auto bookmark entries, and are dark with a dark reader theme', async ({ browser }) => {
  const context = await browser.newContext({ colorScheme: 'dark' });
  const page = await context.newPage();
  await setUp(page);
  await page.goto(`${server.url}/settings`);
  await expect(page.locator('h2', { hasText: 'Font Size' })).toBeVisible();
  await expect(page.locator('h2', { hasText: 'Manual Bookmark' })).toHaveCount(1);
  await expect(page.locator('h2', { hasText: 'Auto Bookmark' })).toHaveCount(0);
  expect(await page.evaluate(() => getComputedStyle(document.body).backgroundColor)).toBe('rgb(18, 18, 18)');

  // Picking a light theme makes the page light at once; leaving the settings drops the class.
  await page.locator('button[title="light-theme"]').click();
  await expect.poll(() => page.evaluate(() => document.documentElement.classList.contains('honmani-dark-settings'))).toBe(false);
  await page.locator('button[title="black-theme"]').click();
  await expect.poll(() => page.evaluate(() => document.documentElement.classList.contains('honmani-dark-settings'))).toBe(true);
  await page.evaluate(() => history.pushState({}, '', '/manage'));
  expect(await page.evaluate(() => document.documentElement.classList.contains('honmani-dark-settings'))).toBe(false);
  await context.close();
});

test('selected options in the settings take the app\'s accent colour, also after a reload', async ({ page }) => {
  await setUp(page);
  await page.goto(`${server.url}/settings`);
  const selected = () =>
    page.evaluate(() => {
      const button = document.querySelector('button.bg-gray-700:not([style*="color"])');
      return [getComputedStyle(button).backgroundColor, getComputedStyle(button).color];
    });
  await page.evaluate(() => honmaniSetAccent('#6750A4', '#FFFFFF'));
  expect(await selected()).toEqual(['rgb(103, 80, 164)', 'rgb(255, 255, 255)']);
  // Anything but a colour is ignored.
  await page.evaluate(() => honmaniSetAccent('red; x', '#000000'));
  expect(await selected()).toEqual(['rgb(103, 80, 164)', 'rgb(255, 255, 255)']);

  await page.reload();
  expect(await selected()).toEqual(['rgb(103, 80, 164)', 'rgb(255, 255, 255)']);
});

test('help texts in the settings show on screen, also for icons far down the page', async ({ page }) => {
  await setUp(page);
  await page.goto(`${server.url}/settings`);
  const icon = page.locator('h2:has-text("Tap To Flip") svg');
  await icon.scrollIntoViewIfNeeded();
  await icon.tap();
  const help = page.locator('div[data-popover].absolute');
  await expect(help).toBeVisible();
  const onScreen = () =>
    help.evaluate((element) => {
      const rect = element.getBoundingClientRect();
      return rect.top >= 0 && rect.bottom <= window.innerHeight && rect.height > 0;
    });
  expect(await onScreen()).toBe(true);
  await page.mouse.wheel(0, -400);
  await page.waitForTimeout(200);
  expect(await onScreen()).toBe(true);
});

test('the reader\'s invisible buttons have names for screen readers', async ({ page }) => {
  await setUp(page, { viewMode: 'paginated', enableTapEdgeToFlip: '1' });
  await importAndOpen(page);

  await expect(page.getByRole('button', { name: 'Show the reader menu' })).toHaveCount(1);
  // ttu draws the page-turn strips at the edges in page mode, when "Tap edge to flip" is on.
  await expect(page.getByRole('button', { name: 'Page to the left' })).toHaveCount(1);
  await expect(page.getByRole('button', { name: 'Page to the right' })).toHaveCount(1);
  const unnamed = await page.locator('button:not([aria-label])').evaluateAll((buttons) =>
    buttons.filter((b) => !b.textContent.trim() && !b.querySelector('[aria-label], title, svg title')).length);
  expect(unnamed).toBe(0);
});

test('Aozora Bunko text files (Shift_JIS, with markup) import as clean text', async ({ page }) => {
  await setUp(page);
  await importAndOpen(
    page,
    {
      name: 'aozora-neko-excerpt-sjis.txt',
      mimeType: 'text/plain',
      buffer: fs.readFileSync(path.join(__dirname, '../fixtures/aozora-neko-excerpt-sjis.txt')),
    },
    'aozora-neko-excerpt-sjis',
  );

  const text = await page.evaluate(() => document.querySelector('.book-content').textContent);
  expect(text).toContain('吾輩は猫である。名前はまだ無い。');
  expect(text).toContain('たくさんおった兄弟が一疋も見えぬ。');
  // Readings, ruby marks, editor notes and the notation guide are gone.
  for (const markup of ['《', '》', '｜', '［＃', '【テキスト中に現れる記号について】']) {
    expect(text).not.toContain(markup);
  }
});

test('the reader starts in the black theme when the system is in dark mode', async ({ browser }) => {
  for (const [colorScheme, theme] of [['dark', 'black-theme'], ['light', 'light-theme']]) {
    const context = await browser.newContext({ colorScheme });
    const page = await context.newPage();
    await setUp(page);
    await page.goto(`${server.url}/manage`);
    expect(await page.evaluate(() => localStorage.getItem('theme'))).toBe(theme);
    await context.close();
  }
});

test('the reader theme follows the system until the user picks one', async ({ browser }) => {
  const context = await browser.newContext({ colorScheme: 'dark' });
  const page = await context.newPage();
  await setUp(page);
  const theme = () => page.evaluate(() => localStorage.getItem('theme'));
  await page.goto(`${server.url}/manage`);
  expect(await theme()).toBe('black-theme');

  await page.emulateMedia({ colorScheme: 'light' });
  await page.reload();
  expect(await theme()).toBe('light-theme');

  // The user picks a theme in ttu's settings; it stays when the system changes again.
  await page.evaluate(() => localStorage.setItem('theme', 'ecru-theme'));
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.reload();
  expect(await theme()).toBe('ecru-theme');
  await page.emulateMedia({ colorScheme: 'light' });
  await page.reload();
  expect(await theme()).toBe('ecru-theme');
  await context.close();
});

test('a theme the user picked is kept', async ({ browser }) => {
  const context = await browser.newContext({ colorScheme: 'dark' });
  const page = await context.newPage();
  await setUp(page, { theme: 'ecru-theme' });
  await page.goto(`${server.url}/manage`);
  expect(await page.evaluate(() => localStorage.getItem('theme'))).toBe('ecru-theme');
  await context.close();
});

// On a OnePlus 12 (Android WebView 153) the vertical reader's line length, which only had a
// max-height, resolved to 0 and pages were blank. Forcing the same fallback here reproduces it;
// a ttu patch (0003) gives the reader an explicit height.
for (const viewMode of ['paginated', 'continuous']) {
  test(`vertical ${viewMode} text is visible when the line-length fallback is 0`, async ({ page }) => {
    await setUp(page, { fontFamilyGroupOne: 'IPAGothic', viewMode });
    await importAndOpen(page);
    await page.addStyleTag({ content: 'div:has(> .book-content) { height: 0 !important; }' });
    await page.waitForTimeout(500);

    const heights = await page.evaluate(() => {
      const content = document.querySelector('.book-content');
      return {
        content: content.getBoundingClientRect().height,
        paragraph: content.querySelector('p').getBoundingClientRect().height,
      };
    });
    expect(heights.content).toBeGreaterThan(300);
    expect(heights.paragraph).toBeGreaterThan(100);
  });
}

// The font settings below stand in for a system font the user picked. Linux CI's default CJK
// fallback has broken vertical metrics; IPAGothic comes with `playwright install --with-deps`.
const userFont = { fontFamilyGroupOne: 'IPAGothic', fontFamilyGroupTwo: 'IPAGothic' };

for (const viewMode of ['paginated', 'continuous']) {
  for (const writingMode of ['vertical-rl', 'horizontal-tb']) {
    test(`tap lookup: ${writingMode}, ${viewMode}`, async ({ page }) => {
      await setUp(page, { ...userFont, writingMode, viewMode });
      await importAndOpen(page);
      expect(await page.evaluate(() => localStorage.getItem('fontFamilyGroupOne'))).toBe('IPAGothic');

      await tapChar(page, '猫');

      await expect.poll(() => page.evaluate(() => window.__messages)).toEqual([
        {
          type: 'lookup',
          text: '猫である。名前はまだ無い。',
          sentence: '吾輩は猫である。',
          sentenceOffset: 3,
          tap: 1,
          ruby: [{ start: 0, end: 1, reading: 'ねこ' }],
        },
      ]);

      // The app replies with the match length; the bridge marks it without touching ttu's DOM.
      const html = await page.evaluate(() => document.querySelector('.book-content').innerHTML);
      await page.evaluate(() => window.honmani.onmessage({ data: JSON.stringify({ type: 'highlight', length: 4 }) }));
      const marked = await page.evaluate(() =>
        Array.from(CSS.highlights.get('honmani-lookup') ?? []).map((range) => range.toString()).join(''),
      );
      expect(marked).toBe('猫である');
      expect(await page.evaluate(() => document.querySelector('.book-content').innerHTML)).toBe(html);
    });
  }
}

// Rotation diagnostics: where the reader was before a resize and where it ended up.
test('diagnostics log the reading position before and after a resize', async ({ page }) => {
  const logs = [];
  page.on('console', (message) => {
    if (message.text().startsWith('honmani position')) logs.push(message.text());
  });
  await page.setViewportSize({ width: 360, height: 736 });
  await setUp(page, { ...userFont, viewMode: 'paginated' });
  await page.addInitScript({ path: path.join(ASSETS, 'viewport-log.js') });
  const paragraphs = Array.from({ length: 40 }, (_, i) => `第${i + 1}段落。吾輩は猫である。名前はまだ無い。どこで生れたかとんと見当がつかぬ。`);
  await importAndOpen(page, { ...nekoEpub, name: 'long.epub', buffer: buildEpub({ title: '長い本', paragraphs }) }, '長い本');
  for (let i = 0; i < 3; i++) await page.keyboard.press('ArrowLeft');
  await page.waitForTimeout(1_000);

  await page.setViewportSize({ width: 736, height: 360 });

  await expect.poll(() => logs, { timeout: 5_000 }).toHaveLength(2);
  expect(logs[0]).toMatch(/^honmani position before resize "第\d+段落/);
  expect(logs[1]).toMatch(/^honmani position after resize "/);
});

// The app covers the reader while a book opens; book-shown.js says when it may uncover it.
test('opening a book reports it shown once its text is on screen, after the spinner', async ({ page }) => {
  await setUp(page, userFont);
  await page.addInitScript({ path: path.join(ASSETS, 'book-shown.js') });
  await page.addInitScript(() => {
    // Records, at the moment of the message, whether ttu's spinner was still up.
    const post = window.honmani.postMessage;
    window.honmani.postMessage = (message) => {
      const parsed = JSON.parse(message);
      if (parsed.type === 'bookShown') parsed.spinner = !!document.querySelector('div.fixed.inset-0.text-7xl');
      post(JSON.stringify(parsed));
    };
  });
  const shown = () => page.evaluate(() => window.__messages.filter((message) => message.type === 'bookShown'));
  await page.goto(`${server.url}/manage`);
  await page.locator('input[type=file][accept*="epub"]').first().setInputFiles(nekoEpub);
  await page.waitForTimeout(500);
  expect(await shown()).toEqual([]);

  await page.getByText('吾輩は猫である').first().click();
  await page.waitForURL(/\/b\?id=\d+/);
  const id = Number(new URL(page.url()).searchParams.get('id'));
  await expect.poll(shown, { timeout: 5_000 }).toEqual([{ type: 'bookShown', id, spinner: false }]);
  await page.waitForTimeout(500);
  expect(await shown()).toHaveLength(1);

  // Back to the list and in again: reported again.
  await page.goBack();
  await page.waitForURL(/\/manage/);
  await page.getByText('吾輩は猫である').first().click();
  await expect.poll(async () => (await shown()).length, { timeout: 5_000 }).toBe(2);
});

// ttu loads a book before it changes the address, through a pushState of its own that
// book-shown.js cannot see: the checks started by the tap must still be running then.
test('a book that opens a while after the tap in the library is still reported shown', async ({ page }) => {
  await page.addInitScript(() => {
    window.__messages = [];
    window.honmani = { postMessage: (message) => window.__messages.push(JSON.parse(message)), onmessage: null };
    window.__routerPushState = history.pushState.bind(history);
  });
  await page.addInitScript({ path: path.join(ASSETS, 'book-shown.js') });
  await page.goto(`${server.url}/manage`);
  await page.evaluate(() => {
    const button = document.createElement('button');
    button.id = 'late-open';
    button.textContent = 'open';
    button.style.cssText = 'position: fixed; top: 0; left: 0; z-index: 99999';
    button.addEventListener('click', () =>
      setTimeout(() => {
        document.body.innerHTML = '<div class="book-content"><p>吾輩は猫である。</p></div>';
        window.__routerPushState({}, '', '/b?id=7');
      }, 500),
    );
    document.body.append(button);
  });

  await page.locator('#late-open').click();

  const shown = () => page.evaluate(() => window.__messages.filter((message) => message.type === 'bookShown'));
  await expect.poll(shown, { timeout: 3_000 }).toEqual([{ type: 'bookShown', id: 7 }]);
});

// ttu's stylesheet paints the page light until its script applies the theme; opening a book in
// the dark flashed white. ttu-defaults.js sets the theme colours before the first frame.
test('a book page has the dark theme background and no loading circle before ttu has started', async ({ page }) => {
  await page.emulateMedia({ colorScheme: 'dark' });
  await setUp(page, userFont);
  await page.addInitScript(() => {
    document.addEventListener('readystatechange', () => {
      if (document.readyState === 'interactive' && !window.__firstBackground) {
        window.__firstBackground = getComputedStyle(document.body).backgroundColor;
        const spinner = document.querySelector('div.fixed.inset-0.text-7xl > svg');
        window.__spinnerVisibility = spinner && getComputedStyle(spinner).visibility;
      }
    });
  });
  await importAndOpen(page);
  await page.goto(page.url());
  expect(await page.evaluate(() => window.__firstBackground)).toBe('rgb(0, 0, 0)');
  // ttu's loading circle is hidden; the app shows its own spinner on the library's cover.
  expect(await page.evaluate(() => window.__spinnerVisibility)).toBe('hidden');
});

// ttu's progress stops where the last page starts; book-end.js reports when the end is on screen.
test('reaching the last page reports the book as finished, once', async ({ page }) => {
  await setUp(page, { ...userFont, writingMode: 'horizontal-tb', viewMode: 'paginated' });
  await page.addInitScript({ path: path.join(ASSETS, 'book-end.js') });
  const paragraphs = Array.from({ length: 40 }, (_, i) => `第${i + 1}段落。吾輩は猫である。名前はまだ無い。どこで生れたかとんと見当がつかぬ。`);
  await importAndOpen(page, { ...nekoEpub, name: 'long.epub', buffer: buildEpub({ title: '長い本', paragraphs }) }, '長い本');
  const finished = () => page.evaluate(() => window.__messages.filter((message) => message.type === 'library.finished'));
  await page.waitForTimeout(2_000);
  expect(await finished()).toEqual([]);

  const id = Number(new URL(page.url()).searchParams.get('id'));
  for (let i = 0; i < 40; i++) {
    await page.keyboard.press('ArrowRight');
    await page.waitForTimeout(100);
  }
  await expect.poll(finished, { timeout: 5_000 }).toEqual([{ type: 'library.finished', id }]);
  await page.keyboard.press('ArrowLeft');
  await page.keyboard.press('ArrowRight');
  await page.waitForTimeout(1_000);
  expect(await finished()).toHaveLength(1);
});

// Books were marked finished on their first pages; ttu's character count must agree as well.
test('the end drawn on screen on the first page of a long book is not reported', async ({ page }) => {
  await setUp(page, { ...userFont, writingMode: 'horizontal-tb', viewMode: 'paginated' });
  await page.addInitScript({ path: path.join(ASSETS, 'book-end.js') });
  const logs = [];
  page.on('console', (message) => {
    if (message.text().startsWith('honmani book end')) logs.push(message.text());
  });
  const paragraphs = Array.from({ length: 200 }, (_, i) => `第${i + 1}段落。吾輩は猫である。名前はまだ無い。どこで生れたかとんと見当がつかぬ。何でも薄暗い所で泣いていた。`);
  paragraphs.push('おわり');
  await importAndOpen(page, { ...nekoEpub, name: 'big.epub', buffer: buildEpub({ title: '大きい本', paragraphs }) }, '大きい本');
  await page.waitForTimeout(1_000);
  // Draws the book's last characters on the first page, as on the device.
  await page.evaluate(() => {
    const walker = document.createTreeWalker(document.querySelector('.book-content'), NodeFilter.SHOW_TEXT);
    let last = null;
    for (let node = walker.nextNode(); node; node = walker.nextNode()) if (node.data.includes('おわり')) last = node;
    Object.assign(last.parentElement.style, { position: 'fixed', top: '40px', left: '40px' });
  });
  await page.keyboard.press('ArrowRight');
  await page.keyboard.press('ArrowLeft');
  await expect.poll(() => logs.length, { timeout: 5_000 }).toBeGreaterThan(0);
  expect(logs[0]).toMatch(/^honmani book end on screen but \d+ of \d+ characters left$/);
  expect(await page.evaluate(() => window.__messages.filter((message) => message.type === 'library.finished'))).toEqual([]);
});

// The paginated reader holds only the current chapter, so its last character is on screen at
// every chapter end: a short last chapter must not make the end of the one before count.
test('the end of a chapter before a short last one is not reported as the end of the book', async ({ page }) => {
  await setUp(page, { ...userFont, writingMode: 'horizontal-tb', viewMode: 'paginated' });
  await page.addInitScript({ path: path.join(ASSETS, 'book-end.js') });
  const chapter = Array.from({ length: 40 }, (_, i) => `第${i + 1}段落。吾輩は猫である。名前はまだ無い。どこで生れたかとんと見当がつかぬ。`);
  const afterword = Array.from({ length: 10 }, (_, i) => `あとがきの${i + 1}。読んでくださってありがとうございました。`);
  const chapters = [chapter, afterword];
  await importAndOpen(page, { ...nekoEpub, name: 'chapters.epub', buffer: buildEpub({ title: '章の本', chapters }) }, '章の本');
  const finished = () => page.evaluate(() => window.__messages.filter((message) => message.type === 'library.finished'));
  const onScreen = (text) => page.evaluate((text) => {
    const walker = document.createTreeWalker(document.querySelector('.book-content'), NodeFilter.SHOW_TEXT);
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      if (!node.data.includes(text)) continue;
      const range = document.createRange();
      range.selectNode(node);
      return Array.from(range.getClientRects()).some((r) => r.width > 0 && r.right > 0 && r.left < innerWidth);
    }
    return false;
  }, text);

  for (let i = 0; i < 40 && !(await onScreen('第40段落')); i++) {
    await page.keyboard.press('ArrowRight');
    await page.waitForTimeout(100);
  }
  expect(await onScreen('第40段落')).toBe(true);
  await page.waitForTimeout(1_000);
  expect(await finished()).toEqual([]);

  const id = Number(new URL(page.url()).searchParams.get('id'));
  for (let i = 0; i < 10 && !(await onScreen('あとがきの10')); i++) {
    await page.keyboard.press('ArrowRight');
    await page.waitForTimeout(100);
  }
  await expect.poll(finished, { timeout: 5_000 }).toEqual([{ type: 'library.finished', id }]);
});

// ttu sends its counts some time after a chapter is drawn; a title page's end is on screen at once.
test('a paginated chapter end is not reported before ttu has sent its counts', async ({ page }) => {
  await setUp(page, { ...userFont, writingMode: 'horizontal-tb', viewMode: 'paginated' });
  // Keeps ttu's page change events from book-end.js only.
  await page.addInitScript(() => {
    const add = document.addEventListener;
    window.__dropPageChange = true;
    document.addEventListener = function (type, ...rest) {
      if (window.__dropPageChange && type === 'ttsu:page.change') return;
      add.call(this, type, ...rest);
    };
  });
  await page.addInitScript({ path: path.join(ASSETS, 'book-end.js') });
  await page.addInitScript(() => {
    window.__dropPageChange = false;
  });
  const story = Array.from({ length: 200 }, (_, i) => `第${i + 1}段落。吾輩は猫である。名前はまだ無い。`);
  await importAndOpen(page, { ...nekoEpub, name: 'titled.epub', buffer: buildEpub({ title: '扉の本', chapters: [['扉の本'], story] }) }, '扉の本');
  await page.keyboard.press('ArrowRight');
  await page.keyboard.press('ArrowLeft');
  await page.waitForTimeout(2_000);
  expect(await page.evaluate(() => window.__messages.filter((message) => message.type === 'library.finished'))).toEqual([]);
});

// ttu restores its position after a resize by paragraph; with paragraphs longer than a screen
// (as in most novels) a rotation lost the place by pages. keep-position.js brings back the first
// character that was on screen.
test.describe('rotation keeps the reading position', () => {
  test.use({ isMobile: true, hasTouch: true, deviceScaleFactor: 4, viewport: { width: 360, height: 736 } });

  const sentence = '吾輩は猫である。名前はまだ無い。どこで生れたかとんと見当がつかぬ。何でも薄暗いじめじめした所でニャーニャー泣いていた事だけは記憶している。';
  const paragraphs = Array.from({ length: 12 }, (_, i) =>
    `【${i + 1}】` + Array.from({ length: 12 }, (_, j) => `（${j + 1}）${sentence}`).join(''));
  const longEpub = { ...nekoEpub, name: 'long.epub', buffer: buildEpub({ title: '長い本', paragraphs }) };

  /** [paragraph index, character index in its text without furigana] of the first character on screen. */
  const firstCharOnScreen = () => {
    const onScreen = (r) => r.width > 0 && r.right > 0 && r.left < innerWidth && r.bottom > 0 && r.top < innerHeight;
    const range = document.createRange();
    const ps = document.querySelectorAll('.book-content p');
    for (let p = 0; p < ps.length; p++) {
      const walker = document.createTreeWalker(ps[p], NodeFilter.SHOW_TEXT);
      let before = 0;
      for (let node = walker.nextNode(); node; node = walker.nextNode()) {
        for (let i = 0; i < node.data.length; i++) {
          range.setStart(node, i);
          range.setEnd(node, i + 1);
          if (Array.from(range.getClientRects()).some(onScreen)) return [p, before + i];
        }
        before += node.data.length;
      }
    }
    return null;
  };
  const charOnScreen = ([p, index]) => {
    const walker = document.createTreeWalker(document.querySelectorAll('.book-content p')[p], NodeFilter.SHOW_TEXT);
    let before = 0;
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      if (index < before + node.data.length) {
        const range = document.createRange();
        range.setStart(node, index - before);
        range.setEnd(node, index - before + 1);
        return Array.from(range.getClientRects()).some(
          (r) => r.width > 0 && r.right > 0 && r.left < innerWidth && r.bottom > 0 && r.top < innerHeight,
        );
      }
      before += node.data.length;
    }
    return false;
  };

  for (const [writingMode, viewMode] of [
    ['vertical-rl', 'paginated'],
    ['horizontal-tb', 'paginated'],
    ['horizontal-tb', 'continuous'],
  ]) {
    test(`${writingMode}, ${viewMode}`, async ({ page }) => {
      await setUp(page, { ...userFont, writingMode, viewMode });
      await importAndOpen(page, longEpub, '長い本');
      for (let i = 0; i < 9; i++) {
        if (viewMode === 'continuous') await page.mouse.wheel(0, 500);
        else await page.keyboard.press(writingMode === 'vertical-rl' ? 'ArrowLeft' : 'ArrowRight');
        await page.waitForTimeout(150);
      }
      await page.waitForTimeout(800);
      const reading = await page.evaluate(firstCharOnScreen);
      expect(reading[0]).toBeGreaterThan(0);

      for (const [width, height] of [[736, 360], [360, 736], [736, 360], [360, 736]]) {
        await page.setViewportSize({ width, height });
        await expect.poll(() => page.evaluate(charOnScreen, reading), { timeout: 3_000 }).toBe(true);
      }
    });
  }
});

// keep-position.js, viewport-log.js and book-end.js look at the text once the scroll settles. In
// continuous mode they must not walk from the start of the book, and must still find the same
// character as the walk from the start (the reference below is the earlier implementation).
test.describe('settle-time walks in continuous mode give the same answers as walking the whole book', () => {
  test.use({ isMobile: true, hasTouch: true, deviceScaleFactor: 4, viewport: { width: 360, height: 736 } });

  const sentence = '吾輩は<ruby>猫<rt>ねこ</rt></ruby>である。名前はまだ<ruby>無<rt>な</rt></ruby>い。どこで<ruby>生<rt>うま</rt></ruby>れたかとんと<ruby>見当<rt>けんとう</rt></ruby>がつかぬ。何でも薄暗いじめじめした所でニャーニャー泣いていた事だけは記憶している。';
  const paragraphs = Array.from({ length: 60 }, (_, i) =>
    `<p>【${i + 1}】${Array.from({ length: 10 }, (_, j) => `<span>（${j + 1}）</span>${sentence}`).join('\n')}</p>`);
  // Blank text and furigana come last, so the last node in the book is not the last character.
  paragraphs.push('<p>おわり<ruby>猫<rt>ねこ</rt></ruby> </p>\n');
  const longEpub = {
    ...nekoEpub,
    name: 'settle.epub',
    buffer: buildEpub({ title: '走査の本', paragraphs, raw: true }),
  };

  /** Scrolls to `fraction` of the book (plus `extra` pixels, so the lines don't sit on the edge). */
  const scrollTo = ([fraction, extra]) => {
    const root = document.querySelector('.book-content');
    const vertical = getComputedStyle(root).writingMode.startsWith('vertical');
    let scroller = document.scrollingElement;
    for (let element = root; element; element = element.parentElement) {
      const style = getComputedStyle(element);
      if ((/auto|scroll/.test(style.overflowY) && element.scrollHeight > element.clientHeight) ||
        (/auto|scroll/.test(style.overflowX) && element.scrollWidth > element.clientWidth)) {
        scroller = element;
        break;
      }
    }
    if (vertical) scroller.scrollLeft = -(fraction * (scroller.scrollWidth - scroller.clientWidth) + extra);
    else scroller.scrollTop = fraction * (scroller.scrollHeight - scroller.clientHeight) + extra;
    return new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve())));
  };

  /** The earlier implementation, kept as the reference. */
  const reference = () => {
    const onScreen = (r) => r.width > 0 && r.right > 0 && r.left < innerWidth && r.bottom > 0 && r.top < innerHeight;
    const root = document.querySelector('.book-content');
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
      acceptNode: (node) => (node.parentElement && node.parentElement.closest('rt, rp') ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT),
    });
    const range = document.createRange();
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      range.selectNodeContents(node);
      if (!Array.from(range.getClientRects()).some(onScreen)) continue;
      for (let offset = 0; offset < node.data.length; offset++) {
        if (!node.data[offset].trim()) continue;
        range.setStart(node, offset);
        range.setEnd(node, offset + 1);
        if (Array.from(range.getClientRects()).some(onScreen)) return { node, offset };
      }
    }
    return null;
  };

  const compare = (referenceSource) => {
    const reference = new Function(`return (${referenceSource})`)();
    const root = document.querySelector('.book-content');
    const T = window.honmaniText;
    const expected = reference();
    const near = T.firstCharNearStart(root, 'rt, rp');
    const used = T.firstCharOnScreen(root, 'rt, rp', true);
    const same = (a) => !!a && !!expected && a.node === expected.node && a.offset === expected.offset;
    return {
      expected: expected && expected.node.data.slice(expected.offset, expected.offset + 8),
      nearSame: same(near),
      usedSame: same(used),
      walkSame: same(T.firstCharWalk(root, 'rt, rp')),
    };
  };

  for (const writingMode of ['horizontal-tb', 'vertical-rl']) {
    test(`first character on screen, ${writingMode}`, async ({ page }) => {
      await setUp(page, { ...userFont, writingMode, viewMode: 'continuous' });
      await importAndOpen(page, longEpub, '走査の本');
      const seen = new Set();
      for (const position of [[0, 0], [0, 7], [0.03, 0], [0.25, 13], [0.5, 0], [0.5, 29], [0.77, 3], [0.97, 41], [1, 0]]) {
        await page.evaluate(scrollTo, position);
        const result = await page.evaluate(compare, reference.toString());
        expect(result, `at ${position}`).toMatchObject({ nearSame: true, usedSame: true, walkSame: true });
        seen.add(result.expected);
      }
      // The positions really were different places.
      expect(seen.size).toBeGreaterThan(5);
    });
  }

  for (const writingMode of ['horizontal-tb', 'vertical-rl']) {
    test(`the book end is reported at the end only, ${writingMode}`, async ({ page }) => {
      await setUp(page, { ...userFont, writingMode, viewMode: 'continuous' });
      await page.addInitScript({ path: path.join(ASSETS, 'book-end.js') });
      await importAndOpen(page, longEpub, '走査の本');
      const finished = () => page.evaluate(() => window.__messages.filter((message) => message.type === 'library.finished'));
      for (const position of [[0, 0], [0.4, 0], [0.9, 0]]) {
        await page.evaluate(scrollTo, position);
        await page.mouse.wheel(0, 1);
        await page.waitForTimeout(1_500);
        expect(await finished(), `at ${position}`).toEqual([]);
      }
      const id = Number(new URL(page.url()).searchParams.get('id'));
      await page.evaluate(scrollTo, [1, 0]);
      await page.mouse.wheel(0, 1);
      await expect.poll(finished, { timeout: 5_000 }).toEqual([{ type: 'library.finished', id }]);
    });
  }
});

test('Aozora books keep their indentation, bottom alignment and upright digits in ttu', async ({ page }) => {
  // Markup and stylesheet as AozoraEpub writes them.
  const css = 'span.upright { -webkit-text-combine: horizontal; text-combine-upright: all; }\np { margin: 0; }\n';
  const book = buildEpub({
    title: '字下げ',
    raw: true,
    css,
    paragraphs: [
      '<p style="padding-inline-start: 2em">二字下げ</p>',
      '<p style="padding-inline-start: 3em; text-indent: -2em">ぶら下げ</p>',
      '<p style="text-align: end; padding-inline-end: 2em">明治<span class="upright">25</span>年</p>',
      '<p>普通</p>',
    ],
  });
  await setUp(page);
  await importAndOpen(page, { name: 'indent.epub', mimeType: 'application/epub+zip', buffer: book }, '字下げ');

  const layout = await page.evaluate(() =>
    [...document.querySelectorAll('.book-content p')].map((p) => {
      const range = document.createRange();
      range.setStart(p.firstChild, 0);
      range.setEnd(p.firstChild, 1);
      return { top: range.getBoundingClientRect().top, em: parseFloat(getComputedStyle(p).fontSize), bottom: p.getBoundingClientRect().bottom };
    }),
  );
  const [two, hanging, raised, plain] = layout;
  expect(two.top - plain.top).toBeCloseTo(2 * plain.em, 0);
  expect(hanging.top - plain.top).toBeCloseTo(plain.em, 0);
  const upright = await page.locator('span.upright').evaluate((span) => getComputedStyle(span).textCombineUpright);
  expect(upright).toBe('all');
  // The raised line ends two characters above the bottom of the line.
  const yearBottom = await page.locator('span.upright').evaluate((span) => {
    const range = document.createRange();
    range.setStartAfter(span);
    range.setEnd(span.parentElement.lastChild, 1);
    return range.getBoundingClientRect().bottom;
  });
  expect(raised.bottom - yearBottom).toBeCloseTo(2 * raised.em, 0);
});
