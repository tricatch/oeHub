// Client-side E2E encryption primitives (aidoc/e2eEncryption/00-design.md §2/§3). The server never
// sees a plaintext private key, workspace key, or content key - only wrapped (encrypted) blobs it
// cannot unwrap. Every function here runs in the browser via WebCrypto (crypto.subtle), which
// requires a secure context (HTTPS, or http://localhost for local dev/tests).
//
// Algorithm choices (design doc §2):
//   - Personal keypair: RSA-OAEP 2048 / SHA-256 - wraps/unwraps the workspace key and, for
//     'private' visibility, the content key directly. Small payloads only (<=190 bytes), which
//     both a 32-byte AES key and a wrapped-key blob comfortably fit under.
//   - Password -> two independent secrets: PBKDF2-SHA256 (600,000 iterations - OWASP Password
//     Storage Cheat Sheet's current minimum recommendation as of this writing; re-check and bump
//     this constant if that guidance moves) yields a master value, and HKDF-SHA256 splits it into
//       * the KEK (AES-256-GCM) that wraps the private key - never leaves the browser, and
//       * the authKey - what the server receives and bcrypt-hashes INSTEAD of the password.
//     The password itself therefore never reaches the server in workspace mode (which is what
//     lets the server-blind claim hold against anyone who can see login requests); knowing the
//     authKey reveals neither the password nor the KEK. See auth-keys.js for the form helpers.
//   - Wrapping the private key (arbitrary-length pkcs8 blob) with that password KEK, or with the
//     recovery key: AES-256-GCM. AES-KW requires the wrapped payload to be a multiple of 8 bytes,
//     which a pkcs8-encoded RSA private key is not guaranteed to be - GCM has no such constraint
//     and it authenticates the blob too.
//   - Workspace key: AES-256-KW (RFC 3394) - wraps/unwraps only, used to wrap/unwrap per-row
//     content keys (which are exactly 32 bytes, satisfying AES-KW's multiple-of-8 requirement).
//   - Content key (DEK) per HOSTS_PFILE/PROXY_VHOST row: AES-256-GCM, used to encrypt/decrypt the
//     row's content directly.
//
// Every wrap/unwrap goes through WebCrypto's dedicated wrapKey()/unwrapKey() API rather than
// "export the key and AES-GCM-encrypt the bytes by hand" - this keeps non-extractable private
// keys non-extractable throughout, per the design doc's rationale.

