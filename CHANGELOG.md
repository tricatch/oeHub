# Changelog

All notable changes to this project are documented in this file.

## [0.9.4] - 2026-09-05

### Added
- oeProxy: added a "Use This IP" action next to the OID/IP badge that explicitly claims the current browsing IP as an owner-identification fallback for 8 hours. The badge shows which account (if any) already holds the current IP, in red, before you claim it.
- oeProxy forward proxy: a self-loop request — one that routes back into this server's own reverse proxy, via a `${PROXY_SVR}` oeHosts entry or a literal loopback address hardcoded directly in one — is now automatically attributed to the authenticated forward-proxy account, without needing an `X-OeHub-Oid` header.

### Changed
- oeProxy: the `X-OeHub-Oid` header value is now HMAC-signed.
- oeProxy: the IP-based owner-identification fallback is now an explicit, time-bounded (8 hour) claim made via "Use This IP" instead of being registered implicitly whenever vhost config is applied or lazily reloaded from a cache miss; claiming a new IP for an account releases that account's previous claim.
- oeProxy: live traffic monitoring no longer copies request/response bodies and headers into an event when no monitor tab is currently watching that account, removing that per-request overhead from all proxied traffic when the monitor isn't open.

### Removed
- oeProxy: the "single account + loopback client" automatic owner-identification shortcut has been removed — it broke as soon as a second account existed and gave no indication that identification was implicit rather than explicit. Use the new "Use This IP" action or the oeOID Chrome extension instead.

### Fixed
- oeProxy forward proxy: a destination that maps to this server's own loopback address is no longer silently redirected to the same generic "not whitelisted" 403 page — it now shows the actual reason (loopback target, unresolved host, or not whitelisted) with matching guidance.
- oeProxy forward proxy: a client connecting from 127.0.0.1 is exempt from the loopback-destination SSRF guard, since it already has direct access to every loopback-bound port on the machine — fixes legitimate local-testing oeHosts entries (e.g. `${PROXY_SVR}`-less `127.0.0.1 mydomain`) being blocked.
- oeProxy forward proxy: self-loop owner attribution now also requires the accepted reverse-proxy connection's actual peer IP to match the IP the forward proxy's own outbound connection used, closing a window where a different host on the same network could otherwise be attributed to another account's self-loop request by reusing its ephemeral source port.
- oeProxy: fixed the live traffic monitor occasionally showing a response event with no matching request row — the "is a monitor tab watching this account" check is now decided once per request and reused for both the request and response sides (and the response body relay), instead of being independently re-checked at each point.

## [0.9.3] - 2026-09-02

