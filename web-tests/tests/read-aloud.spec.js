// @ts-check
/**
 * Read aloud, the page's half (read-aloud.js with sentence-follow.js): cutting the book into
 * the sentences to speak, the mark on the one being spoken and keeping it on screen. The text is
 * spoken by the app, so here the app is a stub that sends the protocol's messages by hand.
 */
const path = require('node:path');
const { test, expect } = require('@playwright/test');

const ASSETS = path.join(__dirname, '../../app/src/main/assets/honmani');
const GIF = 'data:image/gif;base64,R0lGODlhAQABAAAAACH5BAEKAAEALAAAAAABAAEAAAICTAEAOw==';

/** Opens a page shaped like ttu's book page with `body` inside .book-content. */
async function openBook(page, body, { storage = {}, css = '' } = {}) {
  await page.addInitScript((storage) => {
    for (const [key, value] of Object.entries(storage)) localStorage.setItem(key, value);
    window.__messages = [];
    window.honmani = {
      postMessage: (message) => window.__messages.push(JSON.parse(message)),
      addEventListener: (type, listener) => (window.__listener = listener),
    };
  }, storage);
  for (const script of ['text.js', 'sentence-follow.js', 'read-aloud.js', 'reader-style.js']) {
    await page.addInitScript({ path: path.join(ASSETS, script) });
  }
  await page.route('**/*', (route) =>
    route.fulfill({
      contentType: 'text/html',
      body: `<!doctype html><meta charset="utf-8"><style>body { font-family: IPAGothic; font-size: 20px; ${css} }</style><body><div class="book-content">${body}</div>`,
    }),
  );
  await page.goto('http://localhost:9/b?id=1');
}

let gen = 0;
/** Sends one message to the page; resolves with the next sentences it posts for a begin, seek, more or advance. */
async function ask(page, type, extra = {}) {
  const id = ++gen;
  const before = await page.evaluate(() => window.__messages.length);
  await page.evaluate((message) => window.__listener({ data: JSON.stringify(message) }), { type: `readAloud.${type}`, gen: id, count: 10, ...extra });
  const handle = await page.waitForFunction(
    ([before, id]) => window.__messages.slice(before).find((m) => m.gen === id),
    [before, id],
    { timeout: 20_000 },
  );
  return handle.jsonValue();
}
const texts = (reply) => reply.items.map((item) => item.text);

test('the sentences of a page are cut as the tap bridge cuts them, each paragraph apart', async ({ page }) => {
  await openBook(page, '<p>吾輩は猫である。名前はまだ無い。</p><p>「どこで生れたか。」と言った。</p><p>終わり</p>');
  const reply = await ask(page, 'begin');
  expect(reply).toMatchObject({ type: 'readAloud.sentences', end: 'section' });
  expect(texts(reply)).toEqual(['吾輩は猫である。', '名前はまだ無い。', '「どこで生れたか。」', 'と言った。', '終わり']);
  expect(reply.items.map((item) => item.i)).toEqual([0, 1, 2, 3, 4]);
});

test('the sentence ends chosen in the reader decide the cut', async ({ page }) => {
  await openBook(page, '<p>一つ目。二つ目、三つ目。</p>');
  await page.evaluate(() => (window.honmaniSentenceEnds = '、'));
  expect(texts(await ask(page, 'begin'))).toEqual(['一つ目。二つ目、', '三つ目。']);
});

test('a ruby base is spoken as its reading and the furigana is never read as text', async ({ page }) => {
  await openBook(
    page,
    '<p>昨日は<ruby>読<rp>(</rp><rt>よ</rt><rp>)</rp></ruby>んだ。<ruby>漢字<rt>かんじ</rt></ruby>と<ruby>東<rt>とう</rt>京<rt>きょう</rt></ruby>と<ruby><rb>明</rb><rb>日</rb><rt>あした</rt></ruby>。</p>',
  );
  expect(texts(await ask(page, 'begin'))).toEqual(['昨日はよんだ。', 'かんじととうきょうとあした。']);
});

test('an empty reading leaves the base as written', async ({ page }) => {
  await openBook(page, '<p>吾輩は<ruby>猫<rt></rt></ruby>である。</p>');
  expect(texts(await ask(page, 'begin'))).toEqual(['吾輩は猫である。']);
});

test('pictures, gaiji, punctuation and blank sentences are skipped; a line break ends a sentence', async ({ page }) => {
  await openBook(
    page,
    `<p><img src="${GIF}"></p><p>。。。</p><p>   </p><p>雨ニモ<img class="gaiji" src="${GIF}">マケズ<br>風ニモマケズ</p><p>…</p><p>！？</p><p>最後。</p>`,
  );
  expect(texts(await ask(page, 'begin'))).toEqual(['雨ニモマケズ', '風ニモマケズ', '最後。']);
});

