/*
 * Tells Honmani when the end of a book is on screen, injected at document start into the
 * ッツ Ebook Reader (ttu) WebView.
 *
 * ttu's progress is where the current page starts, so a book read to its last page stays below
 * 100%. When the last character of the book is on screen, this posts
 * { type: 'library.finished', id } (ttu's book id from the address), once per book and page load;
 * the library then counts the book as finished.
 *
 * Books were sometimes marked finished on their first pages, with the last character reported on
 * screen. So ttu's own count must agree: its page change events carry the characters before the
 * current page and in the whole book. In continuous mode the page holds the whole book and at
 * most a page's worth (MAX_PAGE_CHARS) may be left. In paginated mode it holds only the current
 * chapter, so its last character is on screen at every chapter end: there the characters on
 * screen must take the count to the book's end (END_SLACK_CHARS for rounding), and without
 * ttu's counts nothing is reported. A refused end is logged for "Share diagnostics".
 */
(() => {
  'use strict';

  if (window.__honmaniBookEnd) return;
  window.__honmaniBookEnd = true;

  const BOOK_ROOT_SELECTOR = '.book-content';
  /** Only in ttu's paginated reader, which renders one chapter at a time. */
  const PAGINATED_SELECTOR = '.book-content-container';
  const SKIPPED_SELECTOR = 'rt, rp, script, style';
  const CHECK_MS = 400;
  /** ttu's own event, dispatched on document when the reading position changes. */
  const PAGE_CHANGE = 'ttsu:page.change';
  /** More characters than any one page shows, even with small text on a tablet. */
  const MAX_PAGE_CHARS = 3000;
  /**
   * Characters a paginated count may be off by: ttu counts per text node, and splits long ones
   * into pieces of up to 120 characters (patch 0005).
   */
  const END_SLACK_CHARS = 120;
  /** ttu's character count: what it doesn't count (get-character-count.ts). */
  const NOT_COUNTED =
    /[^0-9A-Z○◯々-〇〻ぁ-ゖゝ-ゞァ-ヺー０-９Ａ-Ｚｦ-ﾝ\p{Radical}\p{Unified_Ideograph}]+/gimu;

  let reported = null;
  let timer = 0;
  /** ttu's counts from its page change events; null until ttu has sent them. */
  let explored = null;
  let total = null;
  let refusedLogged = null;

  function bookId() {
    if (!location.pathname.startsWith('/b')) return null;
    const id = Number(new URLSearchParams(location.search).get('id'));
    return Number.isInteger(id) && id > 0 ? id : null;
  }

  function onScreen(rect) {
    return rect.width > 0 && rect.height > 0 && rect.right > 0 && rect.left < innerWidth &&
      rect.bottom > 0 && rect.top < innerHeight;
  }

  /** The last character of book text that isn't blank (furigana skipped): { node, offset }. */
  function lastChar(root) {
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
      acceptNode: (node) =>
        node.parentElement && node.parentElement.closest(SKIPPED_SELECTOR)
          ? NodeFilter.FILTER_REJECT
          : NodeFilter.FILTER_ACCEPT,
    });
    let last = null;
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      if (node.data.trim()) last = node;
    }
    if (!last) return null;
    let offset = last.data.length - 1;
    while (offset > 0 && !last.data[offset].trim()) offset--;
    return { node: last, offset };
  }

  /** Whether the last character in the page (the chapter's, in paginated mode) is on screen. */
  function endOnScreen(root) {
    const end = lastChar(root);
    if (!end) return false;
    const range = document.createRange();
    range.setStart(end.node, end.offset);
    range.setEnd(end.node, end.offset + 1);
    return Array.from(range.getClientRects()).some(onScreen);
  }

  function rectsOnScreen(node) {
    const range = document.createRange();
    range.selectNode(node);
    return Array.from(range.getClientRects()).some(onScreen);
  }

  function isGaiji(node) {
    return node instanceof HTMLImageElement && Array.from(node.classList).some((name) => name.includes('gaiji'));
  }

  /** ttu's counted text nodes and gaiji images, in order (get-paragraph-nodes.ts). */
  function countedNodes(root) {
    const nodes = [];
    const visit = (parent) => {
      for (const child of parent.childNodes) {
        if (child.nodeType === Node.TEXT_NODE) {
          if (child.data.replace(/\s/g, '')) nodes.push(child);
        } else if (isGaiji(child)) {
          nodes.push(child);
        } else if (child instanceof Element && child.nodeName !== 'RT' &&
          !child.hasAttribute('aria-hidden') && !child.hasAttribute('hidden')) {
          visit(child);
        }
      }
    };
    visit(root);
    return nodes;
  }

  function charCount(node) {
    return isGaiji(node) ? 1 : Array.from(node.data.replace(NOT_COUNTED, '')).length;
  }

  /**
   * ttu's count of the characters on screen, from the chapter's end back: ttu's count before
   * the page stops at the first text node not wholly before it, so this counts whole nodes.
   */
  function charsOnScreen(root) {
    const nodes = countedNodes(root);
    let count = 0;
    for (let i = nodes.length - 1; i >= 0; i--) {
      if (!rectsOnScreen(nodes[i])) break;
      count += charCount(nodes[i]);
    }
    return count;
  }

  /** Whether ttu's counts allow the end of the book to be on this page. */
  function endByCount(root) {
    const paginated = !!root.querySelector(PAGINATED_SELECTOR);
    // Continuous mode holds the whole book: there unknown counts don't block.
    if (explored === null || total === null || total <= 0) return !paginated;
    const left = paginated ? total - explored - charsOnScreen(root) : total - explored;
    if (left <= (paginated ? END_SLACK_CHARS : MAX_PAGE_CHARS)) return true;
    if (refusedLogged !== explored) {
      refusedLogged = explored;
      console.log(`honmani book end on screen but ${left} of ${total} characters left`);
    }
    return false;
  }

  function check() {
    const id = bookId();
    if (id === null || id === reported) return;
    const root = document.querySelector(BOOK_ROOT_SELECTOR);
    if (!root || !endOnScreen(root) || !endByCount(root)) return;
    reported = id;
    if (window.honmani) window.honmani.postMessage(JSON.stringify({ type: 'library.finished', id }));
  }

  function checkLater() {
    clearTimeout(timer);
    timer = setTimeout(check, CHECK_MS);
  }

  let countsFor = null;
  document.addEventListener(PAGE_CHANGE, (event) => {
    // Books open inside the running page, so counts from the book before must not carry over.
    if (countsFor !== bookId()) {
      countsFor = bookId();
      explored = null;
      total = null;
    }
    const detail = event.detail || {};
    if (typeof detail.exploredCharCount === 'number') explored = detail.exploredCharCount;
    if (typeof detail.bookCharCount === 'number') total = detail.bookCharCount;
    checkLater();
  });
  for (const type of ['scroll', 'keyup', 'click', 'touchend', 'wheel']) {
    window.addEventListener(type, checkLater, { capture: true, passive: true });
  }
  window.addEventListener('resize', checkLater);
  window.addEventListener('load', () => setTimeout(check, 1500));
})();
