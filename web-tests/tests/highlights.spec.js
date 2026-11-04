// @ts-check
const path = require('node:path');
const { test, expect } = require('@playwright/test');

const ASSETS = path.join(__dirname, '../../app/src/main/assets/honmani');
const GIF = 'data:image/gif;base64,R0lGODlhAQABAAAAACH5BAEKAAEALAAAAAABAAEAAAICTAEAOw==';

/**
 * Opens a ttu-like book page (/b?id=1) with `body` inside .book-content. Section "a" holds the
 * first 13 counted characters of SECTIONS below, "b" the rest, unless a test says otherwise.
 */
async function openBook(page, body, { storage = {}, css = '', bridge = false } = {}) {
  await page.addInitScript((storage) => {
    for (const [key, value] of Object.entries(storage)) localStorage.setItem(key, value);
    window.__messages = [];
    window.honmani = {
      postMessage: (message) => window.__messages.push(JSON.parse(message)),
      addEventListener: (type, listener) => (window.__listener = listener),
    };
  }, storage);
  await page.addInitScript({ path: path.join(ASSETS, 'text.js') });
  await page.addInitScript({ path: path.join(ASSETS, 'highlights.js') });
  if (bridge) await page.addInitScript({ path: path.join(ASSETS, 'tap-bridge.js') });
  await page.addInitScript({ path: path.join(ASSETS, 'reader-style.js') });
  await page.route('**/*', (route) =>
    route.fulfill({
      contentType: 'text/html',
      body: `<!doctype html><meta charset="utf-8"><style>body { font-family: IPAGothic; font-size: 20px; ${css} }</style><body><div class="book-content">${body}</div>`,
    }),
  );
  await page.goto('http://localhost:9/b?id=1');
  await expect.poll(() => page.evaluate(() => window.__messages.some((m) => m.type === 'highlights.request'))).toBe(true);
}

/** The app's answer. `sections` is [[id, start]...]. */
function send(page, sections, items, bookId = 1) {
  return page.evaluate(
    ([sections, items, bookId]) => window.__listener({ data: JSON.stringify({ type: 'highlights', bookId, sections, total: 99, items }) }),
    [sections, items, bookId],
  );
}

/** The texts of the ranges drawn in one colour. */
const drawn = (page, color) =>
  page.evaluate((color) => [...(CSS.highlights.get(`honmani-hl-${color}`) ?? [])].map((range) => range.toString()), color);

/** Selects from (text node containing `from`, at its first char) to the end of `to` and measures it. */
function measureText(page, from, to) {
  return page.evaluate(
    ([from, to]) => {
      const find = (text) => {
        const walker = document.createTreeWalker(document.querySelector('.book-content'), NodeFilter.SHOW_TEXT);
        for (let node = walker.nextNode(); node; node = walker.nextNode()) {
          if (node.parentElement.closest('rt')) continue;
          const at = node.data.indexOf(text);
          if (at >= 0) return { node, at };
        }
        throw new Error(`no ${text}`);
      };
      const a = find(from);
      const b = find(to);
      const range = document.createRange();
      range.setStart(a.node, a.at);
      range.setEnd(b.node, b.at + to.length);
      return window.honmaniHighlights.measure(range);
    },
    [from, to],
  );
}

const TWO_SECTIONS =
  '<div id="ttu-a"><p>吾輩は猫である。</p><p>名前はまだ無い。</p></div><div id="ttu-b"><p>どこで生れたか。</p></div>';
// a: 吾輩は猫である (7) + 名前はまだ無い (7) = 14; b starts at 14.
const A_B = [['ttu-a', 0], ['ttu-b', 14]];

test('items are drawn in their colour, across paragraphs and across sections', async ({ page }) => {
  await openBook(page, TWO_SECTIONS);
  await send(page, A_B, [
    { id: 1, start: 2, end: 5, color: 0 },
    { id: 2, start: 5, end: 10, color: 1 },
    { id: 3, start: 12, end: 17, color: 3 },
  ]);
  await expect.poll(() => drawn(page, 0)).toEqual(['は猫で']);
  // Colour 1 crosses the paragraph end; the full stop lies inside the range but is not counted.
  expect(await drawn(page, 1)).toEqual(['ある。名前は']);
  // Colour 3 crosses from section a into b: drawn as two pieces, one per section.
  expect(await drawn(page, 3)).toEqual(['無い。', 'どこで']);
  expect(await drawn(page, 2)).toEqual([]);
});

