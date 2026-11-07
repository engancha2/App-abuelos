import { test } from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import { createPlay, summarizeSubscription } from '../src/play.js';

const silent = { info() {}, warn() {} };

test('Google Play: pregunta por la suscripción y la confirma con el permiso de androidpublisher', async (t) => {
  const { privateKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
  const credentials = {
    project_id: 'cerca-test',
    client_email: 'cerca@cerca-test.iam.gserviceaccount.com',
    private_key: privateKey.export({ type: 'pkcs8', format: 'pem' }),
  };
  const calls = [];
  const realFetch = globalThis.fetch;
  t.after(() => { globalThis.fetch = realFetch; });
  globalThis.fetch = async (url, opts = {}) => {
    calls.push({ url, opts });
    if (url.startsWith('https://oauth2.googleapis.com/')) {
      const payload = JSON.parse(Buffer.from(new URLSearchParams(opts.body).get('assertion').split('.')[1], 'base64url'));
      assert.equal(payload.scope, 'https://www.googleapis.com/auth/androidpublisher');
      return new Response(JSON.stringify({ access_token: 'acceso', expires_in: 3600 }));
    }
    assert.equal(opts.headers.authorization, 'Bearer acceso');
    if (url.includes('/tokens/no-existe')) return new Response('{}', { status: 404 });
    if (url.includes('/tokens/falla')) return new Response('caído', { status: 500 });
    if (url.endsWith(':acknowledge')) return new Response('{}');
    return new Response(JSON.stringify({ subscriptionState: 'SUBSCRIPTION_STATE_ACTIVE' }));
  };

  const play = createPlay({ credentials, packageName: 'com.estudiofenix.cerca.familia', log: silent });
  assert.equal(play.enabled, true);
  assert.deepEqual(await play.getSubscription('abc'), { subscriptionState: 'SUBSCRIPTION_STATE_ACTIVE' });
  assert.equal(calls[1].url,
    'https://androidpublisher.googleapis.com/androidpublisher/v3/applications/com.estudiofenix.cerca.familia/purchases/subscriptionsv2/tokens/abc');
  assert.equal(await play.getSubscription('no-existe'), null);
  await assert.rejects(play.getSubscription('falla'), /Google Play 500/, 'un fallo no se confunde con "no existe"');
  assert.equal(await play.acknowledge('premium', 'abc'), true);
  assert.equal(calls.at(-1).url,
    'https://androidpublisher.googleapis.com/androidpublisher/v3/applications/com.estudiofenix.cerca.familia/purchases/subscriptions/premium/tokens/abc:acknowledge');
  assert.equal(calls.filter((c) => c.url.startsWith('https://oauth2')).length, 1, 'reutiliza el acceso');

  assert.equal(createPlay({ credentials: null, packageName: 'x', log: silent }).enabled, false);
});

test('Google Play: qué cuenta como suscripción pagada', () => {
  const later = new Date(Date.now() + 86400_000).toISOString();
  const earlier = new Date(Date.now() - 86400_000).toISOString();
  const sub = (state, expiryTime) => ({ subscriptionState: state, lineItems: [{ productId: 'premium', expiryTime }] });
  assert.equal(summarizeSubscription(sub('SUBSCRIPTION_STATE_ACTIVE', later)).entitled, true);
  assert.equal(summarizeSubscription(sub('SUBSCRIPTION_STATE_CANCELED', later)).entitled, true, 'cancelada pero pagada hasta que caduque');
  assert.equal(summarizeSubscription(sub('SUBSCRIPTION_STATE_IN_GRACE_PERIOD', later)).entitled, true);
  assert.equal(summarizeSubscription(sub('SUBSCRIPTION_STATE_ON_HOLD', later)).entitled, false);
  assert.equal(summarizeSubscription(sub('SUBSCRIPTION_STATE_PENDING', later)).entitled, false);
  assert.equal(summarizeSubscription(sub('SUBSCRIPTION_STATE_ACTIVE', earlier)).entitled, false);
  assert.equal(summarizeSubscription({}).entitled, false);
});
