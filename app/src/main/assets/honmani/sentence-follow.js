/*
 * Marks one passage of the book as "the one being read" and keeps it on screen, injected at
 * document start into the ッツ Ebook Reader (ttu) WebView. It knows nothing about speech: read
 * aloud (read-aloud.js) uses it, and so can anything else that follows the text, such as an
 * audiobook with subtitles.
 *
 *   window.honmaniFollow.mark(range)         draws the "honmani-read-aloud" highlight over a Range
 *   window.honmaniFollow.clear()             removes it
 *   window.honmaniFollow.keepOnScreen(range) brings the start of the Range into view; resolves to
 *                                            true when it is on screen afterwards
 *
 * The mark is a CSS Custom Highlight: ttu's DOM, character counts and layout are never touched.
 * It draws below the word marks and the user's highlights (priority -4; theirs are -3 and up) with
 * a translucent tint, so those stay visible through it. Reader-style.js turns it grey in e-ink
 * mode; nothing here animates.
 *
 * Keeping on screen goes through the same path as the reader's own page turns: in paginated mode
 * ttu's keyboard shortcuts (ArrowDown forward, ArrowUp back, in both writing directions), so
 * ttu saves its bookmark and Honmani's statistics see a normal page turn; in continuous mode the
 * book's scroll box is scrolled so the start of the passage sits at the start edge.
 *
 * The reader can take control: a scroll or page turn that did not come from here (while a mark
 * is shown) detaches the view. The mark keeps moving, but keepOnScreen then does nothing, and
 * { type: 'follow.detached' } goes to the app. Our own turns run inside auto(fn) (keepOnScreen
 * does), and the moments around a resize count as ours too, so only the reader's gestures detach.
 *
 *   window.honmaniFollow.auto(fn)       runs fn (async) as our own scrolling
 *   window.honmaniFollow.detached()     whether the reader has taken control
 *   window.honmaniFollow.attach()       follows again; { type: 'follow.attach' } from the app does the same
 *                                       (the app then sends the passage again to bring it on screen)
 *   window.honmaniFollow.reattach()     follows again, and tells the app ({ type: 'follow.attached' })
 *
 * clear() follows again too: closing or restarting starts attached.
 */
