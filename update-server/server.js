/**
 * HTTP-сервер обновлений: раздача каталога release/, GET /version.json для wiva-android APK.
 * Сканирует wiva-android-{version}-release.apk, сортирует по имени файла, отдаёт последний.
 */
const http = require('http');
const fs = require('fs');
const path = require('path');
const url = require('url');

const PORT = Number(process.env.PORT) || 9082;
const RELEASE_DIR = process.env.RELEASE_DIR || path.join(__dirname, 'release');
// BASE_URL — URL для поля url в version.json. При запуске без Docker задайте его,
// иначе клиенты получат localhost и не смогут скачать файл с другой машины.
const DEFAULT_UPDATE_BASE_URL = process.env.DEFAULT_UPDATE_BASE_URL || `http://dev.ishaker.ru:${PORT}`;
const BASE_URL = process.env.BASE_URL || DEFAULT_UPDATE_BASE_URL;

const APK_NAME_RE = /^(?:viwa|wiva)-android-(.+?)-release\.apk$/;

function logReleaseDirDiagnostics() {
  console.log('[Update server] === Диагностика каталога release ===');
  console.log('[Update server] RELEASE_DIR (env/default):', RELEASE_DIR);
  const resolvedDir = path.resolve(RELEASE_DIR);
  console.log('[Update server] path.resolve(RELEASE_DIR):', resolvedDir);
  let list;
  try {
    list = fs.readdirSync(RELEASE_DIR);
  } catch (err) {
    console.log('[Update server] readdirSync error:', err.message);
    return;
  }
  console.log('[Update server] Всего записей в каталоге:', list.length);
  list.forEach((name, i) => {
    const fullPath = path.join(RELEASE_DIR, name);
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
  const latest = getLatestApk();
  console.log(
    '[Update server] getLatestApk():',
    latest ? `name="${latest.name}" version="${latest.version}"` : 'null',
  );
  console.log('[Update server] === Конец диагностики ===');
}

/** Последний по имени файла wiva-android-{version}-release.apk (сортировка по строке имени). */
function getLatestApk() {
  let list;
  try {
    list = fs.readdirSync(RELEASE_DIR);
  } catch (err) {
    return null;
  }
  const candidates = [];
  for (const name of list) {
    const m = name.match(APK_NAME_RE);
    if (m) candidates.push({ name, version: m[1] });
  }
  if (candidates.length === 0) return null;
  candidates.sort((a, b) => a.name.localeCompare(b.name));
  const last = candidates[candidates.length - 1];
  return { name: last.name, version: last.version };
}

function readChangelogText() {
  const changelogPath = path.join(RELEASE_DIR, 'CHANGELOG.md');
  try {
    const st = fs.statSync(changelogPath);
    if (!st.isFile()) return '';
    return fs.readFileSync(changelogPath, 'utf8');
  } catch {
    return '';
  }
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

function isPathSafe(pathname) {
  if (pathname.includes('..')) return false;
  const filePath = path.join(RELEASE_DIR, pathname);
  const resolvedBase = path.resolve(RELEASE_DIR);
  const resolvedFile = path.resolve(filePath);
  const baseWithSep = resolvedBase.endsWith(path.sep)
    ? resolvedBase
    : resolvedBase + path.sep;
  return resolvedFile === resolvedBase || resolvedFile.startsWith(baseWithSep);
}

const server = http.createServer((req, res) => {
  const parsed = url.parse(req.url, true);
  const pathname = decodeURIComponent(parsed.pathname || '/').replace(/^\/+/, '');

  if (pathname === 'version.json') {
    sendVersionJson(res);
    return;
  }

  if (pathname === 'CHANGELOG.md') {
    const changelogPath = path.join(RELEASE_DIR, 'CHANGELOG.md');
    if (!isPathSafe('CHANGELOG.md')) {
      res.writeHead(400);
      res.end('Bad request');
      return;
    }
    fs.stat(changelogPath, (err, stat) => {
      if (err || !stat.isFile()) {
        res.writeHead(404);
        res.end('Not found');
        return;
      }
      res.writeHead(200, {
        'Content-Type': 'text/markdown; charset=utf-8',
        'Content-Length': stat.size,
      });
      fs.createReadStream(changelogPath).pipe(res);
    });
    return;
  }

  if (!isPathSafe(pathname)) {
    res.writeHead(400);
    res.end('Bad request');
    return;
  }

  const filePath = path.join(RELEASE_DIR, pathname);
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
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(
    `[Update server] listening on port ${PORT}, RELEASE_DIR=${RELEASE_DIR}, BASE_URL=${BASE_URL}`,
  );
  logReleaseDirDiagnostics();
});
