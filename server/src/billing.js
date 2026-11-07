import { sha256 } from './util.js';

/**
 * El mes gratis y la suscripción.
 *
 * - El mes gratis empieza al emparejar su móvil por primera vez y no pide tarjeta. Va ligado al móvil
 *   (no a la cuenta): borrar a la persona y volver a darla de alta no da otro mes.
 * - Después, cualquiera de la familia puede suscribirse desde la app Android (Google Play) y las
 *   funciones de pago vuelven para toda la familia de esa persona, también en el panel web.
 * - Sin suscripción, su móvil sigue funcionando: llamadas, contactos, SOS y "¿dónde está ahora?".
 *   Lo de pago deja de llegarle a su móvil, pero la configuración se guarda tal cual: al suscribirse
 *   vuelve todo como estaba.
 */
export const TRIAL_DAYS = 30;
export const REMIND_DAYS_BEFORE = 5;
const DAY = 86400_000;

/** Lo que se bloquea sin suscripción (para enseñarlo en las apps y el panel). */
export const PREMIUM_FEATURES = [
  { id: 'zones', title: 'Zonas seguras', description: 'Aviso al momento si sale de casa o del barrio.' },
  { id: 'history', title: 'Historial de recorridos', description: 'Por dónde ha ido las últimas horas o días.' },
  { id: 'autoAnswer', title: 'Contestar sola', description: 'Si no llega al teléfono, la llamada se contesta en manos libres.' },
  { id: 'ringtones', title: 'Timbres fuertes', description: 'Timbres que se oyen bien y siempre a todo volumen.' },
];

/** Cómo identifica Google Play a quien compra (obfuscatedAccountId): sin datos personales, máx. 64 caracteres. */
export const accountRef = (userId) => sha256(`grandpa-user:${userId}`);

/** El móvil del mayor para el mes gratis (ANDROID_ID), sin guardar el identificador en sí. */
export const deviceRef = (deviceId) => sha256(`grandpa-device:${deviceId}`);

/**
 * El plan de una persona.
 * - trial: mes gratis (trialEndsAt null = aún no ha empezado: empieza al emparejar su móvil)
 * - premium: con suscripción pagada (premiumUntil = hasta cuándo, autoRenew = si se renovará)
 * - free: se acabó el mes gratis y no hay suscripción
 */
export function planOf(senior, subscription, { enabled = true, now = Date.now() } = {}) {
  if (!enabled) return { status: 'premium', premium: true, billingEnabled: false };
  const trialEndsAt = senior.trial_started_at ? senior.trial_started_at + TRIAL_DAYS * DAY : null;
  if (subscription && subscription.expires_at > now && subscription.entitled) {
    return {
      status: 'premium', premium: true, billingEnabled: true, trialEndsAt,
      premiumUntil: subscription.expires_at, autoRenew: subscription.auto_renew === 1,
    };
  }
  if (!trialEndsAt || trialEndsAt > now) {
    return {
      status: 'trial', premium: true, billingEnabled: true, trialEndsAt,
      trialDaysLeft: trialEndsAt ? Math.max(1, Math.ceil((trialEndsAt - now) / DAY)) : TRIAL_DAYS,
    };
  }
  return { status: 'free', premium: false, billingEnabled: true, trialEndsAt };
}

/** La configuración que se manda a su móvil: sin lo de pago si no tiene plan. */
export function effectiveConfig(cfg, premium) {
  if (premium) return cfg;
  const out = structuredClone(cfg);
  out.location.zones = [];
  // "Solo si sale de una zona" sin zonas no tiene sentido: deja de enviar ubicación sola
  // ("¿dónde está ahora?" y la posición del SOS siguen funcionando)
  if (out.location.mode === 'zone') out.location.mode = 'off';
  out.calls.autoAnswerOnCall = 0;
  out.calls.ringtone = 'system';
  out.calls.ringMax = false;
  return out;
}
