// @ts-check
const path = require('node:path');
const { test, expect } = require('@playwright/test');

const BRIDGE = path.join(__dirname, '../../app/src/main/assets/honmani/tap-bridge.js');
const TEXT = path.join(__dirname, '../../app/src/main/assets/honmani/text.js');
const FIXTURE = 'file://' + path.join(__dirname, '../fixtures/book.html');

test.beforeEach(async ({ page }) => {
  await page.addInitScript({ path: TEXT });
  // Stand-in for the object WebViewCompat.addWebMessageListener injects.
  await page.addInitScript(() => {
    window.__messages = [];
    window.honmani = { postMessage: (message) => window.__messages.push(JSON.parse(message)), onmessage: null };
  });
  // Same timing as WebViewCompat.addDocumentStartJavaScript.
  await page.addInitScript({ path: BRIDGE });
  await page.goto(FIXTURE);
});

/** Center of the `occurrence`-th match of `char` in the element's own text nodes (rt excluded). */
async function charCenter(page, selector, char, occurrence = 0) {
  return page.evaluate(
    ({ selector, char, occurrence }) => {
      const walker = document.createTreeWalker(document.querySelector(selector), NodeFilter.SHOW_TEXT);
      let seen = 0;
      for (let node = walker.nextNode(); node; node = walker.nextNode()) {
        if (node.parentElement.closest('rt, rp')) continue;
        for (let i = node.data.indexOf(char); i >= 0; i = node.data.indexOf(char, i + 1)) {
          if (seen++ === occurrence) {
            const range = document.createRange();
            range.setStart(node, i);
            range.setEnd(node, i + char.length);
            // First non-empty box; see charRects() in tap-bridge.js.
            const rect = Array.from(range.getClientRects()).find((r) => r.width > 0 && r.height > 0);
            return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
          }
        }
      }
      throw new Error(`"${char}" not found in ${selector}`);
    },
    { selector, char, occurrence },
  );
}

async function tapChar(page, selector, char, occurrence = 0) {
  const { x, y } = await charCenter(page, selector, char, occurrence);
  await page.mouse.click(x, y);
}

const messages = (page) => page.evaluate(() => window.__messages);

test('vertical text: scans forward from the tapped character, skipping furigana', async ({ page }) => {
  await tapChar(page, '#v1', '吾');

  expect(await messages(page)).toEqual([
    {
      type: 'lookup',
      text: '吾輩は猫である。名前はまだ無い。」',
      sentence: '「吾輩は猫である。',
      sentenceOffset: 1,
      tap: 1,
      ruby: [{ start: 3, end: 4, reading: 'ねこ' }],
    },
  ]);
});

test('sends furigana of ruby words in the scanned text: joint, per character and with <rb>', async ({ page }) => {
  await tapChar(page, '#h3', '漢');

  const [message] = await messages(page);
  expect(message.text).toBe('漢字と東京と明日。');
  expect(message.ruby).toEqual([
    { start: 0, end: 2, reading: 'かんじ' },
    { start: 3, end: 4, reading: 'とう' },
    { start: 4, end: 5, reading: 'きょう' },
    { start: 6, end: 8, reading: 'あした' },
  ]);
});

test('leaves out furigana of a ruby word tapped in the middle', async ({ page }) => {
  await tapChar(page, '#h3', '字');

  const [message] = await messages(page);
  expect(message.text).toBe('字と東京と明日。');
  expect(message.ruby.map((span) => span.reading)).toEqual(['とう', 'きょう', 'あした']);
});

test('sends no ruby field without furigana in the scanned text', async ({ page }) => {
  await tapChar(page, '#v2', 'ど');

  const [message] = await messages(page);
  expect(message).not.toHaveProperty('ruby');
});

