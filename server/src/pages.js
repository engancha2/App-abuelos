import fs from 'node:fs';
import path from 'node:path';

const escapeHtml = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

/**
 * Las páginas públicas (presentación, privacidad, borrar la cuenta) están en server/pages con huecos
 * {{ASI}} que se rellenan con los datos legales y de Google Play de las variables de entorno.
 * Un dato que falta se ve como [falta ASI], para que no se publique una página a medias sin darse cuenta.
 */
export function createPages({ dir, vars }) {
  const cache = new Map();
  return function render(name) {
    if (!cache.has(name)) {
      const html = fs.readFileSync(path.join(dir, `${name}.html`), 'utf8')
        .replace(/\{\{(\w+)\}\}/g, (_, key) => (vars[key] ? escapeHtml(vars[key]) : `[falta ${key}]`));
      cache.set(name, html);
    }
    return cache.get(name);
  };
}

/** Los datos de quien publica la app (RGPD) y de las apps en Google Play. */
export function pageVars(env) {
  return {
    LEGAL_NAME: env.LEGAL_NAME,          // titular: empresa o autónomo
    LEGAL_ID: env.LEGAL_ID,              // NIF / CIF
    LEGAL_ADDRESS: env.LEGAL_ADDRESS,    // domicilio
    LEGAL_EMAIL: env.LEGAL_EMAIL,        // contacto y privacidad
    HOSTING: env.HOSTING_PROVIDER,       // dónde está el servidor, p. ej. "Hetzner Online GmbH (Alemania)"
    SENIOR_PACKAGE: env.PLAY_SENIOR_PACKAGE ?? 'com.estudiofenix.cerca',
    FAMILY_PACKAGE: env.PLAY_PACKAGE_NAME ?? 'com.estudiofenix.cerca.familia',
    UPDATED: env.LEGAL_UPDATED ?? '7 de noviembre de 2026',
  };
}

/** Lo que falta por configurar antes de publicar (se avisa en el registro al arrancar). */
export const missingVars = (vars) => Object.entries(vars).filter(([, v]) => !v).map(([k]) => k);