const OE_CRYPTO = (function () {
  'use strict';

  const PBKDF2_ITERATIONS = 600000;

  // ---- base64 helpers (binary-safe, not the same as the app's toBase64Url/fromBase64Url in
  // util.js, which operate on JS strings, not raw key/ciphertext bytes) ----

  function bufToBase64(buf) {
    const bytes = new Uint8Array(buf);
    let binary = '';
    for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
    return btoa(binary);
  }

  function base64ToBuf(b64) {
    const binary = atob(b64);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return bytes.buffer;
  }

  function randomBytes(length) {
    return crypto.getRandomValues(new Uint8Array(length));
  }

  // ---- Personal keypair (RSA-OAEP 2048) ----

  async function generateKeyPair() {
    return crypto.subtle.generateKey(
      { name: 'RSA-OAEP', modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256' },
      true,
      ['wrapKey', 'unwrapKey']
    );
  }

  async function exportPublicKey(publicKey) {
    return bufToBase64(await crypto.subtle.exportKey('spki', publicKey));
  }

  async function importPublicKey(base64) {
    return crypto.subtle.importKey(
      'spki', base64ToBuf(base64),
      { name: 'RSA-OAEP', hash: 'SHA-256' },
      true, ['wrapKey']
    );
  }

  // ---- Password -> authKey + KEK (PBKDF2-SHA256, then HKDF-SHA256 with distinct info labels) ----

  // 32 bytes as unpadded base64url = 43 chars; the server checks this exact shape (AuthKey.java).
  function bufToBase64Url(buf) {
    return bufToBase64(buf).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  }

  async function deriveKeys(password, saltBytes) {
    const encoder = new TextEncoder();
    const baseKey = await crypto.subtle.importKey(
      'raw', encoder.encode(password), 'PBKDF2', false, ['deriveBits']
    );
    const master = await crypto.subtle.deriveBits(
      { name: 'PBKDF2', salt: saltBytes, iterations: PBKDF2_ITERATIONS, hash: 'SHA-256' },
      baseKey, 256
    );
    const hkdfKey = await crypto.subtle.importKey('raw', master, 'HKDF', false, ['deriveBits', 'deriveKey']);
    const noSalt = new Uint8Array(0);
    const authBits = await crypto.subtle.deriveBits(
      { name: 'HKDF', hash: 'SHA-256', salt: noSalt, info: encoder.encode('oehub-auth-v1') },
      hkdfKey, 256
    );
    const kek = await crypto.subtle.deriveKey(
      { name: 'HKDF', hash: 'SHA-256', salt: noSalt, info: encoder.encode('oehub-kek-v1') },
      hkdfKey,
      { name: 'AES-GCM', length: 256 },
      false,
      ['wrapKey', 'unwrapKey']
    );
    return { authKey: bufToBase64Url(authBits), kek };
  }

  // ---- Wrap/unwrap the private key with an AES-GCM KEK (password-derived or recovery-key) ----
  // Returns/accepts { iv, wrapped, salt } as base64 strings - salt is only meaningful for the
  // password path (PBKDF2 needs it to re-derive the same secrets) and is null for the recovery path.

  async function wrapPrivateKey(privateKey, kek) {
    const iv = randomBytes(12);
    const wrapped = await crypto.subtle.wrapKey('pkcs8', privateKey, kek, { name: 'AES-GCM', iv });
    return { iv: bufToBase64(iv), wrapped: bufToBase64(wrapped) };
  }

  // extractable: true only for a key that is about to be re-wrapped (wrapPrivateKey needs to export
  // it) and is dropped right afterwards - password change, recovery-code reissue, recovery reset.
  // The copy cached for the session (login) passes false, so a script running in the page can use
  // it to unwrap keys during the session but cannot export the raw private key and keep it.
  async function unwrapPrivateKey(record, kek, extractable = true) {
    return crypto.subtle.unwrapKey(
      'pkcs8',
      base64ToBuf(record.wrapped),
      kek,
      { name: 'AES-GCM', iv: base64ToBuf(record.iv) },
      { name: 'RSA-OAEP', hash: 'SHA-256' },
      extractable,
      ['unwrapKey']
    );
  }

  // ---- Recovery key: 256-bit random, used directly as an AES-GCM key (no PBKDF2 - it already
  // has full entropy) - e2eEncryption design doc §3 "복구키" ----

  function generateRecoveryKeyBytes() {
    return randomBytes(32); // 256 bits, used as the raw AES-GCM key material directly
  }

  async function importRecoveryKeyAsAesGcm(bytes) {
    return crypto.subtle.importKey('raw', bytes, { name: 'AES-GCM' }, false, ['wrapKey', 'unwrapKey']);
  }

  // Human-facing display form of the exact 32 recovery-key bytes (not an independently generated
  // string) - base64url, grouped for readability. Fully reversible via parseRecoveryDisplayCode,
  // since unwrapping later needs these exact bytes back, not merely "a string with enough entropy".
  // Groups are space-separated, not hyphen-separated - base64url's own alphabet already uses '-'
  // (in place of '+'), so a '-' separator would be ambiguous with real encoded data and stripping
  // it blindly (as parseRecoveryDisplayCode must) would corrupt any code containing one.
  function formatRecoveryDisplayCode(bytes) {
    const b64url = bufToBase64(bytes).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
    return b64url.match(/.{1,6}/g).join(' ');
  }

  function parseRecoveryDisplayCode(displayCode) {
    const b64url = displayCode.replace(/\s+/g, '');
    const b64 = b64url.replace(/-/g, '+').replace(/_/g, '/');
    const padded = b64 + '='.repeat((4 - (b64.length % 4)) % 4);
    return new Uint8Array(base64ToBuf(padded));
  }

  // Recovery verifier (design doc §3 "복구 플로우 프로토콜"): a one-way HKDF derivation of the
  // recovery code bytes, sent to the server so it can authenticate a recovery attempt without
  // ever seeing the code itself - the code bytes are the AES-GCM key that unwraps the private
  // key, so only this derived value may ever leave the browser.
  async function deriveRecoveryVerifier(recoveryBytes) {
    const key = await crypto.subtle.importKey('raw', recoveryBytes, 'HKDF', false, ['deriveBits']);
    const bits = await crypto.subtle.deriveBits(
      { name: 'HKDF', hash: 'SHA-256', salt: new Uint8Array(0), info: new TextEncoder().encode('oehub-recovery-verifier') },
      key, 256
    );
    return bufToBase64(bits);
  }

  // ---- Workspace key (AES-256-KW) ----

  async function generateWorkspaceKey() {
    return crypto.subtle.generateKey({ name: 'AES-KW', length: 256 }, true, ['wrapKey', 'unwrapKey']);
  }

  async function wrapWorkspaceKeyForUser(workspaceKey, publicKey) {
    const wrapped = await crypto.subtle.wrapKey('raw', workspaceKey, publicKey, { name: 'RSA-OAEP' });
    return bufToBase64(wrapped);
  }

  async function unwrapWorkspaceKeyForUser(wrappedBase64, privateKey) {
    return crypto.subtle.unwrapKey(
      'raw', base64ToBuf(wrappedBase64), privateKey,
      { name: 'RSA-OAEP' },
      { name: 'AES-KW', length: 256 },
      true, ['wrapKey', 'unwrapKey']
    );
  }

  // ---- Content key (DEK, AES-256-GCM) - one per HOSTS_PFILE/PROXY_VHOST row ----

  async function generateContentKey() {
    return crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, true, ['encrypt', 'decrypt']);
  }

  async function encryptContent(plaintext, contentKey) {
    const iv = randomBytes(12);
    const ciphertext = await crypto.subtle.encrypt(
      { name: 'AES-GCM', iv }, contentKey, new TextEncoder().encode(plaintext)
    );
    return { iv: bufToBase64(iv), ciphertext: bufToBase64(ciphertext) };
  }

  async function decryptContent(record, contentKey) {
    const plaintextBuf = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv: base64ToBuf(record.iv) }, contentKey, base64ToBuf(record.ciphertext)
    );
    return new TextDecoder().decode(plaintextBuf);
  }

  // 'private' visibility: content key wrapped directly by the owner's personal key pair.
  async function wrapContentKeyWithPersonalKey(contentKey, publicKey) {
    const wrapped = await crypto.subtle.wrapKey('raw', contentKey, publicKey, { name: 'RSA-OAEP' });
    return bufToBase64(wrapped);
  }

  async function unwrapContentKeyWithPersonalKey(wrappedBase64, privateKey) {
    return crypto.subtle.unwrapKey(
      'raw', base64ToBuf(wrappedBase64), privateKey,
      { name: 'RSA-OAEP' },
      { name: 'AES-GCM', length: 256 },
      true, ['encrypt', 'decrypt']
    );
  }

  // 'collabo'/'public' visibility: content key wrapped by the shared workspace key (design doc
  // §6 - both grades use the identical wrap, differing only in search/discovery scope).
  async function wrapContentKeyWithWorkspaceKey(contentKey, workspaceKey) {
    const wrapped = await crypto.subtle.wrapKey('raw', contentKey, workspaceKey, { name: 'AES-KW' });
    return bufToBase64(wrapped);
  }

  async function unwrapContentKeyWithWorkspaceKey(wrappedBase64, workspaceKey) {
    return crypto.subtle.unwrapKey(
      'raw', base64ToBuf(wrappedBase64), workspaceKey,
      { name: 'AES-KW' },
      { name: 'AES-GCM', length: 256 },
      true, ['encrypt', 'decrypt']
    );
  }

  return {
    PBKDF2_ITERATIONS,
    generateKeyPair, exportPublicKey, importPublicKey,
    deriveKeys,
    wrapPrivateKey, unwrapPrivateKey,
    generateRecoveryKeyBytes, importRecoveryKeyAsAesGcm, formatRecoveryDisplayCode, parseRecoveryDisplayCode,
    deriveRecoveryVerifier,
    generateWorkspaceKey, wrapWorkspaceKeyForUser, unwrapWorkspaceKeyForUser,
    generateContentKey, encryptContent, decryptContent,
    wrapContentKeyWithPersonalKey, unwrapContentKeyWithPersonalKey,
    wrapContentKeyWithWorkspaceKey, unwrapContentKeyWithWorkspaceKey,
    bufToBase64, base64ToBuf, randomBytes,
  };
})();
