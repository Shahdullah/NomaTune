// Harness for the anonymous client-rotation fix.
// Run it from morideobfuscator/javascript/:
//   cd morideobfuscator/javascript && node tests/__rotation.test.mjs
// src/__test-index.mjs is a verbatim copy of src/index.js that additionally exports
// { resolveWithClients, resolveWithClient, failureKind } for unit testing. Keep the two
// in sync when src/index.js changes (diff them: only the __test export should differ).
import crypto from 'node:crypto';
Object.defineProperty(globalThis, 'crypto', { value: crypto.webcrypto, writable: true, configurable: true });
globalThis.__archiveTuneCacheRead = async () => undefined;
globalThis.__archiveTuneCacheWrite = async () => {};
globalThis.__archiveTuneCacheRemove = async () => {};
globalThis.__archiveTuneSha1 = async (v) => 'x';
globalThis.__archiveTuneUuid = () => crypto.randomUUID();
globalThis.__archiveTuneRandom = (n) => 'AAAA';
globalThis.__archiveTuneHttp = async () => JSON.stringify({ ok: false, kind: 'NETWORK', message: 'blocked in test' });
globalThis.__archiveTunePlayerSource = async () => 'ARCHIVETUNE_PLAYER_SOURCE_ERROR|HTTP|x';

const { __test } = await import('../src/__test-index.mjs');
const request = { mediaId: 'dX3k_QDnzHE', quality: 'HIGH', videoPoToken: 'tok', sessionPoToken: 'sess' };

function fakeFormat() {
  return {
    itag: 251, mime_type: 'audio/webm; codecs="opus"', has_audio: true, has_video: false,
    is_type_otf: false, url: 'https://rr1---sn-x.googlevideo.com/a?n=abc', bitrate: 130000,
    average_bitrate: 130000, audio_sample_rate: 48000, content_length: '1000', is_original: true,
    decipher: async () => 'https://rr1---sn-x.googlevideo.com/a?n=abc',
  };
}
function playable() {
  return { playability_status: { status: 'OK' }, streaming_data: { adaptive_formats: [fakeFormat()], expires: '3600' }, basic_info: { title: 'x', duration: 244 }, player_config: {} };
}
function fail(kind, message) {
  const e = new Error(message); e.kind = kind; return e;
}

function fakeYoutube(behaviour) {
  return {
    calls: [],
    session: { player: { player_id: 'test', signature_timestamp: 19000, data: {} } },
    getPlayer: async () => ({ player_id: 'test', signature_timestamp: 19000, data: {} }),
    async getBasicInfo(id, opts) {
      this.calls.push(opts.client);
      const b = behaviour[opts.client];
      if (!b) throw fail('INTERNAL', 'unexpected client ' + opts.client);
      if (b.error) throw b.error;
      return b.info;
    },
  };
}

const cases = [
  ['MWEB NO_FORMAT (SABR-only) -> VISIONOS plays', { MWEB: { error: fail('NO_FORMAT', 'YouTube offered only SABR streams to MWEB') }, VISIONOS: { info: playable() } }, true, ['MWEB','VISIONOS']],
  ['MWEB LOGIN_REQUIRED "Please sign in" -> VISIONOS plays', { MWEB: { error: fail('LOGIN_REQUIRED', 'Please sign in') }, VISIONOS: { info: playable() } }, true, ['MWEB','VISIONOS']],
  ['MWEB HTTP 403 -> VISIONOS plays', { MWEB: { error: fail('HTTP', 'HTTP 403') }, VISIONOS: { info: playable() } }, true, ['MWEB','VISIONOS']],
  ['MWEB NETWORK -> do NOT rotate', { MWEB: { error: fail('NETWORK', 'fetch failed') }, VISIONOS: { info: playable() } }, false, ['MWEB']],
  ['both fail -> aggregated message', { MWEB: { error: fail('LOGIN_REQUIRED', 'Please sign in') }, VISIONOS: { error: fail('NO_FORMAT', 'No direct audio format') } }, false, ['MWEB','VISIONOS']],
];

let ok = true;
for (const [name, behaviour, shouldSucceed, expectedClients] of cases) {
  const yt = fakeYoutube(behaviour);
  let passed = false; let message = '';
  try { await __test.resolveWithClients(yt, request, ['MWEB', 'VISIONOS']); passed = true; }
  catch (e) { message = e.message; }
  const clientsOk = JSON.stringify(yt.calls) === JSON.stringify(expectedClients);
  const good = passed === shouldSucceed && clientsOk;
  ok &&= good;
  console.log(`${good ? 'PASS' : 'FAIL'}  ${name}`);
  console.log(`      clients tried: ${yt.calls.join(' -> ')} | outcome: ${passed ? 'resolved' : 'threw'}`);
  if (message) console.log(`      reason surfaced: ${message}`);
}
process.exit(ok ? 0 : 1);
