// ASHA Care service worker (ADR-017). Caches the app's own files so it opens without a connection.
// It never touches API or login traffic: only same-origin GETs under /asha/ are answered here, so
// no response containing anything about a person is ever cached. Visit data lives in store.js,
// encrypted.
"use strict";

const VERSION = "asha-shell-v3";
const SHELL = ["./", "index.html", "app.js", "i18n.js", "store.js", "voice.js", "pcm-worklet.js", "app.css",
  "manifest.webmanifest", "icon.svg", "settings.json"];

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(VERSION).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (event) => {
  event.waitUntil(caches.keys()
    .then((keys) => Promise.all(keys.filter((k) => k !== VERSION).map((k) => caches.delete(k))))
    .then(() => self.clients.claim()));
});

self.addEventListener("fetch", (event) => {
  const url = new URL(event.request.url);
  const scope = new URL(self.registration.scope);
  if (event.request.method !== "GET" || url.origin !== scope.origin || !url.pathname.startsWith(scope.pathname)) return;
  // Network first, so a connected device always gets the current build; the cache is the fallback.
  // A login redirect carries ?code=…&state=…, which is never stored: the page is cached without it.
  // Only the app page itself is cached as the shell: opening another file directly never replaces it.
  const page = url.pathname === scope.pathname || url.pathname === scope.pathname + "index.html";
  if (event.request.mode === "navigate" && !page) return;
  const key = page ? new URL("index.html", scope).href : url.origin + url.pathname;
  event.respondWith(fetch(event.request)
    .then((response) => {
      if (response.ok && response.type === "basic") {
        const copy = response.clone();
        caches.open(VERSION).then((c) => c.put(key, copy));
      }
      return response;
    })
    .catch(() => caches.match(key).then((hit) => hit || Response.error())));
});
