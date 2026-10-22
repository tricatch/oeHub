// Re-caches this session's private/workspace keys when they have expired out of OE_SESSION_KEYS's
// idle cache (session-keys.js) while the login cookie itself is still valid. The keys can only be
// re-derived from the password, so instead of sending the user through log out / log in, a modal
// (#keyUnlockModal in app-layout.pebble) asks for the password once and unwraps the account's own
// wrapped private key with it, entirely in the browser - exactly what login.pebble does right after
// login. Nothing is sent to the server: the wrapped key is already readable by this session
// (/api/user/crypto-keys), so checking the password against it locally exposes nothing new.
//
// The modal also lets the user pick how long the keys may sit idle in this browser before the next
// prompt (OE_SESSION_KEYS.saveIdleTtl); the choice is saved only together with a successful unlock.
//
// ensure() resolves true once the keys are cached (or there is nothing to unlock: self-hosted, or
// an account without a keypair) and false when the user cancels. Concurrent callers (the layout's
// page-load check and a page's own decrypt path) share one pending prompt.

const OE_KEY_UNLOCK = (function () {
  'use strict';

  let pending = null;

  function ensure() {
    if (!window.OE_WORKSPACE_MODE) return Promise.resolve(true);
    if (!pending) {
      pending = check().finally(function () { pending = null; });
    }
    return pending;
  }

  async function check() {
    let privateKey = await OE_SESSION_KEYS.loadPrivateKey();
    const wsKey = await OE_SESSION_KEYS.loadWorkspaceKey();
    if (privateKey && wsKey) return true;

    const res = await fetch('/api/user/crypto-keys');
    if (!res.ok) return false;
    const data = await res.json();
    if (!data.wrappedPrivateKey) return true; // pre-e2e account or instance admin: no keypair

    if (!privateKey) {
      privateKey = await promptForPassword(JSON.parse(data.wrappedPrivateKey));
      if (!privateKey) return false;
    }
    // The two keys expire independently (each load only renews its own entry), so a still-cached
    // private key can restore the workspace key without asking for the password at all.
    if (data.wrappedWsKey && !(await OE_SESSION_KEYS.loadWorkspaceKey())) {
      await OE_SESSION_KEYS.saveWorkspaceKey(await OE_CRYPTO.unwrapWorkspaceKeyForUser(data.wrappedWsKey, privateKey));
    }
    return true;
  }

  async function domReady() {
    if (document.readyState !== 'loading') return;
    await new Promise(function (resolve) { document.addEventListener('DOMContentLoaded', resolve, { once: true }); });
  }

  // Option label per OE_SESSION_KEYS.IDLE_TTL_CHOICES entry (0 = until logout).
  function ttlLabel(ms) {
    return MSG[ms ? 'key.unlock.ttl.' + (ms / 3600000) + 'h' : 'key.unlock.ttl.never'];
  }

  function fillTtlSelect(select, current) {
    select.innerHTML = '';
    OE_SESSION_KEYS.IDLE_TTL_CHOICES.forEach(function (ms) {
      const opt = document.createElement('option');
      opt.value = String(ms);
      opt.textContent = ttlLabel(ms);
      select.appendChild(opt);
    });
    select.value = String(current);
  }

  // Shows the modal and resolves with the unwrapped (non-extractable, as at login) private key,
  // already saved to the session cache, or null when the modal is dismissed.
  async function promptForPassword(record) {
    await domReady();
    const modalEl = document.getElementById('keyUnlockModal');
    if (!modalEl) return null;
    const input = document.getElementById('kuPassword');
    const errEl = document.getElementById('kuError');
    const btn = document.getElementById('btnKeyUnlockConfirm');
    const ttlSelect = document.getElementById('kuIdleTtl');
    fillTtlSelect(ttlSelect, await OE_SESSION_KEYS.loadIdleTtl());
    const modal = bootstrap.Modal.getOrCreateInstance(modalEl);

    input.value = '';
    errEl.classList.add('d-none');
    btn.disabled = true;

    return new Promise(function (resolve) {
      let unlocked = null;

      function showError(text) {
        errEl.textContent = text;
        errEl.classList.remove('d-none');
      }
      function onInput() { btn.disabled = input.value === ''; }
      function onKeydown(e) { if (e.key === 'Enter' && !btn.disabled) { e.preventDefault(); onConfirm(); } }
      function onShown() { input.focus(); }
      async function onConfirm() {
        const password = input.value;
        if (!password) { showError(MSG['auth.error.password.required']); return; }
        btn.disabled = true;
        try {
          const keys = await OE_CRYPTO.deriveKeys(password, new Uint8Array(OE_CRYPTO.base64ToBuf(record.salt)));
          let privateKey;
          try {
            privateKey = await OE_CRYPTO.unwrapPrivateKey(record, keys.kek, false);
          } catch (e) {
            input.value = '';
            showError(MSG['key.unlock.error.invalid']);
            input.focus();
            return; // GCM authentication failed: wrong password
          }
          await OE_SESSION_KEYS.saveIdleTtl(Number(ttlSelect.value));
          await OE_SESSION_KEYS.savePrivateKey(privateKey);
          unlocked = privateKey;
          modal.hide();
        } catch (err) {
          console.error('Key unlock failed:', err);
          showError(MSG['key.unlock.error.failed']);
        } finally {
          btn.disabled = input.value === '';
        }
      }
      function onHidden() {
        input.value = '';
        input.removeEventListener('input', onInput);
        input.removeEventListener('keydown', onKeydown);
        btn.removeEventListener('click', onConfirm);
        modalEl.removeEventListener('shown.bs.modal', onShown);
        resolve(unlocked);
      }

      input.addEventListener('input', onInput);
      input.addEventListener('keydown', onKeydown);
      btn.addEventListener('click', onConfirm);
      modalEl.addEventListener('shown.bs.modal', onShown);
      modalEl.addEventListener('hidden.bs.modal', onHidden, { once: true });
      modal.show();
    });
  }

  return { ensure };
})();
