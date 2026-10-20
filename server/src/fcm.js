import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

/**
 * Notificaciones con Firebase Cloud Messaging (API HTTP v1), sin dependencias.
 * Necesita la clave JSON de una cuenta de servicio del proyecto de Firebase; sin ella no envía nada
 * y el resto del servidor funciona igual (los móviles se enteran por WebSocket o en el siguiente latido).
 */
export function createFcm({ credentials, log }) {
  if (!credentials) {
    log.info('FCM desactivado: falta la clave de la cuenta de servicio de Firebase');
    return { enabled: false, send: async () => 'disabled' };
  }
  const { project_id: projectId, client_email: clientEmail, private_key: privateKey } = credentials;
  let cached = null; // { token, exp }

  async function accessToken() {
    if (cached && cached.exp > Date.now() + 60_000) return cached.token;
    const iat = Math.floor(Date.now() / 1000);
    const b64 = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');
    const unsigned = `${b64({ alg: 'RS256', typ: 'JWT' })}.${b64({
      iss: clientEmail,
      scope: 'https://www.googleapis.com/auth/firebase.messaging',
      aud: 'https://oauth2.googleapis.com/token',
      iat,
      exp: iat + 3600,
    })}`;
    const signature = crypto.sign('RSA-SHA256', Buffer.from(unsigned), privateKey).toString('base64url');
    const res = await fetch('https://oauth2.googleapis.com/token', {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: `${unsigned}.${signature}` }),
    });
    if (!res.ok) throw new Error(`OAuth ${res.status}: ${(await res.text()).slice(0, 200)}`);
    const data = await res.json();
    cached = { token: data.access_token, exp: Date.now() + data.expires_in * 1000 };
    return cached.token;
  }

  /** Envía un mensaje FCM. Devuelve 'ok', 'invalid' (el token ya no sirve: hay que borrarlo) o 'error'. */
  async function send(message) {
    try {
      const res = await fetch(`https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`, {
        method: 'POST',
        headers: { authorization: `Bearer ${await accessToken()}`, 'content-type': 'application/json' },
        body: JSON.stringify({ message }),
      });
      if (res.ok) return 'ok';
      const text = await res.text();
      if (res.status === 404 || /UNREGISTERED/.test(text) || (res.status === 400 && /registration token/i.test(text))) return 'invalid';
      log.warn({ status: res.status, body: text.slice(0, 300) }, 'FCM: envío fallido');
      return 'error';
    } catch (err) {
      log.warn({ err: err.message }, 'FCM: envío fallido');
      return 'error';
    }
  }

  return { enabled: true, send };
}

/**
 * La clave se busca en FIREBASE_SERVICE_ACCOUNT (el JSON tal cual, en base64 o la ruta a un archivo)
 * y, si no está, en `<DATA_DIR>/firebase-service-account.json` (en Dokploy: dentro del volumen /data).
 */
export function loadFcmCredentials(dataDir, value = process.env.FIREBASE_SERVICE_ACCOUNT) {
  const parse = (text) => {
    const c = JSON.parse(text);
    if (!c.project_id || !c.client_email || !c.private_key) throw new Error('faltan project_id, client_email o private_key');
    return c;
  };
  const v = value?.trim();
  try {
    if (v?.startsWith('{')) return parse(v);
    if (v && fs.existsSync(v)) return parse(fs.readFileSync(v, 'utf8'));
    if (v) return parse(Buffer.from(v, 'base64').toString('utf8'));
    const file = path.join(dataDir, 'firebase-service-account.json');
    if (fs.existsSync(file)) return parse(fs.readFileSync(file, 'utf8'));
  } catch (err) {
    throw new Error(`La clave de Firebase no es válida: ${err.message}`);
  }
  return null;
}
