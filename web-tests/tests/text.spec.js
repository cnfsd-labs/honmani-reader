// @ts-check
const path = require('node:path');
const { test, expect } = require('@playwright/test');

const TEXT = path.join(__dirname, '../../app/src/main/assets/honmani/text.js');

test('text.js shares the helpers on one object and loads only once', async ({ page }) => {
  await page.addInitScript({ path: TEXT });
  await page.addInitScript({ path: TEXT });
  await page.goto('file://' + path.join(__dirname, '../fixtures/book.html'));
  const result = await page.evaluate(() => {
    const T = window.honmaniText;
    document.body.innerHTML =
      '<div id="a"><p id="p">本<ruby>文<rt>ぶん</rt></ruby><span id="s">字</span></p></div>';
    const rt = document.querySelector('rt').firstChild;
    const span = document.querySelector('#s').firstChild;
    return {
      names: Object.keys(T).sort(),
      skipsRt: T.isSkipped(rt),
      skipsText: T.isSkipped(span),
      blockOfSpan: T.blockAncestor(span, document.body).id,
      blockOfDivChild: T.blockAncestor(document.querySelector('#p').firstChild, document.body).id,
      isBlock: [T.isBlock(document.querySelector('#p')), T.isBlock(document.querySelector('#s'))],
      customTest: T.blockAncestor(span, document.body, (e) => e.id === 'a').id,
      fractions: [T.toFraction(0.5), T.toFraction('12.5%'), T.toFraction('0.25'), T.toFraction(7), T.toFraction(-1),
        T.toFraction('x'), T.toFraction(null)],
    };
  });
  expect(result.names).toEqual(
    ['SKIPPED_SELECTOR', 'blockAncestor', 'bookId', 'firstCharNearStart', 'firstCharOnScreen', 'firstCharWalk', 'isBlock',
      'isGaiji', 'isSkipped', 'onScreen', 'textData', 'toFraction'].sort(),
  );
  expect(result.skipsRt).toBe(true);
  expect(result.skipsText).toBe(false);
  expect(result.blockOfSpan).toBe('p');
  expect(result.blockOfDivChild).toBe('p');
  expect(result.isBlock).toEqual([true, false]);
  expect(result.customTest).toBe('a');
  expect(result.fractions).toEqual([0.5, 0.125, 0.25, 1, 0, null, null]);
});

test('bookId reads the id of a book address only', async ({ page }) => {
  await page.addInitScript({ path: TEXT });
  await page.route('**/*', (route) => route.fulfill({ contentType: 'text/html', body: '<p>x</p>' }));
  const ids = [];
  for (const url of ['/b?id=7', '/b?id=0', '/b?id=abc', '/manage?id=7', '/b']) {
    await page.goto('http://localhost:9' + url);
    ids.push(await page.evaluate(() => window.honmaniText.bookId()));
  }
  expect(ids).toEqual([7, null, null, null, null]);
});
