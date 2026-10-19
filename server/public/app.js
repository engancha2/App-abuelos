// Cerca · Panel familiar (vanilla JS, sin build)
const $ = (sel, root = document) => root.querySelector(sel);
const view = $('#view');
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

const state = {
  me: null,
  meta: null,
  senior: null,     // detalle de la persona abierta
  draft: null,      // copia editable de la config
  dirty: false,
  tab: 'mapa',
  maps: [],
  poll: null,
};

// ---------------------------------------------------------------- API
async function api(method, url, body) {
  const opts = { method, headers: {} };
  if (body instanceof FormData) opts.body = body;
  else if (body !== undefined) { opts.body = JSON.stringify(body); opts.headers['content-type'] = 'application/json'; }
  const res = await fetch(url, opts);
  const data = res.status === 204 ? null : await res.json().catch(() => null);
  if (res.status === 401 && !url.startsWith('/api/auth') && url !== '/api/me') { state.me = null; location.hash = '#/login'; throw new Error('Sesión caducada'); }
  if (!res.ok) throw new Error(data?.error ?? `Error ${res.status}`);
  return data;
}

function toast(msg, error = false) {
  const t = $('#toast');
  t.textContent = msg;
  t.className = `toast${error ? ' error' : ''}`;
  t.hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => (t.hidden = true), error ? 5000 : 2500);
}

