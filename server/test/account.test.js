import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { buildServer } from '../src/server.js';

const silentFcm = { enabled: false, send: async () => 'disabled' };

async function setup() {
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'cerca-account-'));
  const app = await buildServer({ dataDir, logger: false, fcm: silentFcm });
  const inject = async (method, url, body, headers = {}) => {
    const r = await app.inject({ method, url, payload: body, headers });
    let json = null;
    try { json = JSON.parse(r.body); } catch { /* html */ }
    return { status: r.statusCode, json, body: r.body, headers: r.headers };
  };
  const session = async (email) => {
    const { json } = await inject('POST', '/api/auth/register', { email, name: email, password: 'contraseña-larga', client: 'app' });
    const auth = { authorization: `Bearer ${json.token}` };
    return (method, url, body) => inject(method, url, body, auth);
  };
  return { app, dataDir, inject, session };
}

test('páginas públicas: presentación en /, panel en /panel, privacidad y borrar la cuenta', async (t) => {
  const { app, inject } = await setup();
  t.after(() => app.close());
  const home = await inject('GET', '/');
  assert.equal(home.status, 200);
  assert.match(home.headers['content-type'], /text\/html/);
  assert.match(home.body, /1 mes gratis/);
  assert.match(home.body, /details\?id=com\.estudiofenix\.cerca"/, 'enlace a la app del mayor en Google Play');
  assert.match(home.body, /\[falta LEGAL_NAME\]/, 'sin configurar, se ve lo que falta');
  const panel = await inject('GET', '/panel');
  assert.equal(panel.status, 200);
  assert.match(panel.body, /<script type="module" src="\/app.js">/);
  assert.equal((await inject('GET', '/privacidad')).status, 200);
  assert.equal((await inject('GET', '/borrar-cuenta')).status, 200);
  assert.equal((await inject('GET', '/site.css')).status, 200);
});

test('borrar la cuenta: se borra lo suyo, lo compartido pasa a la familia y se puede hacer desde la web', async (t) => {
  const { app, dataDir, inject, session } = await setup();
  t.after(() => app.close());
  const xavi = await session('xavi@test.com');
  const marta = await session('marta@test.com');

  // Rosa solo la tiene Xavi; Pepe lo comparten Xavi (lo creó) y Marta
  const rosa = (await xavi('POST', '/api/seniors', { name: 'Rosa' })).json.id;
  const pepe = (await xavi('POST', '/api/seniors', { name: 'Pepe' })).json.id;
  await xavi('POST', `/api/seniors/${pepe}/members`, { email: 'marta@test.com' });
  const { code } = (await xavi('POST', `/api/seniors/${rosa}/pair-code`)).json;
  const device = (await inject('POST', '/api/device/pair', { code, device: { model: 'Redmi' } })).json.token;
  fs.writeFileSync(path.join(dataDir, 'uploads', `${rosa}-foto.jpg`), 'jpg');
  fs.writeFileSync(path.join(dataDir, 'uploads', `${pepe}-foto.jpg`), 'jpg');

  assert.equal((await xavi('POST', '/api/me/delete', { password: 'otra' })).status, 403, 'contraseña mal: no se borra y no se cierra la sesión');
  const done = await xavi('POST', '/api/me/delete', { password: 'contraseña-larga' });
  assert.equal(done.status, 200);
  assert.equal(done.json.removedSeniors, 1);
  assert.equal(done.json.cancelSubscription, false);

  assert.equal((await xavi('GET', '/api/me')).status, 401, 'la sesión ya no vale');
  assert.equal((await inject('POST', '/api/auth/login', { email: 'xavi@test.com', password: 'contraseña-larga' })).status, 401);
  assert.equal((await inject('GET', '/api/device/config', null, { authorization: `Bearer ${device}` })).status, 401, 'el móvil de Rosa se desconecta');
  assert.ok(!fs.existsSync(path.join(dataDir, 'uploads', `${rosa}-foto.jpg`)), 'las fotos de Rosa se borran');
  assert.ok(fs.existsSync(path.join(dataDir, 'uploads', `${pepe}-foto.jpg`)));
  const seniors = (await marta('GET', '/api/seniors')).json;
  assert.deepEqual(seniors.map((s) => [s.name, s.role]), [['Pepe', 'owner']], 'Pepe pasa a Marta');

  // Desde la web, sin sesión
  assert.equal((await inject('POST', '/api/account/delete', { email: 'marta@test.com', password: 'mal' })).status, 403);
  const web = await inject('POST', '/api/account/delete', { email: 'marta@test.com', password: 'contraseña-larga' });
  assert.equal(web.status, 200);
  assert.equal(web.json.removedSeniors, 1);
  assert.equal((await marta('GET', '/api/me')).status, 401);
});
