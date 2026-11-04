/*
 * Honmani's reader looks that go beyond ttu's settings, injected at document start. The switch is
 * read from localStorage when the page loads (Honmani's reader settings reload the page when
 * their screen closes), and writes one <style> element, or none while it is off:
 *
 * "honmaniEInk" = "1" (off by default): for e-ink screens. On a book page (the rules apply while
 * the page has `.book-content`, so the library keeps its colours) there are no CSS transitions or animations, no text or box shadows, plain black text on white (ttu's theme in
 * localStorage is not touched, so turning the mode off brings the theme back as it was), and the
 * marks in the text (word marks, the lookup mark, search hits) are greys as solid backgrounds
 * instead of colours. The WebView paints no text-decoration in ::highlight(), so the marks differ
 * by background alone. word-marks.js reads the switch too, to show its marks without a fade.
 */
(() => {
  'use strict';

  if (window.__honmaniReaderStyle) return;
  window.__honmaniReaderStyle = true;

  const EINK_KEY = 'honmaniEInk';
  const BOOK_ROOT_SELECTOR = '.book-content';
  const EINK_STYLE_ID = 'honmani-eink-style';

  const H = 'html:has(.book-content)';
  const EINK_CSS = `
    ${H} { --background-color: #ffffff !important; --font-color: #000000 !important; color-scheme: light; }
    ${H} body { background-color: #ffffff !important; color: #000000 !important; }
    ${H} body [style^="color:"], ${H} ${BOOK_ROOT_SELECTOR}, ${H} ${BOOK_ROOT_SELECTOR} * { color: #000000 !important; }
    ${H} *, ${H} *::before, ${H} *::after {
      transition: none !important; animation: none !important; scroll-behavior: auto !important;
      text-shadow: none !important; box-shadow: none !important;
    }
    ${H} ::highlight(honmani-learning), ${H} ::highlight(honmani-learning-fresh) { background-color: #c0c0c0 !important; }
    ${H} ::highlight(honmani-new), ${H} ::highlight(honmani-new-fresh) { background-color: #e0e0e0 !important; }
    ${H} ::highlight(honmani-due), ${H} ::highlight(honmani-due-fresh) { background-color: #9c9c9c !important; }
    ${H} ::highlight(honmani-one-unknown), ${H} ::highlight(honmani-one-unknown-fresh) { background-color: #ececec !important; }
    ${H} ::highlight(honmani-search-hit) { background-color: #8c8c8c !important; color: #000000 !important; }
    ${H} ::highlight(honmani-lookup) { background-color: #000000 !important; color: #ffffff !important; }`;

  function read(key) {
    try {
      return window.localStorage.getItem(key);
    } catch (error) {
      return null; // No storage: nothing is changed.
    }
  }

  function addStyle(id, css) {
    const style = document.createElement('style');
    style.id = id;
    style.textContent = css;
    document.documentElement.appendChild(style);
    return style;
  }

  function start() {
    if (read(EINK_KEY) === '1') addStyle(EINK_STYLE_ID, EINK_CSS);
  }

  if (document.documentElement) {
    start();
  } else {
    const observer = new MutationObserver(() => {
      if (!document.documentElement) return;
      observer.disconnect();
      start();
    });
    observer.observe(document, { childList: true });
  }
})();
