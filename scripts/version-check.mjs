import fs from 'node:fs';

function fail(message) {
  console.error(message);
  process.exitCode = 1;
}

const pkg = JSON.parse(fs.readFileSync(new URL('../package.json', import.meta.url), 'utf8'));
const lock = JSON.parse(fs.readFileSync(new URL('../package-lock.json', import.meta.url), 'utf8'));
const gradle = fs.readFileSync(new URL('../android/app/build.gradle', import.meta.url), 'utf8');
const bridge = fs.readFileSync(new URL('../bridge/server.js', import.meta.url), 'utf8');
const buildScript = fs.readFileSync(new URL('../build-android.ps1', import.meta.url), 'utf8');
const releaseWorkflow = fs.readFileSync(new URL('../.github/workflows/release.yml', import.meta.url), 'utf8');
const publishScript = fs.readFileSync(new URL('../publish-release.ps1', import.meta.url), 'utf8');

const version = pkg.version;
if (!/^\d+\.\d+\.\d+$/.test(version)) fail(`Invalid package version: ${version}`);
if (lock.version !== version || lock.packages?.['']?.version !== version) {
  fail('package-lock.json version does not match package.json');
}

const gradleVersion = gradle.match(/versionName\s+'([^']+)'/)?.[1];
if (gradleVersion !== version) fail(`Gradle versionName ${gradleVersion} != ${version}`);

const bridgeVersion = bridge.match(/new McpServer\(\{ name: 'android-private-mcp', version: '([^']+)' \}\)/)?.[1];
if (bridgeVersion !== version) fail(`MCP server version ${bridgeVersion} != ${version}`);

if (!buildScript.includes('mcp-android-$version-debug.apk')) {
  fail('build-android.ps1 does not derive the APK name from package.json version');
}

if (releaseWorkflow.includes('--clobber') || releaseWorkflow.includes('gh release upload')) {
  fail('release workflow must not overwrite existing release assets');
}
if (!releaseWorkflow.includes('GITHUB_REF_NAME') || !releaseWorkflow.includes('gh release view "$RELEASE_TAG"')) {
  fail('release workflow must enforce main branch and reject existing tags');
}
if (!publishScript.includes('git branch --show-current') ||
    !publishScript.includes('git status --porcelain') ||
    !publishScript.includes('git fetch origin main') ||
    !publishScript.includes('git rev-parse origin/main')) {
  fail('publish-release.ps1 must require a clean main branch synchronized with origin/main');
}
if (publishScript.includes('--clobber') || publishScript.includes('gh release upload')) {
  fail('publish-release.ps1 must not overwrite existing release assets');
}

const releaseTag = process.env.RELEASE_TAG?.trim();
if (releaseTag) {
  const normalized = releaseTag.replace(/^v/i, '');
  if (normalized !== version) fail(`Release tag ${releaseTag} != v${version}`);
}

if (!process.exitCode) console.log(`Version consistency OK: ${version}`);