const ago = (ts) => {
  if (!ts) return 'nunca';
  const s = Math.round((Date.now() - ts) / 1000);
  if (s < 60) return 'hace un momento';
  if (s < 3600) return `hace ${Math.round(s / 60)} min`;
  if (s < 86400) return `hace ${Math.round(s / 3600)} h`;
  return new Date(ts).toLocaleString('es-ES', { dateStyle: 'short', timeStyle: 'short' });
};
const timeFmt = (ts) => new Date(ts).toLocaleString('es-ES', { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
const uid = () => Math.random().toString(36).slice(2, 10);

// ---------------------------------------------------------------- Router
window.addEventListener('hashchange', route);
$('#back').onclick = () => { if (confirmLeave()) location.hash = '#/'; };
$('#logout').onclick = async () => { await api('POST', '/api/auth/logout'); state.me = null; location.hash = '#/login'; };
$('#save').onclick = saveConfig;
$('#discard').onclick = () => { state.draft = structuredClone(state.senior.config); setDirty(false); renderDetail(); };
window.addEventListener('beforeunload', (e) => { if (state.dirty) e.preventDefault(); });

function confirmLeave() {
  return !state.dirty || confirm('Tienes cambios sin guardar. ¿Salir sin guardar?');
}

function cleanup() {
  state.maps.forEach((m) => m.remove());
  state.maps = [];
  clearInterval(state.poll);
  state.poll = null;
}

async function route() {
  cleanup();
  if (!state.meta) state.meta = await api('GET', '/api/meta');
  if (!state.me) {
    try { state.me = await api('GET', '/api/me'); } catch { state.me = null; }
  }
  const hash = location.hash || '#/';
  if (!state.me) return renderAuth(hash === '#/register' ? 'register' : 'login');

  $('#logout').hidden = false;
  const m = hash.match(/^#\/s\/(\d+)(?:\/(\w+))?/);
  if (m) {
    const id = Number(m[1]);
    if (!state.senior || state.senior.id !== id) {
      setDirty(false);
      state.senior = await api('GET', `/api/seniors/${id}`);
      state.draft = structuredClone(state.senior.config);
    }
    state.tab = m[2] ?? 'mapa';
    return renderDetail();
  }
  state.senior = null;
  setDirty(false);
  return renderHome();
}

function setTitle(text, back) {
  $('#title').textContent = text;
  $('#back').hidden = !back;
}

function setDirty(d) {
  state.dirty = d;
  $('#savebar').hidden = !d;
}

// ---------------------------------------------------------------- Auth
function renderAuth(mode) {
  setTitle('Cerca', false);
  $('#logout').hidden = true;
  const reg = mode === 'register';
  view.innerHTML = `
    <div class="auth card">
      <h2>${reg ? 'Crear cuenta' : 'Panel familiar'}</h2>
      <p class="hint" style="text-align:center">Configura el móvil de tu familiar y recibe avisos.</p>
      <form id="authform">
        ${reg ? `<label class="field"><span>Tu nombre</span><input name="name" type="text" required autocomplete="name"></label>` : ''}
        <label class="field"><span>Email</span><input name="email" type="email" required autocomplete="email"></label>
        <label class="field"><span>Contraseña</span><input name="password" type="password" required minlength="${reg ? 8 : 1}" autocomplete="${reg ? 'new-password' : 'current-password'}"></label>
        <button class="btn primary" style="width:100%">${reg ? 'Crear cuenta' : 'Entrar'}</button>
      </form>
      ${state.meta.allowRegistration ? `<p style="text-align:center"><a href="${reg ? '#/login' : '#/register'}">${reg ? 'Ya tengo cuenta' : 'Crear una cuenta'}</a></p>` : ''}
    </div>`;
  $('#authform').onsubmit = async (e) => {
    e.preventDefault();
    const body = Object.fromEntries(new FormData(e.target));
    try {
      await api('POST', reg ? '/api/auth/register' : '/api/auth/login', body);
      state.me = await api('GET', '/api/me');
      location.hash = '#/';
      route();
      setupPush(false);
    } catch (err) { toast(err.message, true); }
  };
}

// ---------------------------------------------------------------- Inicio
async function renderHome() {
  setTitle('Cerca', false);
  const seniors = await api('GET', '/api/seniors');
  view.innerHTML = `
    ${seniors.length ? '' : `<div class="empty"><p style="font-size:2.5rem;margin:0">👵👴</p><p>Añade a tu familiar para empezar.</p></div>`}
    ${seniors.map((s) => {
      const st = s.status;
      return `
      <div class="card clickable" data-id="${s.id}">
        <div class="row">
          <span class="dot ${s.online ? 'on' : ''}" title="${s.online ? 'Conectado' : 'Sin conexión en vivo'}"></span>
          <div class="grow">
            <b>${esc(s.name)}</b>
            <div class="muted" style="font-size:.9rem">
              ${s.paired ? `Visto ${ago(s.lastSeen)}${st?.battery != null ? ` · 🔋 ${st.battery}%${st.charging ? ' ⚡' : ''}` : ''}` : 'Móvil sin emparejar'}
            </div>
          </div>
          ${s.pendingAlerts ? `<span class="badge">${s.pendingAlerts}</span>` : ''}
        </div>
      </div>`;
    }).join('')}
    <form id="addform" class="card row">
      <input name="name" type="text" placeholder="Nombre (p. ej. Abuela Rosa)" class="grow" required>
      <button class="btn primary">Añadir</button>
    </form>
    <div id="pushbox"></div>`;
  view.querySelectorAll('[data-id]').forEach((el) => (el.onclick = () => (location.hash = `#/s/${el.dataset.id}`)));
  $('#addform').onsubmit = async (e) => {
    e.preventDefault();
    try {
      const { id } = await api('POST', '/api/seniors', { name: new FormData(e.target).get('name') });
      location.hash = `#/s/${id}/ajustes`;
    } catch (err) { toast(err.message, true); }
  };
  renderPushBox();
}

// ---------------------------------------------------------------- Notificaciones push
async function renderPushBox() {
  const box = $('#pushbox');
  if (!box || !('serviceWorker' in navigator) || !('PushManager' in window)) return;
  const reg = await navigator.serviceWorker.register('/sw.js');
  const sub = await reg.pushManager.getSubscription();
  if (sub && Notification.permission === 'granted') { await sendSub(sub); return; }
  box.innerHTML = `
    <div class="card row">
      <div class="grow"><b>Activa los avisos en este dispositivo</b><div class="muted" style="font-size:.9rem">SOS, salidas de zona, batería baja…</div></div>
      <button class="btn primary" id="pushbtn">Activar</button>
    </div>`;
  box.querySelector('#pushbtn').onclick = () => setupPush(true);
}

async function setupPush(ask) {
  if (!('serviceWorker' in navigator) || !('PushManager' in window)) return;
  if (!ask && Notification.permission !== 'granted') return;
  try {
    if ((await Notification.requestPermission()) !== 'granted') return toast('No has dado permiso de notificaciones', true);
    const reg = await navigator.serviceWorker.register('/sw.js');
    await navigator.serviceWorker.ready;
    const sub = (await reg.pushManager.getSubscription()) ?? await reg.pushManager.subscribe({
      userVisibleOnly: true, applicationServerKey: b64ToBytes(state.meta.vapidPublicKey),
    });
    await sendSub(sub);
    if (ask) { toast('Avisos activados'); const box = $('#pushbox'); if (box) box.innerHTML = ''; }
  } catch (err) { toast(`No se pudieron activar los avisos: ${err.message}`, true); }
}
const sendSub = (sub) => api('POST', '/api/push/subscribe', sub.toJSON());
function b64ToBytes(b64) {
  const s = atob((b64 + '='.repeat((4 - (b64.length % 4)) % 4)).replace(/-/g, '+').replace(/_/g, '/'));
  return Uint8Array.from(s, (c) => c.charCodeAt(0));
}

// ---------------------------------------------------------------- Detalle
const TABS = [
  ['mapa', 'Mapa'], ['avisos', 'Avisos'], ['contactos', 'Contactos'], ['llamadas', 'Llamadas'],
  ['ubicacion', 'Ubicación'], ['apps', 'Apps'], ['ajustes', 'Ajustes'],
];

function renderDetail() {
  cleanup();
  const s = state.senior;
  setTitle(s.name, true);
  view.innerHTML = `
    <nav class="tabs" role="tablist">
      ${TABS.map(([k, label]) => `<button role="tab" data-tab="${k}" aria-selected="${state.tab === k}">${label}${k === 'avisos' && s.pendingAlerts ? ` <span class="badge">${s.pendingAlerts}</span>` : ''}</button>`).join('')}
    </nav>
    <section id="tab"></section>`;
  view.querySelectorAll('[data-tab]').forEach((b) => (b.onclick = () => (location.hash = `#/s/${s.id}/${b.dataset.tab}`)));
  const tab = $('#tab');
  ({ mapa: tabMap, avisos: tabAlerts, contactos: tabContacts, llamadas: tabCalls, ubicacion: tabLocation, apps: tabApps, ajustes: tabSettings })[state.tab]?.(tab);
}

const change = (fn) => { fn(state.draft); setDirty(true); };

async function saveConfig() {
  try {
    await api('PUT', `/api/seniors/${state.senior.id}/config`, state.draft);
    await reloadSenior();
    setDirty(false);
    toast(state.senior.online ? 'Guardado y enviado al móvil' : 'Guardado. Le llegará al móvil cuando se conecte');
    renderDetail();
  } catch (err) { toast(err.message, true); }
}

async function reloadSenior() {
  state.senior = await api('GET', `/api/seniors/${state.senior.id}`);
  if (!state.dirty) state.draft = structuredClone(state.senior.config);
}

function newMap(el, center = [40.4168, -3.7038], zoom = 6) {
  const map = L.map(el).setView(center, zoom);
  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19, attribution: '&copy; OpenStreetMap',
  }).addTo(map);
  state.maps.push(map);
  return map;
}

function drawZones(map, zones, opts = {}) {
  return zones.map((z) => L.circle([z.lat, z.lng], { radius: z.radius, color: '#1f6f5c', weight: 2, fillOpacity: 0.12, ...opts })
    .bindTooltip(esc(z.name)).addTo(map));
}

// ---- Mapa
async function tabMap(el) {
  const s = state.senior;
  if (!s.paired) {
    el.innerHTML = `<div class="card empty">Primero empareja el móvil desde <a href="#/s/${s.id}/ajustes">Ajustes</a>.</div>`;
    return;
  }
  el.innerHTML = `
    <div class="card row">
      <div class="grow" id="mapstatus"></div>
      <button class="btn primary" id="locate">¿Dónde está ahora?</button>
    </div>
    <div id="map" class="map"></div>
    <div class="row">
      <label class="field grow" style="margin:0"><span>Recorrido</span>
        <select id="range">
          <option value="3">Últimas 3 horas</option>
          <option value="24" selected>Últimas 24 horas</option>
          <option value="72">Últimos 3 días</option>
          <option value="168">Última semana</option>
        </select>
      </label>
    </div>
    <p class="hint">Modo actual: <b>${modeLabel(s.config.location)}</b>. Lo cambias en <a href="#/s/${s.id}/ubicacion">Ubicación</a>.</p>`;

  const map = newMap($('#map'));
  drawZones(map, s.config.location.zones);
  let marker = null, accuracy = null, track = null, first = true;

  const refresh = async () => {
    await reloadSenior();
    const hours = Number($('#range')?.value ?? 24);
    const pts = await api('GET', `/api/seniors/${s.id}/locations?since=${Date.now() - hours * 3600_000}`);
    const last = state.senior.lastLocation;
    const st = state.senior.status;
    $('#mapstatus').innerHTML = `
      <div class="row" style="gap:8px">
        <span class="dot ${state.senior.online ? 'on' : ''}"></span>
        <b>${state.senior.online ? 'Conectado' : `Visto ${ago(state.senior.lastSeen)}`}</b>
        ${st?.battery != null ? `<span class="pill">🔋 ${st.battery}%${st.charging ? ' ⚡' : ''}</span>` : ''}
      </div>
      <div class="muted" style="font-size:.9rem">${last ? `Última posición ${ago(last.ts)}${last.accuracy ? ` · ±${Math.round(last.accuracy)} m` : ''}` : 'Aún no hay posiciones'}</div>`;
    track?.remove();
    track = pts.length > 1 ? L.polyline(pts.map((p) => [p.lat, p.lng]), { color: '#d9822b', weight: 3, opacity: 0.7 }).addTo(map) : null;
    if (last) {
      const ll = [last.lat, last.lng];
      if (!marker) marker = L.marker(ll).addTo(map); else marker.setLatLng(ll);
      marker.bindPopup(`<b>${esc(s.name)}</b><br>${timeFmt(last.ts)}`);
      accuracy?.remove();
      if (last.accuracy) accuracy = L.circle(ll, { radius: last.accuracy, color: '#3b82f6', weight: 1, fillOpacity: 0.1 }).addTo(map);
      if (first) { map.setView(ll, 16); first = false; }
    } else if (first && s.config.location.zones[0]) {
      map.setView([s.config.location.zones[0].lat, s.config.location.zones[0].lng], 15); first = false;
    }
  };
  $('#range').onchange = refresh;
  $('#locate').onclick = async () => {
    try {
      const r = await api('POST', `/api/seniors/${s.id}/locate`);
      toast(r.live ? 'Pidiendo la ubicación… aparecerá en unos segundos' : 'El móvil no está conectado ahora; se la pediremos en cuanto se conecte');
    } catch (err) { toast(err.message, true); }
  };
  await refresh();
  state.poll = setInterval(() => refresh().catch(() => {}), 10_000);
}

function modeLabel(loc) {
  return {
    off: 'Desactivada',
    continuous: `Continua (cada ${loc.continuousSeconds} s)`,
    interval: `Cada ${loc.intervalMinutes} min`,
    zone: 'Solo si sale de una zona',
  }[loc.mode];
}

// ---- Avisos
async function tabAlerts(el) {
  const s = state.senior;
  const alerts = await api('GET', `/api/seniors/${s.id}/alerts`);
  const icons = { sos: '🆘', zone_exit: '📍', zone_enter: '🏠', low_battery: '🔋', offline: '📵', setup_problem: '⚠️' };
  el.innerHTML = `
    ${alerts.some((a) => !a.acknowledged) ? `<button class="btn" id="ack" style="margin-bottom:12px">Marcar todo como visto</button>` : ''}
    ${alerts.length ? '' : '<div class="card empty">Sin avisos. ¡Todo tranquilo!</div>'}
    ${alerts.map((a) => `
      <div class="card alert ${a.acknowledged ? '' : 'unread'}">
        <span class="ico">${icons[a.type] ?? '🔔'}</span>
        <div class="grow">
          <div>${esc(a.message)}</div>
          <div class="muted" style="font-size:.88rem">${timeFmt(a.ts)}
            ${a.lat != null ? ` · <a href="https://www.google.com/maps?q=${a.lat},${a.lng}" target="_blank" rel="noopener">Ver en Google Maps</a>` : ''}</div>
        </div>
      </div>`).join('')}`;
  const ack = $('#ack');
  if (ack) ack.onclick = async () => { await api('POST', `/api/seniors/${s.id}/alerts/ack`); await reloadSenior(); renderDetail(); };
}

// ---- Contactos
function tabContacts(el) {
  const c = state.draft;
  el.innerHTML = `
    <p class="hint">Aparecen como botones grandes en la pantalla de inicio, en este orden. Máximo 12; lo ideal son 4–6.</p>
    ${c.contacts.length ? '' : '<div class="card empty">Aún no hay contactos.</div>'}
    ${c.contacts.map((x, i) => `
      <div class="card">
        <div class="contact">
          ${x.photo ? `<img class="avatar" src="${esc(x.photo)}" alt="">` : `<span class="avatar">${esc(x.name[0] ?? '?')}</span>`}
          <div class="grow">
            <b>${esc(x.name)}</b> ${c.sosContactId === x.id ? '<span class="pill bad">SOS</span>' : ''}
            <div class="muted">${esc(x.phone)}${x.whatsapp ? ' · WhatsApp' : ''}</div>
          </div>
        </div>
        <div class="contact actions" style="margin-top:10px">
          <button class="btn small" data-act="up" data-i="${i}" ${i === 0 ? 'disabled' : ''}>↑</button>
          <button class="btn small" data-act="down" data-i="${i}" ${i === c.contacts.length - 1 ? 'disabled' : ''}>↓</button>
          <label class="btn small">Foto<input type="file" accept="image/*" data-act="photo" data-i="${i}" hidden></label>
          <button class="btn small" data-act="edit" data-i="${i}">Editar</button>
          ${c.sosContactId === x.id ? '' : `<button class="btn small" data-act="sos" data-i="${i}">Usar para SOS</button>`}
          <button class="btn small danger" data-act="del" data-i="${i}">Quitar</button>
        </div>
      </div>`).join('')}
    <form id="cform" class="card">
      <h2 id="cformtitle">Añadir contacto</h2>
      <input type="hidden" name="index" value="-1">
      <label class="field"><span>Nombre que verá (corto: “Xavi”, “Marta hija”)</span><input name="name" type="text" maxlength="40" required></label>
      <label class="field"><span>Teléfono</span><input name="phone" type="tel" required placeholder="+34 600 000 000"></label>
      <label class="check"><input type="checkbox" name="whatsapp"><span>Tiene WhatsApp<small>Muestra también un botón para abrir su chat.</small></span></label>
      <div class="row"><button class="btn primary">Guardar contacto</button><button type="button" class="btn ghost" id="ccancel" hidden>Cancelar</button></div>
    </form>`;

  el.querySelectorAll('[data-act]').forEach((b) => {
    const i = Number(b.dataset.i);
    if (b.dataset.act === 'photo') {
      b.onchange = async () => {
        if (!b.files[0]) return;
        try {
          const fd = new FormData();
          fd.append('photo', await resizeImage(b.files[0], 512), 'photo.jpg');
          const { photo } = await api('POST', `/api/seniors/${state.senior.id}/photo`, fd);
          change((d) => (d.contacts[i].photo = photo));
          tabContacts(el);
        } catch (err) { toast(err.message, true); }
      };
      return;
    }
    b.onclick = () => {
      const list = state.draft.contacts;
      switch (b.dataset.act) {
        case 'up': change(() => ([list[i - 1], list[i]] = [list[i], list[i - 1]])); break;
        case 'down': change(() => ([list[i + 1], list[i]] = [list[i], list[i + 1]])); break;
        case 'sos': change((d) => (d.sosContactId = list[i].id)); break;
        case 'del':
          if (!confirm(`¿Quitar a ${list[i].name}?`)) return;
          change((d) => { const [gone] = d.contacts.splice(i, 1); if (d.sosContactId === gone.id) d.sosContactId = d.contacts[0]?.id ?? null; });
          break;
        case 'edit': {
          const f = $('#cform');
          f.index.value = i; f.name.value = list[i].name; f.phone.value = list[i].phone; f.whatsapp.checked = list[i].whatsapp;
          $('#cformtitle').textContent = `Editar a ${list[i].name}`;
          $('#ccancel').hidden = false;
          f.scrollIntoView({ behavior: 'smooth' });
          return;
        }
      }
      tabContacts(el);
    };
  });
  $('#ccancel').onclick = () => tabContacts(el);
  $('#cform').onsubmit = (e) => {
    e.preventDefault();
    const f = e.target;
    const i = Number(f.index.value);
    const data = { name: f.name.value.trim(), phone: f.phone.value.trim(), whatsapp: f.whatsapp.checked };
    if (i < 0 && state.draft.contacts.length >= 12) return toast('Máximo 12 contactos', true);
    change((d) => {
      if (i >= 0) Object.assign(d.contacts[i], data);
      else d.contacts.push({ id: uid(), photo: null, ...data });
      if (!d.sosContactId) d.sosContactId = d.contacts[0].id;
    });
    tabContacts(el);
  };
}

function resizeImage(file, size) {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => {
      const side = Math.min(img.width, img.height);  // recorte cuadrado centrado
      const canvas = document.createElement('canvas');
      canvas.width = canvas.height = size;
      canvas.getContext('2d').drawImage(img, (img.width - side) / 2, (img.height - side) / 2, side, side, 0, 0, size, size);
      canvas.toBlob((b) => (b ? resolve(b) : reject(new Error('No se pudo procesar la foto'))), 'image/jpeg', 0.85);
      URL.revokeObjectURL(img.src);
    };
    img.onerror = () => reject(new Error('No se pudo leer la imagen'));
    img.src = URL.createObjectURL(file);
  });
}