test('tapping ruby base text starts at the base, not the furigana', async ({ page }) => {
  await tapChar(page, '#v1', '猫');

  const [message] = await messages(page);
  expect(message.text).toBe('猫である。名前はまだ無い。」');
  expect(message.sentence).toBe('「吾輩は猫である。');
  expect(message.sentenceOffset).toBe(4);
});

test('taps on ruby still work when the page cancels the click, as ttu does', async ({ page }) => {
  await page.evaluate(() => {
    for (const ruby of document.querySelectorAll('ruby')) {
      ruby.addEventListener('click', (event) => {
        event.preventDefault();
        event.stopImmediatePropagation();
        ruby.classList.add('reveal-rt');
      });
    }
  });

  await tapChar(page, '#v1', '猫');

  const [message] = await messages(page);
  expect(message.text).toBe('猫である。名前はまだ無い。」');
  // The page's own handler still ran.
  expect(await page.locator('#v1 ruby').getAttribute('class')).toBe('reveal-rt');
});

test('sentence includes closing quote and stops at the paragraph', async ({ page }) => {
  await tapChar(page, '#v1', '名');

  const [message] = await messages(page);
  expect(message.text).toBe('名前はまだ無い。」');
  expect(message.sentence).toBe('名前はまだ無い。」');
  expect(message.sentenceOffset).toBe(0);
});

test('sends the sentence before the tapped one, within the paragraph', async ({ page }) => {
  await tapChar(page, '#v1', '名');
  await tapChar(page, '#h4', '風');
  await tapChar(page, '#v1', '吾');

  const [second, lineBreak, first] = await messages(page);
  // The opening quote belongs to the sentence it starts, as in "sentence" itself.
  expect(second.previousSentence).toBe('「吾輩は猫である。');
  // A line break ends a sentence.
  expect(lineBreak.previousSentence).toBe('雨ニモマケズ');
  // Nothing before the first sentence of a paragraph: the field is left out.
  expect(first).not.toHaveProperty('previousSentence');
});

test('the sentence before is cut to its last 200 characters', async ({ page }) => {
  await page.evaluate(() => {
    document.querySelector('#h2').textContent = `${'あ'.repeat(300)}。ここです。`;
    // The long paragraph pushes the tapped sentence below the screen.
    window.scrollTo(0, 1000);
  });

  await tapChar(page, '#h2', 'こ');

  const [message] = await messages(page);
  expect(message.sentence).toBe('ここです。');
  expect(message.previousSentence).toBe('あ'.repeat(199) + '。');
});

test('scan text is capped at 20 characters', async ({ page }) => {
  await tapChar(page, '#v2', 'ど');

  const [message] = await messages(page);
  expect(Array.from(message.text)).toHaveLength(17);
  expect(message.text).toBe('どこで生れたかとんと見当がつかぬ。');
});

test('horizontal text works and rp/rt are skipped', async ({ page }) => {
  await tapChar(page, '#h1', '読');

  const [message] = await messages(page);
  expect(message.text).toBe('読みませんでした。リンク');
  expect(message.sentence).toBe('昨日は本を読みませんでした。');
  expect(message.sentenceOffset).toBe(5);
});

test('a tap on an opening bracket looks up the word after it and marks only that', async ({ page }) => {
  await tapChar(page, '#v1', '「');

  const [message] = await messages(page);
  expect(message.text).toBe('吾輩は猫である。名前はまだ無い。」');
  expect(message.sentence).toBe('「吾輩は猫である。');
  expect(message.sentenceOffset).toBe(1);
  expect(message.ruby).toEqual([{ start: 3, end: 4, reading: 'ねこ' }]);

  await reply(page, { type: 'highlight', length: 2 });
  expect(await highlighted(page)).toEqual(['吾輩']);
});

