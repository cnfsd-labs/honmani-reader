# Patches to ッツ Ebook Reader

`:app:buildTtu` builds ttu from a copy of `third_party/ttu-ebook-reader` with these patches
applied in file-name order, so the submodule itself stays pristine.

Keep this list short; behaviour that can be changed from outside (localStorage defaults, injected
scripts) belongs in `app/src/main/assets/honmani/` instead.

| Patch | Why |
|---|---|
| `0001-detect-kiwi-browser-without-ua-parser-js.patch` | ua-parser-js 2.x is AGPL-3.0-or-later. ttu only uses it to detect Kiwi Browser; a user-agent regex does the same, and the AGPL code stays out of the APK. Proposed upstream: ttu-ttu/ebook-reader#549. |
| `0002-read-shift-jis-and-aozora-bunko-text.patch` | ttu reads `.txt` files as UTF-8 only, so Aozora Bunko's Shift_JIS files became mojibake. Falls back to Shift_JIS and strips Aozora markup (readings, ruby marks, editor notes). Honmani itself now converts Aozora texts to EPUB with ruby before ttu sees them (`AozoraEpub`), and converts other encodings to UTF-8; the patch remains for text files ttu imports directly. |
| `0003-give-vertical-reader-an-explicit-height.patch` | The actual cause of the blank pages on the OnePlus 12: in vertical mode the book container has only a `max-height` (paginated) or a percentage height (continuous), so its line length depends on the browser's fallback for orthogonal flows, which Android WebView resolved to 0 (one or two characters per line, nothing visible). Sets an explicit height. Proposed upstream: ttu-ttu/ebook-reader#548. |
| `0004-hide-bookmark-marker-and-auto-bookmark-settings.patch` | Honmani saves the reading position on every page turn (`autoBookmarkTime` 0 from `ttu-defaults.js`). The bookmark marker in the page corner would then show on every page, and the Auto Bookmark settings could only break that (the time field resets to 3 s, it can't be set to 0). Removes the marker (both view modes) and the two settings. Honmani-specific, not for upstream. |
| `0005-split-long-text-nodes-for-finer-positions.patch` | ttu works out the character count and bookmarks per text node, so in books with paragraphs longer than a page (web novels) the count at the bottom and the saved position stayed on one number for several pages, and Honmani's statistics couldn't tell those pages apart. Splits long text nodes at sentence ends (the text looks the same). Worth proposing upstream. |
| `0006-measure-a-chapter-before-saving-its-position.patch` | Turning back over a chapter boundary (paginated reader) saved the chapter's start instead of its last page: ttu's automatic bookmark ran before the chapter's paragraphs were measured. Measures them first. Worth proposing upstream. |

When bumping the submodule, run `./gradlew :app:buildTtu`; a patch that no longer applies fails
the build.
