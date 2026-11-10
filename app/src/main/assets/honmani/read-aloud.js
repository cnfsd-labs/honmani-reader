/*
 * Read aloud, the page's half, injected at document start into the ッツ Ebook Reader (ttu)
 * WebView. The app speaks (Android TextToSpeech); this script cuts the book into sentences, in
 * reading order from where the reader is, marks the one being spoken and keeps it on screen
 * (sentence-follow.js, which knows nothing about speech). It never speaks and has no timing of
 * its own: everything happens on messages from the app.
 *
 * The page owns the cursor and cuts sentences lazily from the live DOM, so a book of any length
 * costs nothing up front and ttu's own section changes (paginated mode shows one section at a
 * time) are followed. A sentence is the app's sentence (honmaniText.sentenceSpans: the configured
 * sentence ends, the same cut as the tap bridge) inside one paragraph. What is spoken:
 *   - the reading in the <rt> over a ruby base instead of the base (a base with no reading, or an
 *     empty one, is read as written); rt and rp are never read as text;
 *   - nothing for pictures and gaiji; sentences without a letter or digit are skipped;
 *   - a sentence over 4,000 characters is split (after a comma or space where it can).
 *
 * App to page (all carry `gen`, the app's counter; the page answers only the latest one):
 *   { type: 'readAloud.begin', gen, count }            start with the first sentence that begins on
 *                                                      the page on screen (index 0)
 *   { type: 'readAloud.seek', gen, index, count }      start again at a sentence sent before
 *   { type: 'readAloud.more', gen, count }             the next sentences
 *   { type: 'readAloud.advance', gen, count }          paginated mode: the section's text is read
 *                                                      to its end; turn pages into the next section
 *   { type: 'readAloud.mark', gen, index, offset }     the sentence is being spoken (from character
 *                                                      `offset` of its text, when the engine says)
 *   { type: 'readAloud.stop' }                         remove the mark, forget the cursor
 *
 * Page to app:
 *   { type: 'readAloud.sentences', gen, items: [{ i, text }], end }
 *       end: null, 'section' (paginated: the text on the page's section is used up), 'book'
 *   { type: 'readAloud.lost', gen }   a seek reached text that is no longer in the page
 *
 * Page turns and scrolling go through honmaniFollow.keepOnScreen, the reader's own path.
 */
