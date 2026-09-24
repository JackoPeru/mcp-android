import { execSync } from 'node:child_process';
import fs from 'node:fs';

let failed = false;
const fail = m => { console.error(`FAIL: ${m}`); failed = true; };
const ok = m => console.log(`OK: ${m}`);

// node >= 22
const major = Number(process.versions.node.split('.')[0]);
if (major >= 22) ok(`node ${process.versions.node} >= 22`);
else fail(`node ${process.versions.node} < 22`);

// ANDROID_HOME
const sdk = process.env.ANDROID_HOME;
if (sdk && fs.existsSync(sdk)) ok(`ANDROID_HOME=${sdk}`);
else fail('ANDROID_HOME mancante o percorso inesistente');

// JDK 17+
try {
  const out = execSync('java -version 2>&1', { encoding: 'utf8' });
  const m = out.match(/version "(\d+)/);
  if (m && Number(m[1]) >= 17) ok(`JDK ${m[1]}+ (${out.split('\n')[0].trim()})`);
  else fail(`JDK non valido: ${out.split('\n')[0]}`);
} catch {
  fail('java non trovato nel PATH (serve JDK 17+)');
}

// TOKEN regex (solo formato, mai stamparlo)
const token = process.env.ANDROID_MCP_TOKEN ?? '';
if (/^[a-f0-9]{64}$/.test(token)) ok('ANDROID_MCP_TOKEN formato 64 hex valido');
else fail('ANDROID_MCP_TOKEN mancante o non 64 hex');

if (failed) process.exitCode = 1;
else console.log('doctor: tutti i controlli passati');
