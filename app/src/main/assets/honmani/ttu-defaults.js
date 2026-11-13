/*
 * Honmani defaults for ッツ Ebook Reader, injected at document start before ttu's own scripts.
 *
 * The APK leaves out ttu's bundled web fonts (~150 MB). ttu's default reader fonts are
 * "Noto Serif JP" / "Noto Sans JP", and the reader waits up to 10 s for its primary font to load
 * before showing a book. Defaulting to the generic families uses the system's Noto CJK fonts and
 * shows the book immediately. The reader theme follows the system's dark mode (black or light)
 * until the user picks a theme in ttu's settings. The text is larger than ttu's 20 px (easier to
 * read kanji on a phone), the reading position is saved on every page turn instead of after 3 s
 * (so ttu's "Create Bookmark" button in the reader's header is hidden), and images are not
 * blurred. Settings the user changes are left alone.
 *
 * A book page paints white for a few frames before ttu applies its theme. On the book page the
 * root element gets the theme's colours right away, so opening a book in a dark theme never
 * flashes white.
 *
 * ttu's full-screen loading circle is hidden (its themed cover stays): the app keeps its library,
 * with its own spinner on the cover, over the reader while a book opens.
 *
 * ttu's settings page is always light. With a dark reader theme it gets dark colours too
 * (class `honmani-dark-settings` on the root element, only while the settings page is shown; ttu
 * changes pages without reloading, so history changes are watched). Its selected options take
 * the app's accent colour, which the app passes with `honmaniSetAccent(accent, onAccent)` and which
 * is kept for the next page load.
 */