(() => {
  'use strict';

  if (window.honmaniFollow) return;

  const HIGHLIGHT_NAME = 'honmani-read-aloud';
  const BOOK_ROOT_SELECTOR = '.book-content';
  const PRIORITY = -4;
  /** Pages turned at most for one passage: more means something is wrong, and the reader stays put. */
  const MAX_TURNS = 40;
  /** Continuous mode: how much of the screen's start edge is left free above the passage. */
  const MARGIN_FRACTION = 0.08;
  /** Continuous mode: a passage starting within this much of the screen's start edge is left be. */
  const COMFORT_FRACTION = 0.7;

  /** Scroll events our own turn can still cause after it ended (an animated page turn). */
  const AUTO_GRACE_MS = 500;
  /** A resize (the bar appearing, a rotation) lays the book out again, with scroll events of its own. */
  const RESIZE_GRACE_MS = 800;

  const hasApi = !!(window.CSS && CSS.highlights && window.Highlight);
  const nextFrame = () => new Promise((resolve) => requestAnimationFrame(() => resolve()));

  let styled = false;
  function addStyle() {
    if (styled) return;
    styled = true;
    const style = document.createElement('style');
    // Dark text stays readable on the tint in light and dark themes alike (it is translucent).
    style.textContent = `::highlight(${HIGHLIGHT_NAME}) { background-color: rgba(66, 133, 244, 0.38); }`;
    (document.head || document.documentElement).appendChild(style);
  }

  let marked = false;
  let detachedNow = false;
  let autoDepth = 0;
  let quietUntil = 0;

  function post(message) {
    const app = window.honmani;
    if (app && typeof app.postMessage === 'function') app.postMessage(JSON.stringify(message));
  }

  function mark(range) {
    if (!hasApi || !range) return;
    marked = true;
    addStyle();
    const highlight = new Highlight(range);
    highlight.priority = PRIORITY;
    CSS.highlights.set(HIGHLIGHT_NAME, highlight);
  }

  function clear() {
    marked = false;
    detachedNow = false;
    if (hasApi) CSS.highlights.delete(HIGHLIGHT_NAME);
  }

  /** Runs `fn` as our own scrolling: it does not detach the view. */
  async function auto(fn) {
    autoDepth++;
    try {
      return await fn();
    } finally {
      autoDepth--;
      quietUntil = Math.max(quietUntil, Date.now() + AUTO_GRACE_MS);
    }
  }

  function onScroll() {
    if (!marked || detachedNow || autoDepth > 0 || Date.now() < quietUntil) return;
    detachedNow = true;
    post({ type: 'follow.detached' });
  }

  function reattach() {
    if (!detachedNow) return;
    detachedNow = false;
    post({ type: 'follow.attached' });
  }

  window.addEventListener('scroll', onScroll, { capture: true, passive: true });
  window.addEventListener('resize', () => {
    quietUntil = Math.max(quietUntil, Date.now() + RESIZE_GRACE_MS);
  });
  if (window.honmani && window.honmani.addEventListener) {
    window.honmani.addEventListener('message', (event) => {
      if (typeof event.data === 'string' && event.data.includes('"follow.attach"')) detachedNow = false;
    });
  }

  function isPaginated() {
    try {
      return (localStorage.getItem('viewMode') || 'paginated') === 'paginated';
    } catch (error) {
      return true;
    }
  }

  /** The first rect of the range that has a size, or null. */
  function firstRect(range) {
    return Array.from(range.getClientRects()).find((rect) => rect.width > 0 && rect.height > 0) || null;
  }

  /** Whether the start of the range is on the page: its first rect lies within the viewport. */
  function startOnScreen(range) {
    const rect = firstRect(range);
    return !!rect && rect.right > 0 && rect.left < innerWidth && rect.bottom > 0 && rect.top < innerHeight;
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

  /** 1 when the start of `range` lies after the page on screen (turn forward), -1 when before it. */
  function directionOf(range) {
    const root = document.querySelector(BOOK_ROOT_SELECTOR);
    const first = root && window.honmaniText.firstCharOnScreen(root, 'rt, rp', false);
    if (!first) return 1;
    // comparePoint: -1 when the page's first character is before the range.
    return range.comparePoint(first.node, first.offset) < 0 ? 1 : -1;
  }

  async function keepPaginated(range) {
    for (let turns = 0; turns < MAX_TURNS && !startOnScreen(range); turns++) {
      if (!range.startContainer.isConnected) return false;
      const code = directionOf(range) > 0 ? 'ArrowDown' : 'ArrowUp';
      window.dispatchEvent(new KeyboardEvent('keydown', { code, key: code, bubbles: true }));
      await nextFrame();
      await nextFrame();
    }
    return startOnScreen(range);
  }

  async function keepContinuous(range) {
    const rect = firstRect(range);
    if (!rect) return false;
    const root = document.querySelector(BOOK_ROOT_SELECTOR);
    if (!root) return false;
    const vertical = getComputedStyle(root).writingMode.startsWith('vertical');
    const scroller = scrollerOf(root);
    // Already in the upper part of the screen (the right part in vertical text): leave it be.
    if (vertical) {
      const distance = innerWidth - rect.right;
      if (distance >= 0 && distance <= innerWidth * COMFORT_FRACTION && rect.left >= 0) return true;
      // Text runs from the right edge to the left; scrolling left is negative (as keep-position.js).
      scroller.scrollBy({ left: rect.right - innerWidth + innerWidth * MARGIN_FRACTION, behavior: 'instant' });
    } else {
      if (rect.top >= 0 && rect.top <= innerHeight * COMFORT_FRACTION && rect.bottom <= innerHeight) return true;
      scroller.scrollBy({ top: rect.top - innerHeight * MARGIN_FRACTION, behavior: 'instant' });
    }
    await nextFrame();
    await nextFrame();
    return startOnScreen(range);
  }

  /** Brings the start of the range into view. */
  async function keepOnScreen(range) {
    if (detachedNow || !range || !range.startContainer.isConnected) return false;
    return auto(() => (isPaginated() ? keepPaginated(range) : keepContinuous(range)));
  }

  window.honmaniFollow = Object.freeze({
    HIGHLIGHT_NAME, mark, clear, keepOnScreen, startOnScreen, auto, reattach,
    detached: () => detachedNow,
    attach: () => { detachedNow = false; },
  });
})();
