/*
 * The reader's own highlights (colours on passages the reader saved), injected at document start
 * into the ッツ Ebook Reader (ttu) WebView. They are drawn with the CSS Custom Highlight API like
 * the word marks, so ttu's DOM is never changed, and they follow the text through any change of
 * font, size, theme, orientation or custom CSS because they are stored as character positions,
 * counted the way ttu counts them (get-character-count.ts, get-paragraph-nodes.ts; EpubPositions
 * does the same in Kotlin): not inside <rt>, hidden or aria-hidden elements, only Japanese
 * letters, digits and Latin letters, a gaiji picture as one.
 *
 * The book HTML is one `<div id="ttu-<idref>">` per spine document. Paginated mode shows one
 * document at a time (ttu puts its id on `.book-content-container`), continuous mode all of them.
 * The app sends where each begins in the book (`highlights`: sections, total, items), so a
 * position in the book is a document's start plus the count inside it.
 *
 * Page to app: `highlights.request` when a book page is up (the app answers with `highlights`).
 * App to page: `highlights` { sections: [[id, start]...], items: [{ id, start, end, color }] }.
 * For the other scripts, `window.honmaniHighlights`: `measure(range)` (the position and text of a
 * selection), `idAt(node, offset)` (the highlight under a point) and `count(text)`.
 *
 * Drawing priority is -3: below the word marks (-1, -2) and the lookup mark and search hits (0),
 * so those stay on top and show through the translucent colour. Under e-ink mode (reader-style.js)
 * the four colours are four solid greys.
 */