test('a line break ends the scanned text and the sentence', async ({ page }) => {
  await tapChar(page, '#h4', '風');

  const [message] = await messages(page);
  expect(message.text).toBe('風ニモマケズ');
  expect(message.sentence).toBe('風ニモマケズ');
  expect(message.sentenceOffset).toBe(0);
  expect(message.ruby).toBeUndefined();

  // Furigana after a line break still lines up with the text.
  await tapChar(page, '#h4', '雪');
  const [, next] = await messages(page);
  expect(next.text).toBe('雪ニモ');
  expect(next.ruby).toEqual([{ start: 0, end: 1, reading: 'ゆき' }]);
});

test('a line break in the source is a space, not the end of the text or the sentence', async ({ page }) => {
  await page.evaluate(() => {
    document.querySelector('#horizontal').insertAdjacentHTML(
      'afterbegin',
      '<p id="nl">彼は東京\nに住む。</p><p id="tabs">猫が\t\r\n鳴く。</p>' +
        '<p id="keep" style="white-space: pre-line">雨が\n降る。</p>',
    );
  });
  await tapChar(page, '#nl', '彼');
  await tapChar(page, '#tabs', '猫');
  // A source that keeps its line breaks (white-space: pre-line) still breaks there.
  await tapChar(page, '#keep', '雨');

  const [source, tabs, kept] = await messages(page);
  expect(source.text).toBe('彼は東京 に住む。');
  expect(source.sentence).toBe('彼は東京 に住む。');
  expect(tabs.text).toBe('猫が  鳴く。');
  expect(kept.text).toBe('雨が');
});

test('a gaiji picture ends the scanned text, and the text after it still lines up', async ({ page }) => {
  await page.evaluate(() => {
    const gif = 'data:image/gif;base64,R0lGODlhAQABAAAAACH5BAEKAAEALAAAAAABAAEAAAICTAEAOw==';
    document.querySelector('#horizontal').insertAdjacentHTML(
      'afterbegin',
      `<p id="gj">東<img class="gaiji" src="${gif}" width="24" height="24">京都に行く。</p>`,
    );
  });
  await tapChar(page, '#gj', '東');
  await tapChar(page, '#gj', '京');

  const [before, after] = await messages(page);
  expect(before.text).toBe('東');
  expect(before.sentence).toBe('東\u3013京都に行く。');
  expect(before.sentenceOffset).toBe(0);
  expect(after.text).toBe('京都に行く。');
  expect(after.sentenceOffset).toBe(2);

  await reply(page, { type: 'highlight', length: 2 });
  expect(await highlighted(page)).toEqual(['京都']);
});

test('a tap is looked up when the finger goes up, also when its click never comes (it stopped a fling)', async ({ page }) => {
  const { x, y } = await charCenter(page, '#v2', 'ど');
  // Pointer events only, as Chromium sends for a tap right after a page swipe.
  await page.evaluate(({ x, y }) => {
    const target = document.elementFromPoint(x, y);
    const init = { bubbles: true, cancelable: true, clientX: x, clientY: y, isPrimary: true, pointerId: 1 };
    target.dispatchEvent(new PointerEvent('pointerdown', init));
    target.dispatchEvent(new PointerEvent('pointerup', init));
  }, { x, y });
  // At once: no waiting for a click.
  expect(await messages(page)).toHaveLength(1);
  expect((await messages(page))[0].text).toBe('どこで生れたかとんと見当がつかぬ。');
});

test('a click without a tap before it (a keyboard, an accessibility service) is looked up', async ({ page }) => {
  const { x, y } = await charCenter(page, '#v2', 'ど');
  await page.evaluate(({ x, y }) => {
    document.elementFromPoint(x, y).dispatchEvent(new MouseEvent('click', { bubbles: true, clientX: x, clientY: y }));
  }, { x, y });
  expect(await messages(page)).toHaveLength(1);
});

