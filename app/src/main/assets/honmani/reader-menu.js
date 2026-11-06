/*
 * Honmani's reader menu, injected at document start into the ッツ Ebook Reader WebView.
 *
 * A tap on ttu's footer (the 32 px strip along the bottom of a book page, which otherwise only
 * hides the progress counter) opens the app's bottom menu: { type: 'reader.menu' }. The tap is
 * not passed on to ttu. Taps on book text are not affected: the footer is outside the book, and
 * tap-bridge.js looks up nothing for a tap there.
 *
 * A long swipe up anywhere on a book page opens the same menu, with { type: 'reader.menuSwipe' }:
 * one finger, at least a quarter of the viewport height up, and mostly straight (|dy| at least
 * twice |dx|), so a page-turn swipe does not trigger it; quick enough not to be a drag of a text
 * selection, and not with text selected. Not in ttu's continuous view with horizontal text (there
 * a swipe up scrolls the book), nor while ttu's reader bar or table of contents is open. The app
 * ignores the message while the lookup panel is open (the menu is a modal sheet already).
 * honmaniReaderMenu.isMenuSwipe(dx, dy, height) is that decision on its own.
 *
 *   honmaniReaderMenu.openContents()
 *     Opens ttu's table of contents, as its button in the reader bar does: the bar is shown (a
 *     tap on the strip at the top of the page), the button clicked, and the bar closed again if
 *     the book has no contents.
 *   honmaniReaderMenu.closeContents()
 *     Closes ttu's table of contents if it is open (however it was opened) and returns true,
 *     else false: the app's Back button asks this before it leaves the book.
 *   honmaniReaderMenu.isMenuSwipe(dx, dy, height)
 *     True for a move of (dx, dy) CSS px, in a viewport `height` px high, that opens the menu.
 */
(() => {
  'use strict';

  if (window.honmaniReaderMenu) return;

  const FOOTER = '#ttu-page-footer';
  const BAR_STRIP = 'button.fixed.inset-x-0.top-0';
  const CONTENTS_BUTTON = '[title="Open Table of Contents"]';
  const CLOSE_CONTENTS_BUTTON = '[title="Close Table of Contents"]';
  /** ttu draws its bar in the next tick; the button is looked for this many frames. */
  const MAX_FRAMES = 20;

  function post(message) {
    if (window.honmani) window.honmani.postMessage(JSON.stringify(message));
  }

  // Capture phase on window, before ttu's footer handler (which hides the progress counter).
  window.addEventListener(
    'click',
    (event) => {
      const footer = event.target instanceof Element ? event.target.closest(FOOTER) : null;
      if (!footer || !window.location.pathname.startsWith('/b')) return;
      event.preventDefault();
      event.stopImmediatePropagation();
      post({ type: 'reader.menu' });
    },
    true,
  );

  /** A swipe up opens the menu from this fraction of the viewport height, and must be this straight. */
  const SWIPE_HEIGHT_FRACTION = 0.25;
  const SWIPE_STRAIGHTNESS = 2;
  /** A swipe takes at most this long (ms); a longer drag may be moving a text selection. */
  const SWIPE_MAX_TIME = 800;
  const READER_BAR = 'div.fixed.inset-x-0.top-0';

  /** Is a move of (dx, dy) CSS px in a viewport `height` px high a long, straight swipe up? */
  function isMenuSwipe(dx, dy, height) {
    return -dy >= height * SWIPE_HEIGHT_FRACTION && -dy >= Math.abs(dx) * SWIPE_STRAIGHTNESS;
  }

  /** ttu scrolls the book itself on a swipe up: continuous view with horizontal text. */
  function swipeScrollsBook() {
    const continuous = (window.localStorage.getItem('viewMode') || 'paginated') !== 'paginated';
    const vertical = getComputedStyle(document.documentElement).writingMode.startsWith('vertical');
    return continuous && !vertical;
  }

  function overlayOpen() {
    return !!document.querySelector(`${READER_BAR}, ${CLOSE_CONTENTS_BUTTON}`);
  }

  // Touch events, not pointer events: the browser cancels a pointer once it starts to scroll.
  let swipeStart = null;
  window.addEventListener(
    'touchstart',
    (event) => {
      const touch = event.touches[0];
      swipeStart =
        event.touches.length === 1 && touch
          ? { x: touch.clientX, y: touch.clientY, time: event.timeStamp }
          : null;
    },
    { capture: true, passive: true },
  );
  window.addEventListener(
    'touchend',
    (event) => {
      const start = swipeStart;
      swipeStart = null;
      const touch = event.changedTouches[0];
      if (!start || !touch || event.touches.length > 0 || event.changedTouches.length !== 1) return;
      // The end of a long press (tap-bridge.js): that drag chose text.
      if (event.defaultPrevented) return;
      if (!window.location.pathname.startsWith('/b')) return;
      if (event.timeStamp - start.time > SWIPE_MAX_TIME) return;
      if (!isMenuSwipe(touch.clientX - start.x, touch.clientY - start.y, window.innerHeight)) return;
      if (swipeScrollsBook() || overlayOpen()) return;
      const selection = window.getSelection();
      if (selection && !selection.isCollapsed) return;
      post({ type: 'reader.menuSwipe' });
    },
    { capture: true, passive: true },
  );
  window.addEventListener(
    'touchcancel',
    () => {
      swipeStart = null;
    },
    { capture: true, passive: true },
  );

  function openContents() {
    const strip = document.querySelector(BAR_STRIP);
    if (strip) strip.click();
    let frames = 0;
    const look = () => {
      const button = document.querySelector(CONTENTS_BUTTON);
      if (button) {
        button.click();
      } else if (++frames < MAX_FRAMES) {
        requestAnimationFrame(look);
      } else if (strip) {
        // No contents in this book: close the bar again (ttu closes it on a click outside it).
        document.body.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      }
    };
    requestAnimationFrame(look);
  }

  function closeContents() {
    const button = document.querySelector(CLOSE_CONTENTS_BUTTON);
    if (!button) return false;
    button.click();
    return true;
  }

  window.honmaniReaderMenu = Object.freeze({ openContents, closeContents, isMenuSwipe });
})();