### Added
- oeProxy: added a forward (upstream) proxy on a fixed port (36980), authenticated with oeHub account credentials. Once authenticated, requests for hosts in that user's currently-selected oeHosts profiles are routed to the recorded IP from an in-memory per-user map instead of a per-request DB lookup; editing or toggling a selected profile refreshes the cache live. Always running, not admin-toggleable.
- oeHosts: added a `--proxy-server` Chrome launch option pointing at the new forward proxy, placed directly below `--host-resolver-rules`. Enabling either `--proxy-server` or `--host-resolver-rules` automatically disables the other, since Chrome ignores `--host-resolver-rules` for requests sent through a fixed proxy.
- oeProxy forward proxy: added an admin-configurable relay whitelist (Settings > oeProxy - Forward Proxy). One domain per line, with `*.` wildcard prefix matching the domain and its subdomains; when non-empty, only whitelisted destinations may be relayed and all others get a 403 rendered as a branded oeProxy error page (matching the reverse proxy's existing 404/502/503 pages). Empty (default) keeps prior unrestricted behavior. For blocked HTTPS destinations, the CONNECT tunnel is redirected to a new loopback-only internal server (port 36981) that completes the TLS handshake with a CA-issued certificate for the requested host and serves the same 403 page, so blocked HTTPS requests render identically to blocked HTTP ones instead of just failing the tunnel.
- Admin: user management now has a "Reset Password" action that generates a random password for the selected account and displays it once for the admin to relay.
- Accounts: added a self-service "Change Password" menu (current / new / confirm password) available to all users, from the account dropdown.

### Fixed
- oeProxy forward proxy: the `${PROXY_SVR}` placeholder in oeHosts profile content is now resolved to the accepting connection's local address; previously it was left unresolved and silently fell back to normal DNS resolution.

### Security
- Updated `jackson-databind`/`jackson-datatype-jsr310`/`jackson-dataformat-yaml` 2.21.3 → 2.22.2, fixing a HIGH-severity `PolymorphicTypeValidator` bypass (CVE-2026-54512) allowing arbitrary class instantiation, plus several moderate `@JsonView`/`@JsonIgnore` mass-assignment bypasses.
- Updated `bcprov-jdk18on`/`bcpkix-jdk18on`/`bcutil-jdk18on` 1.79 → 1.85, fixing a CRITICAL GOST 28147 CTR keystream-reuse bug (CVE-2025-14813) plus a moderate LDAP injection and a moderate risky-cipher issue.
- Updated `assertj-core` (test-only) 3.27.3 → 3.27.7, fixing a HIGH-severity XXE in `isXmlEqualTo` (CVE-2026-24400).

## [0.9.2] - 2026-08-05

### Added
- Windows: `oelink.exe` is now code-signed as part of the build process, avoiding the "unknown publisher" SmartScreen warning on launch.

### Fixed
- oeProxy: fixed a virtual-thread starvation bug where `HttpStreamReader`/`HttpStreamWriter` inherited `synchronized` buffered I/O from `BufferedInputStream`/`BufferedOutputStream`; a virtual thread blocked on a read pinned its carrier thread instead of yielding it, so a handful of concurrent connections could stall unrelated requests (e.g. static assets stuck pending) for up to the read-timeout.
- oeProxy: an upstream connection timeout or failure now returns a proper 502/504 error response instead of leaving the client's request pending indefinitely.
- oelink (macOS): the "Chrome already running" relaunch dialog now follows the OS display language instead of always showing Korean.

## [0.9.1] - 2026-07-15

### Added
- oeHosts: the merge preview panel (shown when 2+ profiles are selected) now has a Save button that creates a new profile from the merged hosts entries, keeping `${PROXY_SVR}` unresolved so the saved profile stays portable.
- oeProxy: the Routes panel now has a Save button that creates a new virtual host from the merged routes, keeping `${LOCAL_SVR}` unresolved so the saved vhost stays portable.
- `OidUtil.decode(oid)` reverses `OidUtil.encode(userNo)`, recovering the original `user_no` from an OID string (or `null` for a malformed one).

### Fixed
- The `X-OeHub-Oid` request header is now decoded and validated before use; a malformed or spoofed header is rejected immediately instead of silently failing the virtual host lookup.
- oeProxy virtual host routing no longer breaks for the first request after a server restart: if a user's routes aren't yet in the in-memory cache, they're now lazily rebuilt from that user's persisted, currently-selected vhosts instead of requiring a re-login or re-save.

## [0.9.0] - 2026-07-11

Initial public release. oeHub bundles two browser-facing dev tools behind a single login.

### oeHosts
- Manage and switch between multiple Chrome host-resolver profiles.
- Merge multiple profiles and preview rules before launching.
- Share profiles with your team via a link.
- Launch Chrome directly with a profile's rules and custom flags (`--user-agent`, `--user-data-dir`, incognito, extra args) via the `oelink://` protocol handler (Windows & macOS).
- Quick-fill URL/User-Agent from admin or personal presets.

### oeProxy
- HTTPS reverse proxy for local virtual hosts, backed by an auto-generated (or imported) self-signed root CA.
- Real-time HTTP request monitor.
- oeOID Chrome extension tags proxied requests with an `X-OeHub-Oid` header for per-user request tracing.

### Accounts
- Multi-user with admin/user roles, first-run setup wizard.
- Per-user backup/restore of hosts profiles, hosts settings, and proxy vhosts (JSON).
