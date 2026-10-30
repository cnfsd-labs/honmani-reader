/*
 * Word marks, injected at document start into the ッツ Ebook Reader (ttu) WebView: on the book
 * page, words the reader is learning (looked up, not known yet) and, if chosen, words probably
 * new to them get a tint. localStorage "honmaniWordMarks" (Honmani's reader settings) is "off",
 * "learning" (the default) or "new" (learning and new words).
 *
 * The app splits the text into words (the splitter that grades books) and knows the reader's
 * words, so the page asks it about the paragraphs near the screen, a few at a time:
 *
 *   { type: 'marks.request', requestId, mode, blocks: [{ id, text }] }
 *     text: the paragraph without furigana (<rt>, <rp>), a <br> as "\n", as the tap bridge
 *     reads it; a long paragraph comes in pieces of up to 4,000 characters, each a block of its
 *     own (id: the piece's, unique on the page)
 *
 * and the app answers on the same object:
 *
 *   { type: 'marks', requestId, color: '#rrggbb', blocks: [{ id, marks: [[start, length, kind]] }] }
 *     kind 0: learning, 1: new; start and length in UTF-16 units of the block's text; color:
 *     the app's accent, for the learning words' tint
 *   { type: 'marks.reset' }   the reader's words changed: paragraphs are asked about again
 *
 * Marks use the CSS Custom Highlight API (Chromium 105+), so ttu's DOM, its character counts
 * and layout are never touched; older WebViews show no marks. The lookup mark (tap-bridge.js)
 * is drawn above them.
 *
 * In page mode the book scrolls inside its own box, which clips what lies beyond the page: the
 * paragraphs are watched within that box, so the pages before and after the one on screen are
 * asked about ahead of time and their marks are there when the page turns. Marks that arrive
 * for the page on screen (when a book opens, a word newly looked up) fade in rather than pop up;
 * when the app asks for fresh marks (after a lookup) those already shown stay as they are.
 */