(() => {
  'use strict';

  if (window.__honmaniHighlights) return;
  window.__honmaniHighlights = true;
  if (!(window.CSS && CSS.highlights && window.Highlight)) return;

  const COLORS = 4;
  const NAMES = Array.from({ length: COLORS }, (_, i) => `honmani-hl-${i}`);
  /** Yellow, green, blue, pink. */
  const RGB = ['255, 213, 0', '60, 200, 100', '70, 150, 255', '255, 90, 160'];
  const PRIORITY = -3;
  const BOOK_ROOT_SELECTOR = '.book-content';
  const TEXT_MAX = 200;
  const REBUILD_DELAY_MS = 200;
  /** The closing punctuation right after a highlight's last character, which its mark takes along. */
  const CLOSING = /^[。．！？!?…‥」』）］】〕〉》”’]*/u;
  /** ttu's counted characters (get-character-count.ts), the same flags. */
  const COUNTED = /[0-9A-Z○◯々-〇〻ぁ-ゖゝ-ゞァ-ヺー０-９Ａ-Ｚｦ-ﾝ\p{Radical}\p{Unified_Ideograph}]/iu;
  const { isGaiji } = window.honmaniText;

  const highlights = NAMES.map(() => {
    const highlight = new Highlight();
    highlight.priority = PRIORITY;
    return highlight;
  });

  /** id -> start, from the app; null until it answers. */
  let sections = null;
  let sectionStarts = [];
  let items = [];
  let root = null;
  let mutations = null;
  let rebuildTimer = 0;
  let styleElement = null;
  let requested = false;
  /** Section root element -> its text index (see indexOf). */
  const indexes = new Map();
  /** The pieces drawn: { id, range }. */
  let drawn = [];

  function count(text) {
    let n = 0;
    for (const char of text) if (COUNTED.test(char)) n++;
    return n;
  }

  function hidden(element) {
    return element.nodeName === 'RT' || element.hasAttribute('hidden') || element.hasAttribute('aria-hidden');
  }

  /**
   * The units ttu counts in a section, in order: { node, from (counted before), size, gaiji }.
   * Text nodes without a counted character are left out.
   */
  function indexOf(section) {
    let index = indexes.get(section);
    if (index) return index;
    const entries = [];
    let total = 0;
    const walker = document.createTreeWalker(section, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT, {
      acceptNode: (node) => {
        if (node.nodeType === Node.TEXT_NODE) return NodeFilter.FILTER_ACCEPT;
        if (hidden(node)) return NodeFilter.FILTER_REJECT;
        return isGaiji(node) ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_SKIP;
      },
    });
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      const gaiji = node.nodeType !== Node.TEXT_NODE;
      const size = gaiji ? 1 : count(node.data);
      if (size === 0) continue;
      entries.push({ node, from: total, size, gaiji });
      total += size;
    }
    index = { entries, total };
    indexes.set(section, index);
    return index;
  }

  /** The section root elements in the book now, with where each begins in the book. */
  function sectionRoots() {
    if (!root || !sections) return [];
    const found = [];
    const candidates = root.matches('[id^="ttu-"]') ? [root] : [];
    for (const element of [...candidates, ...root.querySelectorAll('[id^="ttu-"]')]) {
      if (sections.has(element.id)) found.push({ element, start: sections.get(element.id) });
    }
    return found;
  }

  /** The section root around `node` and where it begins in the book, or null. */
  function sectionOf(node) {
    if (!sections) return null;
    for (let element = node.nodeType === Node.ELEMENT_NODE ? node : node.parentElement; element; element = element.parentElement) {
      if (element.id && sections.has(element.id)) return { element, start: sections.get(element.id) };
    }
    return null;
  }

  /** Where in `text` (a code unit offset) the counted character number `k` begins. */
  function offsetOfCounted(text, k) {
    let seen = 0;
    let i = 0;
    for (const char of text) {
      if (COUNTED.test(char)) {
        if (seen === k) return i;
        seen++;
      }
      i += char.length;
    }
    return text.length;
  }

  /** The entry holding the counted character number `k` (relative to the section), or null. */
  function entryAt(index, k) {
    const { entries } = index;
    let low = 0;
    let high = entries.length - 1;
    while (low <= high) {
      const middle = (low + high) >> 1;
      const entry = entries[middle];
      if (k < entry.from) high = middle - 1;
      else if (k >= entry.from + entry.size) low = middle + 1;
      else return entry;
    }
    return null;
  }

  /** A Range over the counted characters [from, to) of the section (relative), or null. */
  function rangeOf(index, from, to) {
    const first = entryAt(index, from);
    const last = entryAt(index, to - 1);
    if (!first || !last) return null;
    const range = document.createRange();
    if (first.gaiji) range.setStartBefore(first.node);
    else range.setStart(first.node, offsetOfCounted(first.node.data, from - first.from));
    if (last.gaiji) range.setEndAfter(last.node);
    else {
      const text = last.node.data;
      const at = offsetOfCounted(text, to - 1 - last.from);
      const end = at + (text.codePointAt(at) > 0xffff ? 2 : 1);
      // ttu doesn't count punctuation: a sentence's mark would stop short of its 。 or 」.
      range.setEnd(last.node, end + text.slice(end).match(CLOSING)[0].length);
    }
    return range;
  }

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

  /** The colours' tints: more on a dark page, where faint ones vanish at low brightness (AMOLED). */
  function writeStyle() {
    let style = styleElement;
    if (!style || !style.isConnected) {
      const parent = document.head || document.documentElement;
      if (!parent) return;
      style = document.createElement('style');
      style.id = 'honmani-highlights-style';
      styleElement = style;
      parent.appendChild(style);
    }
    const alpha = darkPage() ? 0.45 : 0.28;
    const css = NAMES.map((name, i) => `::highlight(${name}) { background-color: rgba(${RGB[i]}, ${alpha}); }`).join('\n');
    if (style.textContent !== css) style.textContent = css;
  }

  /** Draws the items that lie in the sections now in the page. */
  function rebuild() {
    for (const highlight of highlights) highlight.clear();
    drawn = [];
    if (!root || !sections || items.length === 0) return;
    const roots = sectionRoots();
    for (const { element, start } of roots) {
      const next = sectionStarts.find((other) => other > start);
      const end = next === undefined ? Infinity : next;
      let index = null;
      for (const item of items) {
        if (item.end <= start || item.start >= end) continue;
        index = index || indexOf(element);
        const from = Math.max(item.start, start) - start;
        const to = Math.min(item.end, end) - start;
        if (to <= from) continue;
        const range = rangeOf(index, from, Math.min(to, index.total));
        if (!range) continue;
        highlights[Math.min(COLORS - 1, Math.max(0, item.color))].add(range);
        drawn.push({ id: item.id, range });
      }
    }
  }

  function apply(message) {
    if (!Array.isArray(message.sections) || !Array.isArray(message.items)) return;
    sections = new Map(message.sections.filter((s) => Array.isArray(s) && typeof s[0] === 'string' && Number.isFinite(s[1])));
    sectionStarts = [...sections.values()].sort((a, b) => a - b);
    items = message.items.filter(
      (item) => item && Number.isFinite(item.start) && Number.isFinite(item.end) && item.end > item.start,
    );
    writeStyle();
    indexes.clear();
    rebuild();
  }

  /** The counted characters of the book before the point (node, offset), or null outside a section. */
  function before(node, offset) {
    const section = sectionOf(node);
    if (!section) return null;
    const index = indexOf(section.element);
    const { entries } = index;
    if (node.nodeType === Node.TEXT_NODE) {
      const entry = entries.find((candidate) => candidate.node === node);
      if (entry) return section.start + entry.from + count(node.data.slice(0, offset));
    }
    // The first unit at or after the point.
    const point = document.createRange();
    point.setStart(node, offset);
    point.collapse(true);
    let low = 0;
    let high = entries.length;
    while (low < high) {
      const middle = (low + high) >> 1;
      if (point.comparePoint(entries[middle].node, 0) >= 0) high = middle;
      else low = middle + 1;
    }
    return section.start + (low < entries.length ? entries[low].from : index.total);
  }

  /** The text of the units inside the range, without furigana, for the list. */
  function textOf(range) {
    const section = sectionOf(range.commonAncestorContainer);
    let text = '';
    const take = (node, from, to) => {
      text += node.nodeType === Node.TEXT_NODE ? node.data.slice(from, to) : '￼';
    };
    const roots = section ? [section.element] : [range.commonAncestorContainer];
    const walker = document.createTreeWalker(roots[0], NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT, {
      acceptNode: (node) => {
        if (node.nodeType === Node.TEXT_NODE) return NodeFilter.FILTER_ACCEPT;
        if (hidden(node)) return NodeFilter.FILTER_REJECT;
        return isGaiji(node) ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_SKIP;
      },
    });
    for (let node = walker.nextNode(); node && text.length < TEXT_MAX; node = walker.nextNode()) {
      if (!range.intersectsNode(node)) {
        if (range.comparePoint(node, 0) > 0) break;
        continue;
      }
      const from = node === range.startContainer ? range.startOffset : 0;
      const to = node === range.endContainer ? range.endOffset : Infinity;
      take(node, from, to);
    }
    return text.replace(/\s+/g, ' ').trim().slice(0, TEXT_MAX);
  }

  /**
   * The position of a selection: { start, end, text } in the book's characters, or null when it
   * is outside the book or holds no counted character.
   */
  function measure(range) {
    if (!sections || !range) return null;
    const start = before(range.startContainer, range.startOffset);
    const end = before(range.endContainer, range.endOffset);
    if (start === null || end === null || end <= start) return null;
    return { start, end, text: textOf(range) };
  }

  /** The id of the highlight that covers the point, or null (the last drawn wins where they overlap). */
  function idAt(node, offset) {
    for (let i = drawn.length - 1; i >= 0; i--) {
      const { id, range } = drawn[i];
      try {
        if (range.isPointInRange(node, offset)) return id;
      } catch (error) {
        // The point is in another document or a removed node.
      }
    }
    return null;
  }

  function isBookPage() {
    return window.honmaniText.bookId() !== null;
  }

  function scheduleRebuild() {
    clearTimeout(rebuildTimer);
    rebuildTimer = setTimeout(() => {
      indexes.clear();
      rebuild();
    }, REBUILD_DELAY_MS);
  }

  function start() {
    const content = document.querySelector(BOOK_ROOT_SELECTOR);
    if (!isBookPage() || !content) {
      if (!isBookPage() && root) stop();
      return;
    }
    if (content === root) return;
    stop();
    root = content;
    for (let i = 0; i < COLORS; i++) CSS.highlights.set(NAMES[i], highlights[i]);
    writeStyle();
    mutations = new MutationObserver(scheduleRebuild);
    mutations.observe(root, { childList: true, subtree: true });
    request();
    if (sections) rebuild();
  }

  function stop() {
    if (mutations) mutations.disconnect();
    mutations = null;
    root = null;
    clearTimeout(rebuildTimer);
    indexes.clear();
    for (const highlight of highlights) highlight.clear();
    drawn = [];
    requested = false;
  }

  function request() {
    const app = window.honmani;
    if (requested || !app || typeof app.postMessage !== 'function') return;
    requested = true;
    app.postMessage(JSON.stringify({ type: 'highlights.request', bookId: window.honmaniText.bookId() }));
  }

  function onAppMessage(event) {
    let message;
    try {
      message = JSON.parse(event.data);
    } catch (error) {
      return;
    }
    // A reply for another book (the app may still be answering the one opened before) is not ours.
    if (message && message.type === 'highlights' && message.bookId === window.honmaniText.bookId()) apply(message);
  }

  function listen() {
    const app = window.honmani;
    if (app && !app.__honmaniHighlights && app.addEventListener) {
      app.__honmaniHighlights = true;
      app.addEventListener('message', onAppMessage);
    }
  }

  window.honmaniHighlights = { measure, idAt, count, NAMES };

  listen();
  const check = () => {
    listen();
    start();
  };
  const pageObserver = new MutationObserver(() => {
    if (!root || !root.isConnected || !isBookPage()) check();
  });
  const watchPage = () => {
    pageObserver.observe(document.documentElement, { childList: true, subtree: true });
    // A theme change rewrites the page's colours without changing its elements.
    new MutationObserver(writeStyle).observe(document.documentElement, { attributes: true, attributeFilter: ['class', 'style'] });
    if (document.body) new MutationObserver(writeStyle).observe(document.body, { attributes: true, attributeFilter: ['class', 'style'] });
    check();
  };
  if (document.documentElement) watchPage();
  else document.addEventListener('DOMContentLoaded', watchPage, { once: true });
  window.addEventListener('popstate', () => setTimeout(check, 0));
})();