test('a very long sentence is split below the length limit, after a comma where there is one', async ({ page }) => {
  const piece = 'あいうえおかきくけこ、';
  await openBook(page, `<p>${piece.repeat(900)}。</p>`);
  const reply = await ask(page, 'begin', { count: 3 });
  expect(reply.items.length).toBe(3);
  for (const item of reply.items) expect(item.text.length).toBeLessThanOrEqual(4_000);
  expect(reply.items[0].text.slice(-1)).toBe('、');
  expect(reply.items[2].text.slice(-1)).toBe('。');
  // Nothing is lost between the pieces.
  const rest = await ask(page, 'more', { count: 10 });
  const all = [...texts(reply), ...texts(rest)].join('');
  expect(all).toBe(piece.repeat(900) + '。');
});

test('more continues where the last answer stopped, seek goes back to a sentence sent before', async ({ page }) => {
  await openBook(page, '<p>一。二。三。四。五。六。</p>');
  expect(texts(await ask(page, 'begin', { count: 2 }))).toEqual(['一。', '二。']);
  expect(texts(await ask(page, 'more', { count: 2 }))).toEqual(['三。', '四。']);
  const back = await ask(page, 'seek', { index: 1, count: 3 });
  expect(back.items).toEqual([{ i: 1, text: '二。' }, { i: 2, text: '三。' }, { i: 3, text: '四。' }]);
  expect(texts(await ask(page, 'more', { count: 5 }))).toEqual(['五。', '六。']);
  const lost = await ask(page, 'seek', { index: 99, count: 1 });
  expect(lost.type).toBe('readAloud.lost');
});

test('the spoken sentence is marked in its own highlight, under the other marks, and stop clears it', async ({ page }) => {
  await openBook(page, '<p>一つ目の文。二つ目の文。</p>');
  await ask(page, 'begin');
  await page.evaluate((gen) => window.__listener({ data: JSON.stringify({ type: 'readAloud.mark', gen, index: 1 }) }), gen);
  const marked = async () => page.evaluate(() => [...(CSS.highlights.get('honmani-read-aloud') ?? [])].map((range) => range.toString()));
  await expect.poll(marked).toEqual(['二つ目の文。']);
  expect(await page.evaluate(() => CSS.highlights.get('honmani-read-aloud').priority)).toBeLessThan(-3);
  await page.evaluate(() => window.__listener({ data: JSON.stringify({ type: 'readAloud.stop' }) }));
  await expect.poll(marked).toEqual([]);
});

test('the mark of a ruby sentence covers the furigana too', async ({ page }) => {
  await openBook(page, '<p>前の文。<ruby>猫<rt>ねこ</rt></ruby>である。</p>');
  const reply = await ask(page, 'begin');
  expect(texts(reply)).toEqual(['前の文。', 'ねこである。']);
  await page.evaluate((gen) => window.__listener({ data: JSON.stringify({ type: 'readAloud.mark', gen, index: 1 }) }), gen);
  await expect
    .poll(() => page.evaluate(() => [...CSS.highlights.get('honmani-read-aloud')].map((range) => range.toString())))
    .toEqual(['猫ねこである。']);
});

test('e-ink mode draws the mark grey, without motion', async ({ page }) => {
  await openBook(page, '<p>一つ目の文。</p>', { storage: { honmaniEInk: '1' } });
  const rules = await page.evaluate(() =>
    [...document.styleSheets].flatMap((sheet) => [...sheet.cssRules]).map((rule) => rule.cssText).filter((text) => text.includes('honmani-read-aloud')),
  );
  expect(rules.join('\n')).toMatch(/rgba\(0, 0, 0, 0\.2\)/);
});

test('with a long paragraph the first sentence is the one that begins on the page, or the rest of the one running over', async ({ page }) => {
  // Three screens of short sentences in a scroller; scrolled to the middle.
  const sentences = Array.from({ length: 150 }, (_, i) => `これは${i}番目の文です。`).join('');
  await openBook(page, `<p>${sentences}</p>`, { storage: { viewMode: 'continuous' } });
  await page.evaluate(() => window.scrollTo(0, 2_000));
  const reply = await ask(page, 'begin', { count: 2 });
  const first = reply.items[0];
  expect(first.i).toBe(0);
  expect(first.text).toMatch(/^これは\d+番目の文です。$/);
  // The first sentence starts on the screen (never one before it, whose start is above).
  const onScreen = await page.evaluate((text) => {
    const walker = document.createTreeWalker(document.querySelector('.book-content'), NodeFilter.SHOW_TEXT);
    const node = walker.nextNode();
    const at = node.data.indexOf(text);
    const range = document.createRange();
    range.setStart(node, at);
    range.setEnd(node, at + 1);
    const rect = range.getBoundingClientRect();
    return rect.bottom > 0 && rect.top < innerHeight;
  }, first.text);
  expect(onScreen).toBe(true);
});
