// Holds this login session's unwrapped private key and workspace key so pages don't need the
// user's password again after login (e2eEncryption design doc §3's "세션 동안 메모리에 들고 있는다").
// oeHub is a traditional multi-page app (full navigation between pages, no SPA router), so a
// plain JS variable would be lost on every navigation. IndexedDB is used instead of
// sessionStorage/localStorage because it's the only browser storage that can hold a CryptoKey
// object directly (structured-clone) - the raw key bytes are never exported to a serializable
// form, so a non-extractable private key stays non-extractable even at rest here. Cleared on
// logout (see OE_SESSION_KEYS.clearAll(), wired into the logout link).
//
// Idle expiry (below): these are exactly what a client-side compromise (XSS, malware, a shared
// machine left unlocked) would go after, since they're the one place the workspace's real
// encryption keys sit ready-to-use with no further secret needed. Bounding how long an unwrapped
// key survives without any actual use of the app narrows that window - it's defense in depth,
// not a substitute for not getting compromised in the first place (see docs/06-security-model.md).

const OE_SESSION_KEYS = (function () {
  'use strict';

  const DB_NAME = 'oehub-session-keys';
  const DB_VERSION = 1;
  const STORE_NAME = 'keys';
  const PRIVATE_KEY_ID = 'privateKey';
  const WORKSPACE_KEY_ID = 'workspaceKey';
  // Each successful load() pushes this back out (expire-after-access, not expire-after-write) -
  // an actively-used session never hits this, only one left sitting idle does.
  const IDLE_TTL_MS = 8 * 60 * 60 * 1000;

  function openDb() {
    return new Promise((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, DB_VERSION);
      req.onupgradeneeded = () => {
        req.result.createObjectStore(STORE_NAME);
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  }

  async function put(id, value) {
    const db = await openDb();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(STORE_NAME, 'readwrite');
      tx.objectStore(STORE_NAME).put({ value: value, savedAt: Date.now() }, id);
      tx.oncomplete = () => { db.close(); resolve(); };
      tx.onerror = () => { db.close(); reject(tx.error); };
    });
  }

  async function get(id) {
    const db = await openDb();
    const entry = await new Promise((resolve, reject) => {
      const tx = db.transaction(STORE_NAME, 'readonly');
      const req = tx.objectStore(STORE_NAME).get(id);
      req.onsuccess = () => { db.close(); resolve(req.result ?? null); };
      req.onerror = () => { db.close(); reject(req.error); };
    });
    if (!entry) return null;
    if (Date.now() - entry.savedAt > IDLE_TTL_MS) {
      await remove(id);
      return null;
    }
    // Touch it: a load this recent counts as activity, so an idle-but-in-use session keeps
    // renewing instead of expiring mid-workday. Fire-and-forget - a lost race against a
    // concurrent expiry check just means the next load re-evaluates freshly, never a crash.
    put(id, entry.value).catch(() => {});
    return entry.value;
  }

  async function remove(id) {
    const db = await openDb();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(STORE_NAME, 'readwrite');
      tx.objectStore(STORE_NAME).delete(id);
      tx.oncomplete = () => { db.close(); resolve(); };
      tx.onerror = () => { db.close(); reject(tx.error); };
    });
  }

  async function clearAll() {
    const db = await openDb();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(STORE_NAME, 'readwrite');
      tx.objectStore(STORE_NAME).clear();
      tx.oncomplete = () => { db.close(); resolve(); };
      tx.onerror = () => { db.close(); reject(tx.error); };
    });
  }

  return {
    savePrivateKey: (key) => put(PRIVATE_KEY_ID, key),
    loadPrivateKey: () => get(PRIVATE_KEY_ID),
    saveWorkspaceKey: (key) => put(WORKSPACE_KEY_ID, key),
    loadWorkspaceKey: () => get(WORKSPACE_KEY_ID),
    clearAll,
  };
})();
