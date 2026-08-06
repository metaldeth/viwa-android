const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');
const http = require('http');

const {
  APK_NAME_RE,
  createRequestHandler,
  getLatestApk,
  parseApkFilename,
  readSigningCredentials,
  DEFAULT_KEY_ALIAS,
} = require('./server.js');

async function withTempDirs(fn) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'viwa-update-'));
  const releaseDir = path.join(root, 'release');
  const signingDir = path.join(root, 'signing');
  fs.mkdirSync(releaseDir);
  fs.mkdirSync(signingDir);
  const prevRelease = process.env.RELEASE_DIR;
  const prevSigning = process.env.SIGNING_DIR;
  const prevToken = process.env.RELEASE_TOKEN;
  process.env.RELEASE_DIR = releaseDir;
  process.env.SIGNING_DIR = signingDir;
  try {
    return await fn({ root, releaseDir, signingDir });
  } finally {
    if (prevRelease === undefined) delete process.env.RELEASE_DIR;
    else process.env.RELEASE_DIR = prevRelease;
    if (prevSigning === undefined) delete process.env.SIGNING_DIR;
    else process.env.SIGNING_DIR = prevSigning;
    if (prevToken === undefined) delete process.env.RELEASE_TOKEN;
    else process.env.RELEASE_TOKEN = prevToken;
    fs.rmSync(root, { recursive: true, force: true });
  }
}

function request(server, options, body) {
  return new Promise((resolve, reject) => {
    const addr = server.address();
    const req = http.request(
      {
        hostname: '127.0.0.1',
        port: addr.port,
        ...options,
      },
      (res) => {
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () => {
          resolve({
            status: res.statusCode,
            headers: res.headers,
            body: Buffer.concat(chunks),
          });
        });
      },
    );
    req.on('error', reject);
    if (body) req.write(body);
    req.end();
  });
}

test('parseApkFilename extracts version', () => {
  assert.deepEqual(parseApkFilename('viwa-android-26.08.05.02-release.apk'), {
    name: 'viwa-android-26.08.05.02-release.apk',
    version: '26.08.05.02',
  });
});

test('getLatestApk picks last by filename sort', async () => {
  await withTempDirs(({ releaseDir }) => {
    fs.writeFileSync(path.join(releaseDir, 'viwa-android-26.08.05.01-release.apk'), 'a');
    fs.writeFileSync(path.join(releaseDir, 'viwa-android-26.08.05.02-release.apk'), 'b');
    const latest = getLatestApk(releaseDir);
    assert.equal(latest.name, 'viwa-android-26.08.05.02-release.apk');
  });
});

test('readSigningCredentials prefers credentials.json', async () => {
  await withTempDirs(({ signingDir }) => {
    fs.writeFileSync(
      path.join(signingDir, 'credentials.json'),
      JSON.stringify({
        keyAlias: 'alias-from-json',
        storePassword: 'store',
        keyPassword: 'key',
      }),
    );
    const creds = readSigningCredentials();
    assert.equal(creds.keyAlias, 'alias-from-json');
    assert.equal(creds.storePassword, 'store');
    assert.equal(creds.keyPassword, 'key');
  });
});

test('readSigningCredentials falls back to pass files', async () => {
  await withTempDirs(({ signingDir }) => {
    fs.writeFileSync(path.join(signingDir, '.storepass'), 'store-pass');
    fs.writeFileSync(path.join(signingDir, '.keypass'), 'key-pass');
    fs.writeFileSync(path.join(signingDir, 'key-alias'), 'custom-alias');
    const creds = readSigningCredentials();
    assert.equal(creds.keyAlias, 'custom-alias');
    assert.equal(creds.storePassword, 'store-pass');
    assert.equal(creds.keyPassword, 'key-pass');
  });
});

test('readSigningCredentials uses default alias', async () => {
  await withTempDirs(({ signingDir }) => {
    fs.writeFileSync(path.join(signingDir, '.storepass'), 'store-pass');
    fs.writeFileSync(path.join(signingDir, '.keypass'), 'key-pass');
    const creds = readSigningCredentials();
    assert.equal(creds.keyAlias, DEFAULT_KEY_ALIAS);
  });
});

test('admin upload requires token', async () => {
  await withTempDirs(async ({ releaseDir }) => {
    process.env.RELEASE_TOKEN = 'secret-token';
    const server = http.createServer(createRequestHandler());
    await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
    try {
      const apkName = 'viwa-android-26.08.05.02-release.apk';
      const body = Buffer.from('fake-apk');
      const unauthorized = await request(server, {
        method: 'POST',
        path: '/admin/upload',
        headers: {
          'X-Filename': apkName,
          'Content-Length': body.length,
        },
      }, body);
      assert.equal(unauthorized.status, 401);

      const ok = await request(server, {
        method: 'POST',
        path: '/admin/upload',
        headers: {
          Authorization: 'Bearer secret-token',
          'X-Filename': apkName,
          'Content-Length': body.length,
        },
      }, body);
      assert.equal(ok.status, 200);
      assert.deepEqual(JSON.parse(ok.body.toString()), {
        ok: true,
        name: apkName,
        version: '26.08.05.02',
      });
      assert.equal(fs.readFileSync(path.join(releaseDir, apkName)).toString(), 'fake-apk');
    } finally {
      server.close();
    }
  });
});

test('admin upload rejects invalid filename', async () => {
  await withTempDirs(async () => {
    process.env.RELEASE_TOKEN = 'secret-token';
    const server = http.createServer(createRequestHandler());
    await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
    try {
      const res = await request(server, {
        method: 'POST',
        path: '/admin/upload',
        headers: {
          Authorization: 'Bearer secret-token',
          'X-Filename': 'bad-name.apk',
          'Content-Length': 3,
        },
      }, Buffer.from('apk'));
      assert.equal(res.status, 400);
    } finally {
      server.close();
    }
  });
});

test('admin endpoints return 503 when token not configured', async () => {
  await withTempDirs(async () => {
    delete process.env.RELEASE_TOKEN;
    const server = http.createServer(createRequestHandler());
    await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
    try {
      const res = await request(server, {
        method: 'GET',
        path: '/admin/signing/credentials.json',
      });
      assert.equal(res.status, 503);
    } finally {
      server.close();
    }
  });
});

test('latest.apk redirects to newest release', async () => {
  await withTempDirs(async ({ releaseDir }) => {
    fs.writeFileSync(path.join(releaseDir, 'viwa-android-26.08.05.01-release.apk'), 'a');
    fs.writeFileSync(path.join(releaseDir, 'viwa-android-26.08.05.02-release.apk'), 'b');
    const server = http.createServer(createRequestHandler());
    await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
    try {
      const res = await request(server, { method: 'GET', path: '/latest.apk' });
      assert.equal(res.status, 302);
      assert.match(res.headers.location, /26\.08\.05\.02-release\.apk$/);
    } finally {
      server.close();
    }
  });
});

test('APK_NAME_RE still accepts viwa and wiva names', () => {
  assert.match('viwa-android-26.07.27.01-release.apk', APK_NAME_RE);
  assert.match('wiva-android-26.04.01.01-release.apk', APK_NAME_RE);
  assert.doesNotMatch('other-app-release.apk', APK_NAME_RE);
});
