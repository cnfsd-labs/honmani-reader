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
 *
 * Selected book text (a long press) is looked up in the same message, with "selection": true,
 * once the selection has settled, and then deselected: "text" is the selection, up to
 * SCAN_LENGTH characters. localStorage
 * "honmaniSelectionLookup" = "0" (Honmani's reader settings) turns it off.
 *
 * Furigana (<rt>, <rp>) is not part of the scanned text; a tap on furigana scans from the start
 * of the base text it reads. Scanning stops at the end of the
 * tapped paragraph and at a line break (<br>). A tap on an opening bracket or quote scans from
 * the character after it.
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
  const { isSkipped, blockAncestor, textData } = window.honmaniText;
  const INTERACTIVE_SELECTOR = 'a[href], button, input, textarea, select, [role="button"]';
  const SENTENCE_END = '。！？!?…\n';
  const CLOSING_QUOTES = '」』）〉》】"\'';
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

  /** Like textWalker, but also stops at <br> elements. */
  function lineWalker(block) {
    return document.createTreeWalker(block, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_ELEMENT, {
      acceptNode: (node) => {
        if (node.nodeType === Node.ELEMENT_NODE) {
          return node.tagName === 'BR' ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_SKIP;
        }
        return isSkipped(node) ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT;
      },
    });
  }

  /**
   * Text of `block` without furigana (a <br> as "\n"), the index of (node, index) within it, and
   * the ruby bases in it as { start, end, reading } (indexes into the text).
   */
  function blockText(block, node, index) {
    const walker = lineWalker(block);
    let text = '';
    let position = -1;
    const ruby = [];
    let lastRt = null;
    for (let current = walker.nextNode(); current; current = walker.nextNode()) {
      if (current.nodeType === Node.ELEMENT_NODE) {
        text += '\n';
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
    let start = position;
    while (start > 0 && !SENTENCE_END.includes(text[start - 1])) start--;
    let end = position;
    while (end < text.length && !SENTENCE_END.includes(text[end])) end++;
    while (end < text.length && SENTENCE_END.includes(text[end])) end++;
    while (end < text.length && CLOSING_QUOTES.includes(text[end])) end++;

    const previous = sentenceBefore(text, start);

    // Keep very long "sentences" (e.g. no punctuation) to a window around the tap.
    if (end - start > MAX_SENTENCE_LENGTH) {
      start = Math.max(start, position - MAX_SENTENCE_LENGTH / 2);
      end = Math.min(end, start + MAX_SENTENCE_LENGTH);
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
    while (begin > 0 && (SENTENCE_END.includes(text[begin - 1]) || CLOSING_QUOTES.includes(text[begin - 1]))) begin--;
    while (begin > 0 && !SENTENCE_END.includes(text[begin - 1])) begin--;
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
    return lookupFrom(root, hit.node, hit.index, Infinity);
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
    const end = limit === Infinity ? paragraph.length : Math.max(start, position + limit);
    const text = takeCodePoints(paragraph.slice(start, end), SCAN_LENGTH).split('\n')[0].trimEnd();
    if (!text.trim() || /^\s/.test(text)) return null;

    const { sentence, offset, previous } = sentenceAround(paragraph, start);
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
    if (hasHighlightApi()) {
      setHighlightStyle(color);
      CSS.highlights.set(HIGHLIGHT_NAME, new Highlight(...ranges));
    } else {
      overlayMark = { ranges, background: highlightBackground(color) };
      drawOverlay();
    }
    logTiming(performance.now());
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
    const from = selectionStart(range, root);
    if (!from) return;
    const length = selectedLength(range, root, from.node, from.index);
    const message = lookupFrom(root, from.node, from.index, length);
    if (!message) return;
    lastSelected = selected;
    clearHighlight();
    message.selection = true;
    post(message);
    selection.removeAllRanges();
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

  function onTap(x, y, target, arrived, via) {
    if (!(target instanceof Element)) return;
    const selection = window.getSelection();
    if (selection && !selection.isCollapsed) return;

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
      const moved = Math.hypot(event.clientX - start.x, event.clientY - start.y);
      if (moved > TAP_SLOP || event.timeStamp - start.time > TAP_TIME) return;
      lastTapUp = { x: event.clientX, y: event.clientY, time: event.timeStamp };
      onTap(event.clientX, event.clientY, event.target, event.timeStamp, 'tap');
    },
    true,
  );
  window.addEventListener('pointercancel', () => {
    tapStart = null;
  }, true);

  // The fallback mark follows the text: capture catches scrolls of ttu's inner containers too.
  const redrawOverlay = () => {
    if (overlayMark) drawOverlay();
  };
  window.addEventListener('scroll', redrawOverlay, true);
  window.addEventListener('resize', redrawOverlay);
})();
