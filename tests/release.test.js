import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';

const expectedSigner = '7be7c380f31c81c050a86ea8cefd4ec3bd41972ddd864a8edb97b1e20c84823f';

test('local release path pins the historical Android signing certificate', () => {
  const script = fs.readFileSync(new URL('../publish-release.ps1', import.meta.url), 'utf8').toLowerCase();
  assert.match(script, new RegExp(expectedSigner));
  assert.match(script, /certificate sha-256|certificate_sha256|signer.*sha-256|signer.*sha256/);
});

test('both release paths reject a non-increasing Android versionCode', () => {
  const local = fs.readFileSync(new URL('../publish-release.ps1', import.meta.url), 'utf8');
  const workflow = fs.readFileSync(new URL('../.github/workflows/release.yml', import.meta.url), 'utf8');
  assert.match(local, /git describe --tags --abbrev=0/);
  assert.match(local, /previousVersionCode/);
  assert.match(workflow, /fetch-depth:\s*0/);
  assert.match(workflow, /git describe --tags --abbrev=0/);
  assert.match(workflow, /previous_code/);
});
