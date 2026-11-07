import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { buildServer } from '../src/server.js';
import { accountRef } from '../src/billing.js';

const DAY = 86400_000;
const silentFcm = { enabled: false, send: async () => 'disabled' };

/** Google Play de mentira: las compras que "existen" y las confirmaciones que se le piden. */
function fakePlay() {
  const purchases = new Map();
  const acks = [];
  return {
    enabled: true,
    purchases,
    acks,
    getSubscription: async (token) => purchases.get(token) ?? null,
    acknowledge: async (productId, token) => { acks.push({ productId, token }); return true; },
  };
}

const purchase = ({ userId, productId = 'premium', state = 'SUBSCRIPTION_STATE_ACTIVE', days = 30, acked = false, linked } = {}) => ({
  subscriptionState: state,
  acknowledgementState: acked ? 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED' : 'ACKNOWLEDGEMENT_STATE_PENDING',
  lineItems: [{
    productId,
    expiryTime: new Date(Date.now() + days * DAY).toISOString(),
    autoRenewingPlan: { autoRenewEnabled: state === 'SUBSCRIPTION_STATE_ACTIVE' },
    offerDetails: { basePlanId: 'mensual' },
  }],
  externalAccountIdentifiers: userId ? { obfuscatedExternalAccountId: accountRef(userId), obfuscatedExternalProfileId: '1' } : undefined,
  ...(linked ? { linkedPurchaseToken: linked } : {}),
});

async function setup({ play = fakePlay(), billing = true } = {}) {
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'cerca-billing-'));
  const app = await buildServer({ dataDir, logger: false, fcm: silentFcm, play, billing });
  const db = new DatabaseSync(path.join(dataDir, 'cerca.db'));
  const session = async (email) => {
    const res = await app.inject({ method: 'POST', url: '/api/auth/register', payload: { email, name: email, password: 'contraseña-larga', client: 'app' } });
    const { token } = JSON.parse(res.body);
    const me = JSON.parse((await app.inject({ method: 'GET', url: '/api/me', headers: { authorization: `Bearer ${token}` } })).body);
    const call = async (method, url, body) => {
      const r = await app.inject({ method, url, payload: body, headers: { authorization: `Bearer ${token}` } });
      return { status: r.statusCode, json: r.body ? JSON.parse(r.body) : null };
    };
    return { call, userId: me.id };
  };
  const device = (token) => async (method, url, body) => {
    const r = await app.inject({ method, url, payload: body, headers: { authorization: `Bearer ${token}` } });
    return { status: r.statusCode, json: r.body ? JSON.parse(r.body) : null };
  };
  /** Crea una persona, la configura con todo lo de pago y empareja su móvil. */
  const newSenior = async (fam, name, deviceId = `android-${name}`) => {
    const { id } = (await fam.call('POST', '/api/seniors', { name })).json;
    await fam.call('PUT', `/api/seniors/${id}/config`, {
      contacts: [{ id: 'c1', name: 'Xavi', phone: '600112233' }],
      calls: { autoAnswerOnCall: 3, ringtone: 'campana', ringMax: true },
      location: { mode: 'zone', zones: [{ name: 'Casa', lat: 41.1, lng: 1.2, radius: 300 }] },
    });
    const { code } = (await fam.call('POST', `/api/seniors/${id}/pair-code`)).json;
    const pair = await app.inject({ method: 'POST', url: '/api/device/pair', payload: { code, device: { model: 'Redmi', id: deviceId } } });
    return { id, dev: device(JSON.parse(pair.body).token) };
  };
  return { app, db, play, session, newSenior };
}

