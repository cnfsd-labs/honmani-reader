/*
 * Honmani's reader settings for ッツ Ebook Reader (ttu), injected at document start after
 * ttu-defaults.js.
 *
 * The app shows its own, smaller reader settings screen. ttu keeps its settings in
 * localStorage and reads them when the page loads, so the app reads and writes the keys here
 * and reloads the page once its screen closes (while the page is covered):
 *
 *   honmaniReaderSettings.read({ requestId })
 *     → { type: 'library.settings', requestId, settings: { key: value or null } }
 *   honmaniReaderSettings.write({ settings })
 *     Stores the values (null removes one). theme 'auto' follows the system's dark mode, as
 *     ttu-defaults.js does until the user picks a theme.
 *   honmaniReaderSettings.fonts({ fonts: [{ name, fileName }] })
 *     Lists the fonts Honmani downloaded in ttu's user fonts (localStorage "userfonts"), served
 *     at /honmani-fonts/<fileName>; ttu writes their @font-face rules when the page loads. Fonts
 *     the reader added in ttu's own settings are kept.
 *
 * When ttu declares the fonts, the chosen one starts loading if it is one of Honmani's: ttu
 * hides the book until the font is ready and waits for the browser's "loadingdone", but the
 * browser only loads a web font once visible text uses it, so ttu would wait for its 30 s
 * timeout.
 *   honmaniReaderSettings.reload()
 *
 * The reader's top bar keeps the table of contents, "Return to bookmark", "Jump" and the image
 * gallery; ttu's flag, custom point, fullscreen, statistics, book manager and bug report entries
 * are hidden (Honmani has its own library, statistics and reading position). Its Settings entry
 * opens the app's screen instead of ttu's page: { type: 'reader.settings' }.
 */
