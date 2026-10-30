/*
 * Honmani diagnostics, injected at document start: logs the window and visual viewport sizes when
 * the page loads and whenever either changes, and in a book where the reading position was
 * before the resize and where it ended up. The app keeps console output for "Share
 * diagnostics"; nothing leaves the device otherwise.
 */
(() => {
  'use strict';

  if (window.__honmaniViewportLog) return;
  window.__honmaniViewportLog = true;

  let last = '';
  function log(reason) {
    const visual = window.visualViewport;
    const size = `inner=${innerWidth}x${innerHeight} visual=${
      visual ? `${Math.round(visual.width)}x${Math.round(visual.height)}@${visual.scale}` : 'none'
    } doc=${document.documentElement.clientWidth}x${document.documentElement.clientHeight}`;
    if (size === last) return;
    last = size;
    console.info(`honmani viewport (${reason}) ${size}`);
  }

  const SETTLE_MS = 1500;

  /**
   * The first characters of book text on screen (furigana skipped), or null outside a book. In
   * continuous mode (no paginated container) the search starts at the screen's start edge.
   */
  function readingPosition() {
    const root = document.querySelector('.book-content');
    if (!root) return null;
    const fast = !root.querySelector('.book-content-container');
    const found = window.honmaniText.firstCharOnScreen(root, 'rt, rp', fast);
    return found ? JSON.stringify(found.node.data.slice(found.offset, found.offset + 12)) : null;
  }

  // Updated once the reader has settled after the user turned a page or scrolled.
  let position = null;
  let positionTimer = 0;
  let resizing = false;
  function rememberPosition() {
    clearTimeout(positionTimer);
    positionTimer = setTimeout(() => {
      if (!resizing) position = readingPosition();
    }, 800);
  }
  for (const type of ['click', 'keyup', 'touchend', 'wheel', 'scroll']) {
    window.addEventListener(type, rememberPosition, { capture: true, passive: true });
  }

  let settleTimer = 0;
  function onResize(reason) {
    log(reason);
    if (!document.querySelector('.book-content')) return;
    if (!resizing) {
      resizing = true;
      console.info(`honmani position before resize ${position ?? readingPosition()}`);
    }
    clearTimeout(settleTimer);
    settleTimer = setTimeout(() => {
      resizing = false;
      position = readingPosition();
      console.info(`honmani position after resize ${position}`);
    }, SETTLE_MS);
  }

  window.addEventListener('DOMContentLoaded', () => log('ready'));
  window.addEventListener('resize', () => onResize('window resize'));
  if (window.visualViewport) window.visualViewport.addEventListener('resize', () => onResize('visual resize'));
})();
