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

  /** Whether a client rect has a width and is at least partly in the viewport (height may be 0). */
  function rectVisible(rect) {
    return rect.width > 0 && rect.right > 0 && rect.left < innerWidth && rect.bottom > 0 && rect.top < innerHeight;
  }

  function textWalker(root, skipped) {
    return document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
      acceptNode: (node) =>
        node.parentElement && node.parentElement.closest(skipped) ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT,
    });
  }

  function charVisible(range, node, offset) {
    range.setStart(node, offset);
    range.setEnd(node, offset + 1);
    return Array.from(range.getClientRects()).some(rectVisible);
  }

  /** The reference: walks every text node from the start of `root` until one has a character on screen. */
  function firstCharWalk(root, skipped) {
    const walker = textWalker(root, skipped);
    const range = document.createRange();
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      range.selectNodeContents(node);
      if (!Array.from(range.getClientRects()).some(rectVisible)) continue;
      for (let offset = 0; offset < node.data.length; offset++) {
        if (!node.data[offset].trim()) continue;
        if (charVisible(range, node, offset)) return { node, offset };
      }
    }
    return null;
  }

  /** How far the fast path looks around before it gives up and lets the full walk decide. */
  const FAST_STEPS = 200;

  /**
   * The same answer as firstCharWalk for a book laid out in one flow (continuous mode: text
   * nodes follow each other down the page, or from right to left in vertical text), without
   * walking from the start of the book: the browser names the text at the screen's start edge,
   * and from there the walk steps back over the text nodes still on screen. Returns undefined
   * when it cannot tell; the caller then walks.
   */
  function firstCharNearStart(root, skipped) {
    if (typeof document.caretRangeFromPoint !== 'function') return undefined;
    const vertical = getComputedStyle(root).writingMode.startsWith('vertical');
    // ttu lays a strip of its own over the first pixels of the page (it opens the reader bar), so
    // the probe goes deeper until it meets the book; the walk back below makes up the distance.
    let hit = null;
    for (const distance of [1, 12, 32, 64, 128]) {
      const found = vertical
        ? document.caretRangeFromPoint(innerWidth - distance, innerHeight / 2)
        : document.caretRangeFromPoint(innerWidth / 2, distance);
      if (found && root.contains(found.startContainer)) {
        hit = found;
        break;
      }
    }
    if (!hit) return undefined;

    const walker = textWalker(root, skipped);
    const range = document.createRange();
    const beforeStart = (rect) => (vertical ? rect.left >= innerWidth : rect.bottom <= 0);
    /** 'on' (a character is on screen), 'before' (all of it lies before the start edge) or 'other'. */
    const where = (node) => {
      range.selectNodeContents(node);
      const rects = Array.from(range.getClientRects()).filter((rect) => rect.width > 0);
      if (rects.some(rectVisible)) return 'on';
      return rects.length > 0 && rects.every(beforeStart) ? 'before' : 'other';
    };

    // The text node the browser named, or the first one after the position it named.
    let node = hit.startContainer;
    if (node.nodeType === Node.TEXT_NODE && !(node.parentElement && node.parentElement.closest(skipped))) {
      walker.currentNode = node;
    } else {
      walker.currentNode = node.childNodes[hit.startOffset] || node;
      node = walker.nextNode();
    }
    // Forward to a node with something on screen.
    let steps = 0;
    while (node && where(node) !== 'on') {
      if (++steps > FAST_STEPS) return undefined;
      node = walker.nextNode();
    }
    if (!node) return undefined;

    // Back over the nodes on screen to the earliest one. A node that lies wholly before the start
    // edge ends the search; nodes with no box (blank text between blocks) are stepped over.
    let earliest = node;
    steps = 0;
    walker.currentNode = node;
    for (let previous = walker.previousNode(); previous; previous = walker.previousNode()) {
      const place = where(previous);
      if (place === 'before') break;
      if (place === 'on') {
        earliest = previous;
        steps = 0;
      } else if (++steps > FAST_STEPS) {
        return undefined;
      }
    }

    // Forward from there, as the full walk does.
    walker.currentNode = earliest;
    steps = 0;
    for (let current = earliest; current; current = walker.nextNode()) {
      if (++steps > FAST_STEPS) return undefined;
      if (where(current) !== 'on') continue;
      for (let offset = 0; offset < current.data.length; offset++) {
        if (!current.data[offset].trim()) continue;
        if (charVisible(range, current, offset)) return { node: current, offset };
      }
    }
    return undefined;
  }

  /**
   * The first character of text on screen in `root` ({ node, offset }; blank characters and text
   * inside `skipped` are not counted), or null. With `fast` (continuous mode only; paginated
   * columns are not one flow) the walk starts at the screen's start edge instead of the book's.
   */
  function firstCharOnScreen(root, skipped, fast = false) {
    if (fast) {
      const found = firstCharNearStart(root, skipped);
      if (found) return found;
    }
    return firstCharWalk(root, skipped);
  }

  window.honmaniText = {
    SKIPPED_SELECTOR, isSkipped, isBlock, blockAncestor, bookId, toFraction, onScreen, firstCharOnScreen,
    // Exposed so tests can compare the fast path with the walk.
    firstCharWalk, firstCharNearStart,
  };
})();
