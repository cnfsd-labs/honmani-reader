/*
 * Helpers the other Honmani scripts share, injected at document start into the ッツ Ebook Reader
 * (ttu) WebView. It only defines `window.honmaniText`, and does nothing else.
 *
 * Load order: this script must come first in DOCUMENT_START_SCRIPTS (ReaderWebView.kt) and in
 * every Playwright setup that injects the other scripts (web-tests registers it in a
 * beforeEach); the others read the object when they start.
 *
 * Only code that is identical in every script that used it lives here. Where copies differ they
 * stay in their scripts: keep-position.js and viewport-log.js skip only rt and rp (not script
 * and style); book-shown.js and book-end.js let a text node without a parent through; word-marks.js
 * caches isBlock per element, while the tap bridge must see a style change at once.
 */
(() => {
  'use strict';

  if (window.honmaniText) return;

  /** Book text that is not read: furigana and anything that is not text. */
  const SKIPPED_SELECTOR = 'rt, rp, script, style';

  /** Whether `node` (a text node) is in furigana, a script or a style, or has no parent. */
  function isSkipped(node) {
    const parent = node.parentElement;
    return !parent || parent.closest(SKIPPED_SELECTOR) !== null;
  }

  /** Whether the element starts a new line: anything that is not inline, ruby or display: contents. */
  function isBlock(element) {
    const display = getComputedStyle(element).display;
    return !display.startsWith('inline') && !display.startsWith('ruby') && display !== 'contents';
  }

  /**
   * The nearest block around `node`, or `root`. `test` decides what a block is; word-marks.js
   * passes a cached isBlock.
   */
  function blockAncestor(node, root, test = isBlock) {
    for (let element = node.parentElement; element && element !== root; element = element.parentElement) {
      if (test(element)) return element;
    }
    return root;
  }

  /** ttu's book id from the address (/b?id=7), or null outside a book. */
  function bookId() {
    if (!location.pathname.startsWith('/b')) return null;
    const id = Number(new URLSearchParams(location.search).get('id'));
    return Number.isInteger(id) && id > 0 ? id : null;
  }

  /** ttu keeps the progress as a number or a string such as "12.3%". */
  function toFraction(progress) {
    const value = typeof progress === 'string' ? parseFloat(progress) / (progress.includes('%') ? 100 : 1) : progress;
    return typeof value === 'number' && isFinite(value) ? Math.min(1, Math.max(0, value)) : null;
  }

  /** Whether a client rect is at least partly in the viewport and has a size. */
  function onScreen(rect) {
    return rect.width > 0 && rect.height > 0 && rect.right > 0 && rect.left < innerWidth &&
      rect.bottom > 0 && rect.top < innerHeight;
  }

  window.honmaniText = { SKIPPED_SELECTOR, isSkipped, isBlock, blockAncestor, bookId, toFraction, onScreen };
})();
