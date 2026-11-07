import { createGoogleAuth } from './google.js';

/**
 * Comprobar las suscripciones de Google Play (Google Play Developer API), sin dependencias.
 *
 * Usa la misma clave de cuenta de servicio que Firebase (src/fcm.js). Para que funcione, esa cuenta
 * de servicio tiene que estar invitada en Play Console (Usuarios y permisos) con permiso para ver
 * los datos financieros y gestionar pedidos y suscripciones, y en su proyecto de Google Cloud
 * tiene que estar activada la "Google Play Android Developer API".
 */
const SCOPE = 'https://www.googleapis.com/auth/androidpublisher';
const API = 'https://androidpublisher.googleapis.com/androidpublisher/v3/applications';

export function createPlay({ credentials, packageName, log }) {
  if (!credentials) {
    log.info('Pagos de Google Play desactivados: falta la clave de la cuenta de servicio');
    return { enabled: false, getSubscription: async () => null, acknowledge: async () => false };
  }
  const accessToken = createGoogleAuth(credentials);
  const pkg = encodeURIComponent(packageName);

  /**
   * La suscripción de un token de compra (formato SubscriptionPurchaseV2) o null si Google no la conoce.
   * Lanza Error si no se ha podido preguntar (sin conexión, permisos…), para no confundirlo con "no existe".
   */
  async function getSubscription(purchaseToken) {
    const res = await fetch(`${API}/${pkg}/purchases/subscriptionsv2/tokens/${encodeURIComponent(purchaseToken)}`, {
      headers: { authorization: `Bearer ${await accessToken(SCOPE)}` },
    });
    if (res.status === 404 || res.status === 410) return null;
    if (res.status === 400) {
      const text = await res.text();
      if (/invalid/i.test(text)) return null;
      throw new Error(`Google Play 400: ${text.slice(0, 200)}`);
    }
    if (!res.ok) throw new Error(`Google Play ${res.status}: ${(await res.text()).slice(0, 200)}`);
    return res.json();
  }

  /** Confirma la compra a Google. Si no se confirma en 3 días, Google la devuelve sola. */
  async function acknowledge(productId, purchaseToken) {
    const url = `${API}/${pkg}/purchases/subscriptions/${encodeURIComponent(productId)}/tokens/${encodeURIComponent(purchaseToken)}:acknowledge`;
    const res = await fetch(url, {
      method: 'POST',
      headers: { authorization: `Bearer ${await accessToken(SCOPE)}`, 'content-type': 'application/json' },
      body: '{}',
    });
    if (!res.ok) log.warn({ status: res.status, body: (await res.text()).slice(0, 200) }, 'Google Play: no se ha podido confirmar la compra');
    return res.ok;
  }

  return { enabled: true, getSubscription, acknowledge };
}

/** Estados en los que la suscripción da acceso (cancelada = ya no se renovará, pero está pagada hasta que caduque). */
const ENTITLED = new Set(['SUBSCRIPTION_STATE_ACTIVE', 'SUBSCRIPTION_STATE_IN_GRACE_PERIOD', 'SUBSCRIPTION_STATE_CANCELED']);

/** Lo que nos importa de la respuesta de Google: producto, hasta cuándo, si da acceso y si se renovará. */
export function summarizeSubscription(sub) {
  const items = Array.isArray(sub?.lineItems) ? sub.lineItems : [];
  // Con varias líneas (cambio de plan), manda la que caduca más tarde
  const item = items.reduce((best, it) => (!best || Date.parse(it.expiryTime) > Date.parse(best.expiryTime) ? it : best), null);
  const expiresAt = item?.expiryTime ? Date.parse(item.expiryTime) : null;
  const state = String(sub?.subscriptionState ?? 'SUBSCRIPTION_STATE_UNSPECIFIED');
  return {
    productId: item?.productId ?? null,
    basePlanId: item?.offerDetails?.basePlanId ?? null,
    state,
    expiresAt: Number.isFinite(expiresAt) ? expiresAt : null,
    entitled: ENTITLED.has(state) && Number.isFinite(expiresAt) && expiresAt > Date.now(),
    autoRenew: item?.autoRenewingPlan?.autoRenewEnabled === true,
    acknowledged: sub?.acknowledgementState === 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
    accountRef: sub?.externalAccountIdentifiers?.obfuscatedExternalAccountId ?? null,
    linkedToken: sub?.linkedPurchaseToken ?? null,
    test: Boolean(sub?.testPurchase),
  };
}
