// ASHA Care offline store (ADR-017). Synthetic data only.
// Everything about a person is written to IndexedDB encrypted: AES-256-GCM with a random data key,
// which is itself wrapped by a key derived from the worker's PIN (PBKDF2-SHA-256). Each record has
// a fresh IV and its own ID as associated data, so records can't be swapped. Only the wrapped key,
// the salt and a failed-attempt counter are stored unencrypted, keyed by a hash of the worker's
// subject, so workers sharing a device have separate keys and records. No tokens are ever stored.
"use strict";

const OfflineStore = (() => {
  const DB_NAME = "asha-offline";
  const ITERATIONS = 600000;
  const MAX_ATTEMPTS = 5;
  const PIN_PATTERN = /^[0-9]{6,12}$/;
  const enc = new TextEncoder();
  const dec = new TextDecoder();

  class WrongPin extends Error {
    constructor(remaining) { super("wrong PIN"); this.remaining = remaining; }
  }
  class Wiped extends Error {}

  function open() {
    return new Promise((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, 1);
      req.onupgradeneeded = () => {
        const db = req.result;
        db.createObjectStore("meta", { keyPath: "owner" });
        db.createObjectStore("records", { keyPath: ["owner", "id"] });
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  }

  async function tx(stores, mode, work) {
    const db = await open();
    try {
      return await new Promise((resolve, reject) => {
        const t = db.transaction(stores, mode);
        let result;
        Promise.resolve(work(t)).then((r) => { result = r; }, reject);
        t.oncomplete = () => resolve(result);
        t.onerror = () => reject(t.error);
        t.onabort = () => reject(t.error || new Error("aborted"));
      });
    } finally {
      db.close();
    }
  }

  const req = (r) => new Promise((resolve, reject) => { r.onsuccess = () => resolve(r.result); r.onerror = () => reject(r.error); });

  /** A stable, non-reversible label for a worker's store. */
  async function ownerOf(subject) {
    const digest = await crypto.subtle.digest("SHA-256", enc.encode("asha-offline:" + subject));
    return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, "0")).join("");
  }

  async function pinKey(pin, salt) {
    const base = await crypto.subtle.importKey("raw", enc.encode(pin), "PBKDF2", false, ["deriveKey"]);
    return crypto.subtle.deriveKey({ name: "PBKDF2", hash: "SHA-256", salt, iterations: ITERATIONS }, base,
      { name: "AES-GCM", length: 256 }, false, ["wrapKey", "unwrapKey"]);
  }

  async function list() {
    return tx(["meta"], "readonly", (t) => req(t.objectStore("meta").getAll()))
      .then((rows) => rows.map((m) => ({ owner: m.owner, createdAt: m.createdAt, attemptsLeft: MAX_ATTEMPTS - m.failed })));
  }

  async function exists(owner) {
    return (await list()).some((m) => m.owner === owner);
  }

  /** Creates the worker's store. Fails if one exists: the PIN is never silently replaced. */
  async function create(owner, pin) {
    if (!PIN_PATTERN.test(pin)) throw new Error("The PIN must be 6 to 12 digits.");
    if (await exists(owner)) throw new Error("Offline data already exists for this worker on this device.");
    const salt = crypto.getRandomValues(new Uint8Array(16));
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const dataKey = await crypto.subtle.generateKey({ name: "AES-GCM", length: 256 }, true, ["encrypt", "decrypt"]);
    const wrapped = await crypto.subtle.wrapKey("raw", dataKey, await pinKey(pin, salt), { name: "AES-GCM", iv });
    await tx(["meta"], "readwrite", (t) => req(t.objectStore("meta").add({ owner, salt, iv, wrapped: new Uint8Array(wrapped),
      failed: 0, createdAt: new Date().toISOString() })));
    return attempt(owner, pin);
  }

  /**
   * Unwraps the data key. A wrong PIN counts an attempt; the last one wipes this worker's store.
   * Attempts are serialised, across tabs too, so overlapping guesses can't share one count (review W5).
   */
  function unlock(owner, pin) {
    const run = () => attempt(owner, pin);
    if (navigator.locks) return navigator.locks.request("asha-offline-unlock", run);
    const next = queue.then(run, run);
    queue = next.catch(() => {});
    return next;
  }
  let queue = Promise.resolve();

  async function attempt(owner, pin) {
    const meta = await tx(["meta"], "readonly", (t) => req(t.objectStore("meta").get(owner)));
    if (!meta) throw new Wiped("no offline data for this worker");
    let key;
    try {
      key = await crypto.subtle.unwrapKey("raw", meta.wrapped, await pinKey(pin, meta.salt), { name: "AES-GCM", iv: meta.iv },
        { name: "AES-GCM", length: 256 }, false, ["encrypt", "decrypt"]);
    } catch (e) {
      const failed = meta.failed + 1;
      if (failed >= MAX_ATTEMPTS) {
        await remove(owner);
        throw new Wiped("too many wrong PINs: this worker's offline data was deleted");
      }
      await tx(["meta"], "readwrite", (t) => req(t.objectStore("meta").put({ ...meta, failed })));
      throw new WrongPin(MAX_ATTEMPTS - failed);
    }
    if (meta.failed) await tx(["meta"], "readwrite", (t) => req(t.objectStore("meta").put({ ...meta, failed: 0 })));
    return session(owner, key);
  }

  function session(owner, key) {
    const aad = (id) => enc.encode(owner + "/" + id);
    return {
      owner,
      /** Stores a JSON value, or bytes when `value` is a Uint8Array. */
      async put(id, value) {
        const bytes = value instanceof Uint8Array;
        const plain = bytes ? value : enc.encode(JSON.stringify(value));
        const iv = crypto.getRandomValues(new Uint8Array(12));
        const ct = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv, additionalData: aad(id) }, key, plain));
        await tx(["records"], "readwrite", (t) => req(t.objectStore("records").put({ owner, id, iv, ct, bytes })));
      },
      async get(id) {
        const row = await tx(["records"], "readonly", (t) => req(t.objectStore("records").get([owner, id])));
        return row ? decrypt(row) : undefined;
      },
      /** All values whose ID starts with `prefix`, as [id, value] pairs. */
      async all(prefix) {
        const range = IDBKeyRange.bound([owner, prefix], [owner, prefix + "￿"]);
        const rows = await tx(["records"], "readonly", (t) => req(t.objectStore("records").getAll(range)));
        return Promise.all(rows.map(async (row) => [row.id, await decrypt(row)]));
      },
      /** IDs only, without decrypting anything. */
      async keys(prefix) {
        const range = IDBKeyRange.bound([owner, prefix], [owner, prefix + "\uffff"]);
        const keys = await tx(["records"], "readonly", (t) => req(t.objectStore("records").getAllKeys(range)));
        return keys.map(([, id]) => id);
      },
      async delete(id) {
        await tx(["records"], "readwrite", (t) => req(t.objectStore("records").delete([owner, id])));
      },
    };

    async function decrypt(row) {
      const plain = new Uint8Array(await crypto.subtle.decrypt({ name: "AES-GCM", iv: row.iv, additionalData: aad(row.id) }, key, row.ct));
      return row.bytes ? plain : JSON.parse(dec.decode(plain));
    }
  }

  /** Deletes one worker's store: key material and every record. */
  async function remove(owner) {
    await tx(["meta", "records"], "readwrite", (t) => {
      t.objectStore("meta").delete(owner);
      return req(t.objectStore("records").delete(IDBKeyRange.bound([owner, ""], [owner, "￿"])));
    });
  }

  return { ownerOf, list, exists, create, unlock, remove, WrongPin, Wiped, PIN_PATTERN, MAX_ATTEMPTS };
})();
