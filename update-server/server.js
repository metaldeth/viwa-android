/**
 * HTTP-сервер обновлений: раздача каталога release/, GET /version.json для viwa-android APK.
 * Admin: скачивание signing materials и загрузка release APK (Bearer / X-Release-Token).
 */
const http = require('http');
const fs = require('fs');
const path = require('path');
const url = require('url');

const PORT = Number(process.env.PORT) || 9082;
function getReleaseDir() {
  return process.env.RELEASE_DIR || path.join(__dirname, 'release');
}

function getSigningDir() {
  return process.env.SIGNING_DIR || path.join(__dirname, 'signing');
}
function getConfiguredReleaseToken() {
  return process.env.RELEASE_TOKEN || '';
}
const DEFAULT_UPDATE_BASE_URL = process.env.DEFAULT_UPDATE_BASE_URL || `http://dev.ishaker.ru:${PORT}`;
const BASE_URL = process.env.BASE_URL || DEFAULT_UPDATE_BASE_URL;
const DEFAULT_KEY_ALIAS = 'viwa-release';

const APK_NAME_RE = /^(?:viwa|wiva)-android-(.+?)-release\.apk$/;

function readFileTrim(filePath) {
  try {
    return fs.readFileSync(filePath, 'utf8').trim();
  } catch {
    return null;
  }
}

function readSigningCredentials() {
  const signingDir = getSigningDir();
  const credentialsPath = path.join(signingDir, 'credentials.json');
  if (fs.existsSync(credentialsPath)) {
    try {
      const parsed = JSON.parse(fs.readFileSync(credentialsPath, 'utf8'));
      const keyAlias = parsed.keyAlias || parsed.key_alias;
      const storePassword = parsed.storePassword || parsed.store_password;
      const keyPassword = parsed.keyPassword || parsed.key_password;
      if (!keyAlias || !storePassword || !keyPassword) {
        throw new Error('credentials.json missing required fields');
      }
      return { keyAlias, storePassword, keyPassword };
    } catch (err) {
      throw new Error(`Invalid credentials.json: ${err.message}`);
    }
  }

  const storePassword = readFileTrim(path.join(signingDir, '.storepass'));
  const keyPassword = readFileTrim(path.join(signingDir, '.keypass'));
  const keyAlias =
    readFileTrim(path.join(signingDir, 'key-alias')) || DEFAULT_KEY_ALIAS;

  if (!storePassword || !keyPassword) {
    throw new Error('Signing credentials not found');
  }

  return { keyAlias, storePassword, keyPassword };
}

function parseApkFilename(filename) {
  const m = String(filename || '').match(APK_NAME_RE);
  if (!m) return null;
  return { name: filename, version: m[1] };
}

function logReleaseDirDiagnostics() {
  console.log('[Update server] === Диагностика каталога release ===');
  const releaseDir = getReleaseDir();
  console.log('[Update server] RELEASE_DIR (env/default):', releaseDir);
  const resolvedDir = path.resolve(releaseDir);
  console.log('[Update server] path.resolve(RELEASE_DIR):', resolvedDir);
  let list;
  try {
    list = fs.readdirSync(releaseDir);
  } catch (err) {
    console.log('[Update server] readdirSync error:', err.message);
    return;
  }
  console.log('[Update server] Всего записей в каталоге:', list.length);
  list.forEach((name, i) => {
    const fullPath = path.join(releaseDir, name);
    let statStr = '?';
    try {
      const st = fs.statSync(fullPath);
      statStr = st.isFile() ? 'file' : st.isDirectory() ? 'dir' : 'other';
    } catch (e) {
      statStr = 'stat error: ' + e.message;
    }
    console.log(`[Update server]   [${i}] "${name}" (${statStr})`);
  });
  const apkNames = list.filter((name) => APK_NAME_RE.test(name));
  console.log('[Update server] Найденные APK ((viwa|wiva)-android-*-release.apk):', apkNames.length);
  apkNames.sort((a, b) => a.localeCompare(b));
  apkNames.forEach((name) => {
    console.log(`[Update server]   - "${name}"`);
  });
  const latest = getLatestApk(releaseDir);
  console.log(
    '[Update server] getLatestApk():',
    latest ? `name="${latest.name}" version="${latest.version}"` : 'null',
  );
  console.log('[Update server] === Конец диагностики ===');
}

