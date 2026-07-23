# Changelog

All notable changes to this project are documented in this file.

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
