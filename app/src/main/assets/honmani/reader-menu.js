/*
 * Honmani's reader menu, injected at document start into the ッツ Ebook Reader WebView.
 *
 * A tap on ttu's footer (the 32 px strip along the bottom of a book page, which otherwise only
 * hides the progress counter) opens the app's bottom menu: { type: 'reader.menu' }. The tap is
 * not passed on to ttu. Taps on book text are not affected: the footer is outside the book, and
 * tap-bridge.js looks up nothing for a tap there.
 *
 *   honmaniReaderMenu.openContents()
 *     Opens ttu's table of contents, as its button in the reader bar does: the bar is shown (a
 *     tap on the strip at the top of the page), the button clicked, and the bar closed again if
 *     the book has no contents.
 */
(() => {
  'use strict';

  if (window.honmaniReaderMenu) return;

  const FOOTER = '#ttu-page-footer';
  const BAR_STRIP = 'button.fixed.inset-x-0.top-0';
  const CONTENTS_BUTTON = '[title="Open Table of Contents"]';
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

  window.honmaniReaderMenu = Object.freeze({ openContents });
})();
