import crypto from 'node:crypto';

export const now = () => Date.now();
export const sha256 = (s) => crypto.createHash('sha256').update(s).digest('hex');
export const randomToken = (bytes = 32) => crypto.randomBytes(bytes).toString('base64url');

export function hashPassword(password) {
  const salt = crypto.randomBytes(16);
  const hash = crypto.scryptSync(password, salt, 64);
  return `scrypt$${salt.toString('base64')}$${hash.toString('base64')}`;
}

export function verifyPassword(password, stored) {
  const [alg, saltB64, hashB64] = String(stored).split('$');
  if (alg !== 'scrypt') return false;
  const expected = Buffer.from(hashB64, 'base64');
  const actual = crypto.scryptSync(password, Buffer.from(saltB64, 'base64'), expected.length);
  return crypto.timingSafeEqual(expected, actual);
}

export function parseCookies(header) {
  const out = {};
  for (const part of String(header ?? '').split(';')) {
    const i = part.indexOf('=');
    if (i < 0) continue;
    out[part.slice(0, i).trim()] = decodeURIComponent(part.slice(i + 1).trim());
  }
  return out;
}

export function haversineMeters(lat1, lng1, lat2, lng2) {
  const R = 6371000;
  const toRad = (d) => (d * Math.PI) / 180;
  const dLat = toRad(lat2 - lat1);
  const dLng = toRad(lng2 - lng1);
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(a));
}

const clamp = (n, min, max, fallback) => {
  const v = Number(n);
  return Number.isFinite(v) ? Math.min(max, Math.max(min, v)) : fallback;
};
// Enteros: la app Android los deserializa como Int
const clampInt = (n, min, max, fallback) => Math.round(clamp(n, min, max, fallback));
const str = (s, max = 200) => String(s ?? '').trim().slice(0, max);

/** Normaliza y valida la configuración que llega del panel. Lanza Error con mensaje legible. */
export function sanitizeConfig(input, defaults) {
  const c = input ?? {};
  const contacts = (Array.isArray(c.contacts) ? c.contacts : []).slice(0, 12).map((x) => {
    const phone = str(x.phone, 30).replace(/[^\d+*#]/g, '');
    if (!phone) throw new Error(`El contacto "${str(x.name, 40)}" no tiene teléfono`);
    return {
      id: str(x.id, 40) || randomToken(6),
      name: str(x.name, 40) || phone,
      phone,
      photo: x.photo && /^\/uploads\/[\w.-]+$/.test(x.photo) ? x.photo : null,
      whatsapp: Boolean(x.whatsapp),
    };
  });

  const zones = (Array.isArray(c.location?.zones) ? c.location.zones : []).slice(0, 10).map((z) => {
    const lat = Number(z.lat), lng = Number(z.lng);
    if (!Number.isFinite(lat) || !Number.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180) {
      throw new Error('Zona con coordenadas no válidas');
    }
    return {
      id: str(z.id, 40) || randomToken(6),
      name: str(z.name, 40) || 'Zona',
      lat, lng,
      radius: clampInt(z.radius, 100, 20000, 300), // Android recomienda >= 100 m para geovallas fiables
    };
  });

  const mode = ['off', 'continuous', 'interval', 'zone'].includes(c.location?.mode) ? c.location.mode : defaults.location.mode;
  if (mode === 'zone' && zones.length === 0) throw new Error('El modo "salir de zona" necesita al menos una zona');

  const filter = ['all', 'silence_unknown', 'block_unknown'].includes(c.calls?.filter) ? c.calls.filter : defaults.calls.filter;
  const sosContactId = contacts.some((x) => x.id === c.sosContactId) ? c.sosContactId : (contacts[0]?.id ?? null);

  return {
    contacts,
    sosContactId,
    calls: { filter, allowRepeated: c.calls?.allowRepeated !== false },
    location: {
      mode,
      continuousSeconds: clampInt(c.location?.continuousSeconds, 10, 300, defaults.location.continuousSeconds),
      intervalMinutes: clampInt(c.location?.intervalMinutes, 1, 240, defaults.location.intervalMinutes),
      outsideZoneSeconds: clampInt(c.location?.outsideZoneSeconds, 15, 600, defaults.location.outsideZoneSeconds),
      zones,
      retentionDays: clampInt(c.location?.retentionDays, 1, 90, defaults.location.retentionDays),
    },
    apps: (Array.isArray(c.apps) ? c.apps : []).filter((p) => /^[\w.]{3,150}$/.test(p)).slice(0, 12),
    ui: {
      fontScale: clamp(c.ui?.fontScale, 0.8, 1.6, 1.0),
      highContrast: Boolean(c.ui?.highContrast),
      showDate: c.ui?.showDate !== false,
    },
    pinHash: typeof c.pinHash === 'string' && /^[a-f0-9]{64}$/.test(c.pinHash) ? c.pinHash : null,
    alerts: {
      lowBatteryPercent: clampInt(c.alerts?.lowBatteryPercent, 5, 50, defaults.alerts.lowBatteryPercent),
      offlineHours: clampInt(c.alerts?.offlineHours, 1, 72, defaults.alerts.offlineHours),
    },
  };
}
