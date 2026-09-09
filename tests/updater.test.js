import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';

const updater = fs.readFileSync(new URL('../android/app/src/main/java/com/example/androidmcp/UpdateManager.java', import.meta.url), 'utf8');
const activity = fs.readFileSync(new URL('../android/app/src/main/java/com/example/androidmcp/MainActivity.java', import.meta.url), 'utf8');
const manifest = fs.readFileSync(new URL('../android/app/src/main/AndroidManifest.xml', import.meta.url), 'utf8');

test('Android updater follows the HermesHub check-download-install flow', () => {
  assert.match(updater, /public static void download\(/);
  assert.match(updater, /public static void installDownloaded\(/);
  assert.match(updater, /findDownloadedUpdate\(/);
  assert.match(updater, /onDownloadProgress\(/);
  assert.match(updater, /onDownloadReady\(/);
  assert.match(updater, /\.part/);
  assert.match(updater, /verifyApkIdentity\(/);
  assert.doesNotMatch(updater, /PackageInstaller/);
  assert.doesNotMatch(updater, /resumePending\(/);
  assert.ok(updater.indexOf('verifyApkIdentity(activity, partial, release)') < updater.indexOf('partial.renameTo(ready)'));
});

test('install handoff uses FileProvider and Android package installer UI', () => {
  assert.match(manifest, /androidx\.core\.content\.FileProvider/);
  assert.match(manifest, /android\.permission\.REQUEST_INSTALL_PACKAGES/);
  assert.match(updater, /FileProvider\.getUriForFile/);
  assert.match(updater, /Intent\.ACTION_VIEW/);
  assert.match(updater, /FLAG_GRANT_READ_URI_PERMISSION/);
  assert.match(updater, /ACTION_MANAGE_UNKNOWN_APP_SOURCES/);
});

test('settings UI exposes separate check download and install actions', () => {
  assert.match(activity, /Controlla aggiornamenti/);
  assert.match(activity, /Scarica aggiornamento/);
  assert.match(activity, /Installa aggiornamento/);
  assert.doesNotMatch(activity, /showUpdateDialog\(/);
});