test('a tap with its click is looked up once; a swipe is no tap', async ({ page }) => {
  await tapChar(page, '#v2', 'ど');
  await page.waitForTimeout(500);
  expect(await messages(page)).toHaveLength(1);

  const { x, y } = await charCenter(page, '#v2', 'こ');
  await page.evaluate(({ x, y }) => {
    const target = document.elementFromPoint(x, y);
    const at = (dx) => ({ bubbles: true, clientX: x + dx, clientY: y, isPrimary: true, pointerId: 1 });
    target.dispatchEvent(new PointerEvent('pointerdown', at(0)));
    target.dispatchEvent(new PointerEvent('pointerup', at(80)));
  }, { x, y });
  await page.waitForTimeout(500);
  expect(await messages(page)).toHaveLength(1);
});

test('a tap on furigana looks up the word it reads, from the start of its base', async ({ page }) => {
  await page.click('#v1 rt');
  await page.click('#h3 rt >> nth=2');

  const [cat, tomorrow] = await messages(page);
  expect(cat.text).toBe('猫である。名前はまだ無い。」');
  expect(cat.ruby).toEqual([{ start: 0, end: 1, reading: 'ねこ' }]);
  // Per-character furigana: きょう reads 京 only.
  expect(tomorrow.text).toBe('京と明日。');

  await reply(page, { type: 'highlight', length: 1 });
  expect(await highlighted(page)).toEqual(['京']);
});

test('characters outside the BMP are kept whole', async ({ page }) => {
  await tapChar(page, '#h2', '𠮟');

  const [message] = await messages(page);
  expect(message.text).toBe('𠮟られた。');
});

test('a tap on an anchor without href is book text; a link is not', async ({ page }) => {
  await page.evaluate(() => {
    document.querySelector('#h2').innerHTML = '<a id="anchor">アンカー</a>の文。';
  });
  await tapChar(page, '#anchor', 'ン');

  const lookups = (await messages(page)).filter((message) => message.type === 'lookup');
  expect(lookups.map((message) => message.text)).toEqual(['ンカーの文。']);
});

test('taps on links, empty space and non-book text start no lookup', async ({ page }) => {
  await tapChar(page, '#h1', 'リ');
  await page.click('#blank');
  await page.mouse.click(5, 5);
  await tapChar(page, '#outside', '本');

  const types = (await messages(page)).map((message) => message.type);
  expect(types).not.toContain('lookup');
});

test('a tap on book content that is not text asks the app to close the panel', async ({ page }) => {
  await page.click('#blank');
  await tapChar(page, '#outside', '本');
  await tapChar(page, '#h1', 'リ');

  // Blank space is book content but no text; outside text and links are neither.
  expect(await messages(page)).toEqual([{ type: 'dismiss' }]);
});

/** Sends a reply as the app would, through the handler the bridge installed. */
async function reply(page, message) {
  await page.evaluate((message) => window.honmani.onmessage({ data: JSON.stringify(message) }), message);
}

const highlighted = (page) =>
  page.evaluate(() => {
    const highlight = CSS.highlights.get('honmani-lookup');
    return highlight ? Array.from(highlight).map((range) => range.toString()) : null;
  });

test('the app can highlight the matched text, skipping furigana, and clear it', async ({ page }) => {
  await tapChar(page, '#v1', '猫');
  await reply(page, { type: 'highlight', length: 3 });

  // 猫 is ruby base text; ねこ is skipped and the mark continues into the next text node.
  expect(await highlighted(page)).toEqual(['猫', 'であ']);
  const style = await page.evaluate(() => document.getElementById('honmani-style')?.textContent);
  expect(style).toContain('::highlight(honmani-lookup)');

  await reply(page, { type: 'clear' });
  expect(await highlighted(page)).toBeNull();
});

test('a highlight answering an older tap is ignored; one for the latest tap is drawn', async ({ page }) => {
  await tapChar(page, '#h2', '𠮟');
  await tapChar(page, '#h1', '昨');
  const taps = (await messages(page)).filter((message) => message.type === 'lookup').map((m) => m.tap);
  expect(taps).toEqual([1, 2]);

  await reply(page, { type: 'highlight', length: 2, tap: 1 });
  expect(await highlighted(page)).toBeNull();
  await reply(page, { type: 'highlight', length: 2, tap: 2 });
  expect(await highlighted(page)).toEqual(['昨日']);
});