test('a mark takes along the closing punctuation ttu does not count, but not a comma', async ({ page }) => {
  await openBook(page, '<div id="ttu-a"><p>寒い冬が来た。」次に、雪だ。</p></div>');
  await send(page, [['ttu-a', 0]], [{ id: 1, start: 0, end: 6, color: 0 }, { id: 2, start: 6, end: 8, color: 1 }]);
  await expect.poll(() => drawn(page, 0)).toEqual(['寒い冬が来た。」']);
  expect(await drawn(page, 1)).toEqual(['次に']);
});

test('an answer for another book is ignored', async ({ page }) => {
  await openBook(page, TWO_SECTIONS);
  await send(page, A_B, [{ id: 1, start: 0, end: 3, color: 0 }], 2);
  await page.waitForTimeout(100);
  expect(await drawn(page, 0)).toEqual([]);
  await send(page, A_B, [{ id: 1, start: 0, end: 3, color: 0 }], 1);
  await expect.poll(() => drawn(page, 0)).toEqual(['吾輩は']);
});

test('a new answer replaces what was drawn, and an empty one clears it', async ({ page }) => {
  await openBook(page, TWO_SECTIONS);
  await send(page, A_B, [{ id: 1, start: 0, end: 3, color: 0 }]);
  await expect.poll(() => drawn(page, 0)).toEqual(['吾輩は']);
  await send(page, A_B, [{ id: 1, start: 0, end: 3, color: 2 }]);
  await expect.poll(() => drawn(page, 2)).toEqual(['吾輩は']);
  expect(await drawn(page, 0)).toEqual([]);
  await send(page, A_B, []);
  await expect.poll(() => drawn(page, 2)).toEqual([]);
});

test('furigana: positions skip the readings, and a highlight over a ruby base takes it whole', async ({ page }) => {
  await openBook(page, '<div id="ttu-a"><p>吾輩は<ruby>猫<rt>ねこ</rt></ruby>である。</p></div>');
  await send(page, [['ttu-a', 0]], [{ id: 1, start: 3, end: 6, color: 0 }]);
  // 猫 is the 4th counted character; the reading is not counted.
  await expect.poll(() => drawn(page, 0)).toEqual(['猫ねこであ']);
  expect(await measureText(page, '猫', 'で')).toEqual({ start: 3, end: 5, text: '猫で' });
});

test('a gaiji picture counts as one character', async ({ page }) => {
  await openBook(page, `<div id="ttu-a"><p>東<img class="gaiji" src="${GIF}" width="20" height="20">京都。</p></div>`);
  await send(page, [['ttu-a', 0]], [{ id: 1, start: 1, end: 3, color: 1 }]);
  await expect.poll(async () => (await drawn(page, 1)).length).toBe(1);
  expect(await page.evaluate(() => {
    const range = [...CSS.highlights.get('honmani-hl-1')][0];
    return [range.startContainer.nodeName, range.endContainer.data.slice(0, range.endOffset)];
  })).toEqual(['P', '京']);
});

test('measure gives the true selection, across paragraphs and sections, with the book position', async ({ page }) => {
  await openBook(page, TWO_SECTIONS);
  await send(page, A_B, []);
  expect(await measureText(page, '猫である', '名前')).toEqual({ start: 3, end: 9, text: '猫である。名前' });
  expect(await measureText(page, '無い', 'どこで')).toEqual({ start: 12, end: 17, text: '無い。どこで' });
  // A long selection is not cut at the lookup length.
  const long = await page.evaluate(() => {
    const walker = document.createTreeWalker(document.querySelector('.book-content'), NodeFilter.SHOW_TEXT);
    const first = walker.nextNode();
    let last = first;
    for (let node = walker.nextNode(); node; node = walker.nextNode()) last = node;
    const range = document.createRange();
    range.setStart(first, 0);
    range.setEnd(last, last.data.length);
    return window.honmaniHighlights.measure(range);
  });
  expect(long).toEqual({ start: 0, end: 21, text: '吾輩は猫である。名前はまだ無い。どこで生れたか。' });
});

