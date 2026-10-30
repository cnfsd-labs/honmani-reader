/*
 * Tells Honmani that the reader is active in a book, injected at document start into the
 * ッツ Ebook Reader (ttu) WebView. Honmani counts reading time while a book is on screen, but
 * not after minutes without any activity (a phone left on the table).
 *
 * On the book page, touches, keys, wheel and scrolling (page turns, lookups) post
 * { type: 'reader.activity' }, at most once every 15 seconds.
 *
 * ttu saves its bookmark on every page turn; each save on the book page posts
 * { type: 'reader.position', id, characters, progress } (ttu's book id, the characters read up to
 * the new position and, when ttu has it, the position as a fraction of the book), so Honmani can
 * tell pages read from pages flicked past, and which part of the book each page was.
 */
(() => {
  'use strict';

  if (window.__honmaniReadingActivity) return;
  window.__honmaniReadingActivity = true;

  const { toFraction } = window.honmaniText;
  const MIN_INTERVAL_MS = 15_000;
  let last = 0;

  function onActivity() {
    if (!location.pathname.startsWith('/b')) return;
    const now = Date.now();
    if (now - last < MIN_INTERVAL_MS) return;
    last = now;
    if (window.honmani) window.honmani.postMessage(JSON.stringify({ type: 'reader.activity' }));
  }

  function onBookmarkSaved(value) {
    if (!location.pathname.startsWith('/b') || !value || typeof value !== 'object') return;
    const id = Number(value.dataId);
    const characters = Number(value.exploredCharCount);
    if (!Number.isInteger(id) || id <= 0 || !Number.isFinite(characters) || characters < 0) return;
    const message = { type: 'reader.position', id, characters: Math.floor(characters) };
    const progress = toFraction(value.progress);
    if (progress !== null) message.progress = progress;
    if (window.honmani) window.honmani.postMessage(JSON.stringify(message));
  }

  for (const method of ['put', 'add']) {
    const original = IDBObjectStore.prototype[method];
    IDBObjectStore.prototype[method] = function (value, ...rest) {
      const request = original.call(this, value, ...rest);
      if (this.name === 'bookmark') {
        request.addEventListener('success', () => onBookmarkSaved(value));
      }
      return request;
    };
  }

  for (const type of ['pointerdown', 'keydown', 'wheel', 'scroll']) {
    window.addEventListener(type, onActivity, { capture: true, passive: true });
  }
})();
