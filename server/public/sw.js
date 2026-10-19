// Service worker: solo para notificaciones push del panel
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()));

self.addEventListener('push', (event) => {
  const data = event.data ? event.data.json() : { title: 'Cerca', body: '' };
  event.waitUntil(self.registration.showNotification(data.title, {
    body: data.body,
    icon: '/icon.svg',
    badge: '/icon.svg',
    tag: `cerca-${data.data?.seniorId ?? ''}-${data.data?.alertId ?? Date.now()}`,
    requireInteraction: data.title.includes('SOS'),
    data: data.data,
  }));
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const id = event.notification.data?.seniorId;
  const url = id ? `/#/s/${id}/avisos` : '/';
  event.waitUntil((async () => {
    const wins = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    for (const w of wins) { await w.focus(); return w.navigate(url); }
    return self.clients.openWindow(url);
  })());
});
