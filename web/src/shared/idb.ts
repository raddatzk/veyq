// Shared IndexedDB helper for the device-local stores (device-trust + remember-me). Each store is a single
// object store in its own DB, created on first open. `idbRun` runs one transaction and settles on **COMMIT**,
// not on request success — a write can still abort at commit (quota / structured-clone failure) after the
// request "succeeded", so resolving early would report a success that never persisted. A blocked open rejects
// rather than hanging the caller. Keeping this in one place means that lifecycle subtlety lives in one copy.

function openDb(dbName: string, store: string): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(dbName, 1)
    req.onupgradeneeded = () => req.result.createObjectStore(store)
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error)
    req.onblocked = () => reject(new Error('indexeddb_blocked'))
  })
}

/** Run one IndexedDB transaction against `dbName`/`store`, settling on commit (see file header). */
export function idbRun<T>(
  dbName: string,
  store: string,
  mode: IDBTransactionMode,
  op: (store: IDBObjectStore) => IDBRequest<T>,
): Promise<T> {
  return openDb(dbName, store).then(
    (db) =>
      new Promise<T>((resolve, reject) => {
        const tx = db.transaction(store, mode)
        const req = op(tx.objectStore(store))
        let result: T
        req.onsuccess = () => {
          result = req.result
        }
        tx.oncomplete = () => {
          db.close()
          resolve(result)
        }
        tx.onerror = () => {
          db.close()
          reject(tx.error)
        }
        tx.onabort = () => {
          db.close()
          reject(tx.error)
        }
      }),
  )
}
