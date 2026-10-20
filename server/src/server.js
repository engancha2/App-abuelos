import Fastify from 'fastify';
import fastifyStatic from '@fastify/static';
import fastifyMultipart from '@fastify/multipart';
import fastifyWebsocket from '@fastify/websocket';
import webpush from 'web-push';
import fs from 'node:fs';
import path from 'node:path';
import { pipeline } from 'node:stream/promises';
import { fileURLToPath } from 'node:url';
import { openDb, DEFAULT_CONFIG, mergeConfig } from './db.js';
import { createFcm, loadFcmCredentials } from './fcm.js';
import { RINGTONES, ringtoneWav } from './ringtones.js';
import {
  now, sha256, randomToken, hashPassword, verifyPassword, parseCookies, haversineMeters, sanitizeConfig, phoneKey,
} from './util.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const env = {
  port: Number(process.env.PORT ?? 3000),
  dataDir: process.env.DATA_DIR ?? path.join(__dirname, '..', 'data'),
  publicUrl: process.env.PUBLIC_URL ?? 'http://localhost:3000',
  allowRegistration: (process.env.ALLOW_REGISTRATION ?? 'true') === 'true',
  cookieSecure: (process.env.COOKIE_SECURE ?? 'auto'),
  vapidSubject: process.env.VAPID_SUBJECT ?? 'mailto:admin@example.com',
};

const SESSION_COOKIE = 'cerca_s';
const SESSION_DAYS = 30;
const PAIR_CODE_MINUTES = 30;