test('idAt finds the highlight under a point, the newest where they overlap', async ({ page }) => {
  await openBook(page, TWO_SECTIONS);
  await send(page, A_B, [
    { id: 7, start: 0, end: 5, color: 0 },
    { id: 8, start: 3, end: 8, color: 1 },
  ]);
  await expect.poll(() => drawn(page, 1)).not.toEqual([]);
  const at = (text, offset) =>
    page.evaluate(
      ([text, offset]) => {
        const walker = document.createTreeWalker(document.querySelector('.book-content'), NodeFilter.SHOW_TEXT);
        for (let node = walker.nextNode(); node; node = walker.nextNode()) {
          if (node.data.includes(text)) return window.honmaniHighlights.idAt(node, node.data.indexOf(text) + offset);
        }
        return 'none';
      },
      [text, offset],
    );
  expect(await at('吾輩は猫である', 1)).toBe(7);
  expect(await at('吾輩は猫である', 5)).toBe(8);
  expect(await at('名前はまだ無い', 6)).toBe(null);
  expect(await at('どこで', 1)).toBe(null);
});

for (const mode of ['horizontal-tb', 'vertical-rl']) {
  test(`${mode}: the same text is drawn, has a box on the page and the same position is measured`, async ({ page }) => {
    await openBook(page, TWO_SECTIONS, { css: `writing-mode: ${mode};` });
    await send(page, A_B, [{ id: 1, start: 12, end: 17, color: 0 }]);
    await expect.poll(() => drawn(page, 0)).toEqual(['無い。', 'どこで']);
    const boxes = await page.evaluate(() =>
      [...CSS.highlights.get('honmani-hl-0')].map((range) => {
        const rect = range.getBoundingClientRect();
        return rect.width > 0 && rect.height > 0;
      }),
    );
    expect(boxes).toEqual([true, true]);
    expect(await measureText(page, '無い', 'どこで')).toEqual({ start: 12, end: 17, text: '無い。どこで' });
  });
}

test('a font size change leaves the highlight on the same text', async ({ page }) => {
  await openBook(page, TWO_SECTIONS);
  await send(page, A_B, [{ id: 1, start: 3, end: 9, color: 0 }]);
  await expect.poll(() => drawn(page, 0)).toEqual(['猫である。名前']);
  const before = await page.evaluate(() => document.querySelector('p').getBoundingClientRect().height);
  await page.evaluate(() => (document.body.style.fontSize = '44px'));
  const after = await page.evaluate(() => document.querySelector('p').getBoundingClientRect().height);
  expect(after).toBeGreaterThan(before);
  expect(await drawn(page, 0)).toEqual(['猫である。名前']);
  // After ttu rebuilds the text (new nodes), the highlight is drawn again on the same characters.
  await page.evaluate(() => {
    const content = document.querySelector('.book-content');
    content.innerHTML = content.innerHTML;
  });
  await expect.poll(() => drawn(page, 0)).toEqual(['猫である。名前']);
});

test('paginated: only the section on the page is drawn, and a turn to the next one draws its own', async ({ page }) => {
  await openBook(page, '<div class="book-content-container" id="ttu-a"><p>吾輩は猫である。</p></div>');
  await send(page, A_B.map(([id, start], i) => [id, i === 0 ? 0 : 7]), [
    { id: 1, start: 0, end: 3, color: 0 },
    { id: 2, start: 8, end: 10, color: 1 },
  ]);
  await expect.poll(() => drawn(page, 0)).toEqual(['吾輩は']);
  expect(await drawn(page, 1)).toEqual([]);
  await page.evaluate(() => {
    const content = document.querySelector('.book-content');
    content.innerHTML = '<div class="book-content-container" id="ttu-b"><p>名前はまだ無い。</p></div>';
  });
  await expect.poll(() => drawn(page, 1)).toEqual(['前は']);
  expect(await drawn(page, 0)).toEqual([]);
});

