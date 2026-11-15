// @ts-check
/**
 * keep-position.js on a stand-in for ttu's paginated reader (one paragraph per screen, the arrow
 * keys turn, a resize goes back to the start like ttu's restore by paragraph).
 */
const path = require('node:path');
const { test, expect } = require('@playwright/test');

const ASSETS = path.join(__dirname, '../../app/src/main/assets/honmani');

test('a second rotation stops the page turns of the restore still running for the first', async ({ page }) => {
  for (const script of ['text.js', 'keep-position.js']) await page.addInitScript({ path: path.join(ASSETS, script) });
  const paragraphs = Array.from({ length: 60 }, (_, i) => `<p>${String(i).padStart(2, '0')}あいう</p>`).join('');
  await page.route('**/*', (route) =>
    route.fulfill({
      contentType: 'text/html',
      body: `<!doctype html><meta charset="utf-8"><style>body { margin: 0; font-family: IPAGothic; font-size: 20px; } p { margin: 0; height: 100vh; }</style><body><div class="book-content">${paragraphs}</div>`,
    }),
  );
  await page.setViewportSize({ width: 360, height: 640 });
  await page.goto('http://localhost:9/b?id=1');
  await page.evaluate(() => {
    window.__turns = 0;
    window.__turnsAtResize = [];
    window.addEventListener('keydown', (event) => {
      if (event.code !== 'ArrowDown' && event.code !== 'ArrowUp') return;
      window.__turns++;
      window.scrollBy({ top: event.code === 'ArrowDown' ? innerHeight : -innerHeight, behavior: 'instant' });
    });
    window.addEventListener('resize', () => {
      window.__turnsAtResize.push(window.__turns);
      window.scrollTo({ top: 0, behavior: 'instant' });
    });
    window.scrollTo({ top: 45 * innerHeight, behavior: 'instant' });
  });
  await page.waitForTimeout(600);

  await page.setViewportSize({ width: 360, height: 600 });
  // The restore starts once the layout settles (700 ms) and needs about 45 turns.
  await expect.poll(() => page.evaluate(() => window.__turns), { timeout: 3_000 }).toBeGreaterThanOrEqual(3);
  await page.setViewportSize({ width: 360, height: 640 });
  await expect.poll(() => page.evaluate(() => window.__turnsAtResize.length)).toBe(2);
  await page.waitForTimeout(500);
  // Within the new layout's settle time nothing turns; the old restore stopped.
  const [turns, atResize] = await page.evaluate(() => [window.__turns, window.__turnsAtResize[1]]);
  expect(turns - atResize).toBeLessThanOrEqual(1);
  // The restore for the new layout brings the place back.
  await expect.poll(() => page.evaluate(() => Math.round(scrollY / innerHeight)), { timeout: 5_000 }).toBe(45);
});
