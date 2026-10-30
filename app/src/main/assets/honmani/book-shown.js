/*
 * Tells Honmani when an opened book is on screen, injected at document start into the
 * ッツ Ebook Reader (ttu) WebView.
 *
 * The app keeps its library over the reader while a book opens, so the reader's blank page and
 * loading spinner never flash up. Once ttu has laid out the book (its spinner is gone and book
 * text or an image is on screen, for books of pictures only), this posts { type: 'bookShown', id } (ttu's book id from the address), once
 * per book opened.
 *
 * ttu's settings are opened the same way, covered until drawn: once their headings are on screen
 * this posts { type: 'pageShown', path: '/settings' }, once per visit.
 */
(() => {
  'use strict';

  if (window.__honmaniBookShown) return;
  window.__honmaniBookShown = true;

  const BOOK_ROOT_SELECTOR = '.book-content';
  /** ttu's full-screen spinner while it lays out a book. */
  const SPINNER_SELECTOR = 'div.fixed.inset-0.text-7xl';
  const { SKIPPED_SELECTOR, bookId, onScreen } = window.honmaniText;
  const CHECK_MS = 50;
  const NAVIGATION_GRACE_MS = 3000;
  /** textAtPoints probes a (GRID - 1) × (GRID - 1) grid of points. */
  const GRID = 6;

  let shown = null;
  /** The book being opened and when it was first seen, for the diagnostics log. */
  let opening = null;
  let openedAt = 0;
  let settingsShown = false;

  /** Whether some book text (furigana aside) or an image is drawn on screen. */
  function contentOnScreen(root) {
    for (const image of root.querySelectorAll('img, svg image, image')) {
      if (onScreen(image.getBoundingClientRect())) return true;
    }
    return textAtPoints(root) || textOnScreen(root);
  }

  /**
   * Book text under a grid of points on the screen. Reopened far into a long chapter (its last
   * page, say), the text on screen comes after thousands of text nodes, past where
   * textOnScreen looks; this finds it directly.
   */
  function textAtPoints(root) {
    if (!document.caretRangeFromPoint) return false;
    const range = document.createRange();
    for (let row = 1; row < GRID; row++) {
      for (let column = 1; column < GRID; column++) {
        const caret = document.caretRangeFromPoint((innerWidth * column) / GRID, (innerHeight * row) / GRID);
        const node = caret && caret.startContainer;
        if (!node || node.nodeType !== Node.TEXT_NODE || !root.contains(node) || !node.data.trim()) continue;
        if (node.parentElement && node.parentElement.closest(SKIPPED_SELECTOR)) continue;
        // The caret can be the nearest text to a blank spot: the text itself must be on screen.
        range.selectNodeContents(node);
        if (Array.from(range.getClientRects()).some(onScreen)) return true;
      }
    }
    return false;
  }

  function textOnScreen(root) {
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
      acceptNode: (node) =>
        !node.data.trim() || (node.parentElement && node.parentElement.closest(SKIPPED_SELECTOR))
          ? NodeFilter.FILTER_REJECT
          : NodeFilter.FILTER_ACCEPT,
    });
    const range = document.createRange();
    // The first few text nodes are enough: the page shows the book from its reading position,
    // so look further only while nothing found is on screen, up to a limit.
    let checked = 0;
    for (let node = walker.nextNode(); node && checked < 400; node = walker.nextNode(), checked++) {
      range.selectNodeContents(node);
      if (Array.from(range.getClientRects()).some(onScreen)) return true;
    }
    return false;
  }

  function post(message) {
    // One more frame, so the page is painted before the app uncovers it.
    requestAnimationFrame(() => {
      if (window.honmani) window.honmani.postMessage(JSON.stringify(message));
    });
  }

  function checkSettings() {
    if (location.pathname !== '/settings') {
      settingsShown = false;
      return;
    }
    if (settingsShown) return;
    const heading = document.querySelector('h2');
    if (!heading || !onScreen(heading.getBoundingClientRect())) return;
    settingsShown = true;
    post({ type: 'pageShown', path: '/settings' });
  }

  function check() {
    checkSettings();
    const id = bookId();
    if (id === null) {
      shown = null;
      opening = null;
      return;
    }
    if (id === shown) return;
    if (id !== opening) {
      opening = id;
      openedAt = Date.now();
    }
    if (document.querySelector(SPINNER_SELECTOR)) return;
    const root = document.querySelector(BOOK_ROOT_SELECTOR);
    if (!root || !contentOnScreen(root)) return;
    shown = id;
    // Kept in "Share diagnostics": how long the library covered the book.
    console.log(`honmani book shown after ${Date.now() - openedAt} ms`);
    post({ type: 'bookShown', id });
  }

  /** Whether nothing is left to report until the next navigation. */
  function settled() {
    const id = bookId();
    if (id !== null) return id === shown;
    return location.pathname !== '/settings' || settingsShown;
  }

  // Checks run only while a book or the settings page is waiting to be shown: a timer left
  // running would wake the phone 20 times a second while reading, or with the app in the
  // background.
  let timer = null;
  /** Until when checks go on after a tap outside a book, which may still open one. */
  let expectingUntil = 0;
  function tick() {
    check();
    if (settled() && Date.now() > expectingUntil) {
      clearInterval(timer);
      timer = null;
    }
  }
  function startChecking() {
    if (timer === null) timer = setInterval(tick, CHECK_MS);
  }

  // Every navigation starts a new visit, also one too quick for the check to see the page between.
  const newVisit = () => {
    settingsShown = false;
    startChecking();
  };
  window.addEventListener('popstate', newVisit);
  // ttu's router keeps its own reference to pushState, so the patch below misses its page
  // changes; they all start with a click (also the app's, see library-bridge.js navigate). ttu
  // may change the page a while after the click (loading the book first), so a click outside a
  // book keeps the checks going for a moment even though nothing waits yet.
  window.addEventListener(
    'click',
    () => {
      if (bookId() === null) expectingUntil = Date.now() + NAVIGATION_GRACE_MS;
      startChecking();
    },
    true,
  );
  for (const method of ['pushState', 'replaceState']) {
    const original = history[method];
    history[method] = function (...args) {
      const result = original.apply(this, args);
      newVisit();
      return result;
    };
  }

  startChecking();
})();
