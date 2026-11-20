/*
 * Marks one passage of the book as "the one being read" and keeps it on screen, injected at
 * document start into the ッツ Ebook Reader (ttu) WebView. It knows nothing about speech: read
 * aloud (read-aloud.js) uses it, and so can anything else that follows the text, such as an
 * audiobook with subtitles.
 *
 *   window.honmaniFollow.mark(range)         draws the "honmani-read-aloud" highlight over a Range
 *   window.honmaniFollow.clear()             removes it
 *   window.honmaniFollow.keepOnScreen(range) brings the start of the Range into view; resolves to
 *                                            true when it is on screen afterwards; a newer
 *                                            call supersedes one still turning pages
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
 * does when it has to move the page), and the moments around a resize count as ours too, so only
 * the reader's gestures detach. A voice reports several words a second: a mark that moves nothing
 * must not go through auto(), or the reader's gesture always falls in its grace time.
 *
 *   window.honmaniFollow.auto(fn)       runs fn (async) as our own scrolling
 *   window.honmaniFollow.detached()     whether the reader has taken control
 *   window.honmaniFollow.attach()       follows again; { type: 'follow.attach' } from the app does the same
 *                                       (the app then sends the passage again to bring it on screen)
 *   window.honmaniFollow.reattach()     follows again, and tells the app ({ type: 'follow.attached' })
 *   window.honmaniFollow.following()    whether a mark is shown and the view still goes with it
 *   window.honmaniFollow.keepMarkOnScreen()  brings the current mark back after a new layout
 *
 * clear() follows again too: closing or restarting starts attached. If the view was still going
 * with the mark, it dispatches 'honmani:follow.end' on document with the marked Range as detail
 * (keep-position.js then keeps the start of that passage on screen through a resize).
 */
(() => {
  'use strict';

  if (window.honmaniFollow) return;

  const HIGHLIGHT_NAME = 'honmani-read-aloud';
  const FOLLOW_END = 'honmani:follow.end';
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
  let markedRange = null;
  let detachedNow = false;
  let autoDepth = 0;
  let quietUntil = 0;
  /** Counts keepOnScreen calls: an earlier call still turning pages stops when a newer one starts. */
  let keeping = 0;

  // With the screen off (read aloud or the audiobook playing on) the page is hidden and draws no
  // frames, yet our page turns wait for frames, and so does ttu to show the next section. While
  // our own turns run in a hidden page, frame callbacks run as message tasks instead (timers are
  // throttled there), also those asked for just before the page hid. Elsewhere frames stay as
  // they are, so nothing animates on and on with the screen off.
  const nativeFrame = window.requestAnimationFrame.bind(window);
  const frameCallbacks = new Map();
  let lastFrameId = 0;
  let frameTaskQueued = false;
  const frameChannel = new MessageChannel();
  const framesInTasks = () => autoDepth > 0 && document.hidden;
  const runFrameCallbacks = (id, time) => {
    const callback = frameCallbacks.get(id);
    if (!callback) return;
    frameCallbacks.delete(id);
    callback(time);
  };
  frameChannel.port1.onmessage = () => {
    frameTaskQueued = false;
    const time = performance.now();
    for (const id of [...frameCallbacks.keys()]) runFrameCallbacks(id, time);
  };
  const queueFrameTask = () => {
    if (frameTaskQueued || frameCallbacks.size === 0) return;
    frameTaskQueued = true;
    frameChannel.port2.postMessage(null);
  };
  window.requestAnimationFrame = (callback) => {
    const id = ++lastFrameId;
    frameCallbacks.set(id, callback);
    if (framesInTasks()) queueFrameTask();
    else nativeFrame((time) => runFrameCallbacks(id, time));
    return id;
  };
  window.cancelAnimationFrame = (id) => {
    frameCallbacks.delete(id);
  };
  document.addEventListener('visibilitychange', () => {
    if (framesInTasks()) queueFrameTask();
  });

  function post(message) {
    const app = window.honmani;
    if (app && typeof app.postMessage === 'function') app.postMessage(JSON.stringify(message));
  }

  function mark(range) {
    if (!hasApi || !range) return;
    marked = true;
    markedRange = range;
    addStyle();
    const highlight = new Highlight(range);
    highlight.priority = PRIORITY;
    CSS.highlights.set(HIGHLIGHT_NAME, highlight);
  }

  function clear() {
    // The view went with the passage: that is where the reader is now, not the first character of
    // the page (a resize right after, such as the read aloud bar going away, keeps it on screen).
    if (marked && !detachedNow && markedRange) {
      document.dispatchEvent(new CustomEvent(FOLLOW_END, { detail: markedRange }));
    }
    marked = false;
    markedRange = null;
    detachedNow = false;
    if (hasApi) CSS.highlights.delete(HIGHLIGHT_NAME);
  }

  /** Runs `fn` as our own scrolling: it does not detach the view. */
  async function auto(fn) {
    autoDepth++;
    if (framesInTasks()) queueFrameTask();
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

  /**
   * The first rect of the range that has a size, or null. Some fonts give a single character in
   * vertical text a box of no height (WenQuanYi Zen Hei): it still shows where the character is.
   */
  function firstRect(range) {
    const rects = Array.from(range.getClientRects());
    return rects.find((rect) => rect.width > 0 && rect.height > 0)
      || rects.find((rect) => rect.width > 0 || rect.height > 0)
      || null;
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

  async function keepPaginated(range, token) {
    if (startOnScreen(range)) return true;
    return auto(async () => {
      for (let turns = 0; turns < MAX_TURNS && !startOnScreen(range); turns++) {
        // A newer call took over (the next passage, a new layout): the latest one turns the pages.
        if (!range.startContainer.isConnected || token !== keeping) return false;
        const code = directionOf(range) > 0 ? 'ArrowDown' : 'ArrowUp';
        window.dispatchEvent(new KeyboardEvent('keydown', { code, key: code, bubbles: true }));
        await nextFrame();
        await nextFrame();
      }
      return startOnScreen(range);
    });
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
    } else if (rect.top >= 0 && rect.top <= innerHeight * COMFORT_FRACTION && rect.bottom <= innerHeight) {
      return true;
    }
    return auto(async () => {
      if (vertical) {
        // Text runs from the right edge to the left; scrolling left is negative (as keep-position.js).
        scroller.scrollBy({ left: rect.right - innerWidth + innerWidth * MARGIN_FRACTION, behavior: 'instant' });
      } else {
        scroller.scrollBy({ top: rect.top - innerHeight * MARGIN_FRACTION, behavior: 'instant' });
      }
      await nextFrame();
      await nextFrame();
      return startOnScreen(range);
    });
  }

  /** Brings the start of the range into view. */
  async function keepOnScreen(range) {
    const token = ++keeping;
    if (detachedNow || !range || !range.startContainer.isConnected) return false;
    return isPaginated() ? keepPaginated(range, token) : keepContinuous(range);
  }

  window.honmaniFollow = Object.freeze({
    HIGHLIGHT_NAME, mark, clear, keepOnScreen, startOnScreen, auto, reattach,
    detached: () => detachedNow,
    following: () => marked && !detachedNow,
    keepMarkOnScreen: () => keepOnScreen(markedRange),
    attach: () => { detachedNow = false; },
  });
})();