// ---- Llamadas
function tabCalls(el) {
  const c = state.draft.calls;
  const opt = (v, title, desc) => `
    <label class="option"><input type="radio" name="filter" value="${v}" ${c.filter === v ? 'checked' : ''}>
      <span><b>${title}</b><small>${desc}</small></span></label>`;
  el.innerHTML = `
    <h2>¿Quién puede llamar?</h2>
    <p class="hint">Se aplica a las llamadas entrantes. Los contactos del inicio siempre suenan.</p>
    ${opt('all', 'Todo el mundo', 'Suenan todas las llamadas, como en un móvil normal.')}
    ${opt('silence_unknown', 'Desconocidos en silencio', 'Las llamadas de números que no están en Contactos entran sin sonar. Quedan en el registro.')}
    ${opt('block_unknown', 'Solo contactos', 'Se rechazan las llamadas de números desconocidos. La mejor protección contra estafas telefónicas.')}
    <div class="card">
      <label class="check"><input type="checkbox" id="rep" ${c.allowRepeated ? 'checked' : ''}>
        <span>Dejar pasar si insiste<small>Si un número desconocido llama 2 veces en 5 minutos, la segunda suena (por si es una urgencia: médico, hospital, vecino…).</small></span></label>
    </div>`;
  el.querySelectorAll('[name=filter]').forEach((r) => (r.onchange = () => change((d) => (d.calls.filter = r.value))));
  $('#rep').onchange = (e) => change((d) => (d.calls.allowRepeated = e.target.checked));
}