test.describe('e-ink', () => {
  const greys = (page) =>
    page.evaluate(() => {
      const p = document.querySelector('p');
      const range = document.createRange();
      range.selectNodeContents(p);
      const out = [];
      for (const name of ['honmani-hl-0', 'honmani-hl-1', 'honmani-hl-2', 'honmani-hl-3']) {
        CSS.highlights.set(name, new Highlight(range));
        out.push(getComputedStyle(p, `::highlight(${name})`).backgroundColor);
      }
      return out;
    });

  test('on: four distinct solid greys, lighter than the lookup mark', async ({ page }) => {
    await openBook(page, TWO_SECTIONS, { storage: { honmaniEInk: '1' } });
    const colors = await greys(page);
    expect(colors).toEqual(['rgb(220, 220, 220)', 'rgb(200, 200, 200)', 'rgb(180, 180, 180)', 'rgb(160, 160, 160)']);
  });

  test('on: a word mark over a highlight darkens it, so both still show', async ({ page }) => {
    await openBook(page, '<div id="ttu-a"><p style="font-size: 60px; margin: 0; line-height: 1.5">吾輩は猫である。</p></div>', {
      storage: { honmaniEInk: '1' },
    });
    await send(page, [['ttu-a', 0]], [{ id: 1, start: 0, end: 4, color: 0 }]);
    await expect.poll(() => drawn(page, 0)).not.toEqual([]);
    // A word mark as word-marks.js draws it (priority -1, above the highlights) over characters 2 to 5.
    await page.evaluate(() => {
      const node = document.querySelector('p').firstChild;
      const range = document.createRange();
      range.setStart(node, 2);
      range.setEnd(node, 6);
      const mark = new Highlight(range);
      mark.priority = -1;
      CSS.highlights.set('honmani-learning', mark);
    });
    const shot = await page.screenshot();
    const pixelOf = (index) =>
      page.evaluate(
        async ([data, index]) => {
          const node = document.querySelector('p').firstChild;
          const range = document.createRange();
          range.setStart(node, index);
          range.setEnd(node, index + 1);
          const rect = range.getBoundingClientRect();
          const image = new Image();
          image.src = `data:image/png;base64,${data}`;
          await image.decode();
          const canvas = document.createElement('canvas');
          canvas.width = image.width;
          canvas.height = image.height;
          const context = canvas.getContext('2d');
          context.drawImage(image, 0, 0);
          const scale = image.width / innerWidth;
          return context.getImageData(Math.round((rect.left + 2) * scale), Math.round((rect.top + 3) * scale), 1, 1).data[0];
        },
        [shot.toString('base64'), index],
      );
    const highlightOnly = await pixelOf(0);
    const both = await pixelOf(3);
    const wordOnly = await pixelOf(5);
    console.log('e-ink greys', { highlightOnly, both, wordOnly });
    expect(highlightOnly).toBe(220);
    expect(wordOnly).toBeLessThan(255);
    expect(both).toBeLessThan(highlightOnly);
    expect(both).toBeLessThan(wordOnly);
  });

  test('off: translucent colours, more opaque on a dark page', async ({ page }) => {
    await openBook(page, TWO_SECTIONS);
    await send(page, A_B, [{ id: 1, start: 0, end: 3, color: 0 }]);
    const light = await greys(page);
    expect(light[0]).toBe('rgba(255, 213, 0, 0.28)');
    await page.evaluate(() => (document.body.style.backgroundColor = '#000'));
    await expect.poll(async () => (await greys(page))[0]).toBe('rgba(255, 213, 0, 0.45)');
  });
});

test('500 highlights: indexing and drawing them all is fast', async ({ page }) => {
  const paragraphs = Array.from({ length: 300 }, (_, i) => `<p>${'吾輩は猫である。名前はまだ無い。'.repeat(3)}${i}</p>`).join('');
  await openBook(page, `<div id="ttu-a">${paragraphs}</div>`);
  const items = Array.from({ length: 500 }, (_, i) => ({ id: i + 1, start: i * 25 + 4, end: i * 25 + 12, color: i % 4 }));
  // Applying builds the index of the section and all 500 ranges: the cost of a page turn in the reader
  // is one such rebuild (200 ms after the DOM changes), so this is the number to look at.
  const apply = await page.evaluate((items) => {
    const t0 = performance.now();
    window.__listener({ data: JSON.stringify({ type: 'highlights', bookId: 1, sections: [['ttu-a', 0]], total: 999999, items }) });
    return performance.now() - t0;
  }, items);
  const total = await page.evaluate(() => [0, 1, 2, 3].reduce((n, c) => n + [...CSS.highlights.get(`honmani-hl-${c}`)].length, 0));
  console.log('500 highlights in 300 paragraphs: index and draw all in', apply.toFixed(1), 'ms');
  expect(total).toBe(500);
  expect(apply).toBeLessThan(500);
});