test('mes gratis: empieza al emparejar, avisa antes de acabar y al acabar quita lo de pago (sin romper su móvil)', async (t) => {
  const { app, db, session, newSenior } = await setup();
  t.after(() => app.close());
  const fam = await session('xavi@test.com');

  // Antes de emparejar: mes gratis sin empezar
  const { id: lonely } = (await fam.call('POST', '/api/seniors', { name: 'Sin móvil' })).json;
  const before = (await fam.call('GET', `/api/seniors/${lonely}`)).json.plan;
  assert.equal(before.status, 'trial');
  assert.equal(before.trialEndsAt, null);
  assert.equal(before.trialDaysLeft, 30);

  const { id, dev } = await newSenior(fam, 'Rosa');
  let detail = (await fam.call('GET', `/api/seniors/${id}`)).json;
  assert.equal(detail.plan.status, 'trial');
  assert.equal(detail.plan.trialDaysLeft, 30);
  assert.equal(detail.billingRef, accountRef(fam.userId));
  let cfg = (await dev('GET', '/api/device/config')).json;
  assert.equal(cfg.config.location.mode, 'zone', 'durante el mes gratis tiene todo');
  assert.equal(cfg.config.calls.autoAnswerOnCall, 3);

  // Quedan 4 días: un aviso (y solo uno)
  db.prepare(`UPDATE seniors SET trial_started_at = ? WHERE id = ?`).run(Date.now() - 26 * DAY, id);
  app.jobs.checkPlans();
  app.jobs.checkPlans();
  let alerts = (await fam.call('GET', `/api/seniors/${id}/alerts`)).json;
  assert.deepEqual(alerts.map((a) => a.type), ['trial_ending']);
  assert.match(alerts[0].message, /Quedan 4 días/);

  // Se acaba el mes
  const version = cfg.version;
  db.prepare(`UPDATE seniors SET trial_started_at = ? WHERE id = ?`).run(Date.now() - 31 * DAY, id);
  db.prepare(`UPDATE trials SET started_at = ?`).run(Date.now() - 31 * DAY); // el de su móvil (el único emparejado)
  app.jobs.checkPlans();
  detail =(await fam.call('GET', `/api/seniors/${id}`)).json;
  assert.equal(detail.plan.status, 'free');
  assert.equal(detail.config.location.mode, 'zone', 'la configuración guardada no se pierde');
  assert.equal(detail.config.calls.ringtone, 'campana');
  alerts = (await fam.call('GET', `/api/seniors/${id}/alerts`)).json;
  assert.deepEqual(alerts.map((a) => a.type).sort(), ['trial_ended', 'trial_ending']);

  cfg = (await dev('GET', '/api/device/config')).json;
  assert.ok(cfg.version > version, 'su móvil recibe la configuración nueva');
  assert.equal(cfg.config.location.mode, 'off');
  assert.deepEqual(cfg.config.location.zones, []);
  assert.equal(cfg.config.calls.autoAnswerOnCall, 0);
  assert.equal(cfg.config.calls.ringtone, 'system');
  assert.equal(cfg.config.calls.ringMax, false);
  assert.equal(cfg.config.contacts.length, 1, 'contactos, llamadas y SOS siguen');
  const hb = (await dev('POST', '/api/device/heartbeat', { battery: 80, locationPermission: 'foreground' })).json;
  assert.equal(hb.configVersion, cfg.version);

  // Sin plan: no hay recorrido, pero sí la última posición
  await dev('POST', '/api/device/locations', [{ lat: 41.12, lng: 1.245, ts: Date.now() - 60_000 }, { lat: 41.13, lng: 1.246, ts: Date.now() }]);
  assert.deepEqual((await fam.call('GET', `/api/seniors/${id}/locations`)).json, []);
  assert.equal((await fam.call('GET', `/api/seniors/${id}`)).json.lastLocation.lat, 41.13);
  // "¿Dónde está ahora?" y el SOS siguen funcionando
  assert.equal((await fam.call('POST', `/api/seniors/${id}/locate`)).status, 200);
  assert.equal((await dev('POST', '/api/device/alert', { type: 'sos', lat: 41.1, lng: 1.2 })).status, 200);

  // Volver a emparejar el mismo móvil con otra persona no da otro mes gratis
  await fam.call('DELETE', `/api/seniors/${id}`);
  const again = await newSenior(fam, 'Rosa otra vez', 'android-Rosa');
  assert.equal((await fam.call('GET', `/api/seniors/${again.id}`)).json.plan.status, 'free');
  // Otro móvil, sí
  const other = await newSenior(fam, 'Pepe');
  assert.equal((await fam.call('GET', `/api/seniors/${other.id}`)).json.plan.status, 'trial');
});

