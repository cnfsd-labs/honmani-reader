/*
 * Honmani library bridge, injected at document start into the ッツ Ebook Reader (ttu) WebView.
 *
 * Honmani's native library keeps the book files; ttu renders books from its own IndexedDB
 * ("books" database, "data" store keyed by id, unique by title). The app calls these functions
 * with evaluateJavascript, and they answer through the `honmani` message channel:
 *
 *   honmaniLibrary.ensure({ requestId, ttuId, ttuTitle, url, fileName })
 *     Makes sure ttu has the book. If ttu still holds ttuId under ttuTitle, answers at once;
 *     otherwise fetches url (served by the app from the library) and imports it through ttu's own
 *     import input on its book list, so ttu parses and stores it as if the user had picked it.
 *     → { type: 'library.ready', requestId, id, title } or { type: 'library.failed', requestId, message }
 *   honmaniLibrary.remove({ requestId, ttuId })
 *     Deletes the book and its bookmark from ttu. → { type: 'library.removed', requestId }
 *   honmaniLibrary.resetProgress({ requestId, ttuId })
 *     Deletes ttu's bookmark for the book, so it opens at the start. → { type: 'library.reset', requestId }
 *   honmaniLibrary.bookmark({ requestId, ttuId })
 *     → { type: 'library.bookmark', requestId, characters, modified }: the reading position ttu
 *     saved for the book (characters before it, when it was saved in ms); characters null if none.
 *   honmaniLibrary.setBookmark({ requestId, ttuId, characters, progress, modified })
 *     Saves a reading position for the book (from progress sync or a backup), which ttu opens it
 *     at. modified (ms) is when it was read, for a later sync to compare; now if not given.
 *     → { type: 'library.bookmarkSet', requestId }
 *   honmaniLibrary.progress()
 *     → { type: 'library.progress', books: [{ id, progress, characters }] }, progress 0..1 and
 *     the characters read up to the reading position (exploredCharCount), from ttu's bookmarks.
 *   honmaniLibrary.open({ ttuId })
 *     Opens the book inside the running page, as ttu's own book list does: a link click that
 *     ttu's router handles. A full page load instead kept the previous page (ttu's grey book
 *     list) on screen until the book page drew something, and showed through when the app
 *     uncovered the reader. If the router doesn't take the click, the browser follows the link.
 *   honmaniLibrary.navigate({ path })
 *     Shows another ttu page (its settings, say) the same way, without a page load.
 *
 * ttu's import gives no result to its caller, so this script watches IndexedDB writes (put and
 * add) to the "data" store while an import is pending (to learn the new id and title), and ttu's
 * "Error importing …" console errors (to report failures).
 */
