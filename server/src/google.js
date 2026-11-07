import crypto from 'node:crypto';

/**
 * Acceso a las APIs de Google con la clave JSON de una cuenta de servicio (sin dependencias).
 * La misma clave sirve para Firebase Cloud Messaging (src/fcm.js) y para comprobar las compras
 * de Google Play (src/play.js): cada una pide su permiso ("scope") y aquí se guarda un acceso por permiso.
 */
export function createGoogleAuth(credentials) {
  const { client_email: clientEmail, private_key: privateKey } = credentials;
  const cache = new Map(); // scope -> { token, exp }

  return async function accessToken(scope) {
    const cached = cache.get(scope);
    if (cached && cached.exp > Date.now() + 60_000) return cached.token;
    const iat = Math.floor(Date.now() / 1000);
    const b64 = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');
    const unsigned = `${b64({ alg: 'RS256', typ: 'JWT' })}.${b64({
      iss: clientEmail,
      scope,
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
    cache.set(scope, { token: data.access_token, exp: Date.now() + data.expires_in * 1000 });
    return data.access_token;
  };
}
