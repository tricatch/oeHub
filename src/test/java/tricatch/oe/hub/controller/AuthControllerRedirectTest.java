package tricatch.oe.hub.controller;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthControllerRedirectTest {

    @Test
    void plainAbsolutePath_isSafe() {
        assertThat(AuthController.isSafeRedirect("/oehub/hosts")).isTrue();
    }

    @Test
    void rootPath_isSafe() {
        assertThat(AuthController.isSafeRedirect("/")).isTrue();
    }

    @Test
    void nullOrEmpty_isUnsafe() {
        assertThat(AuthController.isSafeRedirect(null)).isFalse();
        assertThat(AuthController.isSafeRedirect("")).isFalse();
    }

    @Test
    void relativePathWithoutLeadingSlash_isUnsafe() {
        assertThat(AuthController.isSafeRedirect("oehub/hosts")).isFalse();
    }

    @Test
    void protocolRelative_doubleSlash_isUnsafe() {
        assertThat(AuthController.isSafeRedirect("//evil.com")).isFalse();
        assertThat(AuthController.isSafeRedirect("//evil.com/path")).isFalse();
    }

    @Test
    void protocolRelative_backslashVariant_isUnsafe() {
        // Some browsers normalize a leading "/\" into "//", turning this into a
        // protocol-relative redirect to evil.com even though it starts with a single "/".
        assertThat(AuthController.isSafeRedirect("/\\evil.com")).isFalse();
    }

    @Test
    void schemeSmuggledAfterPath_isStillSafe() {
        // No scheme/host possible once the second character is neither '/' nor '\' - this is
        // just an unusual but harmless path segment, not a redirect target.
        assertThat(AuthController.isSafeRedirect("/:evil.com")).isTrue();
    }
}
