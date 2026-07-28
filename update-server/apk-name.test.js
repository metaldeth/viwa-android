const test = require('node:test');
const assert = require('node:assert/strict');

const APK_NAME_RE = /^(?:viwa|wiva)-android-(.+?)-release\.apk$/;

test('accepts viwa-android release apk names', () => {
  assert.match('viwa-android-26.07.27.01-release.apk', APK_NAME_RE);
});

test('accepts legacy wiva-android release apk names', () => {
  assert.match('wiva-android-26.04.01.01-release.apk', APK_NAME_RE);
});

test('rejects unrelated apk names', () => {
  assert.doesNotMatch('other-app-release.apk', APK_NAME_RE);
});
