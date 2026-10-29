// Login/registration/password helpers for workspace mode (e2eEncryption design doc §3): the
// browser derives an authKey and a KEK from the password (OE_CRYPTO.deriveKeys), and only the
// authKey is ever sent to the server, in the same form/JSON field that used to carry the password.
// To the server that field is now an opaque secret it bcrypt-hashes and compares, so a request log,
// a TLS-terminating proxy or a patched server process no longer sees anything that unwraps the
// private key. Self-hosted never encrypts content and keeps sending the password as before.
//
// Everything that needs the password-derived secrets goes through here so the salt handling and
// the "never submit the typed password" rule live in one place (CLAUDE.md's shared-script rule).

const OE_AUTH = (function () {
  'use strict';

  // Mirrors the server's own password rules (PasswordUtil.validateNewPassword). The server can no
  // longer see the password in workspace mode, so this client-side check is the only enforcement.
  // Returns an i18n message key, or null when the password is acceptable.
  function passwordErrorKey(password, confirm) {
    if (!password) return 'auth.error.password.required';
    if (password.length < 8) return 'auth.error.password.too.short';
    if (password !== confirm) return 'auth.error.password.mismatch';
    return null;
  }

  // Client-side mirror of the server's user id rules (SetupController / AuthController.processRegister
  // / UserIdRules), so a form can report its first failing field in on-screen order before it gets
  // to the password. The server stays the source of truth. Returns an i18n message key or null.
  function userIdErrorKey(userId) {
    if (!userId || !userId.trim()) return 'auth.error.userid.required';
    if (userId.length < 4) return 'auth.error.userid.too.short';
    if (!/^[A-Za-z0-9._-]+$/.test(userId)) return 'auth.error.userid.invalid.chars';
    if (userId.indexOf('__') === 0) return 'auth.error.userid.reserved';
    return null;
  }

  // The account's PBKDF2 salt, needed BEFORE logging in (the authKey depends on it). The server
  // answers with a deterministic decoy for unknown accounts, so this doesn't reveal which ids exist.
  async function fetchSalt(userId) {
    const res = await fetch('/api/auth/kdf?userId=' + encodeURIComponent(userId));
    if (!res.ok) throw new Error('kdf lookup failed: ' + res.status);
    const data = await res.json();
    return new Uint8Array(OE_CRYPTO.base64ToBuf(data.salt));
  }

  // Secrets for an existing account at login: { authKey, kek }.
  async function keysForLogin(userId, password) {
    return OE_CRYPTO.deriveKeys(password, await fetchSalt(userId));
  }

  // Secrets for a NEW password (registration, setup, recovery reset, change password) with a fresh
  // random salt: { salt (base64, goes into the wrapped-private-key record), authKey, kek }.
  async function keysForNewPassword(password) {
    const salt = OE_CRYPTO.randomBytes(16);
    const keys = await OE_CRYPTO.deriveKeys(password, salt);
    return { salt: OE_CRYPTO.bufToBase64(salt), authKey: keys.authKey, kek: keys.kek };
  }

  // What re-wrapping the private key needs from the CURRENT password of the logged-in account (the
  // "confirm your password" step of change-password and recovery-code reissue): its authKey, and the
  // private key unwrapped from the account's own wrapped key - extractable, held in memory only
  // until it has been re-wrapped. The key cached for the session is deliberately not extractable,
  // so it can't be used for this; the password is at hand here anyway.
  //
  // privateKey is null when the password doesn't unwrap the key, i.e. it is wrong. The caller must
  // still send the request (with UNUSABLE_WRAP) so the server counts the attempt and answers.
  async function currentSecrets(password) {
    const res = await fetch('/api/user/crypto-keys');
    if (!res.ok) throw new Error('crypto-keys lookup failed: ' + res.status);
    const data = await res.json();
    const record = JSON.parse(data.wrappedPrivateKey);
    const keys = await OE_CRYPTO.deriveKeys(password, new Uint8Array(OE_CRYPTO.base64ToBuf(record.salt)));
    let privateKey = null;
    try {
      privateKey = await OE_CRYPTO.unwrapPrivateKey(record, keys.kek, true);
    } catch (e) {
      privateKey = null; // GCM authentication failed: wrong password
    }
    return { authKey: keys.authKey, privateKey };
  }

  // Stands in for a re-wrapped key when the current password turned out to be wrong. The server
  // verifies the password before it would ever store a wrap, and rejects one that isn't a real
  // {iv, wrapped} record, so this can never overwrite the account's real key.
  const UNUSABLE_WRAP = '{}';

  // Submits a classic <form> with the authKey standing in for the password field(s). The visible
  // inputs lose their name (so the typed password can't be submitted) and are cleared; hidden
  // inputs with the original names carry the authKey. fieldNames: e.g. ['password', 'confirmPassword'].
  function submitWithAuthKey(form, fieldNames, authKey) {
    fieldNames.forEach(function (name) {
      const visible = form.elements[name];
      if (visible) {
        visible.removeAttribute('name');
        visible.value = '';
      }
      const hidden = document.createElement('input');
      hidden.type = 'hidden';
      hidden.name = name;
      hidden.value = authKey;
      form.appendChild(hidden);
    });
    form.submit();
  }

  return { passwordErrorKey, userIdErrorKey, fetchSalt, keysForLogin, keysForNewPassword, currentSecrets, UNUSABLE_WRAP, submitWithAuthKey };
})();