(() => {
  'use strict';

  if (window.honmaniLibrary) return;

  const { toFraction } = window.honmaniText;
  const DB_NAME = 'books';
  const IMPORT_INPUT = 'input[type="file"][accept^="application/epub+zip"]';
  const INPUT_WAIT_MS = 15000;
  const IMPORT_TIMEOUT_MS = 180000;

  function post(message) {
    if (window.honmani) window.honmani.postMessage(JSON.stringify(message));
  }

  /** Opens ttu's database; never creates it (ttu must create it with its own schema). */
  function openDb() {
    return new Promise((resolve, reject) => {
      const request = indexedDB.open(DB_NAME);
      request.onupgradeneeded = () => request.transaction.abort();
      request.onsuccess = () => {
        const db = request.result;
        db.onversionchange = () => db.close();
        resolve(db);
      };
      request.onerror = () => reject(new Error('ttu has no book database yet'));
    });
  }

  function done(request) {
    return new Promise((resolve, reject) => {
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
  }

  function committed(transaction) {
    return new Promise((resolve, reject) => {
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(transaction.error);
      transaction.onabort = () => reject(transaction.error || new Error('Aborted'));
    });
  }

  async function withDb(work) {
    const db = await openDb();
    try {
      return await work(db);
    } finally {
      db.close();
    }
  }

  /** Whether ttu holds a book with this id and title. */
  async function hasBook(id, title) {
    if (typeof id !== 'number' || typeof title !== 'string') return false;
    try {
      return await withDb(async (db) => {
        if (!db.objectStoreNames.contains('data')) return false;
        // Every book with that title: two editions may share one.
        const index = db.transaction('data').objectStore('data').index('title');
        return (await done(index.getAllKeys(title))).includes(id);
      });
    } catch (error) {
      return false;
    }
  }

  // --- Watching ttu's import -------------------------------------------------------------------

  /** The import in progress: { since, resolve, reject }. */
  let pendingImport = null;

  /** Wraps IDBObjectStore's put and add, which ttu uses to store an updated or a new book. */
  function watchDataWrites() {
    for (const method of ['put', 'add']) {
      const original = IDBObjectStore.prototype[method];
      IDBObjectStore.prototype[method] = function (value, ...rest) {
        const request = original.call(this, value, ...rest);
        const pending = pendingImport;
        if (
          pending &&
          this.name === 'data' &&
          value &&
          typeof value.title === 'string' &&
          typeof value.elementHtml === 'string' &&
          (value.lastBookModified || 0) >= pending.since
        ) {
          const title = value.title;
          const transaction = this.transaction;
          request.addEventListener('success', () => {
            const id = request.result;
            transaction.addEventListener('complete', () => pending.resolve({ id, title }));
          });
        }
        return request;
      };
    }
  }

  function watchImportErrors() {
    const original = console.error;
    console.error = function (...args) {
      const text = args.map(String).join(' ');
      if (pendingImport && text.startsWith('Error importing')) {
        pendingImport.reject(new Error(text));
      }
      return original.apply(this, args);
    };
  }

  function waitForElement(selector, timeoutMs) {
    return new Promise((resolve, reject) => {
      const deadline = Date.now() + timeoutMs;
      const check = () => {
        const element = document.querySelector(selector);
        if (element) resolve(element);
        else if (Date.now() > deadline) reject(new Error('ttu’s book list is not ready'));
        else setTimeout(check, 100);
      };
      check();
    });
  }

  async function importBook(url, fileName) {
    const response = await fetch(url);
    if (!response.ok) throw new Error(`The book file is missing (${response.status})`);
    const file = new File([await response.blob()], fileName);
    const input = await waitForElement(IMPORT_INPUT, INPUT_WAIT_MS);

    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => finish(new Error('ttu did not finish importing the book')), IMPORT_TIMEOUT_MS);
      const finish = (error, result) => {
        clearTimeout(timer);
        pendingImport = null;
        if (error) reject(error);
        else resolve(result);
      };
      pendingImport = {
        since: Date.now(),
        resolve: (result) => finish(null, result),
        reject: (error) => finish(error),
      };
      const files = new DataTransfer();
      files.items.add(file);
      input.files = files.files;
      input.dispatchEvent(new Event('change'));
    });
  }

  // --- Commands --------------------------------------------------------------------------------

  /** Commands run one at a time: ttu imports one book at a time. */
  let queue = Promise.resolve();
  function enqueue(work) {
    queue = queue.then(work, work);
  }

  function ensure({ requestId, ttuId, ttuTitle, url, fileName }) {
    enqueue(async () => {
      try {
        if (await hasBook(ttuId, ttuTitle)) {
          post({ type: 'library.ready', requestId, id: ttuId, title: ttuTitle });
          return;
        }
        const { id, title } = await importBook(url, fileName);
        post({ type: 'library.ready', requestId, id, title });
      } catch (error) {
        post({ type: 'library.failed', requestId, message: String((error && error.message) || error) });
      }
    });
  }

  function remove({ requestId, ttuId }) {
    enqueue(async () => {
      try {
        await withDb(async (db) => {
          const stores = ['data', 'bookmark', 'lastItem'].filter((name) => db.objectStoreNames.contains(name));
          const transaction = db.transaction(stores, 'readwrite');
          transaction.objectStore('data').delete(ttuId);
          if (stores.includes('bookmark')) transaction.objectStore('bookmark').delete(ttuId);
          if (stores.includes('lastItem')) {
            const lastItem = transaction.objectStore('lastItem');
            const cursorRequest = lastItem.openCursor();
            cursorRequest.onsuccess = () => {
              const cursor = cursorRequest.result;
              if (!cursor) return;
              if (cursor.value && cursor.value.dataId === ttuId) cursor.delete();
              cursor.continue();
            };
          }
          await committed(transaction);
        });
      } catch (error) {
        // Nothing to delete when ttu has no database or no such book.
      }
      post({ type: 'library.removed', requestId });
    });
  }

  function resetProgress({ requestId, ttuId }) {
    enqueue(async () => {
      try {
        await withDb(async (db) => {
          if (!db.objectStoreNames.contains('bookmark')) return;
          const transaction = db.transaction('bookmark', 'readwrite');
          transaction.objectStore('bookmark').delete(ttuId);
          await committed(transaction);
        });
      } catch (error) {
        // No database or no bookmark: nothing to reset.
      }
      post({ type: 'library.reset', requestId });
    });
  }

  function bookmark({ requestId, ttuId }) {
    enqueue(async () => {
      let found = null;
      try {
        found = await withDb(async (db) => {
          if (!db.objectStoreNames.contains('bookmark')) return null;
          return (await done(db.transaction('bookmark').objectStore('bookmark').get(ttuId))) || null;
        });
      } catch (error) {
        // No database yet: no bookmark.
      }
      const characters = found && Number.isFinite(found.exploredCharCount) ? Math.max(0, found.exploredCharCount) : null;
      const modified = found && Number.isFinite(found.lastBookmarkModified) ? found.lastBookmarkModified : 0;
      post({ type: 'library.bookmark', requestId, characters, modified });
    });
  }

  function setBookmark({ requestId, ttuId, characters, progress, modified }) {
    enqueue(async () => {
      try {
        await withDb(async (db) => {
          if (!db.objectStoreNames.contains('bookmark')) return;
          const transaction = db.transaction('bookmark', 'readwrite');
          transaction.objectStore('bookmark').put({
            dataId: ttuId,
            exploredCharCount: Math.max(0, Math.floor(characters)),
            progress: Math.min(1, Math.max(0, progress)),
            lastBookmarkModified: Number.isFinite(modified) && modified > 0 ? modified : Date.now(),
          });
          await committed(transaction);
        });
      } catch (error) {
        // No database: ttu opens the book at its start, as without sync.
      }
      post({ type: 'library.bookmarkSet', requestId });
    });
  }

  function progress() {
    enqueue(async () => {
      let books = [];
      try {
        books = await withDb(async (db) => {
          if (!db.objectStoreNames.contains('bookmark')) return [];
          const bookmarks = await done(db.transaction('bookmark').objectStore('bookmark').getAll());
          return bookmarks
            .map((bookmark) => ({
              id: bookmark.dataId,
              progress: toFraction(bookmark.progress),
              characters: Number.isFinite(bookmark.exploredCharCount) ? Math.max(0, bookmark.exploredCharCount) : null,
            }))
            .filter((book) => typeof book.id === 'number' && book.progress !== null);
        });
      } catch (error) {
        // No database yet: no progress.
      }
      post({ type: 'library.progress', books });
    });
  }

  function open({ ttuId }) {
    navigate({ path: `/b?id=${Number(ttuId)}` });
  }

  function navigate({ path }) {
    // Only paths on the reader's own origin.
    if (typeof path !== 'string' || !path.startsWith('/') || path.startsWith('//')) return;
    // Browsers read a backslash as a slash, so "/\host" is protocol-relative: check what it resolves to.
    let resolved;
    try {
      resolved = new URL(path, location.href);
    } catch (error) {
      return;
    }
    if (resolved.origin !== location.origin) return;
    const target = resolved.pathname + resolved.search + resolved.hash;
    // ttu's router listens for link clicks on its root element, the first child of <body>.
    const root = document.body && document.body.firstElementChild;
    if (!root) {
      location.assign(target);
      return;
    }
    const link = document.createElement('a');
    link.href = target;
    link.hidden = true;
    root.appendChild(link);
    link.click();
    link.remove();
  }

  watchDataWrites();
  watchImportErrors();
  window.honmaniLibrary = Object.freeze({ ensure, remove, resetProgress, bookmark, setBookmark, progress, open, navigate });
})();
