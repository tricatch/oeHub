package tricatch.oe.hub.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;
import java.util.Map;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises static/js/crypto.js (OE_CRYPTO) in a real browser via WebCrypto - this is the only
 * way to verify these algorithm choices actually work together (WebCrypto has no server-side/JVM
 * equivalent in this project). No server-side wiring exists yet (crypto.js is only loaded on
 * /register so far, unused by its form) - this test just needs any page that includes the script.
 * See aidoc/e2eEncryption/00-design.md §2/§3 for the design this implements.
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CryptoPrimitivesTest {

    private static final int PORT = 39916;

    private E2eServer server;
    private Playwright playwright;
    private Browser browser;

    @BeforeAll
    void startAll() throws Exception {
        // Workspace mode (e2eEncryption design doc §1): self-hosted's identity crypto is a dummy
        // placeholder (nothing ever decrypts it there), so login_unwrapsAndCachesPrivate... below
        // needs a real account, which only exists in workspace mode. The other tests here just
        // call OE_CRYPTO primitives directly via page.evaluate() and don't care about server mode
        // at all, so there's no cost to running the whole class this way.
        server = new E2eServer(PORT, List.of("-Doe.mode=workspace"));
        server.start();
        playwright = Playwright.create();
        browser = playwright.chromium().launch();

        // OeHubApplication redirects every request except /setup (and static assets) to /setup
        // until setup is complete - workspace mode only needs an admin account, no CA
        // (cloudGroupService design doc §2.6), so this is quicker than self-hosted's two-step
        // version.
        var setupPage = browser.newPage();
        setupPage.navigate(server.baseUrl() + "/setup");
        setupPage.locator("form[action='/setup'] input[name=userId]").fill("cryptotest-instanceadmin");
        setupPage.locator("form[action='/setup'] input[name=password]").fill("InstAdminPass123!");
        setupPage.locator("form[action='/setup'] input[name=confirm]").fill("InstAdminPass123!");
        setupPage.locator("form[action='/setup'] button[type=submit]").click();
        assertThat(setupPage.locator("#recoveryCodeModal.show")).isVisible();
        setupPage.locator("#btnRecoveryCodeContinue").click();

        // The account login_unwrapsAndCachesPrivateAndWorkspaceKeys... below logs in as: a real
        // workspace founder, who gets a real keypair, password wrap, and workspace-key wrap.
        setupPage.navigate(server.baseUrl() + "/register");
        setupPage.locator("input[name=wsName]").fill("Crypto Test Co");
        setupPage.locator("input[name=userId]").fill("cryptotest-admin");
        setupPage.locator("input[name=password]").fill("AdminPass123!");
        setupPage.locator("input[name=confirmPassword]").fill("AdminPass123!");
        setupPage.locator("form[action='/register'] button[type=submit]").click();
        assertThat(setupPage.locator("#recoveryCodeModal.show")).isVisible();
        setupPage.locator("#btnRecoveryCodeContinue").click();
        setupPage.waitForURL(java.util.regex.Pattern.compile(".*/login$"));
        setupPage.close();
    }

    @AfterAll
    void stopAll() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
        if (server != null) server.close();
    }

    private com.microsoft.playwright.Page newPage() {
        var page = browser.newPage();
        page.navigate(server.baseUrl() + "/register");
        return page;
    }

    @Test
    void login_unwrapsAndCachesPrivateAndWorkspaceKeys_survivingNavigation() {
        // cryptotest-admin (created in @BeforeAll) founded a workspace via /register, so it has
        // both a personal keypair and a HUB_WS_KEY wrap of its own making - logging in should
        // unwrap and cache both (e2eEncryption design doc §3).
        var page = browser.newPage();
        page.navigate(server.baseUrl() + "/login");
        page.locator("input[name=userId]").fill("cryptotest-admin");
        page.locator("input[name=password]").fill("AdminPass123!");
        page.locator("#btnLoginSubmit").click();
        page.waitForURL(server.baseUrl() + "/");

        Object cachedOnLandingPage = page.evaluate("""
            async () => {
                // "/" uses layout.pebble, not app-layout.pebble, so session-keys.js isn't loaded
                // there yet - load it dynamically just to read back what login.pebble stored, to
                // prove the write itself succeeded before checking cross-page persistence below.
                await new Promise((resolve, reject) => {
                    const s = document.createElement('script');
                    s.src = '/js/session-keys.js';
                    s.onload = resolve;
                    s.onerror = reject;
                    document.head.appendChild(s);
                });
                const priv = await OE_SESSION_KEYS.loadPrivateKey();
                const ws = await OE_SESSION_KEYS.loadWorkspaceKey();
                return { privType: priv ? priv.type : null, wsAlgo: ws ? ws.algorithm.name : null };
            }
            """);
        assertThat(((Map<?, ?>) cachedOnLandingPage).get("privType")).isEqualTo("private");
        assertThat(((Map<?, ?>) cachedOnLandingPage).get("wsAlgo")).isEqualTo("AES-KW");

        // Navigate to a completely different page (app-layout.pebble, which loads
        // session-keys.js on its own) - this is the actual property under test: IndexedDB (not
        // a JS variable) survives a full page navigation in this multi-page app.
        page.navigate(server.baseUrl() + "/oehub/hosts");
        Object cachedAfterNav = page.evaluate("""
            async () => {
                const priv = await OE_SESSION_KEYS.loadPrivateKey();
                const ws = await OE_SESSION_KEYS.loadWorkspaceKey();
                return { privType: priv ? priv.type : null, wsAlgo: ws ? ws.algorithm.name : null };
            }
            """);
        assertThat(((Map<?, ?>) cachedAfterNav).get("privType")).isEqualTo("private");
        assertThat(((Map<?, ?>) cachedAfterNav).get("wsAlgo")).isEqualTo("AES-KW");

        page.close();
    }

    @Test
    void keyPair_generatesAndExportsPublicKey() {
        var page = newPage();
        Object spkiLength = page.evaluate("""
            async () => {
                const kp = await OE_CRYPTO.generateKeyPair();
                const pub = await OE_CRYPTO.exportPublicKey(kp.publicKey);
                return pub.length;
            }
            """);
        // A 2048-bit RSA SPKI DER, base64-encoded, is comfortably over 300 chars.
        assertThat(((Number) spkiLength).intValue()).isGreaterThan(300);
        page.close();
    }

    @Test
    void privateKey_wrapWithPasswordDerivedKek_roundTrips() {
        var page = newPage();
        Object ok = page.evaluate("""
            async () => {
                const kp = await OE_CRYPTO.generateKeyPair();
                const salt = OE_CRYPTO.randomBytes(16);
                const kek = await OE_CRYPTO.deriveKeyFromPassword('correct horse battery staple', salt);
                const record = await OE_CRYPTO.wrapPrivateKey(kp.privateKey, kek);

                // Re-derive the KEK independently (as a real login would, from the same password
                // and salt) rather than reusing the in-memory kek object, to prove the derivation
                // itself is reproducible - not just that the same CryptoKey object works twice.
                const kek2 = await OE_CRYPTO.deriveKeyFromPassword('correct horse battery staple', salt);
                const unwrapped = await OE_CRYPTO.unwrapPrivateKey(record, kek2);

                // Prove the unwrapped key is functionally the real private key: wrap a workspace
                // key with the public key, then unwrap it with the recovered private key.
                const wsKey = await OE_CRYPTO.generateWorkspaceKey();
                const wrappedWs = await OE_CRYPTO.wrapWorkspaceKeyForUser(wsKey, kp.publicKey);
                const unwrappedWs = await OE_CRYPTO.unwrapWorkspaceKeyForUser(wrappedWs, unwrapped);
                return unwrappedWs.algorithm.name === 'AES-KW';
            }
            """);
        assertThat((Boolean) ok).isTrue();
        page.close();
    }

    @Test
    void privateKey_wrongPassword_failsToUnwrap() {
        var page = newPage();
        Object threw = page.evaluate("""
            async () => {
                const kp = await OE_CRYPTO.generateKeyPair();
                const salt = OE_CRYPTO.randomBytes(16);
                const kek = await OE_CRYPTO.deriveKeyFromPassword('right-password', salt);
                const record = await OE_CRYPTO.wrapPrivateKey(kp.privateKey, kek);

                const wrongKek = await OE_CRYPTO.deriveKeyFromPassword('wrong-password', salt);
                try {
                    await OE_CRYPTO.unwrapPrivateKey(record, wrongKek);
                    return false; // must not reach here
                } catch (e) {
                    return true; // GCM auth tag check must fail with the wrong-derived KEK
                }
            }
            """);
        assertThat((Boolean) threw).isTrue();
        page.close();
    }

    @Test
    void recoveryKey_displayCodeRoundTripsToExactBytes_andUnwrapsPrivateKey() {
        var page = newPage();
        Object ok = page.evaluate("""
            async () => {
                const kp = await OE_CRYPTO.generateKeyPair();
                const recoveryBytes = OE_CRYPTO.generateRecoveryKeyBytes();
                const displayCode = OE_CRYPTO.formatRecoveryDisplayCode(recoveryBytes);
                const parsedBytes = OE_CRYPTO.parseRecoveryDisplayCode(displayCode);

                if (parsedBytes.length !== recoveryBytes.length) return false;
                for (let i = 0; i < recoveryBytes.length; i++) {
                    if (parsedBytes[i] !== recoveryBytes[i]) return false;
                }

                const recoveryKek = await OE_CRYPTO.importRecoveryKeyAsAesGcm(recoveryBytes);
                const record = await OE_CRYPTO.wrapPrivateKey(kp.privateKey, recoveryKek);

                // Simulate the actual recovery flow: user re-types the display code, we re-parse
                // and re-import it independently, then unwrap with that.
                const reparsedBytes = OE_CRYPTO.parseRecoveryDisplayCode(displayCode);
                const recoveryKek2 = await OE_CRYPTO.importRecoveryKeyAsAesGcm(reparsedBytes);
                const unwrapped = await OE_CRYPTO.unwrapPrivateKey(record, recoveryKek2);
                return unwrapped.type === 'private';
            }
            """);
        assertThat((Boolean) ok).isTrue();
        page.close();
    }

    @Test
    void recoveryDisplayCode_roundTripsForManyRandomByteSequences() {
        // formatRecoveryDisplayCode/parseRecoveryDisplayCode must round-trip for every possible
        // byte sequence, not just whichever one generateRecoveryKeyBytes() happens to produce in
        // a single test run - a base64url-vs-separator-character collision bug here only shows up
        // for byte patterns that happen to encode a '+' or '/' at the right position, so a single
        // random sample is not reliable coverage (this caught exactly such a bug: using '-' as
        // both the group separator and part of the base64url alphabet corrupted some codes).
        var page = newPage();
        Object ok = page.evaluate("""
            () => {
                for (let trial = 0; trial < 300; trial++) {
                    const bytes = OE_CRYPTO.randomBytes(32);
                    const displayCode = OE_CRYPTO.formatRecoveryDisplayCode(bytes);
                    const parsed = OE_CRYPTO.parseRecoveryDisplayCode(displayCode);
                    if (parsed.length !== bytes.length) return false;
                    for (let i = 0; i < bytes.length; i++) {
                        if (parsed[i] !== bytes[i]) return false;
                    }
                }
                return true;
            }
            """);
        assertThat((Boolean) ok).isTrue();
        page.close();
    }

    @Test
    void recoveryVerifier_sameBytesSameVerifier_differentBytesDifferentVerifier_neverEqualsRawBytes() {
        // e2eEncryption design doc §3 "복구 플로우 프로토콜": the verifier must be a deterministic,
        // one-way derivation of the recovery code bytes - deterministic so the server can compare
        // it across the verify and reset requests, one-way so it never leaks the raw AES-GCM key
        // material those bytes actually are.
        var page = newPage();
        Object result = page.evaluate("""
            async () => {
                const bytesA = OE_CRYPTO.generateRecoveryKeyBytes();
                const bytesB = OE_CRYPTO.generateRecoveryKeyBytes();

                const verifierA1 = await OE_CRYPTO.deriveRecoveryVerifier(bytesA);
                const verifierA2 = await OE_CRYPTO.deriveRecoveryVerifier(bytesA);
                const verifierB = await OE_CRYPTO.deriveRecoveryVerifier(bytesB);

                return {
                    sameBytesSameVerifier: verifierA1 === verifierA2,
                    differentBytesDifferentVerifier: verifierA1 !== verifierB,
                    verifierNotEqualToRawBytes: verifierA1 !== OE_CRYPTO.bufToBase64(bytesA)
                };
            }
            """);
        var map = (Map<?, ?>) result;
        assertThat((Boolean) map.get("sameBytesSameVerifier")).isTrue();
        assertThat((Boolean) map.get("differentBytesDifferentVerifier")).isTrue();
        assertThat((Boolean) map.get("verifierNotEqualToRawBytes")).isTrue();
        page.close();
    }

    @Test
    void workspaceKey_wrapForMultipleMembers_eachUnwrapsIndependently() {
        var page = newPage();
        Object ok = page.evaluate("""
            async () => {
                const wsKey = await OE_CRYPTO.generateWorkspaceKey();
                const alice = await OE_CRYPTO.generateKeyPair();
                const bob = await OE_CRYPTO.generateKeyPair();

                const wrappedForAlice = await OE_CRYPTO.wrapWorkspaceKeyForUser(wsKey, alice.publicKey);
                const wrappedForBob = await OE_CRYPTO.wrapWorkspaceKeyForUser(wsKey, bob.publicKey);

                const aliceWsKey = await OE_CRYPTO.unwrapWorkspaceKeyForUser(wrappedForAlice, alice.privateKey);
                const bobWsKey = await OE_CRYPTO.unwrapWorkspaceKeyForUser(wrappedForBob, bob.privateKey);

                // Both members' independently-unwrapped workspace keys must decrypt the same
                // shared content: wrap a DEK with Alice's copy, unwrap it with Bob's copy.
                const dek = await OE_CRYPTO.generateContentKey();
                const wrappedDek = await OE_CRYPTO.wrapContentKeyWithWorkspaceKey(dek, aliceWsKey);
                const dekFromBob = await OE_CRYPTO.unwrapContentKeyWithWorkspaceKey(wrappedDek, bobWsKey);

                const enc = await OE_CRYPTO.encryptContent('127.0.0.1 shared.oe', dek);
                const decrypted = await OE_CRYPTO.decryptContent(enc, dekFromBob);
                return decrypted === '127.0.0.1 shared.oe';
            }
            """);
        assertThat((Boolean) ok).isTrue();
        page.close();
    }

    @Test
    void contentKey_privateVisibility_wrappedWithPersonalKey_roundTrips() {
        var page = newPage();
        Object ok = page.evaluate("""
            async () => {
                const owner = await OE_CRYPTO.generateKeyPair();
                const dek = await OE_CRYPTO.generateContentKey();
                const enc = await OE_CRYPTO.encryptContent('127.0.0.1 private.oe', dek);
                const wrappedDek = await OE_CRYPTO.wrapContentKeyWithPersonalKey(dek, owner.publicKey);

                const unwrappedDek = await OE_CRYPTO.unwrapContentKeyWithPersonalKey(wrappedDek, owner.privateKey);
                const decrypted = await OE_CRYPTO.decryptContent(enc, unwrappedDek);
                return decrypted === '127.0.0.1 private.oe';
            }
            """);
        assertThat((Boolean) ok).isTrue();
        page.close();
    }

    @Test
    void contentKey_tamperedCiphertext_failsToDecrypt() {
        var page = newPage();
        Object threw = page.evaluate("""
            async () => {
                const dek = await OE_CRYPTO.generateContentKey();
                const enc = await OE_CRYPTO.encryptContent('127.0.0.1 tamper-test.oe', dek);

                // Flip a byte in the ciphertext - AES-GCM's auth tag must catch this.
                const bytes = new Uint8Array(OE_CRYPTO.base64ToBuf(enc.ciphertext));
                bytes[0] = bytes[0] ^ 0xFF;
                const tampered = { iv: enc.iv, ciphertext: OE_CRYPTO.bufToBase64(bytes) };

                try {
                    await OE_CRYPTO.decryptContent(tampered, dek);
                    return false;
                } catch (e) {
                    return true;
                }
            }
            """);
        assertThat((Boolean) threw).isTrue();
        page.close();
    }

    @Test
    void rotation_privateToWorkspaceVisibility_dekUnchanged_onlyRewrapped() {
        // Mirrors e2eEncryption design doc §7's "private ↔ collabo/public 전환" - content itself
        // (and its DEK) never changes, only which KEK wraps the DEK.
        var page = newPage();
        Object ok = page.evaluate("""
            async () => {
                const owner = await OE_CRYPTO.generateKeyPair();
                const wsKey = await OE_CRYPTO.generateWorkspaceKey();
                const dek = await OE_CRYPTO.generateContentKey();
                const enc = await OE_CRYPTO.encryptContent('127.0.0.1 rotate.oe', dek);

                // Starts 'private': DEK wrapped by owner's personal key.
                const wrappedPrivate = await OE_CRYPTO.wrapContentKeyWithPersonalKey(dek, owner.publicKey);

                // Rotate to workspace-shared: unwrap with the personal key, re-wrap with the
                // workspace key. The ciphertext (enc) itself is never touched.
                const recoveredDek = await OE_CRYPTO.unwrapContentKeyWithPersonalKey(wrappedPrivate, owner.privateKey);
                const wrappedShared = await OE_CRYPTO.wrapContentKeyWithWorkspaceKey(recoveredDek, wsKey);

                const dekAgain = await OE_CRYPTO.unwrapContentKeyWithWorkspaceKey(wrappedShared, wsKey);
                const decrypted = await OE_CRYPTO.decryptContent(enc, dekAgain);
                return decrypted === '127.0.0.1 rotate.oe';
            }
            """);
        assertThat((Boolean) ok).isTrue();
        page.close();
    }
}
