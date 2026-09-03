package tricatch.oe.proxy.exception;

import java.security.cert.CertificateException;

/**
 * Thrown when an upstream (origin) server's TLS certificate is neither chain-validated by the
 * JVM's default trust store nor pinned by an admin-approved fingerprint in the trusted-upstream-
 * certs allowlist. Distinguished from a generic {@link CertificateException} so the 502 error
 * page can tell the visitor specifically to ask an admin to approve the certificate, instead of
 * showing a generic connection-failure message.
 */
public class UntrustedUpstreamCertificateException extends CertificateException {

    public UntrustedUpstreamCertificateException(String host, String sha256Fingerprint) {
        super("Untrusted certificate for upstream host " + host + " (sha256=" + sha256Fingerprint + ")");
    }
}