export async function buildServer({ dataDir = env.dataDir, logger = true, fcm = null } = {}) {
  const db = openDb(dataDir);
  const uploadsDir = path.join(dataDir, 'uploads');
  fs.mkdirSync(uploadsDir, { recursive: true });

  // ---------- Web Push (claves VAPID generadas una vez y guardadas en BD) ----------
  let vapid = db.prepare(`SELECT value FROM settings WHERE key='vapid'`).get();
  if (!vapid) {
    const keys = webpush.generateVAPIDKeys();
    db.prepare(`INSERT INTO settings(key,value) VALUES('vapid',?)`).run(JSON.stringify(keys));
    vapid = { value: JSON.stringify(keys) };
  }
  const vapidKeys = JSON.parse(vapid.value);
  webpush.setVapidDetails(env.vapidSubject, vapidKeys.publicKey, vapidKeys.privateKey);

  const app = Fastify({ logger, trustProxy: true, bodyLimit: 1024 * 1024 });
  // Firebase Cloud Messaging: avisos a la app de la familia y "despertar" al móvil del mayor (los tests pasan uno falso)
  const push = fcm ?? createFcm({ credentials: loadFcmCredentials(dataDir), log: app.log });
  await app.register(fastifyMultipart, { limits: { fileSize: 5 * 1024 * 1024, files: 1 } });
  await app.register(fastifyWebsocket, { options: { maxPayload: 64 * 1024 } });
  await app.register(fastifyStatic, { root: path.join(__dirname, '..', 'public'), prefix: '/' });
  await app.register(fastifyStatic, {
    root: uploadsDir, prefix: '/uploads/', decorateReply: false,
    setHeaders: (res) => res.setHeader('Cache-Control', 'public, max-age=31536000, immutable'),
  });

  // ---------- Helpers ----------
  const q = {
    userByEmail: db.prepare(`SELECT * FROM users WHERE email = ?`),
    userBySession: db.prepare(`SELECT u.id, u.email, u.name, s.expires_at FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.token_hash = ? AND s.expires_at > ?`),
    seniorByDevice: db.prepare(`SELECT * FROM seniors WHERE device_token_hash = ?`),
    seniorForUser: db.prepare(`SELECT s.*, m.role FROM seniors s JOIN members m ON m.senior_id = s.id WHERE s.id = ? AND m.user_id = ?`),
    membersOf: db.prepare(`SELECT u.id, u.email, u.name, m.role FROM members m JOIN users u ON u.id = m.user_id WHERE m.senior_id = ?`),
    insertLocation: db.prepare(`INSERT INTO locations(senior_id, lat, lng, accuracy, battery, source, ts) VALUES (?,?,?,?,?,?,?)`),
    insertAlert: db.prepare(`INSERT INTO alerts(senior_id, type, message, lat, lng, ts) VALUES (?,?,?,?,?,?)`),
    touch: db.prepare(`UPDATE seniors SET last_seen = ?, offline_alerted = 0 WHERE id = ?`),
  };

  const cfgOf = (senior) => mergeConfig(DEFAULT_CONFIG, JSON.parse(senior.config_json));

  function setSessionCookie(req, reply, token, maxAgeSec) {
    const secure = env.cookieSecure === 'auto' ? req.protocol === 'https' : env.cookieSecure === 'true';
    reply.header('Set-Cookie',
      `${SESSION_COOKIE}=${token}; Path=/; HttpOnly; SameSite=Lax; Max-Age=${maxAgeSec}${secure ? '; Secure' : ''}`);
  }

  // La sesión llega por cookie (panel web) o por "Authorization: Bearer" (app Cerca Familia)
  function sessionToken(req) {
    const auth = String(req.headers.authorization ?? '');
    return auth.startsWith('Bearer ') ? auth.slice(7) : parseCookies(req.headers.cookie)[SESSION_COOKIE];
  }

  function requireUser(req, reply) {
    const token = sessionToken(req);
    const row = token && q.userBySession.get(sha256(token), now());
    if (!row) { reply.code(401).send({ error: 'No has iniciado sesión' }); return null; }
    const { expires_at: expiresAt, ...user } = row;
    // Sesión deslizante: quien usa la app a menudo no tiene que volver a entrar nunca
    if (expiresAt - now() < (SESSION_DAYS / 2) * 86400_000) {
      db.prepare(`UPDATE sessions SET expires_at = ? WHERE token_hash = ?`).run(now() + SESSION_DAYS * 86400_000, sha256(token));
    }
    return user;
  }

  function requireSenior(req, reply) {
    const user = requireUser(req, reply);
    if (!user) return null;
    const senior = q.seniorForUser.get(Number(req.params.id), user.id);
    if (!senior) { reply.code(404).send({ error: 'No encontrado' }); return null; }
    return { user, senior };
  }

  function requireDevice(req, reply) {
    const auth = String(req.headers.authorization ?? '');
    const token = auth.startsWith('Bearer ') ? auth.slice(7) : null;
    const senior = token && q.seniorByDevice.get(sha256(token));
    if (!senior) { reply.code(401).send({ error: 'Dispositivo no emparejado' }); return null; }
    q.touch.run(now(), senior.id);
    return senior;
  }

  // Rate limit muy simple para login/emparejamiento (por IP)
  const attempts = new Map();
  function tooMany(key, max = 10, windowMs = 10 * 60 * 1000) {
    const t = now();
    const rec = attempts.get(key) ?? { n: 0, since: t };
    if (t - rec.since > windowMs) { rec.n = 0; rec.since = t; }
    rec.n++;
    attempts.set(key, rec);
    return rec.n > max;
  }

  // ---------- Conexiones en vivo con dispositivos ----------
  const deviceSockets = new Map(); // seniorId -> socket

  function sendToDevice(seniorId, msg) {
    const ws = deviceSockets.get(seniorId);
    if (ws && ws.readyState === 1) { ws.send(JSON.stringify(msg)); return true; }
    return false;
  }

  /** Despierta al móvil del mayor por FCM (cuando no tiene el WebSocket abierto). true si Firebase lo ha aceptado. */
  async function pushToDevice(seniorId, data) {
    const row = db.prepare(`SELECT fcm_token FROM seniors WHERE id = ?`).get(seniorId);
    if (!row?.fcm_token) return false;
    const result = await push.send({
      token: row.fcm_token,
      data: Object.fromEntries(Object.entries(data).map(([k, v]) => [k, String(v)])), // FCM solo admite textos
      android: { priority: 'high', ttl: '600s' },
    });
    if (result === 'invalid') db.prepare(`UPDATE seniors SET fcm_token = NULL WHERE id = ?`).run(seniorId);
    return result === 'ok';
  }

  async function notifyMembers(seniorId, title, body, data = {}) {
    const subs = db.prepare(`SELECT p.* FROM push_subs p JOIN members m ON m.user_id = p.user_id WHERE m.senior_id = ?`).all(seniorId);
    const tokens = db.prepare(`SELECT f.token FROM fcm_tokens f JOIN members m ON m.user_id = f.user_id WHERE m.senior_id = ?`).all(seniorId);
    await Promise.all([
      ...subs.map(async (s) => {
        try {
          await webpush.sendNotification({ endpoint: s.endpoint, keys: JSON.parse(s.keys_json) },
            JSON.stringify({ title, body, data: { seniorId, ...data } }), { TTL: 3600, urgency: 'high' });
        } catch (err) {
          if (err.statusCode === 404 || err.statusCode === 410) db.prepare(`DELETE FROM push_subs WHERE endpoint = ?`).run(s.endpoint);
          else app.log.warn({ err: err.message }, 'push fallido');
        }
      }),
      ...tokens.map(async ({ token }) => {
        const result = await push.send({
          token,
          notification: { title, body },
          data: Object.fromEntries(Object.entries({ seniorId, ...data }).map(([k, v]) => [k, String(v)])),
          // Los canales "sos" y "avisos" los crea la app de la familia; el SOS suena aunque esté en silencio
          android: { priority: 'high', notification: { channel_id: data.type === 'sos' ? 'sos' : 'avisos' } },
        });
        if (result === 'invalid') db.prepare(`DELETE FROM fcm_tokens WHERE token = ?`).run(token);
      }),
    ]);
  }

  const ALERT_TITLES = {
    sos: '🆘 SOS', zone_exit: '📍 Ha salido de la zona', zone_enter: '🏠 Ha vuelto a la zona',
    low_battery: '🔋 Batería baja', offline: '📵 Sin conexión', setup_problem: '⚠️ Revisa el móvil',
    auto_answer: '📞 Llamada contestada sola',
  };

  function createAlert(senior, type, message, lat = null, lng = null) {
    const info = q.insertAlert.run(senior.id, type, message, lat, lng, now());
    notifyMembers(senior.id, `${ALERT_TITLES[type] ?? 'Aviso'} · ${senior.name}`, message, { alertId: Number(info.lastInsertRowid), type })
      .catch(() => {});
    return Number(info.lastInsertRowid);
  }

  // ======================================================================
  // AUTH FAMILIA
  // ======================================================================
  app.get('/api/meta', async () => ({ allowRegistration: env.allowRegistration, vapidPublicKey: vapidKeys.publicKey }));

  // Timbres: la lista (para elegir) y cada sonido (para escucharlo y para que el móvil del mayor lo descargue)
  app.get('/api/ringtones', async () => RINGTONES);
  app.get('/ringtones/:file', async (req, reply) => {
    const wav = ringtoneWav(String(req.params.file).replace(/\.wav$/, ''));
    if (!wav) return reply.code(404).send({ error: 'No existe ese timbre' });
    return reply.header('Content-Type', 'audio/wav').header('Cache-Control', 'public, max-age=86400').send(wav);
  });

  app.post('/api/auth/register', async (req, reply) => {
    if (!env.allowRegistration) return reply.code(403).send({ error: 'El registro está desactivado' });
    const { email, name, password } = req.body ?? {};
    if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email ?? '')) return reply.code(400).send({ error: 'Email no válido' });
    if (String(password ?? '').length < 8) return reply.code(400).send({ error: 'La contraseña debe tener al menos 8 caracteres' });
    if (q.userByEmail.get(email)) return reply.code(409).send({ error: 'Ya existe una cuenta con ese email' });
    const info = db.prepare(`INSERT INTO users(email, name, pass_hash, created_at) VALUES (?,?,?,?)`)
      .run(email, String(name || email.split('@')[0]).slice(0, 60), hashPassword(password), now());
    return startSession(req, reply, Number(info.lastInsertRowid));
  });

  app.post('/api/auth/login', async (req, reply) => {
    if (tooMany(`login:${req.ip}`)) return reply.code(429).send({ error: 'Demasiados intentos, espera unos minutos' });
    const { email, password } = req.body ?? {};
    const user = q.userByEmail.get(String(email ?? ''));
    if (!user || !verifyPassword(String(password ?? ''), user.pass_hash)) {
      return reply.code(401).send({ error: 'Email o contraseña incorrectos' });
    }
    return startSession(req, reply, user.id);
  });

  function startSession(req, reply, userId) {
    const token = randomToken();
    db.prepare(`INSERT INTO sessions(token_hash, user_id, expires_at) VALUES (?,?,?)`)
      .run(sha256(token), userId, now() + SESSION_DAYS * 86400_000);
    // La app guarda el token y lo manda como Bearer; el navegador usa la cookie (y nunca ve el token)
    if (req.body?.client === 'app') return { ok: true, token };
    setSessionCookie(req, reply, token, SESSION_DAYS * 86400);
    return { ok: true };
  }

  app.post('/api/auth/logout', async (req, reply) => {
    const token = sessionToken(req);
    if (token) db.prepare(`DELETE FROM sessions WHERE token_hash = ?`).run(sha256(token));
    // Ese móvil deja de recibir avisos
    if (req.body?.fcmToken) db.prepare(`DELETE FROM fcm_tokens WHERE token = ?`).run(String(req.body.fcmToken));
    setSessionCookie(req, reply, '', 0);
    return { ok: true };
  });

  app.get('/api/me', async (req, reply) => {
    const user = requireUser(req, reply);
    return user ?? undefined;
  });

  app.post('/api/push/subscribe', async (req, reply) => {
    const user = requireUser(req, reply);
    if (!user) return;
    const { endpoint, keys } = req.body ?? {};
    if (!endpoint || !keys?.p256dh || !keys?.auth) return reply.code(400).send({ error: 'Suscripción no válida' });
    db.prepare(`INSERT INTO push_subs(endpoint, user_id, keys_json) VALUES (?,?,?)
                ON CONFLICT(endpoint) DO UPDATE SET user_id = excluded.user_id, keys_json = excluded.keys_json`)
      .run(endpoint, user.id, JSON.stringify(keys));
    return { ok: true };
  });

  // App Cerca Familia: token de Firebase de este móvil para recibir los avisos
  app.post('/api/push/fcm', async (req, reply) => {
    const user = requireUser(req, reply);
    if (!user) return;
    const token = String(req.body?.token ?? '');
    if (!/^[\w:-]{20,4096}$/.test(token)) return reply.code(400).send({ error: 'Token no válido' });
    db.prepare(`INSERT INTO fcm_tokens(token, user_id, updated_at) VALUES (?,?,?)
                ON CONFLICT(token) DO UPDATE SET user_id = excluded.user_id, updated_at = excluded.updated_at`)
      .run(token, user.id, now());
    return { ok: true };
  });

  // ======================================================================
  // PANEL: PERSONAS MAYORES
  // ======================================================================
  function seniorSummary(s) {
    return {
      id: s.id,
      name: s.name,
      role: s.role,
      paired: Boolean(s.device_token_hash),
      online: deviceSockets.has(s.id),
      pushReady: Boolean(s.fcm_token), // se le puede despertar al instante aunque no esté "online"
      lastSeen: s.last_seen,
      status: s.status_json ? JSON.parse(s.status_json) : null,
      device: s.device_info ? JSON.parse(s.device_info) : null,
      lastLocation: db.prepare(`SELECT lat, lng, accuracy, battery, source, ts FROM locations WHERE senior_id = ? ORDER BY ts DESC LIMIT 1`).get(s.id) ?? null,
      pendingAlerts: db.prepare(`SELECT COUNT(*) AS n FROM alerts WHERE senior_id = ? AND acknowledged = 0`).get(s.id).n,
    };
  }

  app.get('/api/seniors', async (req, reply) => {
    const user = requireUser(req, reply);
    if (!user) return;
    const rows = db.prepare(`SELECT s.*, m.role FROM seniors s JOIN members m ON m.senior_id = s.id WHERE m.user_id = ? ORDER BY s.name`).all(user.id);
    return rows.map(seniorSummary);
  });

  app.post('/api/seniors', async (req, reply) => {
    const user = requireUser(req, reply);
    if (!user) return;
    const name = String(req.body?.name ?? '').trim().slice(0, 60);
    if (!name) return reply.code(400).send({ error: 'Pon un nombre' });
    const info = db.prepare(`INSERT INTO seniors(name, config_json, created_at) VALUES (?,?,?)`)
      .run(name, JSON.stringify(DEFAULT_CONFIG), now());
    const id = Number(info.lastInsertRowid);
    db.prepare(`INSERT INTO members(senior_id, user_id, role) VALUES (?,?, 'owner')`).run(id, user.id);
    return { id };
  });

  app.get('/api/seniors/:id', async (req, reply) => {
    const ctx = requireSenior(req, reply);
    if (!ctx) return;
    const { senior } = ctx;
    const cfg = cfgOf(senior);
    return {
      ...seniorSummary(senior),
      config: { ...cfg, pinHash: undefined, hasPin: Boolean(cfg.pinHash) },
      configVersion: senior.config_version,
      apps: senior.apps_json ? JSON.parse(senior.apps_json) : [],
      members: q.membersOf.all(senior.id),
      pairCode: senior.pair_expires > now() ? senior.pair_code : null,
      pairExpires: senior.pair_expires > now() ? senior.pair_expires : null,
    };
  });

  app.patch('/api/seniors/:id', async (req, reply) => {
    const ctx = requireSenior(req, reply);
    if (!ctx) return;
    const name = String(req.body?.name ?? '').trim().slice(0, 60);
    if (name) db.prepare(`UPDATE seniors SET name = ? WHERE id = ?`).run(name, ctx.senior.id);
    return { ok: true };
  });

  app.delete('/api/seniors/:id', async (req, reply) => {
    const ctx = requireSenior(req, reply);
    if (!ctx) return;
    if (ctx.senior.role !== 'owner') return reply.code(403).send({ error: 'Solo quien lo creó puede eliminarlo' });
    deviceSockets.get(ctx.senior.id)?.close();
    db.prepare(`DELETE FROM seniors WHERE id = ?`).run(ctx.senior.id);
    return { ok: true };
  });

  /** Guarda y avisa al móvil: por WebSocket si está conectado y, si no, despertándolo por FCM. */
  function saveConfig(senior, cfg, { notifyDevice = true } = {}) {
    const version = senior.config_version + 1;
    db.prepare(`UPDATE seniors SET config_json = ?, config_version = ? WHERE id = ?`).run(JSON.stringify(cfg), version, senior.id);
    if (notifyDevice && !sendToDevice(senior.id, { type: 'config', version })) {
      pushToDevice(senior.id, { type: 'config', version }).catch(() => {});
    }
    return version;
  }

  app.put('/api/seniors/:id/config', async (req, reply) => {
    const ctx = requireSenior(req, reply);
    if (!ctx) return;
    let cfg;
    try {
      cfg = sanitizeConfig(req.body, DEFAULT_CONFIG);
    } catch (err) {
      return reply.code(400).send({ error: err.message });
    }
    cfg.pinHash = cfgOf(ctx.senior).pinHash; // el PIN solo cambia por su propio endpoint
    return { ok: true, version: saveConfig(ctx.senior, cfg) };
  });

  app.post('/api/seniors/:id/pin', async (req, reply) => {
    const ctx = requireSenior(req, reply);
    if (!ctx) return;
    const pin = String(req.body?.pin ?? '');
    if (!/^\d{4,8}$/.test(pin)) return reply.code(400).send({ error: 'El PIN debe tener entre 4 y 8 números' });
    const cfg = cfgOf(ctx.senior);
    cfg.pinHash = sha256(`cerca:${ctx.senior.id}:${pin}`);
    return { ok: true, version: saveConfig(ctx.senior, cfg) };
  });

  app.post('/api/seniors/:id/pair-code', async (req, reply) => {
    const ctx = requireSenior(req, reply);
    if (!ctx) return;
    let code;
    do { code = String(Math.floor(100000 + Math.random() * 900000)); }
    while (db.prepare(`SELECT 1 FROM seniors WHERE pair_code = ? AND pair_expires > ?`).get(code, now()));
    const expires = now() + PAIR_CODE_MINUTES * 60_000;
    db.prepare(`UPDATE seniors SET pair_code = ?, pair_expires = ? WHERE id = ?`).run(code, expires, ctx.senior.id);
    return { code, expires };
  });

  app.post('/api/seniors/:id/unpair', async (req, reply) => {
    const ctx = requireSenior(req, reply);
    if (!ctx) return;
    deviceSockets.get(ctx.senior.id)?.close();
    db.prepare(`UPDATE seniors SET device_token_hash = NULL, device_info = NULL, fcm_token = NULL WHERE id = ?`).run(ctx.senior.id);
    return { ok: true };
  });

  app.post('/api/seniors/:id/locate', async (req, reply) => {
    const ctx = requireSenior(req, reply);
    if (!ctx) return;
    const live = sendToDevice(ctx.senior.id, { type: 'locate' });
    // Si no está conectado, se le despierta por FCM; y por si acaso, queda pendiente para el siguiente latido
    const pushed = !live && await pushToDevice(ctx.senior.id, { type: 'locate' });
    db.prepare(`UPDATE seniors SET locate_requested = 1 WHERE id = ?`).run(ctx.senior.id);
    return { ok: true, live, pushed };
  });

  // "Hacerlo sonar en su móvil": que la familia compruebe que el timbre elegido se oye (suena unos segundos)
  app.post('/api/seniors/:id/ring-test', async (req, reply) => {
    const ctx = requireSenior(req, reply);
    if (!ctx) return;
    const msg = { type: 'ring', ringtone: cfgOf(ctx.senior).calls.ringtone };
    const live = sendToDevice(ctx.senior.id, msg);
    const pushed = !live && await pushToDevice(ctx.senior.id, msg);
    return { ok: true, live, pushed };
  });

  app.get('/api/seniors/:id/locations', async (req, reply) => {
    const ctx = requireSenior(req, reply);
    if (!ctx) return;
    const since = Number(req.query.since ?? now() - 24 * 3600_000);
    return db.prepare(`SELECT lat, lng, accuracy, battery, source, ts FROM locations WHERE senior_id = ? AND ts >= ? ORDER BY ts ASC LIMIT 5000`)
      .all(ctx.senior.id, since);
  });

  app.get('/api/seniors/:id/alerts', async (req, reply) => {
    const ctx = requireSenior(req, reply);
    if (!ctx) return;
    return db.prepare(`SELECT * FROM alerts WHERE senior_id = ? ORDER BY ts DESC LIMIT 100`).all(ctx.senior.id);
  });

  app.post('/api/seniors/:id/alerts/ack', async (req, reply) => {
    const ctx = requireSenior(req, reply);
    if (!ctx) return;
    db.prepare(`UPDATE alerts SET acknowledged = 1 WHERE senior_id = ?`).run(ctx.senior.id);
    return { ok: true };
  });

  app.post('/api/seniors/:id/members', async (req, reply) => {
    const ctx = requireSenior(req, reply);
    if (!ctx) return;
    const other = q.userByEmail.get(String(req.body?.email ?? ''));
    if (!other) return reply.code(404).send({ error: 'Esa persona tiene que crear antes su cuenta en Cerca' });
    db.prepare(`INSERT OR IGNORE INTO members(senior_id, user_id, role) VALUES (?,?, 'member')`).run(ctx.senior.id, other.id);
    return { ok: true };
  });

  app.delete('/api/seniors/:id/members/:userId', async (req, reply) => {
    const ctx = requireSenior(req, reply);
    if (!ctx) return;
    if (ctx.senior.role !== 'owner') return reply.code(403).send({ error: 'Solo quien lo creó puede quitar familiares' });
    db.prepare(`DELETE FROM members WHERE senior_id = ? AND user_id = ? AND role != 'owner'`).run(ctx.senior.id, Number(req.params.userId));
    return { ok: true };
  });

  app.post('/api/seniors/:id/photo', async (req, reply) => {
    const ctx = requireSenior(req, reply);
    if (!ctx) return;
    const file = await req.file();
    if (!file) return reply.code(400).send({ error: 'Falta la foto' });
    const ext = { 'image/jpeg': 'jpg', 'image/png': 'png', 'image/webp': 'webp' }[file.mimetype];
    if (!ext) return reply.code(400).send({ error: 'Formato no admitido (JPG, PNG o WebP)' });
    const name = `${ctx.senior.id}-${randomToken(12)}.${ext}`;
    await pipeline(file.file, fs.createWriteStream(path.join(uploadsDir, name)));
    if (file.file.truncated) {
      fs.rmSync(path.join(uploadsDir, name), { force: true });
      return reply.code(400).send({ error: 'La foto pesa demasiado (máx. 5 MB)' });
    }
    return { photo: `/uploads/${name}` };
  });

  // ======================================================================
  // API DEL DISPOSITIVO
  // ======================================================================
  app.post('/api/device/pair', async (req, reply) => {
    if (tooMany(`pair:${req.ip}`, 20)) return reply.code(429).send({ error: 'Demasiados intentos' });
    const code = String(req.body?.code ?? '').replace(/\D/g, '');
    const senior = db.prepare(`SELECT * FROM seniors WHERE pair_code = ? AND pair_expires > ?`).get(code, now());
    if (!senior) return reply.code(404).send({ error: 'Código incorrecto o caducado' });
    const token = randomToken();
    deviceSockets.get(senior.id)?.close(); // si había otro móvil emparejado, se desconecta
    db.prepare(`UPDATE seniors SET device_token_hash = ?, device_info = ?, fcm_token = NULL, pair_code = NULL, pair_expires = NULL, last_seen = ? WHERE id = ?`)
      .run(sha256(token), JSON.stringify(req.body?.device ?? {}), now(), senior.id);
    return { token, seniorId: senior.id, name: senior.name };
  });

  // Token de Firebase del móvil del mayor, para despertarlo al instante (cambios, "¿dónde está?")
  app.post('/api/device/fcm', async (req, reply) => {
    const senior = requireDevice(req, reply);
    if (!senior) return;
    const token = String(req.body?.token ?? '');
    if (!/^[\w:-]{20,4096}$/.test(token)) return reply.code(400).send({ error: 'Token no válido' });
    db.prepare(`UPDATE seniors SET fcm_token = ? WHERE id = ?`).run(token, senior.id);
    return { ok: true };
  });

  // El mayor marca o desmarca un favorito desde su agenda. Se guarda en la misma lista que edita la familia.
  app.post('/api/device/favorites', async (req, reply) => {
    const senior = requireDevice(req, reply);
    if (!senior) return;
    const phone = String(req.body?.phone ?? '').replace(/[^\d+*#]/g, '').slice(0, 30);
    const key = phoneKey(phone);
    if (!key) return reply.code(400).send({ error: 'Número no válido' });
    const cfg = cfgOf(senior);
    const index = cfg.contacts.findIndex((c) => phoneKey(c.phone) === key);
    if (req.body?.favorite === false) {
      if (index < 0) return { ok: true, version: senior.config_version };
      const [gone] = cfg.contacts.splice(index, 1);
      if (cfg.sosContactId === gone.id) cfg.sosContactId = cfg.contacts[0]?.id ?? null;
    } else {
      if (index >= 0) return { ok: true, version: senior.config_version };
      if (cfg.contacts.length >= 12) return reply.code(400).send({ error: 'Ya hay 12 favoritos. Quita alguno antes de añadir otro.' });
      const name = String(req.body?.name ?? '').trim().slice(0, 40) || phone;
      cfg.contacts.push({ id: randomToken(6), name, phone, photo: null, whatsapp: false });
    }
    // El propio móvil ya va a sincronizar: no hace falta avisarle
    return { ok: true, version: saveConfig(senior, sanitizeConfig(cfg, DEFAULT_CONFIG), { notifyDevice: false }) };
  });

  app.get('/api/device/config', async (req, reply) => {
    const senior = requireDevice(req, reply);
    if (!senior) return;
    return { version: senior.config_version, seniorId: senior.id, name: senior.name, config: cfgOf(senior) };
  });

  app.post('/api/device/heartbeat', async (req, reply) => {
    const senior = requireDevice(req, reply);
    if (!senior) return;
    const b = req.body ?? {};
    const prev = senior.status_json ? JSON.parse(senior.status_json) : {};
    const status = {
      battery: Number.isFinite(b.battery) ? b.battery : null,
      charging: Boolean(b.charging),
      isDefaultHome: b.isDefaultHome !== false,
      isDefaultDialer: b.isDefaultDialer !== false,
      locationPermission: b.locationPermission ?? null,
      locationEnabled: b.locationEnabled !== false,
      appVersion: String(b.appVersion ?? ''),
      // false = la familia ha elegido timbre pero falta el permiso de "Modificar ajustes del sistema"
      ringtoneAllowed: typeof b.ringtoneAllowed === 'boolean' ? b.ringtoneAllowed : null,
      ts: now(),
    };
    db.prepare(`UPDATE seniors SET status_json = ? WHERE id = ?`).run(JSON.stringify(status), senior.id);
    if (Array.isArray(b.apps)) {
      const apps = b.apps.slice(0, 300).map((a) => ({ pkg: String(a.pkg).slice(0, 150), label: String(a.label).slice(0, 60) }));
      db.prepare(`UPDATE seniors SET apps_json = ? WHERE id = ?`).run(JSON.stringify(apps), senior.id);
    }

    // Avisos de configuración rota (solo cuando cambia de bien a mal, para no spamear)
    const cfg = cfgOf(senior);
    const problems = [];
    if (!status.isDefaultHome && prev.isDefaultHome !== false) problems.push('ya no es la pantalla de inicio');
    if (!status.isDefaultDialer && prev.isDefaultDialer !== false) problems.push('ya no es la app de teléfono');
    if (cfg.location.mode !== 'off' && status.locationPermission !== 'always' && prev.locationPermission === 'always') {
      problems.push('le falta el permiso de ubicación "Permitir siempre"');
    }
    if (cfg.location.mode !== 'off' && !status.locationEnabled && prev.locationEnabled !== false) problems.push('tiene la ubicación desactivada');
    if (problems.length) createAlert(senior, 'setup_problem', `El móvil ${problems.join(', ')}.`);

    if (status.battery != null && !status.charging && status.battery <= cfg.alerts.lowBatteryPercent
        && (prev.battery == null || prev.battery > cfg.alerts.lowBatteryPercent)) {
      createAlert(senior, 'low_battery', `Le queda un ${status.battery}% de batería.`);
    }

    const locate = senior.locate_requested === 1;
    if (locate) db.prepare(`UPDATE seniors SET locate_requested = 0 WHERE id = ?`).run(senior.id);
    return { configVersion: senior.config_version, locate };
  });

  app.post('/api/device/locations', async (req, reply) => {
    const senior = requireDevice(req, reply);
    if (!senior) return;
    const items = (Array.isArray(req.body) ? req.body : [req.body]).slice(0, 500);
    let n = 0;
    for (const l of items) {
      const lat = Number(l?.lat), lng = Number(l?.lng);
      if (!Number.isFinite(lat) || !Number.isFinite(lng)) continue;
      const ts = Number.isFinite(l.ts) ? Math.min(l.ts, now()) : now();
      q.insertLocation.run(senior.id, lat, lng, Number(l.accuracy) || null, Number.isFinite(l.battery) ? l.battery : null,
        String(l.source ?? 'gps').slice(0, 20), ts);
      n++;
    }
    return { ok: true, stored: n };
  });

  app.post('/api/device/alert', async (req, reply) => {
    const senior = requireDevice(req, reply);
    if (!senior) return;
    const { type, lat, lng, zoneName, contactName } = req.body ?? {};
    const la = Number.isFinite(lat) ? lat : null;
    const ln = Number.isFinite(lng) ? lng : null;
    if (la != null && ln != null) q.insertLocation.run(senior.id, la, ln, null, null, `alert:${type}`, now());

    let message;
    switch (type) {
      case 'sos': message = `${senior.name} ha pulsado el botón SOS.`; break;
      case 'zone_exit': message = `${senior.name} ha salido de "${String(zoneName ?? 'la zona').slice(0, 40)}".`; break;
      case 'zone_enter': message = `${senior.name} ha vuelto a "${String(zoneName ?? 'la zona').slice(0, 40)}".`; break;
      case 'auto_answer':
        message = `${senior.name} no contestaba: la llamada de ${String(contactName ?? 'un familiar').slice(0, 40)} se ha contestado sola en manos libres.`;
        break;
      default: return reply.code(400).send({ error: 'Tipo de alerta no válido' });
    }
    // Para zone_exit comprobamos en servidor que de verdad está fuera de todas las zonas (evita falsos positivos)
    if (type === 'zone_exit' && la != null) {
      const zones = cfgOf(senior).location.zones;
      const inside = zones.some((z) => haversineMeters(la, ln, z.lat, z.lng) <= z.radius);
      if (inside) return { ok: true, ignored: true };
    }
    return { ok: true, alertId: createAlert(senior, type, message, la, ln) };
  });

  // WebSocket: canal en vivo para "¿dónde está?" y cambios de configuración
  app.get('/api/device/ws', { websocket: true }, (socket, req) => {
    const auth = String(req.headers.authorization ?? '');
    const token = auth.startsWith('Bearer ') ? auth.slice(7) : null;
    const senior = token && q.seniorByDevice.get(sha256(token));
    if (!senior) { socket.close(4001, 'unauthorized'); return; }

    deviceSockets.get(senior.id)?.close(4000, 'replaced');
    deviceSockets.set(senior.id, socket);
    q.touch.run(now(), senior.id);
    socket.send(JSON.stringify({ type: 'hello', configVersion: senior.config_version }));

    let alive = true;
    const ping = setInterval(() => {
      if (!alive) { socket.terminate(); return; }
      alive = false;
      socket.ping();
    }, 30_000);
    socket.on('pong', () => { alive = true; q.touch.run(now(), senior.id); });
    socket.on('message', () => { alive = true; q.touch.run(now(), senior.id); });
    socket.on('close', () => {
      clearInterval(ping);
      if (deviceSockets.get(senior.id) === socket) deviceSockets.delete(senior.id);
    });
  });

  app.get('/health', async () => ({ ok: true }));

  // ======================================================================
  // TAREAS PERIÓDICAS
  // ======================================================================
  function checkOffline() {
    const rows = db.prepare(`SELECT * FROM seniors WHERE device_token_hash IS NOT NULL AND offline_alerted = 0`).all();
    for (const s of rows) {
      const cfg = cfgOf(s);
      if (s.last_seen && now() - s.last_seen > cfg.alerts.offlineHours * 3600_000 && !deviceSockets.has(s.id)) {
        db.prepare(`UPDATE seniors SET offline_alerted = 1 WHERE id = ?`).run(s.id);
        createAlert(s, 'offline', `El móvil lleva más de ${cfg.alerts.offlineHours} h sin conectar. Puede estar apagado o sin batería.`);
      }
    }
  }

  function purgeOldData() {
    for (const s of db.prepare(`SELECT id, config_json FROM seniors`).all()) {
      const days = mergeConfig(DEFAULT_CONFIG, JSON.parse(s.config_json)).location.retentionDays;
      db.prepare(`DELETE FROM locations WHERE senior_id = ? AND ts < ?`).run(s.id, now() - days * 86400_000);
    }
    db.prepare(`DELETE FROM alerts WHERE ts < ?`).run(now() - 90 * 86400_000);
    db.prepare(`DELETE FROM sessions WHERE expires_at < ?`).run(now());
  }

  const timers = [setInterval(checkOffline, 5 * 60_000), setInterval(purgeOldData, 60 * 60_000)];
  app.addHook('onClose', async () => { timers.forEach(clearInterval); db.close(); });
  app.decorate('jobs', { checkOffline, purgeOldData });

  return app;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const app = await buildServer();
  await app.listen({ port: env.port, host: '0.0.0.0' });
}
