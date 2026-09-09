import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const read = path => fs.readFileSync(path, 'utf8');

test('optional privileged backends are lazy and accessibility XML is not all-events', () => {
  const activity = read('android/app/src/main/java/com/example/androidmcp/MainActivity.java');
  const foreground = read('android/app/src/main/java/com/example/androidmcp/McpForegroundService.java');
  const transportManager = read('android/app/src/main/java/com/example/androidmcp/TransportManager.java');
  const discovery = read('android/app/src/main/java/com/example/androidmcp/LanDiscoveryResponder.java');
  const shizuku = read('android/app/src/main/java/com/example/androidmcp/ShizukuBridge.java');
  const accessibilityXml = read('android/app/src/main/res/xml/accessibility_service.xml');
  const idleStart = foreground.indexOf('private void enterLowPowerIdle()');
  const idleEnd = foreground.indexOf('@Override public IBinder', idleStart);
  const idleBlock = foreground.slice(idleStart, idleEnd);
  const stopStart = foreground.indexOf('static void stopNow()');
  const stopEnd = foreground.indexOf('static boolean isRunning()', stopStart);
  const stopBlock = foreground.slice(stopStart, stopEnd);

  assert.doesNotMatch(activity, /ShizukuBridge\.initialize\(this\)/);
  assert.doesNotMatch(foreground, /ShizukuBridge\.initialize\(this\)/);
  assert.doesNotMatch(accessibilityXml, /typeAllMask/);
  assert.match(idleBlock, /transportManager\.stop\(\)/);
  assert.match(transportManager, /unregisterNetworkCallback/);
  assert.match(transportManager, /TRANSPORT_WIFI/);
  assert.match(transportManager, /TRANSPORT_VPN/);
  assert.match(transportManager, /removeCapability\(NetworkCapabilities\.NET_CAPABILITY_NOT_VPN\)/);
  assert.match(transportManager, /discoveryResponder\.stop\(\)/);
  assert.match(transportManager, /TransportReconciliation\.shouldStartLanDiscovery\(lanEndpoint,\s*discoveryResponder\.isRunning\(\)\)/);
  assert.doesNotMatch(discovery, /SecretStore/);
  assert.match(stopBlock, /current\.enterLowPowerIdle\(\)/);
  assert.match(shizuku, /removeBinderDeadListener/);
});
