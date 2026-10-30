// @ts-check
/** Builds a minimal EPUB 3 in memory. */
const { zipSync, strToU8 } = require('fflate');

/**
 * Paragraphs are wrapped in <p> unless `raw` is set, in which case they are XHTML blocks. `css`
 * becomes the book's stylesheet. `chapters` (lists of paragraphs) makes one file per chapter
 * instead of `paragraphs`.
 */
function buildEpub({ title, paragraphs = [], chapters = [paragraphs], raw = false, css = '' }) {
  const names = chapters.map((_, i) => `ch${i + 1}`);
  const chapterFile = (chapter, i) => strToU8(`<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="ja">
<head><title>${i + 1}</title>${css ? '<link rel="stylesheet" type="text/css" href="style.css"/>' : ''}</head>
<body>
${chapter.map((p) => (raw ? p : `<p>${p}</p>`)).join('\n')}
</body>
</html>`);
  const files = {
    mimetype: [strToU8('application/epub+zip'), { level: 0 }],
    'META-INF/container.xml': strToU8(`<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>`),
    'OEBPS/content.opf': strToU8(`<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id" xml:lang="ja">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="id">urn:uuid:3f1e1c4a-5c0e-4f6a-9d63-0a1b2c3d4e5f</dc:identifier>
    <dc:title>${title}</dc:title>
    <dc:language>ja</dc:language>
    <meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    ${names.map((name) => `<item id="${name}" href="${name}.xhtml" media-type="application/xhtml+xml"/>`).join('\n    ')}
    ${css ? '<item id="style" href="style.css" media-type="text/css"/>' : ''}
  </manifest>
  <spine page-progression-direction="rtl">${names.map((name) => `<itemref idref="${name}"/>`).join('')}</spine>
</package>`),
    'OEBPS/nav.xhtml': strToU8(`<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>目次</title></head>
<body><nav epub:type="toc"><ol>${names.map((name, i) => `<li><a href="${name}.xhtml">${i + 1}</a></li>`).join('')}</ol></nav></body>
</html>`),
  };
  names.forEach((name, i) => {
    files[`OEBPS/${name}.xhtml`] = chapterFile(chapters[i], i);
  });
  if (css) files['OEBPS/style.css'] = strToU8(css);
  return Buffer.from(zipSync(files));
}

module.exports = { buildEpub };