/** Последний по имени файла viwa-android-{version}-release.apk (сортировка по строке имени). */
function getLatestApk(releaseDir = getReleaseDir()) {
  let list;
  try {
    list = fs.readdirSync(releaseDir);
  } catch {
    return null;
  }
  const candidates = [];
  for (const name of list) {
    const parsed = parseApkFilename(name);
    if (parsed) candidates.push(parsed);
  }
  if (candidates.length === 0) return null;
  candidates.sort((a, b) => a.name.localeCompare(b.name));
  return candidates[candidates.length - 1];
}

function readChangelogText() {
  const changelogPath = path.join(getReleaseDir(), 'CHANGELOG.md');
  try {
    const st = fs.statSync(changelogPath);
    if (!st.isFile()) return '';
    return fs.readFileSync(changelogPath, 'utf8');
  } catch {
    return '';
  }
}

function isPathSafe(baseDir, pathname) {
  if (pathname.includes('..')) return false;
  const filePath = path.join(baseDir, pathname);
  const resolvedBase = path.resolve(baseDir);
  const resolvedFile = path.resolve(filePath);
  const baseWithSep = resolvedBase.endsWith(path.sep)
    ? resolvedBase
    : resolvedBase + path.sep;
  return resolvedFile === resolvedBase || resolvedFile.startsWith(baseWithSep);
}

function getReleaseToken(req) {
  const auth = req.headers.authorization || req.headers.Authorization;
  if (auth && auth.startsWith('Bearer ')) {
    return auth.slice('Bearer '.length);
  }
  return req.headers['x-release-token'] || req.headers['X-Release-Token'] || '';
}

function requireAdmin(req, res) {
  const configuredToken = getConfiguredReleaseToken();
  if (!configuredToken) {
    res.writeHead(503, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({ error: 'Admin endpoints disabled' }));
    return false;
  }
  if (getReleaseToken(req) !== configuredToken) {
    res.writeHead(401, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({ error: 'Unauthorized' }));
    return false;
  }
  return true;
}

function sendVersionJson(res) {
  const latest = getLatestApk();
  const changelog = readChangelogText();
  if (!latest) {
    console.log('[Update server] GET /version.json => 404 (No APK found)');
    res.writeHead(404, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({ error: 'No APK found' }));
    return;
  }
  console.log(
    '[Update server] GET /version.json => 200',
    latest.name,
    latest.version,
  );
  const fileUrl = `${BASE_URL.replace(/\/$/, '')}/${encodeURIComponent(latest.name)}`;
  res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(
    JSON.stringify({
      version: latest.version,
      url: fileUrl,
      changelog,
    }),
  );
}

function collectRequestBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on('data', (chunk) => chunks.push(chunk));
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

function sendSigningFile(res, relativePath, contentType) {
  const signingDir = getSigningDir();
  if (!isPathSafe(signingDir, relativePath)) {
    res.writeHead(400);
    res.end('Bad request');
    return;
  }
  const filePath = path.join(signingDir, relativePath);
  fs.stat(filePath, (err, stat) => {
    if (err || !stat.isFile()) {
      res.writeHead(404);
      res.end('Not found');
      return;
    }
    res.writeHead(200, {
      'Content-Type': contentType,
      'Content-Length': stat.size,
    });
    fs.createReadStream(filePath).pipe(res);
  });
}

function handleAdminSigningCredentials(res) {
  try {
    const credentials = readSigningCredentials();
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify(credentials));
  } catch (err) {
    console.log('[Update server] GET /admin/signing/credentials.json => 404', err.message);
    res.writeHead(404, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({ error: err.message }));
  }
}