// ---- Ubicación
function tabLocation(el) {
  state.maps.forEach((m) => m.remove());
  state.maps = [];
  const l = state.draft.location;
  const opt = (v, title, desc, extra = '') => `
    <label class="option"><input type="radio" name="mode" value="${v}" ${l.mode === v ? 'checked' : ''}>
      <span style="flex:1"><b>${title}</b><small>${desc}</small>${l.mode === v ? extra : ''}</span></label>`;
  el.innerHTML = `
    <h2>¿Cuándo envía su ubicación?</h2>
    ${opt('zone', 'Solo si sale de una zona', 'Avisa cuando sale de casa, del barrio… y mientras esté fuera envía su posición con frecuencia. Lo que menos batería gasta.',
      `<label class="field" style="margin-top:10px"><span>Mientras está fuera, cada (segundos)</span><input type="number" id="outside" min="15" max="600" value="${l.outsideZoneSeconds}"></label>`)}
    ${opt('interval', 'Cada cierto tiempo', 'Envía su posición periódicamente, esté donde esté.',
      `<label class="field" style="margin-top:10px"><span>Cada (minutos)</span><input type="number" id="interval" min="1" max="240" value="${l.intervalMinutes}"></label>`)}
    ${opt('continuous', 'Continuamente', 'Seguimiento casi en tiempo real. Gasta bastante batería: útil en momentos concretos (si se ha perdido).',
      `<label class="field" style="margin-top:10px"><span>Cada (segundos)</span><input type="number" id="cont" min="10" max="300" value="${l.continuousSeconds}"></label>`)}
    ${opt('off', 'Desactivada', 'No se envía ninguna ubicación. El SOS sí incluye la posición si la hay.')}

    <h2>Zonas seguras</h2>
    <p class="hint">Toca el mapa para añadir una zona. En los modos "cada cierto tiempo" y "continuo" también te avisa si sale de ellas.</p>
    <div id="zmap" class="map small"></div>
    <div id="zlist"></div>

    <h2>Privacidad</h2>
    <label class="field"><span>Borrar el historial de posiciones pasados (días)</span>
      <input type="number" id="retention" min="1" max="90" value="${l.retentionDays}"></label>`;

  el.querySelectorAll('[name=mode]').forEach((r) => (r.onchange = () => { change((d) => (d.location.mode = r.value)); tabLocation(el); }));
  const num = (id, key) => { const i = $(`#${id}`); if (i) i.onchange = () => change((d) => (d.location[key] = Number(i.value))); };
  num('outside', 'outsideZoneSeconds'); num('interval', 'intervalMinutes'); num('cont', 'continuousSeconds'); num('retention', 'retentionDays');

  const last = state.senior.lastLocation;
  const center = l.zones[0] ? [l.zones[0].lat, l.zones[0].lng] : last ? [last.lat, last.lng] : undefined;
  const map = newMap($('#zmap'), center, center ? 15 : 6);
  let circles = [];
  const renderZones = () => {
    circles.forEach((c) => c.remove());
    circles = drawZones(map, l.zones);
    $('#zlist').innerHTML = l.zones.map((z, i) => `
      <div class="card row">
        <div class="grow"><b>${esc(z.name)}</b><div class="muted" style="font-size:.9rem">Radio ${z.radius} m</div></div>
        <input type="number" min="100" max="20000" step="50" value="${z.radius}" data-r="${i}" style="width:110px" aria-label="Radio en metros">
        <button class="btn small danger" data-z="${i}">Quitar</button>
      </div>`).join('') || '<div class="card empty">Sin zonas todavía.</div>';
    el.querySelectorAll('[data-z]').forEach((b) => (b.onclick = () => { change((d) => d.location.zones.splice(Number(b.dataset.z), 1)); renderZones(); }));
    el.querySelectorAll('[data-r]').forEach((inp) => (inp.onchange = () => {
      change((d) => (d.location.zones[Number(inp.dataset.r)].radius = Math.max(100, Number(inp.value)))); renderZones();
    }));
  };
  map.on('click', (e) => {
    const name = prompt('Nombre de la zona (p. ej. Casa, Barrio, Centro de día):', l.zones.length ? '' : 'Casa');
    if (!name) return;
    change((d) => d.location.zones.push({ id: uid(), name: name.slice(0, 40), lat: e.latlng.lat, lng: e.latlng.lng, radius: 300 }));
    renderZones();
  });
  renderZones();
}