test('suscripción: se comprueba con Google Play, se confirma y vuelve todo para toda la familia', async (t) => {
  const { app, db, play, session, newSenior } = await setup();
  t.after(() => app.close());
  const xavi = await session('xavi@test.com');
  const marta = await session('marta@test.com');
  const { id, dev } = await newSenior(xavi, 'Rosa');
  await xavi.call('POST', `/api/seniors/${id}/members`, { email: 'marta@test.com' });
  db.prepare(`UPDATE seniors SET trial_started_at = ? WHERE id = ?`).run(Date.now() - 40 * DAY, id);
  app.jobs.checkPlans();
  assert.equal((await dev('GET', '/api/device/config')).json.config.location.mode, 'off');

  const token = 'token-de-compra-de-marta-1234567890';
  // Errores antes de comprar bien
  assert.equal((await marta.call('POST', `/api/seniors/${id}/subscription`, { purchaseToken: 'corto' })).status, 400);
  assert.equal((await marta.call('POST', `/api/seniors/${id}/subscription`, { purchaseToken: token })).status, 400, 'Google no la conoce');
  play.purchases.set(token, purchase({ userId: marta.userId, productId: 'otra-cosa' }));
  assert.equal((await marta.call('POST', `/api/seniors/${id}/subscription`, { purchaseToken: token })).status, 400, 'otro producto');
  play.purchases.set(token, purchase({ userId: xavi.userId }));
  assert.equal((await marta.call('POST', `/api/seniors/${id}/subscription`, { purchaseToken: token })).status, 403, 'la compró otra cuenta');

  // Paga Marta (que no creó a la persona): vale para todos
  play.purchases.set(token, purchase({ userId: marta.userId }));
  const ok = await marta.call('POST', `/api/seniors/${id}/subscription`, { purchaseToken: token });
  assert.equal(ok.status, 200);
  assert.equal(ok.json.plan.status, 'premium');
  assert.equal(ok.json.plan.autoRenew, true);
  assert.deepEqual(play.acks, [{ productId: 'premium', token }], 'confirmada a Google');
  assert.equal((await xavi.call('GET', `/api/seniors/${id}`)).json.plan.status, 'premium');
  const cfg = (await dev('GET', '/api/device/config')).json.config;
  assert.equal(cfg.location.mode, 'zone', 'vuelve lo que tenía');
  assert.equal(cfg.calls.ringtone, 'campana');

  // Mandarla otra vez (al abrir la app) no la confirma dos veces
  play.purchases.set(token, purchase({ userId: marta.userId, acked: true }));
  assert.equal((await marta.call('POST', `/api/seniors/${id}/subscription`, { purchaseToken: token })).status, 200);
  assert.equal(play.acks.length, 1);

  // La misma compra no vale para otra persona
  const pepe = await newSenior(marta, 'Pepe');
  assert.equal((await marta.call('POST', `/api/seniors/${pepe.id}/subscription`, { purchaseToken: token })).status, 409);

  // Caduca sin renovarse: se vuelve a preguntar a Google y se acaba el plan
  db.prepare(`UPDATE subscriptions SET expires_at = ?, updated_at = 0 WHERE purchase_token = ?`).run(Date.now() + 60_000, token);
  play.purchases.set(token, purchase({ userId: marta.userId, state: 'SUBSCRIPTION_STATE_EXPIRED', days: -1, acked: true }));
  await app.jobs.refreshSubscriptions();
  assert.equal((await xavi.call('GET', `/api/seniors/${id}`)).json.plan.status, 'free');
  assert.equal((await dev('GET', '/api/device/config')).json.config.location.mode, 'off');
  const alerts = (await xavi.call('GET', `/api/seniors/${id}/alerts`)).json.map((a) => a.type);
  assert.ok(!alerts.includes('trial_ended') || alerts.filter((a) => a === 'trial_ended').length === 1);

  // Se vuelve a suscribir (token nuevo enlazado al anterior)
  const token2 = 'token-nuevo-de-marta-0987654321';
  play.purchases.set(token2, purchase({ userId: marta.userId, linked: token }));
  assert.equal((await marta.call('POST', `/api/seniors/${id}/subscription`, { purchaseToken: token2 })).json.plan.status, 'premium');

  // Si se borra a la persona, la suscripción queda suelta y se puede pasar a otra
  await xavi.call('DELETE', `/api/seniors/${id}`);
  const moved = await marta.call('POST', `/api/seniors/${pepe.id}/subscription`, { purchaseToken: token2 });
  assert.equal(moved.status, 200);
  assert.equal(moved.json.plan.status, 'premium');
});

test('pagos: pendiente de pago, Google Play sin configurar y todo gratis con BILLING=off', async (t) => {
  {
    const { app, play, session, newSenior } = await setup();
    t.after(() => app.close());
    const fam = await session('xavi@test.com');
    const { id } = await newSenior(fam, 'Rosa');
    play.purchases.set('token-pendiente-1234567890', purchase({ userId: fam.userId, state: 'SUBSCRIPTION_STATE_PENDING' }));
    const r = await fam.call('POST', `/api/seniors/${id}/subscription`, { purchaseToken: 'token-pendiente-1234567890' });
    assert.equal(r.json.pending, true);
    assert.equal(r.json.plan.status, 'trial', 'hasta que se pague, sigue como estaba');
    assert.equal(play.acks.length, 0, 'no se confirma lo que aún no está pagado');
  }
  {
    const { app, session, newSenior } = await setup({ play: { enabled: false, getSubscription: async () => null, acknowledge: async () => false } });
    t.after(() => app.close());
    const fam = await session('xavi@test.com');
    const { id } = await newSenior(fam, 'Rosa');
    assert.equal((await fam.call('POST', `/api/seniors/${id}/subscription`, { purchaseToken: 'token-cualquiera-1234567890' })).status, 503);
  }
  {
    const { app, db, session, newSenior } = await setup({ billing: false });
    t.after(() => app.close());
    const fam = await session('xavi@test.com');
    const { id, dev } = await newSenior(fam, 'Rosa');
    db.prepare(`UPDATE seniors SET trial_started_at = ? WHERE id = ?`).run(Date.now() - 400 * DAY, id);
    app.jobs.checkPlans();
    const plan = (await fam.call('GET', `/api/seniors/${id}`)).json.plan;
    assert.equal(plan.status, 'premium');
    assert.equal(plan.billingEnabled, false);
    assert.equal((await dev('GET', '/api/device/config')).json.config.location.mode, 'zone');
    assert.equal((await fam.call('GET', `/api/seniors/${id}/alerts`)).json.length, 0);
    const meta = JSON.parse((await app.inject({ method: 'GET', url: '/api/meta' })).body);
    assert.equal(meta.billing.enabled, false);
  }
});