(() => {
  'use strict';

  const prefersDark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
  const DEFAULTS = {
    fontFamilyGroupOne: 'serif',
    fontFamilyGroupTwo: 'sans-serif',
    fontSize: '28',
    autoBookmarkTime: '0',
    // Images in books are shown, not blurred until tapped.
    hideSpoilerImage: '0',
  };
  /** ttu's theme colours (lib/data/theme-option.ts): background, text. */
  const DARK_TEXT = 'rgba(255, 255, 255, 0.87)';
  const LIGHT_TEXT = 'rgba(0, 0, 0, 0.87)';
  const THEME_COLORS = {
    'light-theme': ['#ffffff', LIGHT_TEXT],
    'ecru-theme': ['#f7f6eb', LIGHT_TEXT],
    'water-theme': ['#dfecf4', LIGHT_TEXT],
    'gray-theme': ['#23272a', DARK_TEXT],
    'dark-theme': ['#121212', 'rgba(255, 255, 255, 0.6)'],
    'black-theme': ['#000000', DARK_TEXT],
  };
  /** ttu's full-screen loading cover (book page and readers), the circle inside it is hidden. */
  const SPINNER_SELECTOR = 'div.fixed.inset-0.text-7xl';
  /** Reader themes with a dark background, for the settings page. */
  const DARK_THEMES = new Set(['gray-theme', 'dark-theme', 'black-theme']);
  const DARK_SETTINGS = 'honmani-dark-settings';
  const SETTINGS = 'honmani-settings';
  /** The app's accent colours, as the app last passed them. */
  const ACCENT = 'honmaniAccent';
  const COLOR = /^#[0-9a-fA-F]{6}$/;
  const DARK_SETTINGS_CSS = `
    html.${DARK_SETTINGS} { color-scheme: dark; --background-color: #121212; }
    html.${DARK_SETTINGS} body { background-color: #121212 !important; color: ${DARK_TEXT}; }
    html.${DARK_SETTINGS} button.bg-white:not([style*="color"]) {
      background-color: transparent !important; color: ${DARK_TEXT} !important;
    }
    html.${DARK_SETTINGS} { --honmani-selected: #4b5563; }
    html.${DARK_SETTINGS} .text-black:not([style*="color"]) { color: ${DARK_TEXT} !important; }
    html.${DARK_SETTINGS} input, html.${DARK_SETTINGS} select, html.${DARK_SETTINGS} textarea {
      color: inherit; background-color: transparent;
    }
    html.${DARK_SETTINGS} select option { background-color: #1f1f1f; }
    html.${DARK_SETTINGS} input:focus, html.${DARK_SETTINGS} select:focus { border-color: ${DARK_TEXT} !important; }
    html.${SETTINGS} button.bg-gray-700:not([style*="color"]) {
      background-color: var(--honmani-accent, var(--honmani-selected, #374151)) !important;
      border-color: var(--honmani-accent, var(--honmani-selected, #374151)) !important;
      color: var(--honmani-on-accent, #ffffff) !important;
    }
    html.${SETTINGS} button.border-blue-300 { border-color: var(--honmani-accent, #93c5fd) !important; }
    html.${SETTINGS} input:focus, html.${SETTINGS} select:focus {
      border-color: var(--honmani-accent, currentColor) !important;
    }`;
  /** ttu's manual bookmark button in the reader's header; every page turn is saved anyway. */
  const BOOKMARK_BUTTON = '[role="button"][title="Create Bookmark"]';
  /** The theme Honmani last set; while ttu's theme still equals it, the user has not picked one. */
  const AUTO_THEME = 'honmaniAutoTheme';
  /**
   * ttu turns a book's font-family naming "sans-serif" into its second font group and "serif" into
   * the first; Honmani's font setting is the first group. The second follows it (so a paragraph
   * the book styles as sans uses the reader's font too) until the user picks another one in ttu's
   * settings: this key holds the value last copied. With BOOK_GOTHIC "1" (the reader's "Keep the
   * book's gothic text") the second group goes back to ttu's sans-serif instead.
   */
  const FONT_TWO_COPY = 'honmaniFontGroupTwoCopy';
  const BOOK_GOTHIC = 'honmaniBookGothic';

  /** Honmani's earlier default, replaced by saving on every page turn. */
  const OLD_BOOKMARK_TIME = '1';

  try {
    if (window.localStorage.getItem('autoBookmarkTime') === OLD_BOOKMARK_TIME) {
      window.localStorage.removeItem('autoBookmarkTime');
    }
    for (const [key, value] of Object.entries(DEFAULTS)) {
      if (window.localStorage.getItem(key) === null) {
        window.localStorage.setItem(key, value);
      }
    }
    const fontOne = window.localStorage.getItem('fontFamilyGroupOne');
    const fontTwo = window.localStorage.getItem('fontFamilyGroupTwo');
    const copied = fontTwo === window.localStorage.getItem(FONT_TWO_COPY);
    if (window.localStorage.getItem(BOOK_GOTHIC) === '1') {
      if (copied) window.localStorage.setItem('fontFamilyGroupTwo', DEFAULTS.fontFamilyGroupTwo);
      window.localStorage.removeItem(FONT_TWO_COPY);
    } else if (fontTwo === DEFAULTS.fontFamilyGroupTwo || copied) {
      window.localStorage.setItem('fontFamilyGroupTwo', fontOne);
      window.localStorage.setItem(FONT_TWO_COPY, fontOne);
    }

    const systemTheme = prefersDark ? 'black-theme' : 'light-theme';
    const theme = window.localStorage.getItem('theme');
    const auto = window.localStorage.getItem(AUTO_THEME);
    if (theme === null || theme === auto) {
      window.localStorage.setItem('theme', systemTheme);
      window.localStorage.setItem(AUTO_THEME, systemTheme);
    } else if (auto !== null) {
      // The user picked a theme in ttu's settings: theirs from now on.
      window.localStorage.removeItem(AUTO_THEME);
    }

    // ttu's stylesheet colours the page from these variables, light until its script sets
    // them on <body>; set on the root element, they apply from the first frame.
    const colors = THEME_COLORS[window.localStorage.getItem('theme')];
    if (colors && window.location.pathname.startsWith('/b')) whenRoot(paintRoot(colors));
  } catch (error) {
    // Storage can be unavailable; ttu then falls back to its own defaults.
  }

  whenRoot((root) => {
    const style = document.createElement('style');
    style.textContent = `${SPINNER_SELECTOR} > svg { visibility: hidden !important; }
      ${BOOKMARK_BUTTON} { display: none !important; }
      ${DARK_SETTINGS_CSS}`;
    root.appendChild(style);
    applyAccent(root, readAccent());
    window.honmaniSetAccent = (accent, onAccent) => {
      if (!COLOR.test(accent) || !COLOR.test(onAccent)) return;
      try {
        window.localStorage.setItem(ACCENT, JSON.stringify([accent, onAccent]));
      } catch (error) {
        // No storage: the colours last until the next page load.
      }
      applyAccent(root, [accent, onAccent]);
    };
    darkSettings(root);
    for (const method of ['pushState', 'replaceState']) {
      const original = history[method];
      history[method] = function (...args) {
        const result = original.apply(this, args);
        darkSettings(root);
        return result;
      };
    }
    window.addEventListener('popstate', () => darkSettings(root));
    // Picking a theme on the settings page switches its colours right away.
    document.addEventListener('click', () => setTimeout(() => darkSettings(root), 0), true);
  });

  /** Marks the settings page while it is shown (accent), dark when the reader theme is dark. */
  function darkSettings(root) {
    let theme = null;
    try {
      theme = window.localStorage.getItem('theme');
    } catch (error) {
      // No storage: ttu's light settings page.
    }
    const settings = window.location.pathname.startsWith('/settings');
    root.classList.toggle(SETTINGS, settings);
    root.classList.toggle(DARK_SETTINGS, settings && DARK_THEMES.has(theme));
  }

  function readAccent() {
    try {
      const colors = JSON.parse(window.localStorage.getItem(ACCENT) || 'null');
      return Array.isArray(colors) && colors.length === 2 && colors.every((c) => COLOR.test(c)) ? colors : null;
    } catch (error) {
      return null;
    }
  }

  function applyAccent(root, colors) {
    if (!colors) return;
    root.style.setProperty('--honmani-accent', colors[0]);
    root.style.setProperty('--honmani-on-accent', colors[1]);
  }

  function paintRoot([background, text]) {
    return (root) => {
      root.style.setProperty('--background-color', background);
      root.style.setProperty('--font-color', text);
    };
  }

  /** Runs [action] on the root element; at document start the parser may not have made it yet. */
  function whenRoot(action) {
    if (document.documentElement) {
      action(document.documentElement);
      return;
    }
    const observer = new MutationObserver(() => {
      if (!document.documentElement) return;
      observer.disconnect();
      action(document.documentElement);
    });
    observer.observe(document, { childList: true });
  }
})();
