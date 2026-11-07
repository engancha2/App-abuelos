import fs from 'node:fs';
import path from 'node:path';
import { createGoogleAuth } from './google.js';

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
  const { project_id: projectId } = credentials;
  const accessToken = createGoogleAuth(credentials);
  const SCOPE = 'https://www.googleapis.com/auth/firebase.messaging';

  /** Envía un mensaje FCM. Devuelve 'ok', 'invalid' (el token ya no sirve: hay que borrarlo) o 'error'. */
  async function send(message) {
    try {
      const res = await fetch(`https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`, {
        method: 'POST',
        headers: { authorization: `Bearer ${await accessToken(SCOPE)}`, 'content-type': 'application/json' },
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
