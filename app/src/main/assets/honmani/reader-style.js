/*
 * Honmani's reader looks that go beyond ttu's settings, injected at document start. Both are
 * read from localStorage when the page loads (Honmani's reader settings reload the page when
 * their screen closes), and each writes one <style> element, or none while it is off:
 *
 * "honmaniEInk" = "1" (off by default): for e-ink screens. On a book page (the rules apply while
 * the page has `.book-content`, so the library keeps its colours) there are no CSS transitions or
 * animations, no text or box shadows, plain black text on white (ttu's theme in
 * localStorage is not touched, so turning the mode off brings the theme back as it was), and the
 * marks in the text (word marks, the lookup mark, search hits) are greys as solid backgrounds
 * instead of colours. The reader's own highlights (highlights.js) are four solid light greys,
 * lighter than the lookup mark; where a word mark lies over one, the word mark's solid grey hides it.
 * The WebView paints no text-decoration in ::highlight(), so the marks differ
 * by background alone. word-marks.js reads the switch too, to show its marks without a fade.
 *
 * "honmaniCustomCss": the reader's own CSS for the book text (empty by default; the app caps it at
 * 20 KB). It is wrapped in a rule for the book's root element, `.book-content { ... }` (CSS
 * nesting), so a selector like `p` reaches only paragraphs of the book and never ttu's menus or
 * Honmani's overlays; `html` or `body` selectors match nothing, and of the at-rules only
 * conditional ones such as @media nest. The text is not used when the wrapper would not stay one
 * rule (a stray `}` that could end it early), when the WebView does not know nesting, or when it
 * is too long. The page's Content-Security-Policy has no style-src, so the inline style applies;
 * url() loads nothing: measured on the 7 Pro, a local server saw no request for url() in a
 * background, content, border-image or @font-face, nor for a plain Image() (the WebView blocks them).
 */
(() => {
  'use strict';

  if (window.__honmaniReaderStyle) return;
  window.__honmaniReaderStyle = true;

  const EINK_KEY = 'honmaniEInk';
  const CSS_KEY = 'honmaniCustomCss';
  const BOOK_ROOT_SELECTOR = '.book-content';
  /** The most CSS (UTF-8 bytes) the reader's own style may have; the app's field has the same cap. */
  const MAX_CUSTOM_CSS_BYTES = 20 * 1024;
  const EINK_STYLE_ID = 'honmani-eink-style';
  const CUSTOM_STYLE_ID = 'honmani-custom-css';

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
    ${H} ::highlight(honmani-lookup) { background-color: #000000 !important; color: #ffffff !important; }
    ${H} ::highlight(honmani-hl-0) { background-color: #dcdcdc !important; }
    ${H} ::highlight(honmani-hl-1) { background-color: #c8c8c8 !important; }
    ${H} ::highlight(honmani-hl-2) { background-color: #b4b4b4 !important; }
    ${H} ::highlight(honmani-hl-3) { background-color: #a0a0a0 !important; }`;

  function read(key) {
    try {
      return window.localStorage.getItem(key);
    } catch (error) {
      return null; // No storage: nothing is changed.
    }
  }

  function utf8Length(text) {
    return new TextEncoder().encode(text).length;
  }

  /** The reader's CSS scoped to the book's root, or null if there is none or it cannot be used safely. */
  function scopedCss(css) {
    if (typeof css !== 'string' || css.trim() === '' || utf8Length(css) > MAX_CUSTOM_CSS_BYTES) return null;
    if (!window.CSS || !CSS.supports('selector(&)')) return null;
    const wrapped = `${BOOK_ROOT_SELECTOR} {\n${css}\n}`;
    try {
      const sheet = new CSSStyleSheet();
      sheet.replaceSync(wrapped);
      // Anything that ended the wrapper early shows as a second rule.
      return sheet.cssRules.length === 1 ? wrapped : null;
    } catch (error) {
      return null;
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

    const css = scopedCss(read(CSS_KEY));
    if (css !== null) {
      const style = addStyle(CUSTOM_STYLE_ID, css);
      // After ttu's own stylesheets, which all sit before the end of <body>, so equal rules lose.
      document.addEventListener('DOMContentLoaded', () => document.body && document.body.appendChild(style), { once: true });
    }
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