test('each tap logs how long its steps took until its mark was painted, once', async ({ page }) => {
  const logs = [];
  page.on('console', (message) => {
    if (message.text().startsWith('honmani tap timing')) logs.push(message.text());
  });
  await tapChar(page, '#v1', '猫');
  await reply(page, { type: 'highlight', length: 3 });
  await expect.poll(() => logs.length).toBe(1);
  expect(logs[0]).toMatch(/^honmani tap timing: tap \+\d+ ms after finger up, message \+\d+, marked \+\d+, painted \+\d+$/);

  // The same mark again (a new accent colour, say) is not a new tap.
  await reply(page, { type: 'highlight', length: 3, color: '#8A5CF5' });
  await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  expect(logs).toHaveLength(1);
});

test('the highlighted word is announced to the word marks, with its colour', async ({ page }) => {
  await page.evaluate(() => {
    window.__lookedUp = [];
    window.addEventListener('honmani-looked-up', (event) =>
      window.__lookedUp.push({ text: event.detail.ranges.map((range) => range.toString()), color: event.detail.color }),
    );
  });
  await tapChar(page, '#v1', '猫');
  await reply(page, { type: 'highlight', length: 3, color: '#8A5CF5' });

  expect(await page.evaluate(() => window.__lookedUp)).toEqual([{ text: ['猫', 'であ'], color: '#8A5CF5' }]);
});

test('the highlight takes the app\'s accent colour', async ({ page }) => {
  await tapChar(page, '#v2', 'ど');
  await reply(page, { type: 'highlight', length: 2, color: '#8A5CF5' });
  const style = () => page.evaluate(() => document.getElementById('honmani-style')?.textContent);
  expect(await style()).toContain('rgba(138, 92, 245, 0.4)');

  // Anything but #rrggbb falls back to the default mark.
  await reply(page, { type: 'highlight', length: 2, color: 'red; } body { display: none' });
  expect(await style()).toContain('rgba(255, 196, 0, 0.45)');
  expect(await highlighted(page)).toEqual(['どこ']);
});

test('a new tap replaces the highlight; a blank tap clears it', async ({ page }) => {
  await tapChar(page, '#v2', 'ど');
  await reply(page, { type: 'highlight', length: 2 });
  expect(await highlighted(page)).toEqual(['どこ']);

  await tapChar(page, '#h1', '読');
  await reply(page, { type: 'highlight', length: 1 });
  expect(await highlighted(page)).toEqual(['読']);

  await page.click('#v1 rt');
  expect(await highlighted(page)).toBeNull();
});

test('malformed replies are ignored', async ({ page }) => {
  await tapChar(page, '#v2', 'ど');
  await page.evaluate(() => window.honmani.onmessage({ data: 'not json' }));
  await reply(page, { type: 'highlight', length: 'all' });

  expect(await highlighted(page)).toBeNull();
});

/** Rects of the fallback mark's boxes, or null without the overlay. */
const overlayBoxes = (page) =>
  page.evaluate(() => {
    const overlay = document.getElementById('honmani-highlight-overlay');
    return overlay
      ? Array.from(overlay.children).map((box) => {
          const rect = box.getBoundingClientRect();
          return { left: rect.left, top: rect.top, width: rect.width, height: rect.height };
        })
      : null;
  });

/** WebViews before Chromium 105 (e.g. Android 12 phones that never update it) lack the API. */
const withoutHighlightApi = (page) => page.evaluate(() => delete window.Highlight);

