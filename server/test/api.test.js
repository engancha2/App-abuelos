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
    blockedApps: ['com.android.settings', 'bad pkg!', 'com.android.settings'],
    ui: { showMenuButton: false },
  };
  const put = await call('PUT', `/api/seniors/${id}/config`, cfg);
  assert.equal(put.status, 200);
  assert.equal((await call('POST', `/api/seniors/${id}/pin`, { pin: '12' })).status, 400);
  assert.equal((await call('POST', `/api/seniors/${id}/pin`, { pin: '2468' })).status, 200);

  const devCfg = (await call('GET', '/api/device/config', null, dev)).json;
  assert.equal(devCfg.config.contacts[0].phone, '+34600112233');
  assert.equal(devCfg.config.location.mode, 'interval');
  assert.deepEqual(devCfg.config.blockedApps, ['com.android.settings']);
  assert.equal(devCfg.config.ui.showMenuButton, false);
  assert.equal(devCfg.config.ui.showContacts, true, 'por defecto el botón Contactos se ve');
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

test('apps: sesión por token, avisos y despertar por FCM, favoritos desde el móvil', async (t) => {
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'cerca-'));
  const sent = [];
  const CADUCADO = 'token-caducado-aaaaaaaaaaaaaaaa';
  const fcm = { enabled: true, send: async (m) => { sent.push(m); return m.token === CADUCADO ? 'invalid' : 'ok'; } };
  const app = await buildServer({ dataDir, logger: false, fcm });
  // También si falla una comprobación: si no, los temporizadores del servidor dejan la prueba colgada
  t.after(async () => { await app.close(); fs.rmSync(dataDir, { recursive: true, force: true }); });
  const call = async (method, url, body, headers = {}) => {
    const res = await app.inject({ method, url, payload: body, headers });
    return { status: res.statusCode, json: res.body ? JSON.parse(res.body) : null, cookie: res.headers['set-cookie'] };
  };
  const tick = () => new Promise((r) => setTimeout(r, 20));
  const to = (token) => sent.filter((m) => m.token === token);

  // La app recibe el token en la respuesta (y no una cookie) y lo usa como Bearer
  const reg = await call('POST', '/api/auth/register', { email: 'hija@test.com', name: 'Marta', password: 'contraseña-larga', client: 'app' });
  assert.equal(reg.status, 200);
  assert.ok(reg.json.token);
  assert.equal(reg.cookie, undefined);
  const fam = { authorization: `Bearer ${reg.json.token}` };
  assert.equal((await call('GET', '/api/me', null, fam)).json.email, 'hija@test.com');
  const login = await call('POST', '/api/auth/login', { email: 'hija@test.com', password: 'contraseña-larga', client: 'app' });
  assert.ok(login.json.token);

  const FAMILIA = 'familia-token-bbbbbbbbbbbbbbbbbbbb';
  assert.equal((await call('POST', '/api/push/fcm', { token: 'x' }, fam)).status, 400);
  assert.equal((await call('POST', '/api/push/fcm', { token: FAMILIA }, fam)).status, 200);
  assert.equal((await call('POST', '/api/push/fcm', { token: CADUCADO }, fam)).status, 200);

  // Emparejar el móvil del abuelo y registrar su token de Firebase
  const { id } = (await call('POST', '/api/seniors', { name: 'Abuelo Julián' }, fam)).json;
  const { code } = (await call('POST', `/api/seniors/${id}/pair-code`, null, fam)).json;
  const dev = { authorization: `Bearer ${(await call('POST', '/api/device/pair', { code })).json.token}` };
  const ABUELO = 'abuelo-token-cccccccccccccccccccc';
  assert.equal((await call('POST', '/api/device/fcm', { token: ABUELO }, dev)).status, 200);
  assert.equal((await call('GET', `/api/seniors/${id}`, null, fam)).json.pushReady, true);

  // Sin WebSocket, un cambio de configuración y "¿dónde está?" lo despiertan por FCM
  const cfg = (await call('GET', `/api/seniors/${id}`, null, fam)).json.config;
  await call('PUT', `/api/seniors/${id}/config`, { ...cfg, ui: { fontScale: 1.2 } }, fam);
  await tick();
  assert.equal(to(ABUELO).at(-1).data.type, 'config');
  assert.equal(to(ABUELO).at(-1).android.priority, 'high');
  const loc = (await call('POST', `/api/seniors/${id}/locate`, null, fam)).json;
  assert.equal(loc.live, false);
  assert.equal(loc.pushed, true);
  assert.equal(to(ABUELO).at(-1).data.type, 'locate');

  // Un SOS llega a la familia por el canal "sos"; el token caducado se borra y no se vuelve a usar
  await call('POST', '/api/device/alert', { type: 'sos', lat: 41.1, lng: 1.2 }, dev);
  await tick();
  const sos = to(FAMILIA).at(-1);
  assert.match(sos.notification.title, /SOS/);
  assert.equal(sos.android.notification.channel_id, 'sos');
  assert.equal(sos.data.type, 'sos');
  assert.equal(sos.data.seniorId, String(id));
  await call('POST', '/api/device/alert', { type: 'sos' }, dev);
  await tick();
  assert.equal(to(CADUCADO).length, 1);
  assert.equal(to(FAMILIA).length, 2);

  // "Contestar sola": nunca a la primera llamada; y la familia se entera cuando pasa
  const calls = async (autoAnswerOnCall) => {
    const c = (await call('GET', `/api/seniors/${id}`, null, fam)).json.config;
    await call('PUT', `/api/seniors/${id}/config`, { ...c, calls: { ...c.calls, autoAnswerOnCall } }, fam);
    return (await call('GET', '/api/device/config', null, dev)).json.config.calls.autoAnswerOnCall;
  };
  assert.equal(await calls(1), 2);
  assert.equal(await calls(3), 3);
  assert.equal(await calls(99), 10);
  assert.equal(await calls(0), 0);
  await call('POST', '/api/device/alert', { type: 'auto_answer', contactName: 'Marta' }, dev);
  await tick();
  assert.match(to(FAMILIA).at(-1).notification.body, /Marta se ha contestado sola en manos libres/);
  assert.equal(to(FAMILIA).at(-1).android.notification.channel_id, 'avisos');

  // Timbres: lista, sonidos, ajuste en la configuración y "hacerlo sonar en su móvil"
  const tones = (await call('GET', '/api/ringtones')).json;
  assert.ok(tones.some((r) => r.id === 'campana') && tones[0].id === 'system');
  const wav = await app.inject({ method: 'GET', url: '/ringtones/campana.wav' });
  assert.equal(wav.statusCode, 200);
  assert.equal(wav.headers['content-type'], 'audio/wav');
  assert.equal(wav.rawPayload.subarray(0, 4).toString(), 'RIFF');
  assert.equal((await app.inject({ method: 'GET', url: '/ringtones/system.wav' })).statusCode, 404, '"system" no es un sonido');
  const ring = async (calls) => {
    const c = (await call('GET', `/api/seniors/${id}`, null, fam)).json.config;
    await call('PUT', `/api/seniors/${id}/config`, { ...c, calls: { ...c.calls, ...calls } }, fam);
    return (await call('GET', '/api/device/config', null, dev)).json.config.calls;
  };
  assert.equal((await ring({ ringtone: 'inventado' })).ringtone, 'system');
  const rc = await ring({ ringtone: 'grave', ringMax: true });
  assert.equal(rc.ringtone, 'grave');
  assert.equal(rc.ringMax, true);
  const test = (await call('POST', `/api/seniors/${id}/ring-test`, null, fam)).json;
  assert.equal(test.pushed, true);
  assert.deepEqual([to(ABUELO).at(-1).data.type, to(ABUELO).at(-1).data.ringtone], ['ring', 'grave']);
  await call('POST', '/api/device/heartbeat', { battery: 70, ringtoneAllowed: false }, dev);
  assert.equal((await call('GET', `/api/seniors/${id}`, null, fam)).json.status.ringtoneAllowed, false);

  // Favoritos desde el móvil: sin duplicados aunque cambie el formato del número
  const fav = (body) => call('POST', '/api/device/favorites', body, dev);
  const devContacts = async () => (await call('GET', '/api/device/config', null, dev)).json.config.contacts;
  const pushesBefore = to(ABUELO).length;
  assert.equal((await fav({ name: 'Pepe', phone: '+34 611 22 33 44' })).status, 200);
  assert.equal((await fav({ name: 'Pepe otra vez', phone: '611223344' })).status, 200);
  assert.deepEqual((await devContacts()).map((c) => [c.name, c.phone]), [['Pepe', '+34611223344']]);
  assert.equal((await call('GET', `/api/seniors/${id}`, null, fam)).json.config.contacts.length, 1, 'la familia lo ve');
  assert.equal(to(ABUELO).length, pushesBefore, 'no se despierta al móvil que ha hecho el cambio');
  assert.equal((await fav({ phone: '611 22 33 44', favorite: false })).status, 200);
  assert.equal((await devContacts()).length, 0);
  assert.equal((await fav({ phone: '12' })).status, 400);
  for (let i = 0; i < 12; i++) await fav({ name: `C${i}`, phone: `6000000${String(i).padStart(2, '0')}` });
  const full = await fav({ name: 'Uno más', phone: '699999999' });
  assert.equal(full.status, 400);
  assert.match(full.json.error, /12 favoritos/);

  // Al cerrar sesión con su token, ese móvil deja de recibir avisos
  const beforeLogout = to(FAMILIA).length;
  await call('POST', '/api/auth/logout', { fcmToken: FAMILIA }, fam);
  assert.equal((await call('GET', '/api/me', null, fam)).status, 401);
  await call('POST', '/api/device/alert', { type: 'sos' }, dev);
  await tick();
  assert.equal(to(FAMILIA).length, beforeLogout);
});
