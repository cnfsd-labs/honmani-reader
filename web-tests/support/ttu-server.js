// @ts-check
/**
 * Serves a ッツ Ebook Reader build the way the app's TtuAssetResolver does: extensionless paths
 * map to prerendered "<path>.html", "/" to "index.html", and files the APK leaves out (web fonts,
 * service worker) are 404s. Pages carry the app's Content-Security-Policy.
 */
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const MIME_TYPES = {
  html: 'text/html',
  js: 'text/javascript',
  mjs: 'text/javascript',
  css: 'text/css',
  json: 'application/json',
  webmanifest: 'application/manifest+json',
  svg: 'image/svg+xml',
  png: 'image/png',
  ico: 'image/x-icon',
  woff2: 'font/woff2',
  txt: 'text/plain',
};

/** Mirrors the Gradle sync filter in app/build.gradle.kts. */
const EXCLUDED = [/\.woff2?$/, /^service-worker\.js$/];

function resolve(urlPath) {
  const trimmed = decodeURIComponent(urlPath).replace(/^\/+/, '');
  if (trimmed.split('/').some((segment) => segment === '..' || segment === '.')) return null;
  if (trimmed === '') return 'index.html';
  const last = trimmed.slice(trimmed.lastIndexOf('/') + 1);
  return last.includes('.') ? trimmed : `${trimmed}.html`;
}

/** Same policy as the app's ReaderPolicy: only the build's own scripts run, never a book's. */
function contentSecurityPolicy(html) {
  const hashes = [...html.matchAll(/<script(\s[^>]*)?>([\s\S]*?)<\/script>/g)]
    .filter((match) => !/\ssrc\s*=/.test(match[1] ?? ''))
    .map((match) => `'sha256-${crypto.createHash('sha256').update(match[2], 'utf8').digest('base64')}'`);
  return [
    `script-src 'self' ${hashes.join(' ')}`.trim(),
    "worker-src 'self' blob:",
    "object-src 'none'",
    "frame-src 'none'",
    "base-uri 'self'",
    "form-action 'none'",
  ].join('; ');
}

function startTtuServer(root) {
  const server = http.createServer((request, response) => {
    const relative = resolve(new URL(request.url ?? '/', 'http://localhost').pathname);
    const file = relative && path.join(root, relative);
    if (!relative || EXCLUDED.some((pattern) => pattern.test(relative)) || !fs.existsSync(file)) {
      response.writeHead(404).end();
      return;
    }
    const extension = relative.slice(relative.lastIndexOf('.') + 1);
    const headers = { 'Content-Type': MIME_TYPES[extension] ?? 'application/octet-stream' };
    if (extension === 'html') {
      const html = fs.readFileSync(file, 'utf8');
      response.writeHead(200, { ...headers, 'Content-Security-Policy': contentSecurityPolicy(html) }).end(html);
      return;
    }
    response.writeHead(200, headers);
    fs.createReadStream(file).pipe(response);
  });
  return new Promise((resolveServer) => {
    server.listen(0, '127.0.0.1', () => {
      const address = /** @type {import('node:net').AddressInfo} */ (server.address());
      resolveServer({ url: `http://127.0.0.1:${address.port}`, close: () => server.close() });
    });
  });
}

module.exports = { startTtuServer, contentSecurityPolicy };
