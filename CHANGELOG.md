# Changelog

All notable changes to this project are documented in this file.

## [0.9.3] - 2026-08-28

### Added
- oeProxy: added a forward (upstream) proxy on a fixed port (36980), authenticated with oeHub account credentials. Once authenticated, requests for hosts in that user's currently-selected oeHosts profiles are routed to the recorded IP from an in-memory per-user map instead of a per-request DB lookup; editing or toggling a selected profile refreshes the cache live. Always running, not admin-toggleable.
- oeHosts: added a `--proxy-server` Chrome launch option pointing at the new forward proxy, placed directly below `--host-resolver-rules`. Enabling either `--proxy-server` or `--host-resolver-rules` automatically disables the other, since Chrome ignores `--host-resolver-rules` for requests sent through a fixed proxy.
- oeProxy forward proxy: added an admin-configurable relay whitelist (Settings > oeProxy - Forward Proxy). One domain per line, with `*.` wildcard prefix matching the domain and its subdomains; when non-empty, only whitelisted destinations may be relayed and all others get a 403 rendered as a branded oeProxy error page (matching the reverse proxy's existing 404/502/503 pages). Empty (default) keeps prior unrestricted behavior. For blocked HTTPS destinations, the CONNECT tunnel is redirected to a new loopback-only internal server (port 36981) that completes the TLS handshake with a CA-issued certificate for the requested host and serves the same 403 page, so blocked HTTPS requests render identically to blocked HTTP ones instead of just failing the tunnel.
- Admin: user management now has a "Reset Password" action that generates a random password for the selected account and displays it once for the admin to relay.
- Accounts: added a self-service "Change Password" menu (current / new / confirm password) available to all users, from the account dropdown.

### Fixed
- oeProxy forward proxy: the `${PROXY_SVR}` placeholder in oeHosts profile content is now resolved to the accepting connection's local address; previously it was left unresolved and silently fell back to normal DNS resolution.

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