test('where two highlights overlap, both colours show (the translucent backgrounds layer)', async ({ page }) => {
  await openBook(page, '<div id="ttu-a"><p style="font-size: 60px; margin: 0; line-height: 1.5">吾輩は猫である。</p></div>', { css: 'background: #fff;' });
  await send(page, [['ttu-a', 0]], [
    { id: 1, start: 0, end: 4, color: 0 },
    { id: 2, start: 2, end: 6, color: 2 },
  ]);
  await expect.poll(() => drawn(page, 2)).not.toEqual([]);
  // The pixel at the top edge of the line box above a character (away from the glyph) in each region.
  const box = (index) =>
    page.evaluate((index) => {
      const node = document.querySelector('p').firstChild;
      const range = document.createRange();
      range.setStart(node, index);
      range.setEnd(node, index + 1);
      const rect = range.getBoundingClientRect();
      return { x: Math.round(rect.left + 2), y: Math.round(rect.top + 3) };
    }, index);
  const shot = await page.screenshot();
  const pixel = ({ x, y }) =>
    page.evaluate(
      async ([data, x, y]) => {
        const image = new Image();
        image.src = `data:image/png;base64,${data}`;
        await image.decode();
        const canvas = document.createElement('canvas');
        canvas.width = image.width;
        canvas.height = image.height;
        const context = canvas.getContext('2d');
        context.drawImage(image, 0, 0);
        return [...context.getImageData(Math.round(x * (image.width / innerWidth)), Math.round(y * (image.height / innerHeight)), 1, 1).data.slice(0, 3)];
      },
      [shot.toString('base64'), x, y],
    );
  const yellow = await pixel(await box(0));
  const both = await pixel(await box(2));
  const blue = await pixel(await box(5));
  console.log('yellow', yellow, 'both', both, 'blue', blue);
  expect(both).not.toEqual(yellow);
  expect(both).not.toEqual(blue);
  // Blue over yellow is darker than yellow alone in the red channel and darker than blue alone in blue.
  expect(both[0]).toBeLessThan(yellow[0]);
  expect(both[2]).toBeLessThan(blue[2]);
});