(() => {
  'use strict';

  if (window.__honmaniWordMarks) return;
  window.__honmaniWordMarks = true;

  const MODE_KEY = 'honmaniWordMarks';
  const BOOK_ROOT_SELECTOR = '.book-content';
  const SKIPPED_SELECTOR = 'rt, rp, script, style';
  const NAMES = ['honmani-learning', 'honmani-new'];
  /** New marks, while they fade in; then they move to NAMES. */
  const FRESH_NAMES = ['honmani-learning-fresh', 'honmani-new-fresh'];
  /** Paragraphs this far from the page (in pages) are asked about before they show. */
  const AHEAD = '100%';
  const FADE_MS = 250;
  const MAX_BATCH_TEXT = 6_000;
  /** Longer paragraphs go in pieces (piecesOf), which end after one of PIECE_ENDS where they can. */
  const MAX_BLOCK_TEXT = 4_000;
  const PIECE_ENDS = '。．！？!?\n';
  const BATCH_DELAY_MS = 80;

  if (!(window.CSS && CSS.highlights && window.Highlight)) return;

  function mode() {
    try {
      const value = window.localStorage.getItem(MODE_KEY);
      return value === 'off' || value === 'new' ? value : 'learning';
    } catch (error) {
      return 'learning';
    }
  }

  function isBookPage() {
    return location.pathname.startsWith('/b');
  }

  /** A block's text nodes with where each starts in its text, by block. */
  const blocks = new Map();
  /** Block elements by id, and ids by element. */
  const elements = new Map();
  const ids = new WeakMap();
  let nextId = 1;
  let nextRequest = 1;
  /** Ids of later pieces of long blocks, by block and start (a first piece has the block's). */
  const pieceIds = new Map();
  let root = null;
  let observer = null;
  let mutations = null;
  /** Blocks to ask about, and the pieces of the one being sent. */
  let queue = new Set();
  let pieces = [];
  let timer = 0;
  /** Bumped by a reset: answers to older requests are dropped. */
  let generation = 0;
  const pending = new Map();
  /** The marks shown in each block: by "start:length:kind", their kind and ranges. */
  const shown = new Map();
  const highlights = NAMES.map(() => new Highlight());
  const fresh = FRESH_NAMES.map(() => new Highlight());
  // Below the lookup mark.
  for (const highlight of [...highlights, ...fresh]) highlight.priority = -1;
  let rgb = null;
  /** The fade of the fresh marks: when it started, and its animation frame. */
  let fadeStart = 0;
  let fadeFrame = 0;

  function setColor(color) {
    const hex = /^#([0-9a-fA-F]{6})$/.exec(color || '');
    const value = hex ? parseInt(hex[1], 16) : 0xffc400;
    rgb = `${(value >> 16) & 255}, ${(value >> 8) & 255}, ${value & 255}`;
    writeStyle(fadeFrame ? (performance.now() - fadeStart) / FADE_MS : 1);
  }

  // The style element this script created, by reference: a book may carry the same id.
  let styleElement = null;

  /** The marks' tints; `fade` (0 to 1): how far the fresh marks have faded in. */
  function writeStyle(fade) {
    if (rgb === null) return;
    let style = styleElement;
    if (!style || !style.isConnected) {
      // Only with the first answer: at document start there is no document element yet.
      const parent = document.head || document.documentElement;
      if (!parent) return;
      style = document.createElement('style');
      style.id = 'honmani-marks-style';
      styleElement = style;
      parent.appendChild(style);
    }
    const alpha = Math.min(1, Math.max(0, fade));
    // Faint tints vanish on a dark page at low screen brightness (AMOLED), so dark pages get more.
    const dark = darkPage();
    const learning = dark ? 0.45 : 0.28;
    const fresh = dark ? 0.45 : 0.2;
    const tints = (names, share) =>
      `::highlight(${names[0]}) { background-color: rgba(${rgb}, ${(learning * share).toFixed(3)}); }\n` +
      `::highlight(${names[1]}) { background-color: rgba(128, 128, 128, ${(fresh * share).toFixed(3)}); }\n`;
    style.textContent = tints(NAMES, 1) + tints(FRESH_NAMES, alpha);
  }

  /** Whether the page behind the text is dark (ttu's dark themes set it on <body>). */
  function darkPage() {
    for (const element of [document.body, document.documentElement]) {
      if (!element) continue;
      const parts = getComputedStyle(element).backgroundColor.replace(/^rgba?\(|\)$/g, '').split(',').map(Number);
      if (parts.length < 3 || parts.slice(0, 3).some((part) => !Number.isFinite(part))) continue;
      if (parts.length > 3 && parts[3] === 0) continue;
      return 0.299 * parts[0] + 0.587 * parts[1] + 0.114 * parts[2] < 128;
    }
    return false;
  }

  function reducedMotion() {
    return !!window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  }

  /** Moves the fresh marks to the others, at full tint. */
  function settle() {
    if (fadeFrame) cancelAnimationFrame(fadeFrame);
    fadeFrame = 0;
    for (let i = 0; i < fresh.length; i++) {
      for (const range of fresh[i]) highlights[i].add(range);
      fresh[i].clear();
    }
    writeStyle(1);
  }

  /** Fades the fresh marks in; marks added while it runs join the fade. */
  function fadeIn() {
    if (fadeFrame) return;
    if (reducedMotion()) {
      settle();
      return;
    }
    fadeStart = performance.now();
    writeStyle(0);
    const step = (now) => {
      const fade = (now - fadeStart) / FADE_MS;
      if (fade >= 1) {
        settle();
        return;
      }
      writeStyle(fade);
      fadeFrame = requestAnimationFrame(step);
    };
    fadeFrame = requestAnimationFrame(step);
  }

  function isSkipped(node) {
    const parent = node.parentElement;
    return !parent || parent.closest(SKIPPED_SELECTOR) !== null;
  }

  const blockCache = new WeakMap();
  function isBlock(element) {
    let block = blockCache.get(element);
    if (block === undefined) {
      const display = getComputedStyle(element).display;
      block = !display.startsWith('inline') && !display.startsWith('ruby') && display !== 'contents';
      blockCache.set(element, block);
    }
    return block;
  }

  function blockAncestor(node) {
    for (let element = node.parentElement; element && element !== root; element = element.parentElement) {
      if (isBlock(element)) return element;
    }
    return root;
  }

  function clips(element) {
    const style = getComputedStyle(element);
    return style.overflowX !== 'visible' || style.overflowY !== 'visible';
  }

  /**
   * Lets go of the paragraphs ttu took out of the book (in page mode it holds one chapter at a
   * time, in the same box), so that a reset doesn't observe every chapter read so far again.
   */
  function forgetDetached() {
    for (const [id, element] of elements) {
      if (element.isConnected && root.contains(element)) continue;
      observer.unobserve(element);
      elements.delete(id);
      ids.delete(element);
      blocks.delete(id);
      queue.delete(id);
      pieces = pieces.filter((piece) => piece.block !== id);
      pieceIds.delete(id);
      for (const mark of (shown.get(id) || new Map()).values()) {
        for (const range of mark.ranges) {
          highlights[mark.kind].delete(range);
          fresh[mark.kind].delete(range);
        }
      }
      shown.delete(id);
    }
  }

  /**
   * A block's text as the tap bridge reads it (blockText in tap-bridge.js): its text nodes,
   * spaces included, and a <br> as a line break, so no word runs across one.
   */
  function dataOf(node) {
    return node.nodeType === Node.TEXT_NODE ? node.data : '\n';
  }

  /** Groups the book's text into paragraphs and watches which come near the screen. */
  function scan() {
    forgetDetached();
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_ELEMENT, {
      acceptNode: (node) => {
        if (node.nodeType === Node.ELEMENT_NODE) {
          return node.tagName === 'BR' && !isSkipped(node) ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_SKIP;
        }
        return isSkipped(node) ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT;
      },
    });
    const found = new Map();
    const withText = new Set();
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      const block = blockAncestor(node);
      if (!found.has(block)) found.set(block, []);
      found.get(block).push(node);
      if (node.nodeType === Node.TEXT_NODE && node.data.trim()) withText.add(block);
    }
    for (const [element, nodes] of found) {
      // Only spaces between other blocks: nothing to ask about.
      if (!withText.has(element)) continue;
      let id = ids.get(element);
      if (id === undefined) {
        id = nextId++;
        ids.set(element, id);
        elements.set(id, element);
        observer.observe(element);
      }
      blocks.set(id, nodes);
    }
  }

  /** When the marks were last reset (page clock), until the first answer after it; 0 otherwise. */
  let resetAt = 0;

  function textOf(id) {
    return (blocks.get(id) || []).map(dataOf).join('');
  }

  /**
   * Block `id`'s text in pieces of at most MAX_BLOCK_TEXT, as { block, start, text }: a chapter
   * can be one long paragraph (or a <div> with <br>s). A piece ends after a sentence where it
   * can, so no word is cut in two.
   */
  function piecesOf(id) {
    const text = textOf(id);
    const pieces = [];
    for (let start = 0; start < text.length; ) {
      let end = Math.min(text.length, start + MAX_BLOCK_TEXT);
      if (end < text.length) {
        let cut = -1;
        for (let i = end - 1; i > start + MAX_BLOCK_TEXT / 2 && cut < 0; i--) {
          if (PIECE_ENDS.includes(text[i])) cut = i + 1;
        }
        if (cut > 0) end = cut;
        // No surrogate pair cut in two.
        else if (/[\ud800-\udbff]/.test(text[end - 1])) end--;
      }
      pieces.push({ block: id, start, text: text.slice(start, end) });
      start = end;
    }
    return pieces;
  }

  /** The same on every request, so answers and diagnostics read alike. */
  function pieceId(piece) {
    if (piece.start === 0) return piece.block;
    if (!pieceIds.has(piece.block)) pieceIds.set(piece.block, new Map());
    const own = pieceIds.get(piece.block);
    if (!own.has(piece.start)) own.set(piece.start, nextId++);
    return own.get(piece.start);
  }

  function send() {
    timer = 0;
    const app = window.honmani;
    if (!app || (queue.size === 0 && pieces.length === 0)) return;
    const wanted = mode();
    if (wanted === 'off') {
      queue.clear();
      pieces = [];
      return;
    }
    let size = 0;
    const batch = [];
    const parts = new Map();
    for (;;) {
      if (pieces.length === 0) {
        const next = queue.values().next();
        if (next.done) break;
        queue.delete(next.value);
        pieces = piecesOf(next.value);
        continue;
      }
      const piece = pieces[0];
      if (batch.length > 0 && size + piece.text.length > MAX_BATCH_TEXT) break;
      pieces.shift();
      if (!piece.text.trim()) continue;
      const id = pieceId(piece);
      batch.push({ id, text: piece.text });
      parts.set(id, piece);
      size += piece.text.length;
    }
    if (batch.length > 0) {
      const requestId = nextRequest++;
      pending.set(requestId, { generation, parts });
      app.postMessage(JSON.stringify({ type: 'marks.request', requestId, mode: wanted, blocks: batch }));
    }
    if (queue.size > 0 || pieces.length > 0) timer = setTimeout(send, BATCH_DELAY_MS);
  }

  function ask(id) {
    queue.add(id);
    if (!timer) timer = setTimeout(send, BATCH_DELAY_MS);
  }

  /** Ranges for text[start, start + length) of block `id`, one per text node (furigana between stays unmarked). */
  function rangesOf(id, start, length) {
    const ranges = [];
    let offset = 0;
    for (const node of blocks.get(id) || []) {
      const from = Math.max(start, offset);
      const size = dataOf(node).length;
      const to = Math.min(start + length, offset + size);
      if (from < to && node.nodeType === Node.TEXT_NODE && node.isConnected) {
        const range = document.createRange();
        range.setStart(node, from - offset);
        range.setEnd(node, to - offset);
        ranges.push(range);
      }
      offset += size;
      if (offset >= start + length) break;
    }
    return ranges;
  }

  function apply(message) {
    const request = pending.get(message.requestId);
    pending.delete(message.requestId);
    if (!request || request.generation !== generation || !Array.isArray(message.blocks)) return;
    setColor(message.color);
    for (const answer of message.blocks) {
      const piece = answer ? request.parts.get(answer.id) : undefined;
      if (!piece) continue;
      const id = piece.block;
      const end = piece.start + piece.text.length;
      // The paragraph changed since it was sent: ask again.
      if (piece.text !== textOf(id).slice(piece.start, end)) {
        if (blocks.has(id)) ask(id);
        continue;
      }
      const before = shown.get(id) || new Map();
      const now = new Map();
      // The marks of the paragraph's other pieces stay as they are.
      const within = (mark) => mark.start >= piece.start && mark.start < end;
      for (const [key, mark] of before) {
        if (!within(mark)) now.set(key, mark);
      }
      for (const mark of Array.isArray(answer.marks) ? answer.marks : []) {
        const [at, length, kind] = mark;
        if (!Number.isInteger(at) || !Number.isInteger(length) || !highlights[kind]) continue;
        const start = piece.start + at;
        const key = `${start}:${length}:${kind}`;
        const kept = before.get(key);
        if (kept && kept.ranges.every((range) => range.startContainer.isConnected)) {
          now.set(key, kept);
          continue;
        }
        const ranges = rangesOf(id, start, length);
        for (const range of ranges) fresh[kind].add(range);
        now.set(key, { kind, start, ranges });
      }
      for (const [key, mark] of before) {
        if (now.get(key) === mark || !within(mark)) continue;
        // Marked at a lookup, and this answer was asked for before the app counted it: kept
        // until the answers after the lookup's reset.
        if (mark.lookedUp === request.generation) {
          now.set(key, mark);
          continue;
        }
        for (const range of mark.ranges) {
          highlights[mark.kind].delete(range);
          fresh[mark.kind].delete(range);
        }
      }
      shown.set(id, now);
    }
    if (fresh.some((highlight) => highlight.size > 0)) fadeIn();
    if (resetAt) {
      // For "Share diagnostics": lookups reset the marks, which may slow the next tap.
      console.info(`honmani marks: first answer ${Math.round(performance.now() - resetAt)} ms after a reset`);
      resetAt = 0;
    }
  }

  /** Clears every mark (another book, marks turned off). */
  function clear() {
    reset();
    if (fadeFrame) cancelAnimationFrame(fadeFrame);
    fadeFrame = 0;
    for (const highlight of [...highlights, ...fresh]) highlight.clear();
    shown.clear();
  }

  /**
   * Asks again about the paragraphs near the screen; the marks shown stay until the answers
   * replace them, so a mark that doesn't change doesn't flash.
   */
  function reset() {
    generation++;
    resetAt = performance.now();
    pending.clear();
    queue.clear();
    pieces = [];
    if (!observer) return;
    // Observing again reports what is near the screen now.
    for (const element of elements.values()) {
      observer.unobserve(element);
      observer.observe(element);
    }
  }

  function stop() {
    if (observer) observer.disconnect();
    if (mutations) mutations.disconnect();
    observer = null;
    mutations = null;
    root = null;
    blocks.clear();
    elements.clear();
    pieceIds.clear();
    clear();
  }

  function start() {
    const content = document.querySelector(BOOK_ROOT_SELECTOR);
    if (!isBookPage() || !content || mode() === 'off') {
      if (!isBookPage() && root) stop();
      return;
    }
    if (content === root) return;
    stop();
    root = content;
    for (let i = 0; i < NAMES.length; i++) {
      CSS.highlights.set(NAMES[i], highlights[i]);
      CSS.highlights.set(FRESH_NAMES[i], fresh[i]);
    }
    observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          const id = ids.get(entry.target);
          if (entry.isIntersecting && id !== undefined) ask(id);
        }
      },
      // In page mode the book box clips the rest of the chapter: the margin only reaches past
      // the page when the box is the root.
      { root: clips(content) ? content : null, rootMargin: AHEAD },
    );
    scan();
    // ttu fills the book in after the page shows (and again after some settings).
    let rescan = 0;
    mutations = new MutationObserver(() => {
      clearTimeout(rescan);
      rescan = setTimeout(() => {
        if (root && root.isConnected) scan();
        else start();
      }, 200);
    });
    mutations.observe(root, { childList: true, subtree: true, characterData: true });
  }

  /**
   * A word the reader just looked up (tap-bridge.js marks it): it is being learned, so it gets
   * its mark now rather than when the app's fresh marks arrive, which confirm or correct it.
   */
  function onLookedUp(event) {
    const detail = event.detail || {};
    const ranges = Array.isArray(detail.ranges) ? detail.ranges : [];
    if (!root || mode() === 'off' || ranges.length === 0) return;
    const first = ranges[0];
    const id = ids.get(blockAncestor(first.startContainer));
    const nodes = id === undefined ? null : blocks.get(id);
    if (!nodes) return;
    let start = -1;
    let offset = 0;
    for (const node of nodes) {
      if (node === first.startContainer) {
        start = offset + first.startOffset;
        break;
      }
      offset += dataOf(node).length;
    }
    if (start < 0) return;
    const length = ranges.reduce((sum, range) => sum + range.endOffset - range.startOffset, 0);
    if (rgb === null) setColor(detail.color);
    const marks = shown.get(id) || new Map();
    const asNew = marks.get(`${start}:${length}:1`);
    if (asNew) {
      for (const range of asNew.ranges) {
        highlights[1].delete(range);
        fresh[1].delete(range);
      }
      marks.delete(`${start}:${length}:1`);
    }
    const key = `${start}:${length}:0`;
    if (!marks.has(key)) {
      const own = rangesOf(id, start, length);
      for (const range of own) highlights[0].add(range);
      marks.set(key, { kind: 0, start, ranges: own, lookedUp: generation });
    } else {
      marks.get(key).lookedUp = generation;
    }
    shown.set(id, marks);
  }

  function onAppMessage(event) {
    let message;
    try {
      message = JSON.parse(event.data);
    } catch (error) {
      return;
    }
    if (!message || typeof message !== 'object') return;
    if (message.type === 'marks') apply(message);
    else if (message.type === 'marks.reset') reset();
  }

  function listen() {
    const app = window.honmani;
    if (app && !app.__honmaniMarks && app.addEventListener) {
      app.__honmaniMarks = true;
      app.addEventListener('message', onAppMessage);
    }
  }

  listen();
  window.addEventListener('honmani-looked-up', onLookedUp);
  const check = () => {
    listen();
    start();
  };
  // The book content appears some time after the route changes; ttu is a single-page app.
  const pageObserver = new MutationObserver(() => {
    if (!root || !root.isConnected || !isBookPage()) check();
  });
  const watchPage = () => {
    pageObserver.observe(document.documentElement, { childList: true, subtree: true });
    check();
  };
  if (document.documentElement) watchPage();
  else document.addEventListener('DOMContentLoaded', watchPage, { once: true });
  window.addEventListener('popstate', () => setTimeout(check, 0));

})();
