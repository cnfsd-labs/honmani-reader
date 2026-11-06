/*
 * Names ttu's invisible reader buttons for TalkBack, which otherwise reads them as just
 * "button": the strip along the top that shows the reader menu and, in some modes, the narrow
 * page-turn strips at the left and right edges. ttu draws them again whenever the page changes,
 * so the names are set again after each change of the page. Also the top bar's icon on the right on
 * a phone (a plain div that opens a small menu with Jump, Images, Settings), which has no name at all.
 */
(() => {
  'use strict';

  const LABELS = [
    ['button.fixed.inset-x-0.top-0', 'Show the reader menu'],
    ['button.fixed.left-0.w-5', 'Page to the left'],
    ['button.fixed.right-0.w-5', 'Page to the right'],
  ];

  /** ttu's header menu on narrow screens: the icon inside the popover that only shows below the sm breakpoint. */
  const MENU_ICON = 'div.sm\\:hidden [data-popover] .cursor-pointer.select-none:not([aria-label])';

  function label() {
    for (const icon of document.querySelectorAll(MENU_ICON)) {
      icon.setAttribute('role', 'button');
      icon.setAttribute('aria-label', 'More: jump, settings');
    }
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
