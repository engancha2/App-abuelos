import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import WebSocket from 'ws';
import { buildServer } from '../src/server.js';

test('flujo completo familia ↔ dispositivo', async () => {
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'cerca-'));
  const app = await buildServer({ dataDir, logger: false });
  let cookie = '';
  const call = async (method, url, body, headers = {}) => {
    const res = await app.inject({ method, url, payload: body, headers: { cookie, ...headers } });
    const setCookie = res.headers['set-cookie'];
    if (setCookie) cookie = String(setCookie).split(';')[0];
    return { status: res.statusCode, json: res.body ? JSON.parse(res.body) : null };
  };

  // Registro y login
  assert.equal((await call('POST', '/api/auth/register', { email: 'xavi@test.com', name: 'Xavi', password: 'corta' })).status, 400);
  assert.equal((await call('POST', '/api/auth/register', { email: 'xavi@test.com', name: 'Xavi', password: 'contraseña-larga' })).status, 200);
  assert.equal((await call('GET', '/api/me')).json.email, 'xavi@test.com');

  // Alta de la abuela + código de emparejamiento
  const { id } = (await call('POST', '/api/seniors', { name: 'Abuela Rosa' })).json;
  const { code } = (await call('POST', `/api/seniors/${id}/pair-code`)).json;
  assert.match(code, /^\d{6}$/);

  // Emparejar el móvil
  assert.equal((await call('POST', '/api/device/pair', { code: '000000' })).status, 404);
  const pair = (await call('POST', '/api/device/pair', { code, device: { model: 'Redmi A3' } })).json;
  assert.ok(pair.token);
  const dev = { authorization: `Bearer ${pair.token}` };
  assert.equal((await call('POST', '/api/device/pair', { code })).status, 404, 'el código es de un solo uso');

  // Config: modo zona sin zonas debe fallar
  const bad = await call('PUT', `/api/seniors/${id}/config`, { location: { mode: 'zone', zones: [] } });
  assert.equal(bad.status, 400);

  const cfg = {
    contacts: [
      { id: 'c1', name: 'Xavi', phone: '+34 600 11 22 33', whatsapp: true },
      { id: 'c2', name: 'Marta', phone: '600445566' },
    ],
    sosContactId: 'c1',
    calls: { filter: 'block_unknown', allowRepeated: true },
    location: { mode: 'interval', intervalMinutes: 10, zones: [{ name: 'Casa', lat: 41.1189, lng: 1.2445, radius: 300 }] },
    apps: ['com.whatsapp', 'bad pkg!'],
  };
  const put = await call('PUT', `/api/seniors/${id}/config`, cfg);
  assert.equal(put.status, 200);
  assert.equal((await call('POST', `/api/seniors/${id}/pin`, { pin: '12' })).status, 400);
  assert.equal((await call('POST', `/api/seniors/${id}/pin`, { pin: '2468' })).status, 200);

  const devCfg = (await call('GET', '/api/device/config', null, dev)).json;
  assert.equal(devCfg.config.contacts[0].phone, '+34600112233');
  assert.equal(devCfg.config.location.mode, 'interval');
  assert.deepEqual(devCfg.config.apps, ['com.whatsapp']);
  assert.match(devCfg.config.pinHash, /^[a-f0-9]{64}$/);
  const panelCfg = (await call('GET', `/api/seniors/${id}`)).json;
  assert.equal(panelCfg.config.pinHash, undefined, 'el hash del PIN no se expone al panel');
  assert.equal(panelCfg.config.hasPin, true);

  // Heartbeat sano y después con problemas → alerta
  await call('POST', '/api/device/heartbeat', { battery: 80, isDefaultHome: true, isDefaultDialer: true, locationPermission: 'always', apps: [{ pkg: 'com.whatsapp', label: 'WhatsApp' }] }, dev);
  await call('POST', '/api/device/heartbeat', { battery: 10, isDefaultHome: false, isDefaultDialer: true, locationPermission: 'always' }, dev);
  let alerts = (await call('GET', `/api/seniors/${id}/alerts`)).json;
  assert.deepEqual(alerts.map((a) => a.type).sort(), ['low_battery', 'setup_problem']);
  // El mismo estado otra vez no duplica avisos
  await call('POST', '/api/device/heartbeat', { battery: 9, isDefaultHome: false, isDefaultDialer: true, locationPermission: 'always' }, dev);
  assert.equal((await call('GET', `/api/seniors/${id}/alerts`)).json.length, 2);

  // Ubicaciones
  const loc = await call('POST', '/api/device/locations', [{ lat: 41.12, lng: 1.245, accuracy: 12, ts: Date.now() }], dev);
  assert.equal(loc.json.stored, 1);

  // zone_exit dentro de la zona se ignora; fuera crea alerta
  assert.equal((await call('POST', '/api/device/alert', { type: 'zone_exit', lat: 41.1190, lng: 1.2446, zoneName: 'Casa' }, dev)).json.ignored, true);
  assert.ok((await call('POST', '/api/device/alert', { type: 'zone_exit', lat: 41.13, lng: 1.26, zoneName: 'Casa' }, dev)).json.alertId);
  assert.ok((await call('POST', '/api/device/alert', { type: 'sos', lat: 41.13, lng: 1.26 }, dev)).json.alertId);

  const summary = (await call('GET', '/api/seniors')).json[0];
  assert.equal(summary.pendingAlerts, 4);
  assert.equal(summary.lastLocation.lat, 41.13);

  // "¿Dónde está?" sin conexión en vivo queda pendiente para el siguiente heartbeat
  assert.equal((await call('POST', `/api/seniors/${id}/locate`)).json.live, false);
  assert.equal((await call('POST', '/api/device/heartbeat', { battery: 50, isDefaultHome: true }, dev)).json.locate, true);

  // WebSocket en vivo
  await app.listen({ port: 0, host: '127.0.0.1' });
  const port = app.server.address().port;
  const ws = new WebSocket(`ws://127.0.0.1:${port}/api/device/ws`, { headers: dev });
  const messages = [];
  await new Promise((resolve, reject) => {
    ws.on('message', (m) => { messages.push(JSON.parse(m)); if (messages.length === 1) resolve(); });
    ws.on('error', reject);
  });
  assert.equal(messages[0].type, 'hello');
  assert.equal((await call('POST', `/api/seniors/${id}/locate`)).json.live, true);
  await call('PUT', `/api/seniors/${id}/config`, { ...cfg, ui: { fontScale: 1.3 } });
  await new Promise((r) => setTimeout(r, 100));
  assert.deepEqual(messages.slice(1).map((m) => m.type), ['locate', 'config']);
  ws.close();

  // Otro usuario no puede ver a la abuela
  cookie = '';
  await call('POST', '/api/auth/register', { email: 'otro@test.com', name: 'Otro', password: 'contraseña-larga' });
  assert.equal((await call('GET', `/api/seniors/${id}`)).status, 404);
  assert.equal((await call('GET', '/api/device/config', null, { authorization: 'Bearer falso' })).status, 401);

  await app.close();
  fs.rmSync(dataDir, { recursive: true, force: true });
});
