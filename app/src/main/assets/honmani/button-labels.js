/*
 * Names ttu's invisible reader buttons for TalkBack, which otherwise reads them as just
 * "button": the strip along the top that shows the reader menu and, in some modes, the narrow
 * page-turn strips at the left and right edges. ttu draws them again whenever the page changes,
 * so the names are set again after each change of the page.
 */
(() => {
  'use strict';

  const LABELS = [
    ['button.fixed.inset-x-0.top-0', 'Show the reader menu'],
    ['button.fixed.left-0.w-5', 'Page to the left'],
    ['button.fixed.right-0.w-5', 'Page to the right'],
  ];

  function label() {
    for (const [selector, name] of LABELS) {
      for (const button of document.querySelectorAll(`${selector}:not([aria-label])`)) {
        button.setAttribute('aria-label', name);
      }
    }
  }

  let queued = false;
  function queue() {
    if (queued) return;
    queued = true;
    requestAnimationFrame(() => {
      queued = false;
      label();
    });
  }

  function start() {
    new MutationObserver(queue).observe(document.documentElement, { childList: true, subtree: true });
    label();
  }

  if (document.documentElement) start();
  else document.addEventListener('DOMContentLoaded', start);
})();
