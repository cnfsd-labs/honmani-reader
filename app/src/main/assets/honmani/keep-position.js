/*
 * Honmani keeps the reading position when the reader is resized (e.g. the phone is rotated),
 * injected at document start into the ッツ Ebook Reader WebView.
 *
 * ttu restores its position after a resize by paragraph, so with paragraphs longer than a screen
 * the text the user was reading can end up pages away. This script remembers the first character
 * on screen whenever the user has moved on, and once ttu has finished its own restore after a
 * resize, brings that character back: in paginated mode by turning pages with ttu's keyboard
 * shortcuts (which keeps ttu's bookmark and statistics consistent), in continuous mode by
 * scrolling it to the start of the screen.
 */
(() => {
  'use strict';

  if (window.__honmaniKeepPosition) return;
  window.__honmaniKeepPosition = true;

  const BOOK_ROOT_SELECTOR = '.book-content';
  const SKIPPED_SELECTOR = 'rt, rp';
  /** ttu's own event, dispatched on document when the reading position changes. */
  const PAGE_CHANGE = 'ttsu:page.change';
  /** Quiet time after the last resize event; by then ttu has laid out the book again. */
  const SETTLE_MS = 700;
  const REMEMBER_MS = 300;
  const MAX_TURNS = 50;

  /** The first character on screen when the user last moved on: { node, offset }. */
  let anchor = null;
  let resizing = false;
  let restoring = false;
  let rememberTimer = 0;
  let settleTimer = 0;

  function isPaginated() {
    try {
      return (localStorage.getItem('viewMode') || 'paginated') === 'paginated';
    } catch (error) {
      return true;
    }
  }

  function onScreen(rect) {
    return rect.width > 0 && rect.right > 0 && rect.left < innerWidth && rect.bottom > 0 && rect.top < innerHeight;
  }

  function rangeOnScreen(range) {
    return Array.from(range.getClientRects()).some(onScreen);
  }

  function charOnScreen(position) {
    if (!position.node.isConnected || position.offset >= position.node.data.length) return false;
    const range = document.createRange();
    range.setStart(position.node, position.offset);
    range.setEnd(position.node, position.offset + 1);
    return rangeOnScreen(range);
  }

  /**
   * The first character of book text on screen (furigana skipped), or null. In continuous mode
   * the search starts at the screen's start edge, not at the book's first character.
   */
  function firstCharOnScreen() {
    const root = document.querySelector(BOOK_ROOT_SELECTOR);
    if (!root) return null;
    return window.honmaniText.firstCharOnScreen(root, SKIPPED_SELECTOR, !isPaginated());
  }

  /** Negative if `a` comes before `b` in the book, 0 if equal, positive after. */
  function compare(a, b) {
    const range = document.createRange();
    range.setStart(b.node, b.offset);
    return range.comparePoint(a.node, a.offset);
  }

  const nextFrame = () => new Promise((resolve) => requestAnimationFrame(() => resolve()));

  /** Called when the user may have moved on: turned a page, scrolled, tapped. */
  function rememberLater() {
    clearTimeout(rememberTimer);
    rememberTimer = setTimeout(() => {
      if (resizing || restoring) return;
      // Keep the anchor while it is on screen, so that a tap or a restore never moves it back.
      if (anchor && charOnScreen(anchor)) return;
      anchor = firstCharOnScreen();
    }, REMEMBER_MS);
  }

  /** The element that scrolls the book in continuous mode. */
  function scrollerOf(element) {
    for (let current = element; current; current = current.parentElement) {
      const style = getComputedStyle(current);
      const scrollsY = /auto|scroll/.test(style.overflowY) && current.scrollHeight > current.clientHeight;
      const scrollsX = /auto|scroll/.test(style.overflowX) && current.scrollWidth > current.clientWidth;
      if (scrollsX || scrollsY) return current;
    }
    return document.scrollingElement;
  }

  /** Continuous mode: scrolls so that the anchor starts the screen (top, or right in vertical text). */
  function scrollToAnchor() {
    const range = document.createRange();
    range.setStart(anchor.node, anchor.offset);
    range.setEnd(anchor.node, anchor.offset + 1);
    const rect = Array.from(range.getClientRects()).find((r) => r.width > 0 && r.height > 0);
    if (!rect) return;
    const root = document.querySelector(BOOK_ROOT_SELECTOR);
    const vertical = getComputedStyle(root).writingMode.startsWith('vertical');
    const scroller = scrollerOf(root);
    if (vertical) scroller.scrollBy({ left: rect.right - innerWidth, behavior: 'instant' });
    else scroller.scrollBy({ top: rect.top, behavior: 'instant' });
  }

  async function restore() {
    if (!anchor || !anchor.node.isConnected || charOnScreen(anchor)) return;
    restoring = true;
    try {
      if (!isPaginated()) {
        scrollToAnchor();
        await nextFrame();
        return;
      }
      let lastDirection = 0;
      let reversals = 0;
      for (let turn = 0; turn < MAX_TURNS && !charOnScreen(anchor); turn++) {
        const first = firstCharOnScreen();
        if (!first) return;
        const direction = compare(first, anchor) < 0 ? 1 : -1;
        if (lastDirection && direction !== lastDirection && ++reversals > 1) return;
        lastDirection = direction;

        // ttu's paginated reader turns pages on ArrowDown / ArrowUp in both writing modes.
        const code = direction > 0 ? 'ArrowDown' : 'ArrowUp';
        window.dispatchEvent(new KeyboardEvent('keydown', { code, key: code, bubbles: true }));
        await nextFrame();
        await nextFrame();

        const now = firstCharOnScreen();
        if (!now || (now.node === first.node && now.offset === first.offset)) return;
      }
    } finally {
      restoring = false;
    }
  }

  function onResize() {
    if (!document.querySelector(BOOK_ROOT_SELECTOR)) return;
    resizing = true;
    clearTimeout(rememberTimer);
    clearTimeout(settleTimer);
    settleTimer = setTimeout(() => {
      resizing = false;
      restore();
    }, SETTLE_MS);
  }

  document.addEventListener(PAGE_CHANGE, rememberLater);
  for (const type of ['click', 'keyup', 'touchend', 'wheel', 'scroll']) {
    window.addEventListener(type, rememberLater, { capture: true, passive: true });
  }
  window.addEventListener('resize', onResize);
  if (window.visualViewport) window.visualViewport.addEventListener('resize', onResize);
})();