(() => {
  'use strict';

  if (window.honmaniReaderSettings) return;

  const KEYS = [
    'theme',
    'fontFamilyGroupOne',
    'fontSize',
    'lineHeight',
    'writingMode',
    'viewMode',
    'hideFurigana',
    'showCharacterCounter',
    'showPercentage',
    'enableReaderWakeLock',
    'honmaniProgressBar',
    'honmaniWordMarks',
    'honmaniVolumeKeys',
    'honmaniSelectionLookup',
    'honmaniHideKnownFurigana',
  ];
  const USER_FONTS = 'userfonts';
  const FONT_PATH = '/honmani-fonts/';
  const FONT_FILE = /^[A-Za-z0-9-]+\.(ttf|otf|woff2?)$/;
  /** Set by ttu-defaults.js: the theme Honmani set to follow the system. */
  const AUTO_THEME = 'honmaniAutoTheme';
  const SETTINGS_ENTRY = '[title="Go to Reader Settings"]';
  const HIDDEN = [
    '[role="button"][title="Complete Book"]',
    '[title="Open Custom Point Actions"]',
    '[role="button"][title="Toggle Fullscreen"]',
    '[role="button"][title="Go to Statistics"]',
    '[role="button"][title="Go to Book Manager"]',
    '[role="button"][title="Report an Issue"]',
    '[role="button"][title="Old Domain used"]',
  ];

  function post(message) {
    if (window.honmani) window.honmani.postMessage(JSON.stringify(message));
  }

  function read({ requestId }) {
    const settings = {};
    try {
      for (const key of KEYS) settings[key] = window.localStorage.getItem(key);
      const auto = window.localStorage.getItem(AUTO_THEME);
      if (auto !== null && auto === settings.theme) settings.theme = 'auto';
    } catch (error) {
      // No storage: ttu's defaults.
    }
    post({ type: 'library.settings', requestId, settings });
  }

  function write({ settings }) {
    if (!settings || typeof settings !== 'object') return;
    try {
      for (const [key, value] of Object.entries(settings)) {
        if (!KEYS.includes(key)) continue;
        if (key === 'theme' && value === 'auto') {
          const dark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
          const theme = dark ? 'black-theme' : 'light-theme';
          window.localStorage.setItem('theme', theme);
          window.localStorage.setItem(AUTO_THEME, theme);
          continue;
        }
        if (value === null || value === undefined) {
          window.localStorage.removeItem(key);
        } else {
          window.localStorage.setItem(key, String(value));
        }
        if (key === 'theme') window.localStorage.removeItem(AUTO_THEME);
      }
    } catch (error) {
      // No storage: nothing to change.
    }
  }

  function isHonmaniFont(font) {
    return Boolean(font) && typeof font.path === 'string' && font.path.startsWith(FONT_PATH);
  }

  function fonts({ fonts: list }) {
    if (!Array.isArray(list)) return;
    const ours = list
      .filter((font) => font && typeof font.name === 'string' && font.name && FONT_FILE.test(font.fileName))
      .map(({ name, fileName }) => ({ name, path: FONT_PATH + fileName, fileName }));
    try {
      let current = [];
      try {
        current = JSON.parse(window.localStorage.getItem(USER_FONTS) || '[]');
      } catch (error) {
        // Unreadable list: start a new one.
      }
      if (!Array.isArray(current)) current = [];
      const own = current.filter((font) => !isHonmaniFont(font) && !ours.some((our) => our.name === font?.name));
      window.localStorage.setItem(USER_FONTS, JSON.stringify([...own, ...ours]));
    } catch (error) {
      // No storage: nothing to change.
    }
  }

  /** ttu's style element with the user fonts' @font-face rules. */
  const USER_FONTS_STYLE = 'ttu-userfonts';

  /**
   * Starts loading the chosen font if it is one Honmani downloaded, as soon as ttu has declared
   * it. Loading ttu's own face (not a second one) matters: ttu's check passes only once every
   * face of the family is loaded.
   */
  const FONT_PRELOAD_GIVE_UP_MS = 30000;

  function preloadFont() {
    if (!document.fonts || !window.MutationObserver) return;
    let family;
    try {
      family = window.localStorage.getItem('fontFamilyGroupOne');
      const list = JSON.parse(window.localStorage.getItem(USER_FONTS) || '[]');
      if (!Array.isArray(list) || !list.some((entry) => isHonmaniFont(entry) && entry.name === family)) return;
    } catch (error) {
      return; // No storage or a broken list: ttu's own handling.
    }
    const load = () =>
      document.fonts.load(`16px "${family.replace(/["\\]/g, '')}"`).catch(() => {
        // ttu falls back after its own timeout.
      });
    const declared = () => document.getElementById(USER_FONTS_STYLE) !== null;
    const observer = new MutationObserver(() => {
      if (!declared()) return;
      observer.disconnect();
      load();
    });
    observer.observe(document, { childList: true, subtree: true });
    // ttu gives up on a font after 30 s; without its style element there is nothing to wait for.
    setTimeout(() => observer.disconnect(), FONT_PRELOAD_GIVE_UP_MS);
  }
  preloadFont();

  function reload() {
    window.location.reload();
  }

  // The bar's Settings entry opens Honmani's screen; capture phase, before ttu's handler.
  window.addEventListener(
    'click',
    (event) => {
      const target = event.target instanceof Element ? event.target.closest(SETTINGS_ENTRY) : null;
      if (!target || !window.location.pathname.startsWith('/b')) return;
      event.preventDefault();
      event.stopImmediatePropagation();
      post({ type: 'reader.settings' });
    },
    true,
  );

  function addStyle() {
    const style = document.createElement('style');
    style.textContent = `${HIDDEN.join(', ')} { display: none !important; }`;
    document.documentElement.appendChild(style);
  }
  if (document.documentElement) {
    addStyle();
  } else {
    const observer = new MutationObserver(() => {
      if (!document.documentElement) return;
      observer.disconnect();
      addStyle();
    });
    observer.observe(document, { childList: true });
  }

  window.honmaniReaderSettings = Object.freeze({ read, write, fonts, reload });
})();
