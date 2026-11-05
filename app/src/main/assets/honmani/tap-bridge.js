/*
 * Honmani tap bridge, injected at document start into the ッツ Ebook Reader WebView.
 *
 * Messages to the app, through the `honmani` object registered with
 * WebViewCompat.addWebMessageListener:
 *
 *   { "type": "lookup", "text": "<up to SCAN_LENGTH chars from the tapped char>",
 *     "sentence": "<sentence around it>", "sentenceOffset": <index of text in sentence>,
 *     "previousSentence": "<the sentence before it in the paragraph>",
 *     "tap": <number of this tap, counting up from 1>,
 *     "ruby": [{ "start": <n>, "end": <n>, "reading": "<furigana>" }] }
 *
 * "ruby" lists furigana for ruby bases that lie wholly inside "text" (indexes into "text"); it is
 * left out when there are none.
 *   { "type": "dismiss" }   (a tap on book content that is not on text)
 *   { "type": "turnPage", "next": <bool> }
 *                           (a tap in the left or right edge zone of a paginated page, instead
 *                           of a lookup, when localStorage "honmaniEdgeTaps" = "1"; the zone is
 *                           10, 15 or 25 percent of the width, "honmaniEdgeZone" narrow, medium
 *                           (default) or wide; the app turns the page, or closes an open lookup
 *                           panel first. Next is the left edge in vertical text and the right edge
 *                           in horizontal text, or both edges with "honmaniEdgeForward" = "1". Taps on links
 *                           and buttons, on the footer and the top bar are not affected)
 *
 * A long press on book text (a finger held still for 400 ms) marks the word under it with the
 * lookup mark and, when the finger lifts, looks it up in the same message, with "selection": true:
 * "text" is the word, up to SCAN_LENGTH characters. Android's own selection (a selection that
 * settles for 250 ms) is looked up the same way and deselected. localStorage
 * "honmaniSelectionLookup" = "0" (Honmani's reader settings) turns both off and leaves the book
 * text natively selectable (otherwise it is not: user-select: none). A selection message also
 * carries "highlight": { start, end, text }, the whole selection as a place in the book
 * (highlights.js), and a tap inside a highlight carries "highlightId": its id.
 *
 * Furigana (<rt>, <rp>) is not part of the scanned text; a tap on furigana scans from the start
 * of the base text it reads. Scanning stops at the end of the
 * tapped paragraph and at a line break (<br>). A tap on an opening bracket or quote scans from
 * the character after it;
 * a tap on other punctuation or a symbol (、。！ ・ …) looks nothing up.
 *
 * Messages from the app (replies on the same object):
 *
 *   { "type": "highlight", "length": <n>, "color": "#rrggbb", "tap": <n> }
 *                                             marks n characters from the last tapped one, in
 *                                             the app's accent colour (optional); "tap" is the
 *                                             number of the lookup it answers: a mark for an
 *                                             older tap than the latest one is ignored
 *   { "type": "clear" }                      removes the mark
 *
 * The mark uses the CSS Custom Highlight API, so ttu's DOM is never modified. WebViews before
 * Chromium 105 lack it; there the mark is drawn as boxes in an overlay outside ttu's content,
 * redrawn when the page scrolls (ttu turns pages by scrolling) or resizes.
 */
