import { test } from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createFcm, loadFcmCredentials } from '../src/fcm.js';

const silent = { info() {}, warn() {} };

test('FCM: firma la petición a Google, reutiliza el acceso y detecta tokens caducados', async () => {
  const { privateKey, publicKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
  const credentials = {
    project_id: 'cerca-test',
    client_email: 'cerca@cerca-test.iam.gserviceaccount.com',
    private_key: privateKey.export({ type: 'pkcs8', format: 'pem' }),
  };
  const calls = [];
  const realFetch = globalThis.fetch;
  globalThis.fetch = async (url, opts) => {
    calls.push({ url, opts });
    if (url.startsWith('https://oauth2.googleapis.com/')) {
      const [h, p, s] = new URLSearchParams(opts.body).get('assertion').split('.');
      assert.ok(crypto.verify('RSA-SHA256', Buffer.from(`${h}.${p}`), publicKey, Buffer.from(s, 'base64url')), 'firma válida');
      assert.equal(JSON.parse(Buffer.from(p, 'base64url')).iss, credentials.client_email);
      return new Response(JSON.stringify({ access_token: 'acceso', expires_in: 3600 }));
    }
    assert.equal(url, 'https://fcm.googleapis.com/v1/projects/cerca-test/messages:send');
    assert.equal(opts.headers.authorization, 'Bearer acceso');
    const { message } = JSON.parse(opts.body);
    if (message.token === 'caducado') return new Response('{"error":{"status":"NOT_FOUND","details":[{"errorCode":"UNREGISTERED"}]}}', { status: 404 });
    if (message.token === 'roto') return new Response('{"error":{"status":"INTERNAL"}}', { status: 500 });
    return new Response('{"name":"projects/cerca-test/messages/1"}');
  };
  try {
    const fcm = createFcm({ credentials, log: silent });
    assert.equal(fcm.enabled, true);
    assert.equal(await fcm.send({ token: 'bueno', data: { type: 'locate' } }), 'ok');
    assert.equal(await fcm.send({ token: 'caducado' }), 'invalid');
    assert.equal(await fcm.send({ token: 'roto' }), 'error');
    assert.equal(calls.filter((c) => c.url.startsWith('https://oauth2')).length, 1, 'el acceso se pide una sola vez');
  } finally {
    globalThis.fetch = realFetch;
  }
});

test('FCM: sin clave queda desactivado y la clave se lee de variable o de /data', async () => {
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'cerca-fcm-'));
  const key = { project_id: 'p', client_email: 'e@p.iam.gserviceaccount.com', private_key: 'k' };
  try {
    assert.equal(loadFcmCredentials(dataDir, undefined), null);
    assert.equal(await createFcm({ credentials: null, log: silent }).send({}), 'disabled');
    assert.equal(loadFcmCredentials(dataDir, JSON.stringify(key)).project_id, 'p');
    assert.equal(loadFcmCredentials(dataDir, Buffer.from(JSON.stringify(key)).toString('base64')).project_id, 'p');
    fs.writeFileSync(path.join(dataDir, 'firebase-service-account.json'), JSON.stringify(key));
    assert.equal(loadFcmCredentials(dataDir, undefined).client_email, key.client_email);
    assert.throws(() => loadFcmCredentials(dataDir, '{"project_id":"p"}'), /no es válida/);
  } finally {
    fs.rmSync(dataDir, { recursive: true, force: true });
  }
});
