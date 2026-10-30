/*
 * The frame around a book, injected at document start into the ッツ Ebook Reader (ttu) WebView.
 *
 * Background: the reader keeps clear of the status and navigation bars, and the app paints those
 * strips. On the book page this posts { type: 'reader.background', color: '#rrggbb' } with the
 * page's background (ttu sets it on <body> from the theme) whenever it changes, so the strips
 * match the book instead of the app's own background.
 *
 * Progress: a slim bar along the bottom of the book page, in the app's accent colour
 * (--honmani-accent, from ttu-defaults.js), filled to the reading position. The position comes
 * from ttu's bookmark when the book is opened and on every page turn. It fills from the right
 * in vertical books, the way their pages turn. localStorage "honmaniProgressBar" = "0" (Honmani's
 * reader settings) turns it off.
 */
(() => {
  'use strict';

  if (window.__honmaniBookFrame) return;
  window.__honmaniBookFrame = true;

  const { bookId, toFraction } = window.honmaniText;
  const BAR_ID = 'honmani-progress';
  const BAR_HEIGHT_PX = 3;
  let lastColor = null;
  /** The reading position of the book on the page, 0 to 1, and which book it is. */
  let progress = null;
  let progressBook = null;

  function post(message) {
    if (window.honmani) window.honmani.postMessage(JSON.stringify(message));
  }

  /** "rgb(r, g, b)" or "rgba(r, g, b, a)" as "#rrggbb"; null for a transparent or unknown colour. */
  function toHex(value) {
    const parts = (value || '').replace(/^rgba?\(|\)$/g, '').split(',').map((part) => Number(part.trim()));
    if (parts.length < 3 || parts.slice(0, 3).some((part) => !Number.isFinite(part))) return null;
    if (parts.length > 3 && parts[3] === 0) return null;
    return '#' + parts.slice(0, 3).map((part) => Math.round(part).toString(16).padStart(2, '0')).join('');
  }

  function reportBackground() {
    if (bookId() === null || !document.body) return;
    const color = toHex(getComputedStyle(document.body).backgroundColor) ??
      toHex(getComputedStyle(document.documentElement).backgroundColor);
    if (color === null || color === lastColor) return;
    lastColor = color;
    post({ type: 'reader.background', color });
  }

  function barWanted() {
    try {
      return window.localStorage.getItem('honmaniProgressBar') !== '0';
    } catch (error) {
      return true; // No storage: the default.
    }
  }

  // The bar this script created, by reference: a book may carry the same id.
  let barElement = null;

  function drawBar() {
    const id = bookId();
    let bar = barElement;
    if (id === null || id !== progressBook || progress === null || !document.body || !barWanted()) {
      if (bar) bar.remove();
      return;
    }
    if (!bar) {
      bar = document.createElement('div');
      bar.id = BAR_ID;
      barElement = bar;
      bar.setAttribute('aria-hidden', 'true');
      bar.style.cssText = `position: fixed; bottom: 0; height: ${BAR_HEIGHT_PX}px; z-index: 2147483647;
        pointer-events: none; background-color: var(--honmani-accent, #888888);
        border-radius: ${BAR_HEIGHT_PX}px; transition: width 150ms ease-out;`;
    }
    if (bar.parentNode !== document.body) document.body.appendChild(bar);
    const vertical = getComputedStyle(document.documentElement).writingMode.startsWith('vertical-rl');
    bar.style.left = vertical ? '' : '0';
    bar.style.right = vertical ? '0' : '';
    bar.style.width = `${(progress * 100).toFixed(3)}%`;
  }

  function onBookmark(value) {
    if (!value || typeof value !== 'object') return;
    const id = Number(value.dataId);
    const fraction = toFraction(value.progress);
    if (id !== bookId() || fraction === null) return;
    progressBook = id;
    progress = fraction;
    drawBar();
  }

  // Bookmarks are read when a book opens and written on every page turn.
  for (const method of ['get', 'put', 'add']) {
    const original = IDBObjectStore.prototype[method];
    IDBObjectStore.prototype[method] = function (...args) {
      const request = original.apply(this, args);
      if (this.name === 'bookmark') {
        request.addEventListener('success', () => onBookmark(method === 'get' ? request.result : args[0]));
      }
      return request;
    };
  }

  function update() {
    reportBackground();
    drawBar();
  }

  // ttu changes the theme by setting <body>'s style, and pages without reloading.
  const observer = new MutationObserver(update);
  function watch() {
    if (!document.body) return false;
    observer.observe(document.body, { attributes: true, attributeFilter: ['style', 'class'] });
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ['style', 'class'] });
    update();
    return true;
  }
  if (!watch()) document.addEventListener('DOMContentLoaded', watch, { once: true });
  for (const method of ['pushState', 'replaceState']) {
    const original = history[method];
    history[method] = function (...args) {
      const result = original.apply(this, args);
      lastColor = null;
      setTimeout(update, 0);
      return result;
    };
  }
  window.addEventListener('popstate', () => {
    lastColor = null;
    setTimeout(update, 0);
  });
})();