(() => {
  'use strict';

  if (window.__honmaniTapBridge) return;
  window.__honmaniTapBridge = true;

  const SCAN_LENGTH = 20;
  /** A tap moves at most this far (CSS px) and lasts at most this long (ms). */
  const TAP_SLOP = 10;
  const TAP_TIME = 500;
  /** How long after a tap its click may come, and be left to ttu as already looked up (ms). */
  const CLICK_AFTER_TAP = 1000;
  const MAX_READING_LENGTH = 32;
  const MAX_SENTENCE_LENGTH = 400;
  const MAX_PREVIOUS_SENTENCE_LENGTH = 200;
  const BOOK_ROOT_SELECTOR = '.book-content';
  /** Pictures shorter and narrower than this (CSS px) are inline characters, not pictures to zoom. */
  const MIN_PICTURE_SIZE = 48;
  const PASS_THROUGH_TYPES = ['image/jpeg', 'image/png', 'image/webp', 'image/gif'];
  const MAX_PICTURE_BYTES = 2000000;
  /** Longest message to the app, in characters; the app's cap is the same (MAX_IMAGE_LENGTH). */
  const MAX_PICTURE_DATA = 3000000;
  /** Longest side of a redrawn picture, tried in turn until the message fits. */
  const PICTURE_SIDES = [2400, 1600, 1000];
  const { isSkipped, blockAncestor, isGaiji, textData, CLOSING_QUOTES, isSentenceEnd, sentenceBounds } = window.honmaniText;
  const INTERACTIVE_SELECTOR = 'a[href], button, input, textarea, select, [role="button"]';
  /** Stands for a gaiji picture in the text (isGaiji): no word runs across it. */
  const GAIJI = '\uFFFC';
  const NOT_WORD_START = /^[\p{P}\p{S}]/u;
  const OPENING_BRACKETS = '「『（(〈《【〔［｛“‘〝"\'';
  const HIGHLIGHT_NAME = 'honmani-lookup';
  const LOOKED_UP_EVENT = 'honmani-looked-up';
  const OVERLAY_ID = 'honmani-highlight-overlay';

  /**
   * Where the last lookup started: { block, node, index, skip }, `skip` being the opening brackets
   * between the tapped character and the scanned text.
   */
  let lastTap = null;

  /** Counts taps (lookups and dismissals); the app echoes a lookup's number in its highlight. */
  let tapNumber = 0;

  /** The fallback mark: { ranges, background }, or null. */
  let overlayMark = null;

  function hasHighlightApi() {
    return Boolean(window.CSS && CSS.highlights && window.Highlight);
  }

  function caretAt(x, y) {
    if (document.caretPositionFromPoint) {
      const position = document.caretPositionFromPoint(x, y);
      return position && { node: position.offsetNode, offset: position.offset };
    }
    const range = document.caretRangeFromPoint && document.caretRangeFromPoint(x, y);
    return range && { node: range.startContainer, offset: range.startOffset };
  }

  /**
   * Boxes of one character. getBoundingClientRect() is not used because, for the first
   * character of a wrapped line, Chromium adds an empty box at the end of the previous line.
   */
  function charRects(node, index) {
    const range = document.createRange();
    range.setStart(node, index);
    range.setEnd(node, index + 1);
    return Array.from(range.getClientRects()).filter((rect) => rect.width > 0 && rect.height > 0);
  }

  /** Index of the character in `node` under (x, y), or -1. The caret can sit on either side. */
  function hitCharIndex(node, offset, x, y) {
    if (!node || node.nodeType !== Node.TEXT_NODE || isSkipped(node)) return -1;
    for (const index of [offset, offset - 1]) {
      if (index < 0 || index >= node.data.length) continue;
      const hit = charRects(node, index).some(
        (rect) => x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom,
      );
      if (hit) {
        // Never start in the middle of a surrogate pair.
        const code = node.data.charCodeAt(index);
        return code >= 0xdc00 && code <= 0xdfff && index > 0 ? index - 1 : index;
      }
    }
    return -1;
  }

  function textWalker(block) {
    return document.createTreeWalker(block, NodeFilter.SHOW_TEXT, {
      acceptNode: (node) => (isSkipped(node) ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT),
    });
  }

  /** The <rt> giving the reading of ruby base text `node`, or null. */
  function rubyTextFor(node, block) {
    const ruby = node.parentElement.closest('ruby');
    if (!ruby || !block.contains(ruby)) return null;
    for (const rt of ruby.querySelectorAll('rt')) {
      if (node.compareDocumentPosition(rt) & Node.DOCUMENT_POSITION_FOLLOWING) return rt;
    }
    return null;
  }

  /** Like textWalker, but also stops at <br> elements and gaiji pictures. */
  function lineWalker(block) {
    return document.createTreeWalker(block, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_ELEMENT, {
      acceptNode: (node) => {
        if (node.nodeType === Node.ELEMENT_NODE) {
          return node.tagName === 'BR' || isGaiji(node) ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_SKIP;
        }
        return isSkipped(node) ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT;
      },
    });
  }

  /**
   * Text of `block` without furigana (a <br> as "\n", a gaiji picture as U+FFFC), the index of
   * (node, index) within it, and the ruby bases in it as { start, end, reading } (indexes into the text).
   */
  function blockText(block, node, index) {
    const walker = lineWalker(block);
    let text = '';
    let position = -1;
    const ruby = [];
    let lastRt = null;
    for (let current = walker.nextNode(); current; current = walker.nextNode()) {
      if (current.nodeType === Node.ELEMENT_NODE) {
        text += current.tagName === 'BR' ? '\n' : GAIJI;
        lastRt = null;
        continue;
      }
      if (current === node) position = text.length + index;
      const data = textData(current);
      const rt = rubyTextFor(current, block);
      if (rt && rt === lastRt) {
        ruby[ruby.length - 1].end += data.length;
      } else if (rt) {
        const reading = rt.textContent.trim();
        ruby.push({ start: text.length, end: text.length + data.length, reading });
      }
      lastRt = rt;
      text += data;
    }
    return { text, position, ruby };
  }

  /** Ruby bases wholly inside text[start, start + length), relative to start. */
  function rubyWithin(ruby, start, length) {
    return ruby
      .map((span) => ({ start: span.start - start, end: span.end - start, reading: span.reading }))
      .filter(
        (span) =>
          span.start >= 0 &&
          span.end <= length &&
          span.end > span.start &&
          span.reading.length > 0 &&
          span.reading.length <= MAX_READING_LENGTH,
      );
  }

  function takeCodePoints(text, count) {
    return Array.from(text).slice(0, count).join('');
  }

  function sentenceAround(text, position) {
    let { start, end } = sentenceBounds(text, position);

    const previous = sentenceBefore(text, start);

    // Keep very long "sentences" (e.g. no punctuation) to a window around the tap.
    if (end - start > MAX_SENTENCE_LENGTH) {
      start = Math.max(start, position - MAX_SENTENCE_LENGTH / 2);
      // Not in the middle of a surrogate pair, at either end.
      if (/[\uDC00-\uDFFF]/.test(text[start])) start++;
      end = Math.min(end, start + MAX_SENTENCE_LENGTH);
      if (end < text.length && /[\uD800-\uDBFF]/.test(text[end - 1])) end--;
    }
    let sentence = text.slice(start, end);
    const leading = sentence.length - sentence.trimStart().length;
    sentence = sentence.trim();
    return { sentence, offset: position - start - leading, previous };
  }

  /**
   * The sentence that ends where the one starting at `boundary` begins, within the same paragraph
   * (empty at the first sentence), cut to its last MAX_PREVIOUS_SENTENCE_LENGTH characters.
   */
  function sentenceBefore(text, boundary) {
    let end = boundary;
    while (end > 0 && /\s/.test(text[end - 1])) end--;
    let begin = end;
    // Past its own ending and closing quotes, then back to the start of that sentence.
    while (begin > 0 && (isSentenceEnd(text[begin - 1]) || CLOSING_QUOTES.includes(text[begin - 1]))) begin--;
    while (begin > 0 && !isSentenceEnd(text[begin - 1])) begin--;
    if (end - begin > MAX_PREVIOUS_SENTENCE_LENGTH) {
      begin = end - MAX_PREVIOUS_SENTENCE_LENGTH;
      // Not in the middle of a surrogate pair.
      if (/[\uDC00-\uDFFF]/.test(text[begin])) begin++;
    }
    return text.slice(begin, end).trim();
  }

  /** A tap on book content that is not on a link, button or similar. */
  function isBookBackground(target) {
    return target.closest(BOOK_ROOT_SELECTOR) !== null && target.closest(INTERACTIVE_SELECTOR) === null;
  }

  /**
   * The share of the page's width at each side where a tap turns the page (edge taps on), by
   * localStorage "honmaniEdgeZone"; medium (the default) is the width edge taps always had.
   */
  const EDGE_ZONES = { narrow: 0.1, medium: 0.15, wide: 0.25 };

  function edgeZone() {
    try {
      return EDGE_ZONES[window.localStorage.getItem('honmaniEdgeZone')] ?? EDGE_ZONES.medium;
    } catch (error) {
      return EDGE_ZONES.medium;
    }
  }

  /** localStorage "honmaniEdgeForward" = "1": both edges turn to the next page. */
  function edgeBothForward() {
    try {
      return window.localStorage.getItem('honmaniEdgeForward') === '1';
    } catch (error) {
      return false;
    }
  }

  function edgeTapsOn() {
    try {
      return window.localStorage.getItem('honmaniEdgeTaps') === '1' &&
        (window.localStorage.getItem('viewMode') || 'paginated') === 'paginated';
    } catch (error) {
      return false;
    }
  }

  /** For a tap in an edge zone of the book page: whether it turns to the next page; else null. */
  function edgeTurn(x, target) {
    const root = target.closest(BOOK_ROOT_SELECTOR);
    if (!root || target.closest(INTERACTIVE_SELECTOR) || !edgeTapsOn()) return null;
    const zone = edgeZone();
    const left = x < innerWidth * zone;
    if (!left && x <= innerWidth * (1 - zone)) return null;
    if (edgeBothForward()) return true;
    const vertical = getComputedStyle(root).writingMode.startsWith('vertical');
    return vertical ? left : !left;
  }

  /** The text node and index of the character under (x, y), or null. */
  function charAt(x, y) {
    const caret = caretAt(x, y);
    if (!caret) return null;
    const index = hitCharIndex(caret.node, caret.offset, x, y);
    return index < 0 ? null : { node: caret.node, index };
  }

  /**
   * For a tap on furigana, the start of the base text it reads: furigana sits right beside its
   * kanji (above them in vertical text), and a tap there means the word. Null for other taps.
   */
  function rubyBaseOf(target) {
    const rt = target.closest('rt');
    const ruby = rt && rt.closest('ruby');
    if (!ruby) return null;
    // The base is the text since the furigana before this one (東<rt>とう</rt>京<rt>きょう</rt>).
    let first = null;
    const walker = textWalker(ruby);
    for (let current = walker.nextNode(); current; current = walker.nextNode()) {
      if (current.compareDocumentPosition(rt) & Node.DOCUMENT_POSITION_PRECEDING) break;
      const previous = rubyTextFor(current, ruby);
      if (previous !== rt) continue;
      if (!first) first = current;
    }
    return first ? { node: first, index: 0 } : null;
  }

  function lookupAt(x, y, target) {
    const root = target.closest(BOOK_ROOT_SELECTOR);
    if (!root || target.closest(INTERACTIVE_SELECTOR)) return null;

    const hit = rubyBaseOf(target) || charAt(x, y);
    if (!hit || !root.contains(hit.node)) return null;
    const message = lookupFrom(root, hit.node, hit.index, Infinity);
    // A tap inside a highlight (highlights.js) names it, so the panel can recolour or remove it.
    const highlighted = message && window.honmaniHighlights ? window.honmaniHighlights.idAt(hit.node, hit.index) : null;
    if (highlighted !== null) message.highlightId = highlighted;
    return message;
  }

  /**
   * The lookup message for the text from (node, index), at most `limit` characters of it (a
   * selection's length; a tap scans SCAN_LENGTH code points whatever follows), or null.
   */
  function lookupFrom(root, node, index, limit) {
    const block = blockAncestor(node, root);
    const { text: paragraph, position, ruby: paragraphRuby } = blockText(block, node, index);
    if (position < 0) return null;

    let start = position;
    while (start < paragraph.length && OPENING_BRACKETS.includes(paragraph[start])) start++;
    // Punctuation and symbols start no word: a tap on 、 or 。 looks nothing up.
    if (NOT_WORD_START.test(paragraph.slice(start, start + 2))) return null;
    const end = limit === Infinity ? paragraph.length : Math.max(start, position + limit);
    const text = takeCodePoints(paragraph.slice(start, end), SCAN_LENGTH).split(/[\n\uFFFC]/)[0].trimEnd();
    if (!text.trim() || /^\s/.test(text)) return null;

    const around = sentenceAround(paragraph, start);
    // The geta mark, the usual stand-in for a missing character, rather than U+FFFC in a card.
    const sentence = around.sentence.replaceAll(GAIJI, '\u3013');
    const { offset } = around;
    const previous = around.previous.replaceAll(GAIJI, '\u3013');
    lastTap = { block, node, index, skip: start - position };
    const message = { type: 'lookup', text, sentence, sentenceOffset: offset, tap: ++tapNumber };
    const ruby = rubyWithin(paragraphRuby, start, text.length);
    if (ruby.length > 0) message.ruby = ruby;
    if (previous) message.previousSentence = previous;
    return message;
  }

  // The elements this script created, kept by reference: a book may carry the same ids.
  let overlayElement = null;
  let styleElement = null;

  function clearHighlight() {
    if (hasHighlightApi()) CSS.highlights.delete(HIGHLIGHT_NAME);
    overlayMark = null;
    if (overlayElement) overlayElement.remove();
  }

  /** Draws the fallback mark's boxes; it never takes taps, so lookups still reach the book. */
  function drawOverlay() {
    let overlay = overlayElement;
    if (!overlayMark) {
      if (overlay) overlay.remove();
      return;
    }
    if (!overlay || !overlay.isConnected) {
      overlay = document.createElement('div');
      overlay.id = OVERLAY_ID;
      overlayElement = overlay;
      overlay.style.cssText =
        'position: fixed; inset: 0; pointer-events: none; z-index: 2147483647; overflow: hidden;';
      document.documentElement.appendChild(overlay);
    }
    const boxes = [];
    for (const range of overlayMark.ranges) {
      for (const rect of range.getClientRects()) {
        if (rect.width <= 0 || rect.height <= 0) continue;
        const box = document.createElement('div');
        box.style.cssText =
          `position: absolute; left: ${rect.left}px; top: ${rect.top}px; ` +
          `width: ${rect.width}px; height: ${rect.height}px; ` +
          `background-color: ${overlayMark.background}; border-radius: 2px;`;
        boxes.push(box);
      }
    }
    overlay.replaceChildren(...boxes);
  }

  /** The mark's colour: the app's accent (#rrggbb) at 40% opacity, else amber. */
  function highlightBackground(color) {
    const hex = /^#([0-9a-fA-F]{6})$/.exec(color || '');
    if (!hex) return 'rgba(255, 196, 0, 0.45)';
    const value = parseInt(hex[1], 16);
    return `rgba(${(value >> 16) & 255}, ${(value >> 8) & 255}, ${value & 255}, 0.4)`;
  }

  function setHighlightStyle(color) {
    let style = styleElement;
    if (!style || !style.isConnected) {
      style = document.createElement('style');
      style.id = 'honmani-style';
      styleElement = style;
      (document.head || document.documentElement).appendChild(style);
    }
    style.textContent = `::highlight(${HIGHLIGHT_NAME}) { background-color: ${highlightBackground(color)}; }`;
  }

  /** Marks `length` characters of book text from the last tap, skipping furigana. */
  function highlight(length, color) {
    clearHighlight();
    if (!lastTap || !lastTap.node.isConnected) return;

    const walker = textWalker(lastTap.block);
    walker.currentNode = lastTap.node;
    const ranges = [];
    let node = lastTap.node;
    let offset = lastTap.index;
    // Past the opening brackets the scan skipped.
    let skip = lastTap.skip;
    while (node && skip > 0) {
      const available = node.data.length - offset;
      if (skip < available) {
        offset += skip;
        skip = 0;
      } else {
        skip -= available;
        node = walker.nextNode();
        offset = 0;
      }
    }
    let remaining = length;
    while (node && remaining > 0) {
      const take = Math.min(remaining, node.data.length - offset);
      if (take > 0) {
        const range = document.createRange();
        range.setStart(node, offset);
        range.setEnd(node, offset + take);
        ranges.push(range);
        remaining -= take;
      }
      node = walker.nextNode();
      offset = 0;
    }
    if (ranges.length === 0) return;
    // The word is being learned now: word-marks.js marks it at once, so its accent stays when
    // this mark goes, before the app's fresh word marks arrive.
    window.dispatchEvent(
      new CustomEvent(LOOKED_UP_EVENT, { detail: { ranges: ranges.map((range) => range.cloneRange()), color } }),
    );
    showMark(ranges, color);
    logTiming(performance.now());
  }

  /** Paints the mark over `ranges` (one per text node), in the colour or the default amber. */
  function showMark(ranges, color) {
    if (hasHighlightApi()) {
      setHighlightStyle(color);
      CSS.highlights.set(HIGHLIGHT_NAME, new Highlight(...ranges));
    } else {
      overlayMark = { ranges, background: highlightBackground(color) };
      drawOverlay();
    }
  }

  function onAppMessage(event) {
    let message;
    try {
      message = JSON.parse(event.data);
    } catch (error) {
      return;
    }
    if (message.type === 'highlight' && Number.isInteger(message.length)) {
      // A reply that comes after a newer tap would mark the wrong text.
      if (Number.isInteger(message.tap) && message.tap < tapNumber) return;
      highlight(message.length, message.color);
    } else if (message.type === 'clear') {
      clearHighlight();
    }
  }

  function post(message) {
    const app = window.honmani;
    if (!app) return;
    // Replies arrive on the same object; set the handler before the first message.
    if (app.onmessage !== onAppMessage) app.onmessage = onAppMessage;
    app.postMessage(JSON.stringify(message));
  }

  /**
   * When the last tap's finger went up and how it arrived (page clock, ms), to log how long each
   * step until its mark took for "Share diagnostics"; null once logged.
   */
  let tapTiming = null;
  let lastUp = 0;

  /** Logs the steps of the last tap once its mark is on screen (two frames: after the paint). */
  function logTiming(marked) {
    const timing = tapTiming;
    tapTiming = null;
    if (!timing) return;
    requestAnimationFrame(() =>
      requestAnimationFrame(() => {
        const since = (time) => Math.round(time - timing.up);
        console.info(
          `honmani tap timing: ${timing.via} +${since(timing.arrived)} ms after finger up, ` +
            `message +${since(timing.posted)}, marked +${since(marked)}, painted +${since(performance.now())}`,
        );
      }),
    );
  }

  /**
   * Looks up a selection once it has settled (a long press selects a word), when localStorage
   * "honmaniSelectionLookup" is not "0". The selected text, up to SCAN_LENGTH characters, is the
   * query, found by the same dictionary scan as for a tap. The message says "selection": true
   * and the selection is cleared, so Android's handles and Copy toolbar go away (the lookup
   * panel offers the copying); with the switch off the selection is left alone.
   */
  const SELECTION_SETTLE = 250;
  let selectionTimer = 0;
  let lastSelected = '';

  function selectionLookupOn() {
    try {
      return window.localStorage.getItem('honmaniSelectionLookup') !== '0';
    } catch (error) {
      return true;
    }
  }

  /** The first text node of the book (furigana skipped) a range reaches, and where it starts. */
  function selectionStart(range, root) {
    if (range.startContainer.nodeType === Node.TEXT_NODE && !isSkipped(range.startContainer)) {
      return { node: range.startContainer, index: range.startOffset };
    }
    const walker = textWalker(root);
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      if (range.comparePoint(node, 0) > 0) return null;
      if (range.intersectsNode(node)) return { node, index: node === range.startContainer ? range.startOffset : 0 };
    }
    return null;
  }

  /** How many characters of book text (no furigana) the range holds from (node, index). */
  function selectedLength(range, root, node, index) {
    const walker = textWalker(root);
    walker.currentNode = node;
    let length = 0;
    for (let current = node; current && range.intersectsNode(current); current = walker.nextNode()) {
      const from = current === node ? index : 0;
      const to = current === range.endContainer ? range.endOffset : current.data.length;
      length += Math.max(0, to - from);
    }
    return length;
  }

  /** A Range over text[from, to) of a block's text as blockText reads it, or null. */
  function rangeFromText(block, from, to) {
    const walker = lineWalker(block);
    const range = document.createRange();
    let offset = 0;
    let started = false;
    for (let current = walker.nextNode(); current; current = walker.nextNode()) {
      const element = current.nodeType === Node.ELEMENT_NODE;
      const length = element ? 1 : textData(current).length;
      if (!started && from < offset + length) {
        if (element) range.setStartBefore(current);
        else range.setStart(current, from - offset);
        started = true;
      }
      if (started && to <= offset + length) {
        if (element) range.setEndAfter(current);
        else range.setEnd(current, to - offset);
        return range;
      }
      offset += length;
    }
    return null;
  }

  /**
   * What a highlight of the selection can cover, as places in the book (highlights.js measure):
   * { word (the selection), sentence, paragraph }. The sentence runs from the start of the
   * selection's first sentence to the end of its last, the paragraph is its block (a line, when the
   * text sits straight in the page). A selection that leaves its block has only the word.
   */
  function highlightExtents(root, from, range) {
    const word = window.honmaniHighlights.measure(range);
    if (!word) return null;
    const extents = { word };
    const block = blockAncestor(from.node, root);
    const { text, position } = blockText(block, from.node, from.index);
    const last = blockText(block, range.endContainer, range.endOffset);
    if (position < 0 || last.position < 0 || range.endContainer.nodeType !== Node.TEXT_NODE) return extents;
    const lastCharacter = Math.max(position, last.position - 1);
    const measureText = (start, end) => {
      while (start < end && /\s/.test(text[start])) start++;
      while (end > start && /\s/.test(text[end - 1])) end--;
      const part = start < end ? rangeFromText(block, start, end) : null;
      return part ? window.honmaniHighlights.measure(part) : null;
    };
    const sentence = measureText(sentenceBounds(text, position).start, sentenceBounds(text, lastCharacter).end);
    if (sentence) extents.sentence = sentence;
    let start = 0;
    let end = text.length;
    if (block === root) {
      start = text.lastIndexOf('\n', position - 1) + 1;
      const next = text.indexOf('\n', lastCharacter);
      end = next < 0 ? text.length : next;
    }
    const paragraph = measureText(start, end);
    if (paragraph) extents.paragraph = paragraph;
    return extents;
  }

  function lookupSelection() {
    selectionTimer = 0;
    const selection = window.getSelection();
    if (!selection || selection.isCollapsed || selection.rangeCount === 0 || !selectionLookupOn()) return;
    const range = selection.getRangeAt(0);
    const inside = range.commonAncestorContainer;
    const root = (inside.nodeType === Node.ELEMENT_NODE ? inside : inside.parentElement)?.closest(BOOK_ROOT_SELECTOR);
    if (!root) return;
    const selected = selection.toString().trim();
    if (!selected || selected === lastSelected) return;
    if (lookupRange(range, root)) {
      lastSelected = selected;
      selection.removeAllRanges();
    }
  }

  /** Looks up the book text a range holds as a selection lookup; false when there is nothing to look up. */
  function lookupRange(range, root) {
    const from = selectionStart(range, root);
    if (!from) return false;
    const length = selectedLength(range, root, from.node, from.index);
    const message = lookupFrom(root, from.node, from.index, length);
    if (!message) return false;
    clearHighlight();
    message.selection = true;
    // The selection, its sentence and its paragraph as places in the book, for a highlight.
    const extents = window.honmaniHighlights ? highlightExtents(root, from, range) : null;
    if (extents) message.highlight = extents;
    post(message);
    return true;
  }

  document.addEventListener('selectionchange', () => {
    clearTimeout(selectionTimer);
    const selection = window.getSelection();
    if (!selection || selection.isCollapsed) {
      lastSelected = '';
      selectionTimer = 0;
      return;
    }
    selectionTimer = setTimeout(lookupSelection, SELECTION_SETTLE);
  });

  /**
   * Honmani's own long press, instead of Android's text selection (whose blue highlight, handles and
   * toolbar differ between WebView versions). A finger held still on book text for LONG_PRESS_TIME
   * marks the word under it (Intl.Segmenter) in the lookup mark's colour, so the reader sees what
   * is chosen; lifting the finger looks it up as a selection lookup ("selection": true, with the
   * highlight extents). Moving more than LONG_PRESS_SLOP, scrolling, a second finger or a cancelled
   * touch drops the mark and looks nothing up: a drag cannot extend the word. While the switch
   * honmaniSelectionLookup is on, the book text cannot be selected natively (user-select: none, no
   * callout, no context menu); with it off, Android's selection is left alone.
   */
  const LONG_PRESS_TIME = 400;
  const LONG_PRESS_SLOP = 12;
  const NO_SELECT_ID = 'honmani-noselect';
  const WORD_WINDOW = 40;
  let noSelectElement = null;
  let hold = null;
  /** Where and when the last long press ended, so the click that follows is kept from ttu once. */
  let lastLongPress = null;
  let wordSegmenter;

  /** Keeps the book text unselectable while selection lookups are on. */
  function syncNoSelect() {
    const style = noSelectElement;
    if (!selectionLookupOn()) {
      if (style) style.remove();
      noSelectElement = null;
      return;
    }
    if (style && style.isConnected) return;
    const parent = document.head || document.documentElement;
    if (!parent) return;
    const element = document.createElement('style');
    element.id = NO_SELECT_ID;
    element.textContent =
      `${BOOK_ROOT_SELECTOR}, ${BOOK_ROOT_SELECTOR} * { ` +
      '-webkit-user-select: none !important; user-select: none !important; ' +
      '-webkit-touch-callout: none !important; }';
    noSelectElement = element;
    parent.appendChild(element);
  }

  /** The [start, end) of the word at `position` in `text`, or null on whitespace, punctuation and symbols. */
  function wordBounds(text, position) {
    const character = String.fromCodePoint(text.codePointAt(position));
    if (/^[\s\p{P}\p{S}\p{C}￼]/u.test(character)) return null;
    if (wordSegmenter === undefined) {
      try {
        wordSegmenter = new Intl.Segmenter('ja', { granularity: 'word' });
      } catch (error) {
        wordSegmenter = null;
      }
    }
    if (wordSegmenter) {
      const from = Math.max(0, position - WORD_WINDOW);
      const piece = text.slice(from, position + WORD_WINDOW);
      for (const part of wordSegmenter.segment(piece)) {
        const start = from + part.index;
        const end = start + part.segment.length;
        if (position < start || position >= end) continue;
        return part.isWordLike === false ? null : { start, end };
      }
    }
    // Without Intl.Segmenter: the run of the same kind of letters (kanji, hiragana, katakana, other).
    const run = /^(?:\p{sc=Han}+|\p{sc=Hiragana}+|\p{sc=Katakana}+|[\p{L}\p{N}]+)/u.exec(text.slice(position));
    return { start: position, end: position + (run ? run[0].length : character.length) };
  }

  /** A Range over the word under (x, y) in the book, or null when the finger is not on a word. */
  function wordRangeAt(x, y, target, root) {
    const hit = rubyBaseOf(target) || charAt(x, y);
    if (!hit || !root.contains(hit.node)) return null;
    const block = blockAncestor(hit.node, root);
    const { text, position } = blockText(block, hit.node, hit.index);
    if (position < 0 || position >= text.length) return null;
    const bounds = wordBounds(text, position);
    return bounds ? rangeFromText(block, bounds.start, bounds.end) : null;
  }

  /** One Range per text node of the book text (furigana left out) a range holds. */
  function textRanges(range, root) {
    const from = selectionStart(range, root);
    if (!from) return [];
    const walker = textWalker(root);
    walker.currentNode = from.node;
    const ranges = [];
    for (let node = from.node; node && range.intersectsNode(node); node = walker.nextNode()) {
      const part = document.createRange();
      part.setStart(node, node === from.node ? from.index : 0);
      part.setEnd(node, node === range.endContainer ? range.endOffset : node.data.length);
      if (!part.collapsed) ranges.push(part);
    }
    return ranges;
  }

  function cancelHold() {
    const current = hold;
    hold = null;
    if (!current) return;
    clearTimeout(current.timer);
    if (current.range) clearHighlight();
  }

  function startHold(event) {
    cancelHold();
    lastLongPress = null;
    if (!event.isPrimary || event.pointerType === 'mouse') return;
    // At document start there may be no <html> yet; each touch checks again.
  syncNoSelect();
  document.addEventListener('DOMContentLoaded', syncNoSelect);
    const target = event.target;
    if (!selectionLookupOn() || !(target instanceof Element)) return;
    const root = target.closest(BOOK_ROOT_SELECTOR);
    if (!root || target.closest(INTERACTIVE_SELECTOR) || pictureAt(target)) return;
    const current = { x: event.clientX, y: event.clientY, target, root, range: null, timer: 0 };
    current.timer = setTimeout(() => {
      current.timer = 0;
      if (hold !== current) return;
      const range = wordRangeAt(current.x, current.y, target, root);
      const ranges = range ? textRanges(range, root) : [];
      if (ranges.length === 0) return;
      current.range = range;
      const selection = window.getSelection();
      if (selection) selection.removeAllRanges();
      clearHighlight();
      showMark(ranges, null);
    }, LONG_PRESS_TIME);
    hold = current;
  }

  /** The finger went up on a long press: look the marked word up. Returns whether there was one. */
  function endHold(event) {
    const current = hold;
    if (!current || !current.range) {
      cancelHold();
      return false;
    }
    hold = null;
    lastLongPress = { x: event.clientX, y: event.clientY, time: event.timeStamp };
    const selection = window.getSelection();
    if (selection) selection.removeAllRanges();
    if (!lookupRange(current.range, current.root)) clearHighlight();
    return true;
  }

  document.addEventListener(
    'contextmenu',
    (event) => {
      if (event.target instanceof Element && event.target.closest(BOOK_ROOT_SELECTOR) && selectionLookupOn()) {
        event.preventDefault();
      }
    },
    true,
  );
  syncNoSelect();

  /**
   * The picture a tap landed on, for the zoom viewer: an <img> or an SVG <image> in the book (a
   * cover page is one too). Not ttu's gaiji, not a picture in a link (the link wins), and not a
   * tiny inline one (a character's picture, an emoji).
   */
  function pictureAt(target) {
    if (target.closest(BOOK_ROOT_SELECTOR) === null) return null;
    let picture = target.closest('img, image');
    if (!picture) {
      const svg = target.closest('svg');
      picture = svg && svg.querySelector('image');
    }
    if (!picture || isGaiji(picture) || picture.closest('a[href]') !== null) return null;
    const box = (picture.closest('svg') || picture).getBoundingClientRect();
    return Math.max(box.width, box.height) < MIN_PICTURE_SIZE ? null : picture;
  }

  let pictureBusy = false;

  function readAsDataUrl(blob) {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(String(reader.result));
      reader.onerror = () => reject(reader.error);
      reader.readAsDataURL(blob);
    });
  }

  /** The picture drawn on a canvas no longer than `side`, as a JPEG on white (transparent areas stay readable). */
  function reencode(src, side) {
    return new Promise((resolve, reject) => {
      const image = new Image();
      image.onload = () => {
        const width = image.naturalWidth || side;
        const height = image.naturalHeight || side;
        const scale = Math.min(1, side / Math.max(width, height));
        const canvas = document.createElement('canvas');
        canvas.width = Math.max(1, Math.round(width * scale));
        canvas.height = Math.max(1, Math.round(height * scale));
        const context = canvas.getContext('2d');
        context.fillStyle = '#fff';
        context.fillRect(0, 0, canvas.width, canvas.height);
        context.drawImage(image, 0, 0, canvas.width, canvas.height);
        resolve(canvas.toDataURL('image/jpeg', 0.9));
      };
      image.onerror = () => reject(new Error('picture not decodable'));
      image.src = src;
    });
  }

  /**
   * Sends the picture's bytes to the app as a data URL: the page's Content-Security-Policy lets
   * it read its own blob: pictures but nothing else, so the app cannot fetch them itself. A
   * small PNG, JPEG, WebP or GIF goes through as it is; anything else, or a big file, is drawn
   * on a canvas first (SVG pictures too), at most MAX_PICTURE_SIDE long.
   */
  async function sendPicture(picture) {
    if (pictureBusy) return;
    pictureBusy = true;
    try {
      const src = picture instanceof SVGElement
        ? (picture.href && picture.href.baseVal) || picture.getAttribute('xlink:href') || ''
        : picture.currentSrc || picture.src;
      if (!src) return;
      let data = null;
      try {
        const blob = await (await fetch(src)).blob();
        if (blob.size <= MAX_PICTURE_BYTES && PASS_THROUGH_TYPES.includes(blob.type)) {
          data = await readAsDataUrl(blob);
        }
      } catch (error) {
        data = null;
      }
      for (let i = 0; i < PICTURE_SIDES.length && (data === null || data.length > MAX_PICTURE_DATA); i++) {
        data = await reencode(src, PICTURE_SIDES[i]);
      }
      if (data !== null && data.length <= MAX_PICTURE_DATA) post({ type: 'image', data });
    } catch (error) {
      // A picture the page cannot read: the tap does nothing.
    } finally {
      pictureBusy = false;
    }
  }

  function onTap(x, y, target, arrived, via) {
    if (!(target instanceof Element)) return;
    const selection = window.getSelection();
    if (selection && !selection.isCollapsed) return;

    // Before the edge taps and the lookup: a tap on a picture only opens the viewer.
    const picture = pictureAt(target);
    if (picture) {
      clearHighlight();
      tapNumber++;
      sendPicture(picture);
      return;
    }

    const next = edgeTurn(x, target);
    if (next !== null) {
      clearHighlight();
      tapNumber++;
      post({ type: 'turnPage', next });
      return;
    }

    const message = lookupAt(x, y, target);
    if (message) {
      clearHighlight();
      post(message);
      tapTiming = { up: lastUp || arrived, arrived, posted: performance.now(), via };
    } else if (isBookBackground(target)) {
      clearHighlight();
      tapNumber++;
      post({ type: 'dismiss' });
    }
  }

  // Capture phase on window: ttu cancels clicks on <ruby> (to reveal furigana) with
  // stopImmediatePropagation(), so a bubbling listener would miss taps on ruby words.
  // The event is left untouched so ttu still handles it.
  window.addEventListener(
    'click',
    (event) => {
      // The click that follows a long press is kept from ttu (it would reveal furigana, say).
      const press = lastLongPress;
      lastLongPress = null;
      if (
        press &&
        event.timeStamp - press.time < CLICK_AFTER_TAP &&
        Math.hypot(event.clientX - press.x, event.clientY - press.y) <= LONG_PRESS_SLOP
      ) {
        event.stopImmediatePropagation();
        event.preventDefault();
        return;
      }
      const tap = lastTapUp;
      lastTapUp = null;
      // The click of a tap already looked up when the finger went up.
      if (
        tap &&
        event.timeStamp - tap.time < CLICK_AFTER_TAP &&
        Math.hypot(event.clientX - tap.x, event.clientY - tap.y) <= TAP_SLOP
      ) {
        return;
      }
      onTap(event.clientX, event.clientY, event.target, event.timeStamp, 'click');
    },
    true,
  );

  // A short, still tap is looked up when the finger goes up, not at its click: the click comes
  // a little later, and not at all for a tap that stops a fling, such as one up to a second
  // after a page swipe (seen on a OnePlus 7 Pro), which then waited 350 ms for it.
  let tapStart = null;
  let lastTapUp = null;
  window.addEventListener(
    'pointerdown',
    (event) => {
      tapStart = event.isPrimary ? { x: event.clientX, y: event.clientY, time: event.timeStamp } : null;
      if (event.isPrimary) startHold(event);
      else cancelHold();
    },
    true,
  );
  window.addEventListener(
    'pointermove',
    (event) => {
      if (hold && event.isPrimary && Math.hypot(event.clientX - hold.x, event.clientY - hold.y) > LONG_PRESS_SLOP) {
        cancelHold();
      }
    },
    true,
  );
  window.addEventListener(
    'pointerup',
    (event) => {
      const start = tapStart;
      tapStart = null;
      lastUp = event.timeStamp;
      if (!start || !event.isPrimary) return;
      if (endHold(event)) return;
      const moved = Math.hypot(event.clientX - start.x, event.clientY - start.y);
      if (moved > TAP_SLOP || event.timeStamp - start.time > TAP_TIME) return;
      lastTapUp = { x: event.clientX, y: event.clientY, time: event.timeStamp };
      onTap(event.clientX, event.clientY, event.target, event.timeStamp, 'tap');
    },
    true,
  );
  window.addEventListener('pointercancel', () => {
    tapStart = null;
    cancelHold();
  }, true);

  // The fallback mark follows the text: capture catches scrolls of ttu's inner containers too.
  const redrawOverlay = () => {
    if (overlayMark) drawOverlay();
  };
  window.addEventListener('scroll', cancelHold, true);
  window.addEventListener('scroll', redrawOverlay, true);
  window.addEventListener('resize', redrawOverlay);
})();
