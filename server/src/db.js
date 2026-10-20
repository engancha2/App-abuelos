import { DatabaseSync } from 'node:sqlite';
import fs from 'node:fs';
import path from 'node:path';

export function openDb(dataDir) {
  fs.mkdirSync(dataDir, { recursive: true });
  const db = new DatabaseSync(path.join(dataDir, 'cerca.db'));
  db.exec(`
    PRAGMA journal_mode = WAL;
    PRAGMA foreign_keys = ON;

    CREATE TABLE IF NOT EXISTS users (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      email TEXT NOT NULL UNIQUE COLLATE NOCASE,
      name TEXT NOT NULL,
      pass_hash TEXT NOT NULL,
      created_at INTEGER NOT NULL
    );

    CREATE TABLE IF NOT EXISTS sessions (
      token_hash TEXT PRIMARY KEY,
      user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      expires_at INTEGER NOT NULL
    );

    CREATE TABLE IF NOT EXISTS seniors (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      name TEXT NOT NULL,
      config_json TEXT NOT NULL,
      config_version INTEGER NOT NULL DEFAULT 1,
      pair_code TEXT,
      pair_expires INTEGER,
      device_token_hash TEXT UNIQUE,
      device_info TEXT,
      status_json TEXT,
      apps_json TEXT,
      last_seen INTEGER,
      locate_requested INTEGER NOT NULL DEFAULT 0,
      offline_alerted INTEGER NOT NULL DEFAULT 0,
      created_at INTEGER NOT NULL
    );

    CREATE TABLE IF NOT EXISTS members (
      senior_id INTEGER NOT NULL REFERENCES seniors(id) ON DELETE CASCADE,
      user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      role TEXT NOT NULL DEFAULT 'member',
      PRIMARY KEY (senior_id, user_id)
    );

    CREATE TABLE IF NOT EXISTS locations (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      senior_id INTEGER NOT NULL REFERENCES seniors(id) ON DELETE CASCADE,
      lat REAL NOT NULL,
      lng REAL NOT NULL,
      accuracy REAL,
      battery INTEGER,
      source TEXT,
      ts INTEGER NOT NULL
    );
    CREATE INDEX IF NOT EXISTS idx_loc_senior_ts ON locations(senior_id, ts);

    CREATE TABLE IF NOT EXISTS alerts (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      senior_id INTEGER NOT NULL REFERENCES seniors(id) ON DELETE CASCADE,
      type TEXT NOT NULL,
      message TEXT NOT NULL,
      lat REAL,
      lng REAL,
      ts INTEGER NOT NULL,
      acknowledged INTEGER NOT NULL DEFAULT 0
    );
    CREATE INDEX IF NOT EXISTS idx_alert_senior_ts ON alerts(senior_id, ts);

    CREATE TABLE IF NOT EXISTS push_subs (
      endpoint TEXT PRIMARY KEY,
      user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      keys_json TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS settings (
      key TEXT PRIMARY KEY,
      value TEXT NOT NULL
    );

    -- Móviles de la familia (app Cerca Familia) que reciben los avisos por Firebase
    CREATE TABLE IF NOT EXISTS fcm_tokens (
      token TEXT PRIMARY KEY,
      user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      updated_at INTEGER NOT NULL
    );
  `);

  // Migraciones de columnas en bases de datos ya creadas
  const seniorCols = db.prepare(`PRAGMA table_info(seniors)`).all().map((c) => c.name);
  if (!seniorCols.includes('fcm_token')) db.exec(`ALTER TABLE seniors ADD COLUMN fcm_token TEXT`); // para despertar al móvil del mayor
  return db;
}

export const DEFAULT_CONFIG = Object.freeze({
  contacts: [],                 // [{ id, name, phone, photo, whatsapp }]
  sosContactId: null,
  calls: {
    filter: 'silence_unknown',  // all | silence_unknown | block_unknown
    allowRepeated: true,        // un desconocido que insiste 2 veces en 5 min pasa (urgencias)
    autoAnswerOnCall: 0,        // si un favorito llama N veces seguidas sin respuesta, la N-ésima se contesta sola en manos libres (0 = no)
    ringtone: 'system',         // timbre (src/ringtones.js); "system" = el que ya tenga el móvil
    ringMax: false,             // timbre siempre a todo volumen
  },
  location: {
    mode: 'off',                // off | continuous | interval | zone
    continuousSeconds: 30,
    intervalMinutes: 15,
    outsideZoneSeconds: 60,     // en modo zona: frecuencia mientras está fuera
    zones: [],                  // [{ id, name, lat, lng, radius }]
    retentionDays: 7,
  },
  blockedApps: [],              // paquetes ocultos en "Aplicaciones" (por defecto se ven todas)
  ui: {
    fontScale: 1.0,
    highContrast: false,
    showDate: true,
    showContacts: true,         // botón "Contactos" (agenda del móvil) en el inicio
    showMenuButton: true,       // rueda ⚙ del "Menú de la familia" junto al título de "Aplicaciones"
    showSms: false,             // botón "Mensajes" (SMS) en el inicio y "Mensaje" en cada contacto
  },
  pinHash: null,                // sha256(`cerca:${seniorId}:${pin}`)
  alerts: {
    lowBatteryPercent: 15,
    offlineHours: 6,
  },
});

export function mergeConfig(base, patch) {
  const out = structuredClone(base);
  for (const [k, v] of Object.entries(patch ?? {})) {
    if (v && typeof v === 'object' && !Array.isArray(v) && out[k] && typeof out[k] === 'object' && !Array.isArray(out[k])) {
      out[k] = mergeConfig(out[k], v);
    } else {
      out[k] = v;
    }
  }
  return out;
}