test('without the Custom Highlight API the mark is drawn as boxes over the text', async ({ page }) => {
  await withoutHighlightApi(page);
  await tapChar(page, '#v1', '猫');
  await reply(page, { type: 'highlight', length: 1, color: '#8A5CF5' });

  const boxes = await overlayBoxes(page);
  expect(boxes).toHaveLength(1);
  const cat = await charCenter(page, '#v1', '猫');
  const [box] = boxes;
  expect(cat.x).toBeGreaterThan(box.left);
  expect(cat.x).toBeLessThan(box.left + box.width);
  expect(cat.y).toBeGreaterThan(box.top);
  expect(cat.y).toBeLessThan(box.top + box.height);
  const background = await page.evaluate(
    () => document.getElementById('honmani-highlight-overlay').firstElementChild.style.backgroundColor,
  );
  expect(background).toBe('rgba(138, 92, 245, 0.4)');

  await reply(page, { type: 'clear' });
  expect(await overlayBoxes(page)).toBeNull();
});

test('the fallback mark skips furigana and lets taps through to the book', async ({ page }) => {
  await withoutHighlightApi(page);
  await tapChar(page, '#v1', '猫');
  await reply(page, { type: 'highlight', length: 3 });
  // 猫 and であ are separate text nodes, so two boxes; none for ねこ.
  expect(await overlayBoxes(page)).toHaveLength(2);

  // A tap on the marked text reaches the book and starts a new lookup.
  await tapChar(page, '#v1', 'で');
  const lookups = (await messages(page)).filter((message) => message.type === 'lookup');
  expect(lookups.map((message) => message.text[0])).toEqual(['猫', 'で']);
  expect(await overlayBoxes(page)).toBeNull();
});

test('the fallback mark follows the text when the page scrolls', async ({ page }) => {
  await withoutHighlightApi(page);
  await page.evaluate(() => {
    const spacer = document.createElement('div');
    spacer.style.height = '3000px';
    document.body.appendChild(spacer);
  });
  await tapChar(page, '#v2', 'ど');
  await reply(page, { type: 'highlight', length: 2 });
  const [before] = await overlayBoxes(page);

  await page.evaluate(() => window.scrollBy(0, 40));
  await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(resolve)));
  const [after] = await overlayBoxes(page);
  expect(after.top).toBeCloseTo(before.top - 40, 0);
});

test('no lookup while text is selected', async ({ page }) => {
  await page.evaluate(() => {
    window.localStorage.setItem('honmaniSelectionLookup', '0');
    const range = document.createRange();
    range.selectNodeContents(document.querySelector('#v2'));
    window.getSelection().addRange(range);
  });
  await tapChar(page, '#v2', '生');

  expect(await messages(page)).toEqual([]);
});

test('elements in the book with the script\'s ids are not taken over', async ({ page }) => {
  await page.evaluate(() => {
    document.body.insertAdjacentHTML(
      'afterbegin',
      '<style id="honmani-style" data-book>p { color: red }</style><div id="honmani-highlight-overlay" data-book></div>',
    );
  });
  await tapChar(page, '#v1', '猫');
  await reply(page, { type: 'highlight', length: 3 });

  const book = await page.evaluate(() => ({
    style: document.querySelector('style[data-book]').textContent,
    overlayChildren: document.querySelector('div[data-book]').children.length,
    styles: Array.from(document.querySelectorAll('style')).filter((e) => e.textContent.includes('::highlight(honmani-lookup)')).length,
  }));
  // The book's own elements are untouched; the script made its own.
  expect(book.style).toBe('p { color: red }');
  expect(book.overlayChildren).toBe(0);
  expect(book.styles).toBe(1);
});

/** Selects from (selector's text node `from`, offset) to (node `to`, offset), as a long press and its handles do. */
async function select(page, selector, from, to) {
  await page.evaluate(
    ({ selector, from, to }) => {
      const texts = [];
      const walker = document.createTreeWalker(document.querySelector(selector), NodeFilter.SHOW_TEXT);
      for (let node = walker.nextNode(); node; node = walker.nextNode()) {
        if (!node.parentElement.closest('rt, rp')) texts.push(node);
      }
      const range = document.createRange();
      range.setStart(texts[from[0]], from[1]);
      range.setEnd(texts[to[0]], to[1]);
      const selection = window.getSelection();
      selection.removeAllRanges();
      selection.addRange(range);
    },
    { selector, from, to },
  );
}

