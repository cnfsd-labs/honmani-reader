/*
 * Audiobook read-along, the page's half, injected at document start into the ッツ Ebook Reader
 * (ttu) WebView. The app plays the audio and knows which subtitle is being heard; it works out
 * (CueAligner) where each subtitle is in the book, as book positions: counted characters, the
 * same units as the highlights (highlights.js). This script only draws the passage it is told
 * to and keeps it on screen (sentence-follow.js); it has no timing of its own.
 *
 * App to page:
 *   { type: 'audiobook.mark', gen, start, end }   the passage [start, end) is being heard
 *   { type: 'audiobook.clear' }                    remove the mark
 *
 * Page to app:
 *   { type: 'audiobook.lost', gen }   the passage could not be brought on screen (its section is
 *                                     too far away to turn to, or the text is not in the page)
 *
 * In paginated mode the page holds one section, so a passage in another section is reached by
 * turning pages the reader's own way (honmaniFollow.keepOnScreen does that within the section);
 * a few turns at most, then the app is told and decides (it can open the book there instead).
 * Marks come one at a time, in order: a newer one replaces one still on its way.
 */
(() => {
  'use strict';

  if (window.honmaniAudiobook) return;

  /** Section changes tried at most for one passage; each can take many page turns, so this is generous. */
  const MAX_SECTION_TURNS = 60;

  const nextFrame = () => new Promise((resolve) => requestAnimationFrame(() => resolve()));

  let latest = 0;

  function post(message) {
    const app = window.honmani;
    if (app && typeof app.postMessage === 'function') app.postMessage(JSON.stringify(message));
  }

  /** The Range of the passage, turning pages while its section is not in the page; null when out of reach. */
  async function reach(start, end, token) {
    let previous = null;
    for (let turns = 0; turns < MAX_SECTION_TURNS; turns++) {
      const found = window.honmaniHighlights.rangeAt(start, end);
      if (found.range) return found.range;
      if (!found.direction) return null;
      const code = found.direction > 0 ? 'ArrowDown' : 'ArrowUp';
      // The same first character after a turn: the end (or start) of the book; more turns won't help.
      const root = document.querySelector('.book-content');
      const first = root && window.honmaniText.firstCharOnScreen(root, 'rt, rp', false);
      if (previous && first && previous.node === first.node && previous.offset === first.offset) return null;
      previous = first;
      window.dispatchEvent(new KeyboardEvent('keydown', { code, key: code, bubbles: true }));
      await nextFrame();
      await nextFrame();
      if (token !== latest) return null;
    }
    return null;
  }

  async function mark(message) {
    const token = ++latest;
    const follow = window.honmaniFollow;
    // The reader took control: the mark moves with the audio, but no page is turned for it.
    // A passage already in the page needs no turn, so it does not count as our own scrolling.
    const inPage = window.honmaniHighlights.rangeAt(message.start, message.end).range;
    const range = inPage || follow.detached() ? inPage : await follow.auto(() => reach(message.start, message.end, token));
    if (token !== latest) return;
    if (follow.detached() && !range) return;
    if (!range) {
      post({ type: 'audiobook.lost', gen: message.gen });
      return;
    }
    follow.mark(range);
    await follow.keepOnScreen(range);
  }

  function clear() {
    latest++;
    window.honmaniFollow.clear();
  }

  function onAppMessage(event) {
    let message;
    try {
      message = JSON.parse(event.data);
    } catch (error) {
      return;
    }
    if (!message || typeof message.type !== 'string' || !message.type.startsWith('audiobook.')) return;
    if (message.type === 'audiobook.mark') {
      if (Number.isInteger(message.gen) && Number.isFinite(message.start) && Number.isFinite(message.end)) mark(message);
    } else if (message.type === 'audiobook.clear') {
      clear();
    }
  }

  function listen() {
    const app = window.honmani;
    if (app && !app.__honmaniAudiobook && app.addEventListener) {
      app.__honmaniAudiobook = true;
      app.addEventListener('message', onAppMessage);
    }
  }

  window.honmaniAudiobook = Object.freeze({ clear });
  listen();
})();