async function handleAdminUpload(req, res) {
  const filename = req.headers['x-filename'] || req.headers['X-Filename'] || '';
  const parsed = parseApkFilename(filename);
  if (!parsed) {
    res.writeHead(400, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({ error: 'Invalid or missing X-Filename header' }));
    return;
  }
  const releaseDir = getReleaseDir();
  if (!isPathSafe(releaseDir, parsed.name)) {
    res.writeHead(400, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({ error: 'Bad filename' }));
    return;
  }

  let body;
  try {
    body = await collectRequestBody(req);
  } catch (err) {
    res.writeHead(500, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({ error: 'Failed to read body' }));
    return;
  }

  const destPath = path.join(releaseDir, parsed.name);
  try {
    fs.mkdirSync(releaseDir, { recursive: true });
    fs.writeFileSync(destPath, body);
  } catch (err) {
    console.log('[Update server] POST /admin/upload write error:', err.message);
    res.writeHead(500, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({ error: 'Failed to write APK' }));
    return;
  }

  console.log('[Update server] POST /admin/upload => ok', parsed.name, `${body.length} bytes`);
  res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify({ ok: true, name: parsed.name, version: parsed.version }));
}

function serveReleaseFile(res, pathname) {
  const releaseDir = getReleaseDir();
  if (!isPathSafe(releaseDir, pathname)) {
    res.writeHead(400);
    res.end('Bad request');
    return;
  }

  const filePath = path.join(releaseDir, pathname);
  fs.stat(filePath, (err, stat) => {
    if (err || !stat.isFile()) {
      res.writeHead(404);
      res.end('Not found');
      return;
    }
    const base = path.basename(filePath);
    const isApk = base.toLowerCase().endsWith('.apk');
    res.setHeader(
      'Content-Type',
      isApk
        ? 'application/vnd.android.package-archive'
        : 'application/octet-stream',
    );
    res.setHeader('Content-Disposition', `attachment; filename="${base}"`);
    res.setHeader('Content-Length', stat.size);
    const stream = fs.createReadStream(filePath);
    stream.on('error', () => {
      if (!res.writableEnded) {
        res.destroy();
      }
    });
    stream.pipe(res);
  });
}

function createRequestHandler() {
  return (req, res) => {
    const parsed = url.parse(req.url, true);
    const pathname = decodeURIComponent(parsed.pathname || '/').replace(/^\/+/, '');

    if (pathname === 'version.json') {
      sendVersionJson(res);
      return;
    }

    if (pathname === 'latest.apk') {
      const latest = getLatestApk();
      if (!latest) {
        res.writeHead(404);
        res.end('Not found');
        return;
      }
      res.writeHead(302, { Location: `/${encodeURIComponent(latest.name)}` });
      res.end();
      return;
    }

    if (pathname === 'CHANGELOG.md') {
      if (!isPathSafe(getReleaseDir(), 'CHANGELOG.md')) {
        res.writeHead(400);
        res.end('Bad request');
        return;
      }
      serveReleaseFile(res, 'CHANGELOG.md');
      return;
    }

    if (pathname === 'admin/signing/release.jks') {
      if (req.method !== 'GET') {
        res.writeHead(405);
        res.end('Method not allowed');
        return;
      }
      if (!requireAdmin(req, res)) return;
      sendSigningFile(res, 'release.jks', 'application/octet-stream');
      return;
    }

    if (pathname === 'admin/signing/credentials.json') {
      if (req.method !== 'GET') {
        res.writeHead(405);
        res.end('Method not allowed');
        return;
      }
      if (!requireAdmin(req, res)) return;
      handleAdminSigningCredentials(res);
      return;
    }

    if (pathname === 'admin/upload') {
      if (req.method !== 'POST') {
        res.writeHead(405);
        res.end('Method not allowed');
        return;
      }
      if (!requireAdmin(req, res)) return;
      handleAdminUpload(req, res);
      return;
    }

    serveReleaseFile(res, pathname);
  };
}

const server = http.createServer(createRequestHandler());

if (require.main === module) {
  server.listen(PORT, '0.0.0.0', () => {
    console.log(
      `[Update server] listening on port ${PORT}, RELEASE_DIR=${getReleaseDir()}, SIGNING_DIR=${getSigningDir()}, BASE_URL=${BASE_URL}`,
    );
    if (!getConfiguredReleaseToken()) {
      console.log('[Update server] RELEASE_TOKEN not set — admin endpoints return 503');
    }
    logReleaseDirDiagnostics();
  });
}

module.exports = {
  APK_NAME_RE,
  DEFAULT_KEY_ALIAS,
  createRequestHandler,
  getLatestApk,
  getReleaseDir,
  getSigningDir,
  parseApkFilename,
  readSigningCredentials,
  isPathSafe,
  getReleaseToken,
  requireAdmin,
};
