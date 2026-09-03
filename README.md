# oeHub

A hub of tools for building development and test environments.

oeHub bundles two browser-facing tools behind a single login: **oeHosts**, which switches Chrome's host-resolver rules per profile and launches Chrome with them via the `oelink://` protocol handler, and **oeProxy**, which terminates HTTPS for local virtual hosts and reverse-proxies to local or remote dev servers, tagging requests with the oeOID header for per-user tracing. This lets a team point real-looking domains at whichever backend — local, staging, or production — they're working against, without touching the OS-level hosts file or juggling certificates by hand.

## Screenshots

<a href="https://velog.velcdn.com/images/tricatch/post/7f21857d-9ac5-439b-b371-35c92faac773/image.png"><img src="https://velog.velcdn.com/images/tricatch/post/7f21857d-9ac5-439b-b371-35c92faac773/image.png" width="200" alt="oeHub screenshot 1"></a>
<a href="https://velog.velcdn.com/images/tricatch/post/7d645fc2-2625-48a9-9188-081cf4ab5ecf/image.png"><img src="https://velog.velcdn.com/images/tricatch/post/7d645fc2-2625-48a9-9188-081cf4ab5ecf/image.png" width="200" alt="oeHub screenshot 2"></a>
<a href="https://velog.velcdn.com/images/tricatch/post/9f2edf4a-1e8b-471f-a0e2-16a41faf8cbc/image.png"><img src="https://velog.velcdn.com/images/tricatch/post/9f2edf4a-1e8b-471f-a0e2-16a41faf8cbc/image.png" width="200" alt="oeHub screenshot 3"></a>
<a href="https://velog.velcdn.com/images/tricatch/post/ec606461-131d-4687-ad0f-41b442a08af0/image.png"><img src="https://velog.velcdn.com/images/tricatch/post/ec606461-131d-4687-ad0f-41b442a08af0/image.png" width="200" alt="oeHub screenshot 4"></a>

## How It Works

<img src="https://velog.velcdn.com/images/tricatch/post/daaf1f0b-006c-4369-9e96-b7cff1551ead/image.png" alt="oeHub request flow: browser configures a domain mapping in oeHosts, launches Chrome via oelink with --host-resolver-rules, which routes the request to oeProxy for TLS termination and destination routing, forwarding to a local or remote dev server">

## Tools

### oeHosts (+oelink)

- Launch Chrome directly with a profile's rules and custom flags (`--user-agent`, `--user-data-dir`, incognito, extra args) through the `oelink://` protocol handler
    - **oelink** is a custom protocol handler installed on the client machine; oeHosts calls out to it to launch Chrome with a profile's `--host-resolver-rules` and other flags
    - Supports both Windows and macOS, with install/uninstall scripts under `oelink/win` and `oelink/mac`
    - The installer files under `oelink/` (including the prebuilt Windows `oelink.exe`) are committed as-is rather than generated at build time, so the protocol handler works right after cloning without a full Gradle build
- Switch between multiple hosts profiles in Chrome
- Select multiple profiles at once and preview the merged host-resolver rules before launching
- Share a hosts profile with your team via a share link
- Quick-fill "Open URL" and "User-Agent" fields from admin-managed or personal presets

### oeProxy (+oeOID)

- Auto-generates TLS certificates from a self-signed root CA (generate or import your own)
- HTTPS reverse proxy for local virtual hosts, letting a local dev server and a remote/production server share a single domain
- Real-time HTTP request monitor
- Tags proxied requests with an `X-OeHub-Oid` header so origin services can identify the acting oeHub user
    - **oeOID** is a Chrome extension that injects the `X-OeHub-Oid` header into requests passing through oeProxy, instead of relying on IP address (which breaks under DHCP)

### oeProxy Forward Proxy