const settle = (page) => page.waitForTimeout(700);

test('a selection is looked up once it has settled, with the selected text as the query', async ({ page }) => {
  // 猫である: the ruby base, then the text after it.
  await select(page, '#v1', [1, 0], [2, 3]);
  expect(await messages(page)).toEqual([]);
  await settle(page);

  expect(await messages(page)).toEqual([
    {
      type: 'lookup',
      text: '猫である',
      sentence: '「吾輩は猫である。',
      sentenceOffset: 4,
      tap: 1,
      ruby: [{ start: 0, end: 1, reading: 'ねこ' }],
      selection: true,
    },
  ]);
});

test('a looked-up selection is cleared, so Android shows no handles or toolbar', async ({ page }) => {
  await select(page, '#v2', [0, 0], [0, 3]);
  expect(await page.evaluate(() => window.getSelection().toString())).toBe('どこで');
  await settle(page);

  expect((await messages(page)).length).toBe(1);
  expect(await page.evaluate(() => window.getSelection().isCollapsed)).toBe(true);
});

test('a selection that keeps changing is looked up once, at its final extent', async ({ page }) => {
  await select(page, '#v2', [0, 0], [0, 2]);
  await page.waitForTimeout(150);
  await select(page, '#v2', [0, 0], [0, 4]);
  await settle(page);

  const lookups = await messages(page);
  expect(lookups.map((message) => message.text)).toEqual(['どこで生']);
});

test('the same selection is not looked up twice, a new one after it is', async ({ page }) => {
  await select(page, '#v2', [0, 0], [0, 3]);
  await settle(page);
  await page.evaluate(() => document.dispatchEvent(new Event('selectionchange')));
  await settle(page);
  expect((await messages(page)).length).toBe(1);

  await select(page, '#v2', [0, 3], [0, 5]);
  await settle(page);
  expect((await messages(page)).map((message) => message.text)).toEqual(['どこで', '生れ']);
});

test('a long selection is capped at the 20 characters of a tap', async ({ page }) => {
  await page.evaluate(() => {
    document.querySelector('#h2').textContent = 'あ'.repeat(40);
  });
  await select(page, '#h2', [0, 0], [0, 40]);
  await settle(page);

  const lookups = await messages(page);
  expect(lookups.at(-1).text).toBe('あ'.repeat(20));
});

test('a selection stops at a line break and skips furigana', async ({ page }) => {
  // From 雨ニモ... through the <br> into the next line.
  await select(page, '#h4', [0, 0], [1, 3]);
  await settle(page);

  const [message] = await messages(page);
  expect(message.text).toBe('雨ニモマケズ');
});

test('the switch honmaniSelectionLookup = 0 turns selection lookups off, and the selection stays', async ({ page }) => {
  await page.evaluate(() => window.localStorage.setItem('honmaniSelectionLookup', '0'));
  await select(page, '#v2', [0, 0], [0, 3]);
  await settle(page);

  expect(await messages(page)).toEqual([]);
  expect(await page.evaluate(() => window.getSelection().toString())).toBe('どこで');
});

test('a selection outside the book is not looked up', async ({ page }) => {
  await select(page, '#outside', [0, 0], [0, 3]);
  await settle(page);

  expect(await messages(page)).toEqual([]);
});

test('the app can highlight a selection lookup like a tap', async ({ page }) => {
  await select(page, '#v2', [0, 0], [0, 3]);
  await settle(page);
  await page.evaluate(() => window.honmani.onmessage({ data: JSON.stringify({ type: 'highlight', length: 3, tap: 1 }) }));

  expect(await page.evaluate(() => CSS.highlights.has('honmani-lookup'))).toBe(true);
});
