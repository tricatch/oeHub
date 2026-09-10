// Shared decrypt helper for group-mode encrypted HOSTS_PFILE content (e2eEncryption design doc
// §1/§6/§9) - used by both hosts.pebble (the editor) and hosts-share.pebble (the read-only
// share viewer) so the visibility->KEK branching lives in exactly one place (CLAUDE.md's
// "extract shared script" rule).
window.OE_CONTENT_CRYPTO = (function() {

  // wrappedContentKeyStr: the row's wrapped_content_key (null/undefined => not encrypted,
  // hostsContentStr is returned verbatim). hostsContentStr: the row's hosts_content, which is a
  // JSON {iv, ciphertext} record (as text) when encrypted. visibility: 'private' unwraps with
  // the caller's personal key; anything else ('public'/'collabo') unwraps with the cached
  // workspace key. Throws if the required session key isn't cached yet.
  async function decrypt(wrappedContentKeyStr, hostsContentStr, visibility) {
    if (!wrappedContentKeyStr) return hostsContentStr;
    let dek;
    if (visibility === 'private') {
      const privateKey = await OE_SESSION_KEYS.loadPrivateKey();
      if (!privateKey) throw new Error('no cached private key');
      dek = await OE_CRYPTO.unwrapContentKeyWithPersonalKey(wrappedContentKeyStr, privateKey);
    } else {
      const wsKey = await OE_SESSION_KEYS.loadWorkspaceKey();
      if (!wsKey) throw new Error('no cached workspace key');
      dek = await OE_CRYPTO.unwrapContentKeyWithWorkspaceKey(wrappedContentKeyStr, wsKey);
    }
    const record = JSON.parse(hostsContentStr);
    return await OE_CRYPTO.decryptContent(record, dek);
  }

  return { decrypt };
})();
