/**
 * Keeps the files the OS handed over until they have reached the workbench.
 *
 * Chromium delivers launch files to the first page load only. If that load is
 * thrown away before the files are added (an expired session sends the app to
 * /login with a full reload), they are gone and the user lands on an empty
 * workbench. The stash lets the next load pick them up.
 *
 * Every function swallows storage failures: the stash is a safety net, and a
 * blocked or broken IndexedDB must never stop the launched files from opening.
 */

const DB_NAME = "blasai-launched-files";
const STORE = "stash";
const KEY = "pending";
/** A stash older than this belongs to a launch the user has moved on from. */
const MAX_AGE_MS = 10 * 60 * 1000;

interface StashEntry {
  savedAt: number;
  files: File[];
}

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, 1);
    request.onupgradeneeded = () => request.result.createObjectStore(STORE);
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

async function run<T>(
  mode: IDBTransactionMode,
  op: (store: IDBObjectStore) => IDBRequest<T>,
): Promise<T> {
  const db = await openDb();
  try {
    return await new Promise<T>((resolve, reject) => {
      const request = op(db.transaction(STORE, mode).objectStore(STORE));
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
  } finally {
    db.close();
  }
}

export async function stashLaunchedFiles(files: File[]): Promise<void> {
  try {
    const entry: StashEntry = { savedAt: Date.now(), files };
    await run("readwrite", (store) => store.put(entry, KEY));
  } catch (error) {
    console.warn("Could not stash launched files:", error);
  }
}

/** Returns the stashed files if a recent launch left any, else an empty list. */
export async function readStashedLaunchedFiles(): Promise<File[]> {
  try {
    const entry = await run<StashEntry | undefined>("readonly", (store) =>
      store.get(KEY),
    );
    if (!entry || Date.now() - entry.savedAt > MAX_AGE_MS) return [];
    return entry.files;
  } catch {
    return [];
  }
}

export async function clearStashedLaunchedFiles(): Promise<void> {
  try {
    await run("readwrite", (store) => store.delete(KEY));
  } catch {
    // Nothing to undo: a stale stash expires on its own.
  }
}