test.describe('with the tap bridge', () => {
  const lookups = (page) => page.evaluate(() => window.__messages.filter((m) => m.type === 'lookup'));
  const centerOf = (page, char) =>
    page.evaluate((char) => {
      const walker = document.createTreeWalker(document.querySelector('.book-content'), NodeFilter.SHOW_TEXT);
      for (let node = walker.nextNode(); node; node = walker.nextNode()) {
        const at = node.data.indexOf(char);
        if (at < 0 || node.parentElement.closest('rt')) continue;
        const range = document.createRange();
        range.setStart(node, at);
        range.setEnd(node, at + 1);
        const rect = [...range.getClientRects()].find((r) => r.width > 0);
        return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
      }
      throw new Error(char);
    }, char);

  const BODY = '<div id="ttu-a"><p>吾輩は<ruby>猫<rt>ねこ</rt></ruby>である。名前はまだ無い。</p><p>どこで生れたかとんと見当がつかぬ。</p></div>';

  test('a tap inside a highlight names it, a tap outside does not', async ({ page }) => {
    await openBook(page, BODY, { bridge: true });
    await send(page, [['ttu-a', 0]], [{ id: 11, start: 2, end: 6, color: 0 }]);
    await expect.poll(() => drawn(page, 0)).not.toEqual([]);
    const inside = await centerOf(page, 'で');
    await page.mouse.click(inside.x, inside.y);
    await expect.poll(async () => (await lookups(page)).length).toBe(1);
    expect((await lookups(page))[0]).toMatchObject({ text: expect.stringMatching(/^である/), highlightId: 11 });
    const outside = await centerOf(page, '名');
    await page.mouse.click(outside.x, outside.y);
    await expect.poll(async () => (await lookups(page)).length).toBe(2);
    expect((await lookups(page))[1].highlightId).toBeUndefined();
  });

  test('a tap on a furigana base inside a highlight names it too', async ({ page }) => {
    await openBook(page, BODY, { bridge: true });
    await send(page, [['ttu-a', 0]], [{ id: 12, start: 3, end: 4, color: 1 }]);
    await expect.poll(() => drawn(page, 1)).not.toEqual([]);
    const base = await centerOf(page, '猫');
    await page.mouse.click(base.x, base.y);
    await expect.poll(async () => (await lookups(page)).length).toBe(1);
    expect((await lookups(page))[0].highlightId).toBe(12);
  });

  test('a long selection carries its true extent beyond the 20 characters of the lookup text', async ({ page }) => {
    await openBook(page, BODY, { bridge: true });
    await send(page, [['ttu-a', 0]], []);
    await page.evaluate(() => {
      const paragraphs = document.querySelectorAll('p');
      const range = document.createRange();
      range.setStart(paragraphs[0].firstChild, 0);
      range.setEnd(paragraphs[1].firstChild, 10);
      const selection = getSelection();
      selection.removeAllRanges();
      selection.addRange(range);
    });
    await expect.poll(async () => (await lookups(page)).length, { timeout: 4000 }).toBe(1);
    const [lookup] = await lookups(page);
    expect(lookup.selection).toBe(true);
    expect(lookup.text.length).toBeLessThanOrEqual(20);
    // 吾輩は猫である (7) + 名前はまだ無い (7) + どこで生れたかとんと (10) = 24 counted characters.
    // A selection that leaves its paragraph has only its own extent.
    expect(lookup.highlight).toEqual({ word: { start: 0, end: 24, text: '吾輩は猫である。名前はまだ無い。どこで生れたかとんと' } });
  });

  /** Selects one character (like a long press) and returns the extents the lookup carries. */
  async function extentsOf(page, char) {
    await page.evaluate((char) => {
      const walker = document.createTreeWalker(document.querySelector('.book-content'), NodeFilter.SHOW_TEXT);
      for (let node = walker.nextNode(); node; node = walker.nextNode()) {
        const at = node.data.indexOf(char);
        if (at < 0 || node.parentElement.closest('rt')) continue;
        const range = document.createRange();
        range.setStart(node, at);
        range.setEnd(node, at + 1);
        getSelection().removeAllRanges();
        getSelection().addRange(range);
        return;
      }
      throw new Error(char);
    }, char);
    await expect.poll(async () => (await lookups(page)).length, { timeout: 4000 }).toBe(1);
    return (await lookups(page))[0].highlight;
  }

  // 吾輩は猫である (0-7) 名前はまだ無い (7-14) | どこで生れたかとんと見当がつかぬ (14-...)
  for (const [name, css] of [['horizontal', ''], ['vertical', 'writing-mode: vertical-rl; height: 300px;']]) {
    test(`${name}: a long press has a word, a sentence and a paragraph extent (ruby excluded)`, async ({ page }) => {
      await openBook(page, BODY, { bridge: true, css });
      await send(page, [['ttu-a', 0]], []);
      const { word, sentence, paragraph } = await extentsOf(page, 'ま');
      expect(word).toEqual({ start: 10, end: 11, text: 'ま' });
      // Only the second sentence of the first paragraph, its full stop with it.
      expect(sentence).toEqual({ start: 7, end: 14, text: '名前はまだ無い。' });
      // The whole first paragraph; the reading of 猫 is no part of it.
      expect(paragraph).toEqual({ start: 0, end: 14, text: '吾輩は猫である。名前はまだ無い。' });
    });

    test(`${name}: the sentence holding a ruby base is whole, with no reading in it`, async ({ page }) => {
      await openBook(page, BODY, { bridge: true, css });
      await send(page, [['ttu-a', 0]], []);
      const { word, sentence } = await extentsOf(page, '猫');
      expect(word).toEqual({ start: 3, end: 4, text: '猫' });
      expect(sentence).toEqual({ start: 0, end: 7, text: '吾輩は猫である。' });
    });
  }

  test('a sentence keeps its closing quote, and a line break ends it', async ({ page }) => {
    await openBook(page, '<div id="ttu-a"><p>「行こう。」と彼は言った<br>次の行はここ</p></div>', { bridge: true });
    await send(page, [['ttu-a', 0]], []);
    const { sentence, paragraph } = await extentsOf(page, '行');
    // 行 is the first match: the sentence is 「行こう。」 (行こう = 3 counted characters).
    expect(sentence).toEqual({ start: 0, end: 3, text: '「行こう。」' });
    expect(paragraph.start).toBe(0);
    expect(paragraph.end).toBe(3 + 'と彼は言った'.length + '次の行はここ'.length);
  });

  test('a sentence longer than the 400 characters of the lookup is not cut', async ({ page }) => {
    const long = 'あ'.repeat(500);
    await openBook(page, `<div id="ttu-a"><p>${long}。次です。</p></div>`, { bridge: true });
    await send(page, [['ttu-a', 0]], []);
    const { sentence } = await extentsOf(page, 'あ');
    expect(sentence.start).toBe(0);
    expect(sentence.end).toBe(500);
  });
});