- Always running (not admin-toggleable) on a fixed port, `36980`
- Requires authenticating with your oeHub account credentials before it will relay any request
- Once authenticated, requests for hosts in that user's currently-selected oeHosts profiles are routed to the recorded IP from an in-memory per-user map, instead of a per-request DB lookup
- oeHosts can point Chrome's `--proxy-server` flag at this forward proxy as an alternative to `--host-resolver-rules` (enabling either one disables the other)
- Admins can restrict relaying to a whitelist of domains (with `*.` wildcard subdomain matching); blocked destinations get a branded 403 error page, including for HTTPS via a dedicated loopback TLS responder on port `36981`
- Since it's a standard HTTP(S) proxy, mobile devices can redirect their hosts by pointing the device's Wi-Fi proxy settings (iOS or Android) at it — no `oelink`/host-resolver-rules equivalent needed on mobile
    - iOS's Wi-Fi proxy setting supports authentication, so it works for both browser and app traffic
    - Android's Wi-Fi proxy setting does not support authentication, so only the browser (which can prompt for and submit credentials itself) can authenticate and use it; other apps' traffic is not proxied

## Best Practices

- When using oeHosts, open the oeHub web UI itself in Edge (or another non-Chrome browser), and let `oelink` launch Chrome as the browser you actually test in. `oelink` launches Chrome with a profile-specific `--user-data-dir`; if the oeHub UI is also running in Chrome, the two can collide over the same user-data-dir and cause profile lock/launch conflicts. Keeping them in separate browsers also keeps your normal work environment (email, docs, regular browsing) cleanly separated from the test environment, where host-resolver rules are redirecting real-looking domains to local or staging servers.

## Accounts

- Multi-user, with `admin` and `user` roles
- The first-run `/setup` wizard creates the initial admin account
- Admins manage users (grant/revoke admin, delete accounts, reset a user's password to a randomly generated one) and the global URL/UA presets from the settings pages
- Each user has a personal settings page for their own presets, oeOID domain list, account info, and self-service password change
- Each user can back up and restore their own hosts profiles, hosts settings, and proxy vhosts as JSON

## Getting Started

Requires JDK 21.

```bash
# development mode (loads templates/static files directly from src, no jar build)
./gradlew run -Djava.net.preferIPv4Stack=true

# production build
./gradlew shadowJar
java -Djava.net.preferIPv4Stack=true -jar oeHub-<version>.jar
```

The app listens on port `36912` by default (override with `-Dport=`), and starts an H2 web console on `port + 1`.

On first launch you're redirected to a setup wizard (`/setup`) to create the admin account and configure the root CA used by oeProxy. Application data (H2 database) is stored under `~/oeHub` by default (`~` is the OS user's home directory).

> [!IMPORTANT]
> On Linux, oeProxy binding to port `443` requires running the JVM as `root`, which makes `~` resolve to `/root` instead of your normal user's home. Pass `-Dhome=/path/to/oeHub` to pin application data to the intended directory instead, e.g.:
> ```bash
> sudo java -Dhome=/home/youruser/oeHub -Djava.net.preferIPv4Stack=true -jar oeHub-<version>.jar
> ```
> `-Dhome` is the oeHub directory itself, not its parent (no `/oeHub` is appended to it).

### Logging

Logging is configured via [Logback](https://logback.qos.ch/), with a default `logback.xml` bundled inside the jar. To override it without rebuilding, point Logback at an external file on the classpath at startup:

```bash
java -Dlogback.configurationFile=/path/to/logback.xml -jar oeHub-<version>.jar
```

Logback checks this system property before falling back to the config packaged in the jar.

## Tech Stack

- [Javalin](https://javalin.io/) + [Pebble](https://pebbletemplates.io/) templates
- [MyBatis](https://mybatis.org/mybatis-3/) + [H2](https://www.h2database.com/) (embedded, file-based)
- [BouncyCastle](https://www.bouncycastle.org/) for certificate generation/parsing
- JWT-based session auth

## i18n

UI is available in English and Korean, switched via a `lang` cookie.

## License

[MIT](LICENSE) &copy; 2026 tricatch