(() => {
  'use strict';

  if (window.honmaniReadAloud) return;

  const { isSkipped, isGaiji, textData, blockAncestor, sentenceSpans, firstCharOnScreen } = window.honmaniText;
  const BOOK_ROOT_SELECTOR = '.book-content';
  const SKIPPED_SELECTOR = 'rt, rp, script, style';
  const MAX_SENTENCE = 4_000;
  const MAX_COUNT = 10;
  /** Sentences kept for seeking back. */
  const KEEP_RECORDS = 400;
  const MAX_ADVANCE_TURNS = 40;
  // How long after a page turn that moved nothing the next section may take to appear before the
  // book counts as ended (ttu loads it asynchronously; a slow phone needs more than a few frames).
  const SECTION_LOAD_MS = 2500;
  const LETTER = /[\p{L}\p{N}]/u;
  const BLANK = /^[\s￼]$/;

  const nextFrame = () => new Promise((resolve) => requestAnimationFrame(() => resolve()));

  function isPaginated() {
    try {
      return (localStorage.getItem('viewMode') || 'paginated') === 'paginated';
    } catch (error) {
      return true;
    }
  }

  // ---- Walking the book's text -----------------------------------------------------------------

  const isSkippedElement = (node) => node.nodeType === 1 && node.matches(SKIPPED_SELECTOR);

  /** A text node of book text, a line break or a gaiji picture: what a sentence is made of. */
  function isToken(node) {
    if (node.nodeType === 3) return !isSkipped(node);
    if (node.nodeType !== 1) return false;
    if (node.tagName === 'BR') return node.closest(SKIPPED_SELECTOR) === null;
    return isGaiji(node);
  }

  function following(node, root, descend) {
    if (descend && node.firstChild) return node.firstChild;
    for (let current = node; current && current !== root; current = current.parentNode) {
      if (current.nextSibling) return current.nextSibling;
    }
    return null;
  }

  function nextToken(node, root) {
    let current = following(node, root, !isSkippedElement(node));
    while (current) {
      if (isToken(current)) return current;
      current = following(current, root, !isSkippedElement(current));
    }
    return null;
  }

  function preceding(node, root) {
    if (node.previousSibling) {
      let current = node.previousSibling;
      while (current.lastChild) current = current.lastChild;
      return current;
    }
    const parent = node.parentNode;
    return parent && parent !== root ? parent : null;
  }

  function previousToken(node, root) {
    for (let current = preceding(node, root); current; current = preceding(current, root)) {
      if (isToken(current)) return current;
    }
    return null;
  }

  /** The reading over a ruby base text node: the first <rt> after it inside its <ruby>, or null. */
  function readingOf(node) {
    const ruby = node.parentElement && node.parentElement.closest('ruby');
    if (!ruby) return null;
    let top = node;
    while (top.parentNode && top.parentNode !== ruby) top = top.parentNode;
    for (let sibling = top.nextSibling; sibling; sibling = sibling.nextSibling) {
      if (sibling.nodeType === 1 && sibling.tagName === 'RT') return sibling;
    }
    return null;
  }

  /**
   * One paragraph's worth of tokens (those with the same nearest block) as text: `text` is what the
   * browser shows (furigana left out, a line break "\n", a gaiji U+FFFC), `speech[i]` what to say for
   * character i (the reading on a ruby base's first character, nothing for the rest of the base),
   * and startNode/startOff/endNode/endOff[i] where character i is in the DOM.
   */
  function buildRun(first, root) {
    const blocks = new Map();
    const blockOf = (token) => {
      const parent = token.parentElement;
      if (!parent) return root;
      if (!blocks.has(parent)) blocks.set(parent, blockAncestor(token, root));
      return blocks.get(parent);
    };
    const block = blockOf(first);
    const run = { tokens: [], text: '', speech: [], startNode: [], startOff: [], endNode: [], endOff: [], base: new Map(), after: null };
    const pieces = [];
    const read = new Set();
    let token = first;
    for (; token && blockOf(token) === block; token = nextToken(token, root)) {
      run.tokens.push(token);
      if (token.nodeType === 3) {
        const data = textData(token);
        run.base.set(token, run.speech.length);
        const rt = readingOf(token);
        const reading = rt ? rt.textContent.trim() : '';
        const useReading = reading !== '' && !read.has(rt);
        if (rt && reading !== '') read.add(rt);
        for (let i = 0; i < data.length; i++) {
          run.speech.push(!rt || reading === '' ? data[i] : useReading && i === 0 ? reading : '');
          run.startNode.push(token);
          run.startOff.push(i);
          run.endNode.push(token);
          run.endOff.push(i + 1);
        }
        pieces.push(data);
      } else {
        const parent = token.parentNode;
        const index = Array.prototype.indexOf.call(parent.childNodes, token);
        const isBreak = token.tagName === 'BR';
        run.speech.push('');
        run.startNode.push(parent);
        run.startOff.push(index);
        run.endNode.push(parent);
        run.endOff.push(index + 1);
        pieces.push(isBreak ? '\n' : '￼');
      }
    }
    run.text = pieces.join('');
    run.after = token;
    return run;
  }

  /** Where to cut `text` between `start` and `end` (> MAX_SENTENCE apart): after a comma or space, else hard. */
  function cutPoint(text, start, end) {
    const limit = start + MAX_SENTENCE;
    for (let i = limit; i > start + MAX_SENTENCE / 2; i--) {
      if ('、， ,　'.includes(text[i - 1])) return i;
    }
    // Never between the halves of a surrogate pair.
    const code = text.charCodeAt(limit - 1);
    return code >= 0xd800 && code <= 0xdbff ? limit - 1 : limit;
  }

  /** The sentences of a run worth speaking: [{ s, e, speech }] with s..e trimmed of blanks. */
  function candidatesOf(run) {
    const found = [];
    const pushSpan = (from, to) => {
      let s = from;
      let e = to;
      while (s < e && BLANK.test(run.text[s])) s++;
      while (e > s && BLANK.test(run.text[e - 1])) e--;
      if (s >= e) return;
      const speech = run.speech.slice(s, e).join('');
      if (LETTER.test(speech)) found.push({ s, e, speech });
    };
    for (const { start, end } of sentenceSpans(run.text)) {
      for (let from = start; from < end; ) {
        const to = end - from > MAX_SENTENCE ? cutPoint(run.text, from, end) : end;
        pushSpan(from, to);
        from = to;
      }
    }
    return found;
  }

  function rangeOf(run, s, e) {
    const range = document.createRange();
    range.setStart(run.startNode[s], run.startOff[s]);
    range.setEnd(run.endNode[e - 1], run.endOff[e - 1]);
    return range;
  }

  // ---- The session -----------------------------------------------------------------------------

  /**
   * session: { gen, run, cands, pos, after, nextIndex, records: Map(index -> record), queue }
   *   run/cands/pos: the paragraph being cut and the next sentence in it; after: the first token
   *   after it (where the next paragraph starts); record: { i, run, cands, candIndex, s, e, speech }
   */
  let session = null;

  const rootElement = () => document.querySelector(BOOK_ROOT_SELECTOR);

  function post(message) {
    const app = window.honmani;
    if (app && typeof app.postMessage === 'function') app.postMessage(JSON.stringify(message));
  }

  function openRun(token, root) {
    session.run = buildRun(token, root);
    session.cands = candidatesOf(session.run);
    session.pos = 0;
    session.after = session.run.after;
  }

  function makeRecord(index, candIndex, override) {
    const cand = override || session.cands[candIndex];
    return { i: index, run: session.run, cands: session.cands, candIndex, s: cand.s, e: cand.e, speech: cand.speech };
  }

  /** The next sentence as a record (not yet numbered into the session), or null at the end of the text. */
  function cutNext() {
    const root = rootElement();
    for (;;) {
      if (session.run && session.pos < session.cands.length) {
        const candIndex = session.pos++;
        return makeRecord(session.nextIndex, candIndex);
      }
      if (!session.after || !root) return null;
      openRun(session.after, root);
    }
  }

  function take() {
    const record = cutNext();
    if (!record) return null;
    session.nextIndex++;
    session.records.set(record.i, record);
    session.records.delete(record.i - KEEP_RECORDS);
    return record;
  }

  /** Sends up to `count` sentences (the ones in `lead` first) and says if the text ran out. */
  function reply(count, lead = []) {
    const items = lead.slice();
    let end = null;
    while (items.length < Math.min(count, MAX_COUNT)) {
      const record = take();
      if (!record) {
        end = isPaginated() ? 'section' : 'book';
        break;
      }
      items.push({ i: record.i, text: record.speech });
    }
    post({ type: 'readAloud.sentences', gen: session.gen, items, end });
  }

  function newSession(gen) {
    if (session) window.honmaniFollow.clear();
    session = { gen, run: null, cands: [], pos: 0, after: null, nextIndex: 0, markedIndex: -1, records: new Map(), queue: Promise.resolve() };
    return session;
  }

  /** The first token of the book text on screen, from where the reading starts. */
  function startToken(root) {
    const first = firstCharOnScreen(root, 'rt, rp', !isPaginated());
    return first && first.node.nodeType === 3 ? first : null;
  }

  function firstTokenOf(root) {
    return nextToken(root, root);
  }

  /** The start of the paragraph that holds `token`. */
  function paragraphStart(token, root) {
    const blockOf = (t) => (t.parentElement ? blockAncestor(t, root) : root);
    const block = blockOf(token);
    let first = token;
    for (let before = previousToken(first, root); before && blockOf(before) === block; before = previousToken(before, root)) first = before;
    return first;
  }

  function begin(message) {
    const root = rootElement();
    const fresh = newSession(message.gen);
    const here = root && startToken(root);
    if (!here) {
      // Nothing to see on the page (a picture page): start at the section's first text.
      if (root) fresh.after = firstTokenOf(root);
      reply(message.count);
      return;
    }
    openRun(paragraphStart(here.node, root), root);
    const run = fresh.run;
    const c = run.base.get(here.node) + here.offset;
    const index = fresh.cands.findIndex((cand) => cand.e > c);
    if (index < 0) {
      // All of this paragraph is before the first visible character (a blank tail).
      fresh.pos = fresh.cands.length;
      reply(message.count);
      return;
    }
    const cand = fresh.cands[index];
    if (cand.s >= c) {
      fresh.pos = index;
      reply(message.count);
      return;
    }
    // The sentence began on an earlier page: the next one if it begins on this page, else the rest of this one.
    const saved = { run: fresh.run, cands: fresh.cands, after: fresh.after };
    fresh.pos = index + 1;
    const next = cutNext();
    const startsHere = next && window.honmaniFollow.startOnScreen(rangeOf(next.run, next.s, next.e));
    Object.assign(fresh, saved, { pos: index + 1 });
    if (startsHere) {
      reply(message.count);
      return;
    }
    let s = c;
    while (s < cand.e && BLANK.test(run.text[s])) s++;
    const rest = { s, e: cand.e, speech: run.speech.slice(s, cand.e).join('') };
    if (!LETTER.test(rest.speech)) {
      reply(message.count);
      return;
    }
    const record = makeRecord(fresh.nextIndex++, index, rest);
    fresh.records.set(record.i, record);
    reply(message.count, [{ i: record.i, text: record.speech }]);
  }

  function seek(message) {
    if (!session) {
      // The page was reloaded since (a font change): the app starts again from the page.
      post({ type: 'readAloud.lost', gen: message.gen });
      return;
    }
    const record = session.records.get(message.index);
    if (!record || !record.run.tokens[0].isConnected || !record.run.tokens[record.run.tokens.length - 1].isConnected) {
      post({ type: 'readAloud.lost', gen: message.gen });
      return;
    }
    session.gen = message.gen;
    session.run = record.run;
    session.cands = record.cands;
    session.pos = record.candIndex;
    session.after = record.run.after;
    session.nextIndex = record.i;
    reply(message.count);
  }

  function more(message) {
    if (!session) {
      post({ type: 'readAloud.lost', gen: message.gen });
      return;
    }
    session.gen = message.gen;
    reply(message.count);
  }

  /** The text of the page's section changed: the first text node is another one. */
  async function advance(message) {
    if (!session) {
      post({ type: 'readAloud.lost', gen: message.gen });
      return;
    }
    session.gen = message.gen;
    const gen = message.gen;
    // A mark still turning pages to its sentence would turn back the page turns below, and the
    // section that does not come would be taken for the end of the book: let it finish first
    // (marks not yet started are dropped, they belong to the old generation).
    const current = session;
    await current.queue.catch(() => {});
    if (session !== current || session.gen !== gen) return;
    const root = rootElement();
    if (!root || !isPaginated()) {
      post({ type: 'readAloud.sentences', gen, items: [], end: 'book' });
      return;
    }
    // The next section replaces this one's text, so the view has to go with it, detached or not.
    window.honmaniFollow.reattach();
    const before = firstTokenOf(root);
    let last = startToken(root);
    await window.honmaniFollow.auto(() => advanceTurns(message, gen, root, before, last));
  }

  async function advanceTurns(message, gen, root, before, last) {
    for (let turns = 0; turns < MAX_ADVANCE_TURNS; turns++) {
      window.dispatchEvent(new KeyboardEvent('keydown', { code: 'ArrowDown', key: 'ArrowDown', bubbles: true }));
      await nextFrame();
      await nextFrame();
      if (!session || session.gen !== gen) return;
      let now = rootElement();
      let token = now && firstTokenOf(now);
      let here = now && startToken(now);
      const unmoved = () => !(token && token !== before) && (!here || (last && here.node === last.node && here.offset === last.offset));
      // Nothing changed yet: the next section may still be loading, so look again for a while.
      for (const deadline = performance.now() + SECTION_LOAD_MS; unmoved() && performance.now() < deadline; ) {
        await nextFrame();
        if (!session || session.gen !== gen) return;
        now = rootElement();
        token = now && firstTokenOf(now);
        here = now && startToken(now);
      }
      if (token && token !== before) {
        session.run = null;
        session.cands = [];
        session.pos = 0;
        session.after = token;
        reply(message.count);
        return;
      }
      if (unmoved()) break; // The end of the book.
      last = here;
    }
    post({ type: 'readAloud.sentences', gen, items: [], end: 'book' });
  }

  /** Where in the sentence's display text the spoken character `offset` is: the index into run. */
  function displayIndexOf(record, offset) {
    let spoken = 0;
    for (let i = record.s; i < record.e; i++) {
      spoken += record.run.speech[i].length;
      if (spoken > offset) return i;
    }
    return record.e - 1;
  }

  function mark(message) {
    if (!session || session.gen !== message.gen) return;
    const record = session.records.get(message.index);
    if (!record) return;
    const current = session;
    current.queue = current.queue.then(async () => {
      if (session !== current || current.gen !== message.gen) return;
      if (!record.run.tokens[0].isConnected) return;
      const whole = rangeOf(record.run, record.s, record.e);
      // The engine reports every word of a sentence: the mark is drawn once per sentence.
      if (current.markedIndex !== record.i || message.offset === 0) {
        window.honmaniFollow.mark(whole);
        current.markedIndex = record.i;
      }
      const from = message.offset > 0 ? displayIndexOf(record, message.offset) : record.s;
      await window.honmaniFollow.keepOnScreen(from === record.s ? whole : rangeOf(record.run, from, record.e));
    });
  }

  function stop() {
    window.honmaniFollow.clear();
    session = null;
  }

  function onAppMessage(event) {
    let message;
    try {
      message = JSON.parse(event.data);
    } catch (error) {
      return;
    }
    if (!message || typeof message.type !== 'string' || !message.type.startsWith('readAloud.')) return;
    const known = { gen: Number.isInteger(message.gen), count: Number.isInteger(message.count) };
    switch (message.type) {
      case 'readAloud.begin':
        if (known.gen && known.count) begin(message);
        break;
      case 'readAloud.seek':
        if (known.gen && known.count && Number.isInteger(message.index)) seek(message);
        break;
      case 'readAloud.more':
        if (known.gen && known.count) more(message);
        break;
      case 'readAloud.advance':
        if (known.gen && known.count) advance(message);
        break;
      case 'readAloud.mark':
        if (known.gen && Number.isInteger(message.index)) {
          message.offset = Number.isInteger(message.offset) ? message.offset : 0;
          mark(message);
        }
        break;
      case 'readAloud.stop':
        stop();
        break;
    }
  }

  function listen() {
    const app = window.honmani;
    if (app && !app.__honmaniReadAloud && app.addEventListener) {
      app.__honmaniReadAloud = true;
      app.addEventListener('message', onAppMessage);
    }
  }

  window.honmaniReadAloud = Object.freeze({ stop });
  listen();
})();
