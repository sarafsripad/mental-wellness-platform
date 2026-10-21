// The worker's offline store (ADR-017): one SQLCipher database per worker, whose key the native
// module unwraps only with the worker's PIN. Visits, outbox entries and recordings are stored here
// and nowhere else. No token is ever stored.
import {open, type DB} from '@op-engineering/op-sqlite';
import {native, settings} from './native';

export class WrongPin extends Error {
  constructor(readonly remaining: number) {
    super('wrong PIN');
  }
}
export class Wiped extends Error {}

export const PIN_PATTERN = /^[0-9]{6,12}$/;
export const MAX_ATTEMPTS = 5;

export type Session = {
  owner: string;
  put(id: string, value: unknown): Promise<void>;
  get<T>(id: string): Promise<T | undefined>;
  all<T>(prefix: string): Promise<Array<[string, T]>>;
  delete(id: string): Promise<void>;
  putAudio(id: string, base64: string): Promise<void>;
  getAudio(id: string): Promise<string | undefined>;
  deleteAudio(id: string): Promise<void>;
  audioIds(): Promise<string[]>;
  close(): void;
};

export const listStores = () => native.listStores();
export const removeStore = (owner: string) => native.removeStore(owner);

export async function createStore(owner: string, pin: string): Promise<Session> {
  return session(owner, await native.createStore(owner, pin));
}

export async function unlockStore(owner: string, pin: string): Promise<Session> {
  try {
    return session(owner, await native.unlockStore(owner, pin));
  } catch (e: any) {
    if (e && e.code === 'WRONG_PIN') throw new WrongPin(Number(e.message));
    if (e && e.code === 'WIPED') throw new Wiped(e.message);
    throw e;
  }
}

async function session(owner: string, key: string): Promise<Session> {
  const db: DB = open({name: `asha-${owner}.sqlite`, location: settings.storeDir, encryptionKey: key});
  await db.execute('CREATE TABLE IF NOT EXISTS record (id TEXT PRIMARY KEY, json TEXT NOT NULL)');
  await db.execute('CREATE TABLE IF NOT EXISTS audio (id TEXT PRIMARY KEY, wav TEXT NOT NULL)');
  return {
    owner,
    async put(id, value) {
      await db.execute('INSERT OR REPLACE INTO record (id, json) VALUES (?, ?)', [id, JSON.stringify(value)]);
    },
    async get<T>(id: string) {
      const r = await db.execute('SELECT json FROM record WHERE id = ?', [id]);
      return r.rows.length ? (JSON.parse(String(r.rows[0].json)) as T) : undefined;
    },
    async all<T>(prefix: string) {
      const r = await db.execute("SELECT id, json FROM record WHERE id >= ? AND id < ? ORDER BY id", [prefix, prefix + '￿']);
      return r.rows.map(row => [String(row.id), JSON.parse(String(row.json)) as T] as [string, T]);
    },
    async delete(id) {
      await db.execute('DELETE FROM record WHERE id = ?', [id]);
    },
    async putAudio(id, base64) {
      await db.execute('INSERT OR REPLACE INTO audio (id, wav) VALUES (?, ?)', [id, base64]);
    },
    async getAudio(id) {
      const r = await db.execute('SELECT wav FROM audio WHERE id = ?', [id]);
      return r.rows.length ? String(r.rows[0].wav) : undefined;
    },
    async deleteAudio(id) {
      await db.execute('DELETE FROM audio WHERE id = ?', [id]);
    },
    async audioIds() {
      const r = await db.execute('SELECT id FROM audio');
      return r.rows.map(row => String(row.id));
    },
    close: () => db.close(),
  };
}
