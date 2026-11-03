/*
 * The page's half of Honmani's in-book search, injected at document start into the
 * ッツ Ebook Reader (ttu) WebView.
 *
 * The app searches the book file itself (ttu shows one chapter at a time in paginated mode, so
 * the page cannot see the whole book) and opens a hit the way progress sync opens a position:
 * it saves ttu's bookmark (library-bridge.js setBookmark) and reloads the page, where ttu
 * restores the bookmark in either reading mode and writing direction.
 *
 *   honmaniSearch.reload({ before, match })
 *     Remembers the hit for the next page load and reloads. Once ttu has laid the book out, the
 *     hit is marked for a moment: `match` preceded by the `before` letters (blanks aside; the
 *     match alone if that is not on screen), the first one on screen.
 *   honmaniSearch.markOnScreen({ before, match })
 *     Marks that text on screen now; true if it was there. Furigana is not part of the text.
 *
 * The mark is a CSS highlight (background only: Android's WebView paints nothing else there).
 */
(() => {
  'use strict';

  if (window.honmaniSearch) return;

  const PENDING_KEY = 'honmaniSearchHit';
  const HIGHLIGHT_NAME = 'honmani-search-hit';
  const BOOK_ROOT_SELECTOR = '.book-content';
  /** ttu's full-screen spinner while it lays out a book. */
  const SPINNER_SELECTOR = 'div.fixed.inset-0.text-7xl';
  const { SKIPPED_SELECTOR, onScreen, textData, firstCharOnScreen } = window.honmaniText;
  const POLL_MS = 150;
  const GIVE_UP_MS = 15000;
  const MARK_MS = 3000;
  /** How long ttu gets to scroll to the bookmark before the hit is looked for on later pages. */
  const SETTLE_MS = 1000;
  const MAX_TURNS = 8;
  /** Text nodes without a box in a row that end the walk over the page (the next page is off screen). */
  const MAX_UNSEEN = 40;
  const MAX_NODES = 3000;

  const hasHighlightApi = () => Boolean(window.CSS && CSS.highlights && window.Highlight);

  function isPaginated() {
    try {
      return (localStorage.getItem('viewMode') || 'paginated') === 'paginated';
    } catch (error) {
      return true;
    }
  }

  /** The text on screen without blanks, each letter with where it is: { text, nodes, offsets }. */
  function screenText(root) {
    const first = firstCharOnScreen(root, SKIPPED_SELECTOR, !isPaginated());
    if (!first) return null;
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
      acceptNode: (node) =>
        node.parentElement && node.parentElement.closest(SKIPPED_SELECTOR) ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT,
    });
    walker.currentNode = first.node;
    const range = document.createRange();
    const letters = [];
    const nodes = [];
    const offsets = [];
    let unseen = 0;
    let count = 0;
    for (let node = first.node; node && count < MAX_NODES; node = walker.nextNode(), count++) {
      range.selectNodeContents(node);
      const rects = Array.from(range.getClientRects());
      if (!rects.some(onScreen)) {
        if (++unseen > MAX_UNSEEN) break;
        continue;
      }
      unseen = 0;
      // A paragraph that runs on to the next page is only partly on screen: look at each letter.
      const partly = rects.some((rect) => rect.left < 0 || rect.top < 0 || rect.right > innerWidth || rect.bottom > innerHeight);
      const data = textData(node);
      for (let i = 0; i < data.length; i++) {
        if (/\s/.test(data[i])) continue;
        if (partly) {
          range.setStart(node, i);
          range.setEnd(node, i + 1);
          if (!Array.from(range.getClientRects()).some(onScreen)) continue;
        }
        letters.push(data[i]);
        nodes.push(node);
        offsets.push(i);
      }
    }
    return { text: letters.join(''), nodes, offsets };
  }

  const stripped = (text) => String(text || '').replace(/\s+/g, '');

  /** Marks `before` + `match` (else `match`) on screen; true if found. */
  function markOnScreen({ before, match } = {}) {
    const needle = stripped(match);
    const root = document.querySelector(BOOK_ROOT_SELECTOR);
    if (!needle || !root || !hasHighlightApi()) return false;
    const seen = screenText(root);
    if (!seen) return false;
    addStyle();
    const lead = stripped(before);
    let start = lead ? seen.text.indexOf(lead + needle) : -1;
    if (start >= 0) start += lead.length;
    else start = seen.text.indexOf(needle);
    if (start < 0) return false;
    const end = start + needle.length - 1;
    const range = document.createRange();
    range.setStart(seen.nodes[start], seen.offsets[start]);
    range.setEnd(seen.nodes[end], seen.offsets[end] + 1);
    CSS.highlights.set(HIGHLIGHT_NAME, new Highlight(range));
    clearTimeout(markTimer);
    markTimer = setTimeout(() => CSS.highlights.delete(HIGHLIGHT_NAME), MARK_MS);
    return true;
  }
  let markTimer = 0;

  let styled = false;
  function addStyle() {
    if (styled) return;
    styled = true;
    const style = document.createElement('style');
    // Dark text on a warm tint, whatever the book's theme.
    style.textContent = `::highlight(${HIGHLIGHT_NAME}) { background-color: rgba(255, 193, 7, 0.75); color: #000; }`;
    (document.head || document.documentElement).appendChild(style);
  }

  function reload(hit) {
    try {
      sessionStorage.setItem(PENDING_KEY, JSON.stringify({ before: hit.before, match: hit.match }));
    } catch (error) {
      // No storage: the page still opens at the hit, only unmarked.
    }
    window.location.reload();
  }

  const nextFrame = () => new Promise((resolve) => requestAnimationFrame(() => resolve()));
  const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

  /**
   * ttu opens a paginated book at the page where the *paragraph* of the bookmark starts, so a hit
   * late in a long paragraph is a page or more further on: turn pages until it shows.
   */
  async function turnToHit(hit) {
    for (let turn = 0; turn < MAX_TURNS; turn++) {
      // ttu's paginated reader turns pages on ArrowDown in both writing modes (as keep-position.js).
      window.dispatchEvent(new KeyboardEvent('keydown', { code: 'ArrowDown', key: 'ArrowDown', bubbles: true }));
      await nextFrame();
      await nextFrame();
      if (markOnScreen(hit)) return true;
    }
    return false;
  }

  /** After a reload from reload(): waits for the book, then marks the hit. */
  async function markPending() {
    let hit = null;
    try {
      hit = JSON.parse(sessionStorage.getItem(PENDING_KEY) || 'null');
      sessionStorage.removeItem(PENDING_KEY);
    } catch (error) {
      return;
    }
    if (!hit || !location.pathname.startsWith('/b')) return;
    const startedAt = Date.now();
    let readyAt = 0;
    while (Date.now() - startedAt < GIVE_UP_MS) {
      await sleep(POLL_MS);
      if (!document.querySelector(BOOK_ROOT_SELECTOR) || document.querySelector(SPINNER_SELECTOR)) continue;
      if (markOnScreen(hit)) return;
      readyAt = readyAt || Date.now();
      // Not there once ttu has had time to scroll to the bookmark: it is further on.
      if (Date.now() - readyAt > SETTLE_MS) {
        if (isPaginated()) await turnToHit(hit);
        return;
      }
    }
  }

  markPending();
  window.honmaniSearch = Object.freeze({ reload, markOnScreen });
})();