// ---- Apps
function tabApps(el) {
  const apps = state.senior.apps;
  const sel = new Set(state.draft.apps);
  el.innerHTML = `
    <p class="hint">Elige qué aplicaciones aparecen en “Mis aplicaciones” (máximo 12). Las demás quedan ocultas.</p>
    ${apps.length ? '' : '<div class="card empty">El móvil aún no ha enviado su lista de aplicaciones. Aparecerá cuando se conecte.</div>'}
    <div class="card">
      ${apps.map((a) => `
        <label class="check"><input type="checkbox" value="${esc(a.pkg)}" ${sel.has(a.pkg) ? 'checked' : ''}>
          <span>${esc(a.label)}<small>${esc(a.pkg)}</small></span></label>`).join('')}
    </div>`;
  el.querySelectorAll('input[type=checkbox]').forEach((cb) => (cb.onchange = () => {
    if (cb.checked && state.draft.apps.length >= 12) { cb.checked = false; return toast('Máximo 12 aplicaciones', true); }
    change((d) => { d.apps = d.apps.filter((p) => p !== cb.value); if (cb.checked) d.apps.push(cb.value); });
  }));
}

// ---- Ajustes
function tabSettings(el) {
  const s = state.senior;
  const d = state.draft;
  const st = s.status;
  const pill = (ok, good, bad) => `<span class="pill ${ok ? 'good' : 'bad'}">${ok ? good : bad}</span>`;
  el.innerHTML = `
    <h2>Móvil</h2>
    <div class="card stack">
      ${s.paired ? `
        <div class="row"><span class="dot ${s.online ? 'on' : ''}"></span><b class="grow">${esc(s.device?.model ?? 'Móvil emparejado')}</b><span class="muted">Visto ${ago(s.lastSeen)}</span></div>
        ${st ? `<div class="row" style="gap:6px">
          ${pill(st.isDefaultHome, 'Pantalla de inicio ✓', 'No es la pantalla de inicio')}
          ${pill(st.isDefaultDialer, 'App de teléfono ✓', 'No es la app de teléfono')}
          ${d.location.mode === 'off' ? '' : pill(st.locationPermission === 'always' && st.locationEnabled, 'Ubicación ✓', 'Problema con la ubicación')}
          ${st.appVersion ? `<span class="pill">v${esc(st.appVersion)}</span>` : ''}
        </div>` : ''}
        <div class="row"><button class="btn" id="newcode">Emparejar otro móvil</button><button class="btn danger" id="unpair">Desemparejar</button></div>
      ` : `
        <p style="margin:0">Instala la app <b>Cerca</b> en su móvil y, en el asistente, introduce este código:</p>
        <div id="codebox">${s.pairCode ? `<div class="paircode">${s.pairCode}</div><p class="hint" style="text-align:center">Caduca ${ago(s.pairExpires).replace('hace', 'en')}</p>` : ''}</div>
        <button class="btn primary" id="newcode">${s.pairCode ? 'Generar otro código' : 'Generar código'}</button>
      `}
    </div>

    <h2>Pantalla</h2>
    <div class="card">
      <label class="field"><span>Tamaño del texto: <b id="fsv">${Math.round(d.ui.fontScale * 100)}%</b></span>
        <input type="range" id="fs" min="0.8" max="1.6" step="0.1" value="${d.ui.fontScale}" style="width:100%"></label>
      <label class="check"><input type="checkbox" id="hc" ${d.ui.highContrast ? 'checked' : ''}><span>Alto contraste<small>Fondo negro y letras blancas, para quien ve poco.</small></span></label>
      <label class="check"><input type="checkbox" id="sd" ${d.ui.showDate ? 'checked' : ''}><span>Mostrar la fecha completa bajo la hora</span></label>
    </div>

    <h2>PIN de ajustes</h2>
    <p class="hint">Protege la salida del launcher y los ajustes del móvil. ${s.config.hasPin ? 'Ya hay un PIN puesto.' : '<b>Aún no hay PIN: cualquiera puede salir del launcher.</b>'}</p>
    <form id="pinform" class="card row">
      <input type="password" inputmode="numeric" pattern="\\d{4,8}" name="pin" placeholder="4 a 8 números" class="grow" required autocomplete="new-password">
      <button class="btn primary">${s.config.hasPin ? 'Cambiar PIN' : 'Poner PIN'}</button>
    </form>

    <h2>Avisos</h2>
    <div class="card">
      <label class="field"><span>Avisar con la batería por debajo de (%)</span><input type="number" id="lb" min="5" max="50" value="${d.alerts.lowBatteryPercent}"></label>
      <label class="field"><span>Avisar si el móvil no conecta en (horas)</span><input type="number" id="oh" min="1" max="72" value="${d.alerts.offlineHours}"></label>
    </div>

    <h2>Familiares con acceso</h2>
    <div class="card stack">
      ${s.members.map((m) => `<div class="row"><span class="grow">${esc(m.name)} <span class="muted">${esc(m.email)}</span></span>
        ${m.role === 'owner' ? '<span class="pill">Responsable</span>' : s.role === 'owner' ? `<button class="btn small danger" data-rm="${m.id}">Quitar</button>` : ''}</div>`).join('')}
      <form id="memform" class="row"><input type="email" name="email" placeholder="Email de otro familiar" class="grow" required><button class="btn">Invitar</button></form>
      <p class="hint" style="margin:0">La otra persona debe crear antes su cuenta en este panel.</p>
    </div>

    <h2>Nombre</h2>
    <form id="nameform" class="card row"><input type="text" name="name" value="${esc(s.name)}" class="grow" required maxlength="60"><button class="btn">Cambiar</button></form>

    ${s.role === 'owner' ? `<h2>Zona peligrosa</h2><div class="card"><button class="btn danger" id="del">Eliminar a ${esc(s.name)} y todos sus datos</button></div>` : ''}`;

  $('#newcode').onclick = async () => {
    if (s.paired && !confirm('El móvil actual dejará de funcionar con el panel en cuanto se empareje el nuevo. ¿Seguir?')) return;
    try {
      const { code, expires } = await api('POST', `/api/seniors/${s.id}/pair-code`);
      Object.assign(state.senior, { pairCode: code, pairExpires: expires, paired: false });
      tabSettings(el);
      pollPairing();
    } catch (err) { toast(err.message, true); }
  };
  const unpair = $('#unpair');
  if (unpair) unpair.onclick = async () => {
    if (!confirm('El móvil dejará de recibir cambios y de enviar ubicación. ¿Desemparejar?')) return;
    await api('POST', `/api/seniors/${s.id}/unpair`); await reloadSenior(); renderDetail();
  };
  if (!s.paired && s.pairCode) pollPairing();

  $('#fs').oninput = (e) => { $('#fsv').textContent = `${Math.round(e.target.value * 100)}%`; change((x) => (x.ui.fontScale = Number(e.target.value))); };
  $('#hc').onchange = (e) => change((x) => (x.ui.highContrast = e.target.checked));
  $('#sd').onchange = (e) => change((x) => (x.ui.showDate = e.target.checked));
  $('#lb').onchange = (e) => change((x) => (x.alerts.lowBatteryPercent = Number(e.target.value)));
  $('#oh').onchange = (e) => change((x) => (x.alerts.offlineHours = Number(e.target.value)));

  $('#pinform').onsubmit = async (e) => {
    e.preventDefault();
    try { await api('POST', `/api/seniors/${s.id}/pin`, { pin: e.target.pin.value }); e.target.reset(); await reloadSenior(); toast('PIN guardado'); tabSettings(el); }
    catch (err) { toast(err.message, true); }
  };
  $('#memform').onsubmit = async (e) => {
    e.preventDefault();
    try { await api('POST', `/api/seniors/${s.id}/members`, { email: e.target.email.value }); await reloadSenior(); toast('Familiar añadido'); tabSettings(el); }
    catch (err) { toast(err.message, true); }
  };
  el.querySelectorAll('[data-rm]').forEach((b) => (b.onclick = async () => {
    await api('DELETE', `/api/seniors/${s.id}/members/${b.dataset.rm}`); await reloadSenior(); tabSettings(el);
  }));
  $('#nameform').onsubmit = async (e) => {
    e.preventDefault();
    await api('PATCH', `/api/seniors/${s.id}`, { name: e.target.name.value }); await reloadSenior(); renderDetail();
  };
  const del = $('#del');
  if (del) del.onclick = async () => {
    if (prompt(`Escribe ELIMINAR para borrar a ${s.name} y todo su historial:`) !== 'ELIMINAR') return;
    await api('DELETE', `/api/seniors/${s.id}`); setDirty(false); state.senior = null; location.hash = '#/';
  };
}

function pollPairing() {
  clearInterval(state.poll);
  state.poll = setInterval(async () => {
    const fresh = await api('GET', `/api/seniors/${state.senior.id}`).catch(() => null);
    if (fresh?.paired) {
      clearInterval(state.poll);
      state.senior = fresh;
      if (!state.dirty) state.draft = structuredClone(fresh.config);
      toast('¡Móvil emparejado!');
      renderDetail();
    }
  }, 3000);
}

route();
