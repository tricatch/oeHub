# oeHub

> **CLAUDE.md update rule:** Record rules and procedures only. Exclude mutable data such as mapping tables and lists. All text must be written in English.

## Brand Colors

- BC100: `#dff2b8` <span style="background:#dff2b8;display:inline-block;width:13px;height:13px;border-radius:3px;vertical-align:middle;border:1px solid rgba(0,0,0,0.15)"></span> — selected item background, hover background tint
- BC300: `#a8d878` <span style="background:#a8d878;display:inline-block;width:13px;height:13px;border-radius:3px;vertical-align:middle;border:1px solid rgba(0,0,0,0.15)"></span> — navbar background, selected item border, primary button, list toggle checked
- BC500: `#7ab84a` <span style="background:#7ab84a;display:inline-block;width:13px;height:13px;border-radius:3px;vertical-align:middle;border:1px solid rgba(0,0,0,0.15)"></span> — icon hover, checkbox/switch checked, logo seed color
- BC700: `#5a9e2f` <span style="background:#5a9e2f;display:inline-block;width:13px;height:13px;border-radius:3px;vertical-align:middle;border:1px solid rgba(0,0,0,0.15)"></span> — icon hover (new button)
- Always use these colors for brand-related UI elements. Do not introduce new greens.

## Buttons

- Use brand greens (BC100–BC500) for primary/positive actions; gray-neutral for secondary; red only for destructive hover states.
- Border-radius is always 6px. Font-weight 600 for labeled buttons.
- Navbar actions are icon-only and borderless. All other buttons have a visible border or fill.
- Table row actions are icon-only — no label. Express intent through the icon, not button color. Show red tint on hover for destructive actions; keep neutral at rest.
- Do not mix Bootstrap utility button classes (`btn-outline-*`, `btn-danger`, etc.) with oe button classes. Use one system per page context.
- Button CSS belongs in the shared stylesheet for that scope (`admin.css`, `brand.css`, etc.), not inline in templates.

## Database Schema

- Schema migrations use `CREATE TABLE IF NOT EXISTS` only. `ALTER TABLE` is not used during the development phase. To apply schema changes, modify the `CREATE TABLE` statement and restart the application after deleting the H2 database file (`~/oeHub/data/oeHub-h2.*`).
- Timestamp columns use the `_at` suffix (e.g. `create_at`, `updated_at`, `last_login_at`). Never use `_dt`.
- Column ordering rule: business columns first, then timestamp columns at the end in this order: `create_at`, `updated_at`, additional timestamps (e.g. `last_login_at`).
- When adding a new non-timestamp column, insert it before the timestamp block.
- When adding a new timestamp column, append it after `updated_at` (and after any existing extra timestamps).
- Apply this ordering consistently in: `CREATE TABLE` statements, all `SELECT` column lists, `INSERT` column lists, and mapper method signatures.

## Mapper — Timestamp Handling

- Timestamp values (`updated_at`, etc.) must **never** be set inside SQL using `CURRENT_TIMESTAMP`. Always pass them as bind parameters (e.g. `#{updatedAt}`).
- The calling service is responsible for setting the timestamp via `LocalDateTime.now()` on the model or as an explicit `@Param` before invoking the mapper method.
- This keeps the Java object and the database value in sync without requiring a post-insert re-fetch.

## Page Layout — Width Toggle System

- Full-page tool pages (settings, licenses, users, etc.) must wrap their content in a `.settings-app` container (`<div class="settings-app" id="...App">`).
- In collapsed state the container is centered at `max-width: 1400px` with `border-left` and `border-right` side lines (`1px solid #dee2e6`). These borders are removed when the `.expanded` class is present.
- Each such page must apply the expanded state immediately at render time with an inline script at the top of the container:
  ```js
  (function(){if(localStorage.getItem('oeWidthExpanded')==='true'){document.getElementById('...App').classList.add('expanded');document.body.classList.add('settings-expanded');}})();
  ```
- Panel-based tool pages (hosts, proxy) use `.hosts-app` / `.proxy-app` containers instead — see `oe-panel.css`.

## Proxy Package — Edit Restrictions

- The `tricatch.oe.proxy` package is a low-level networking layer. Do **not** modify files in this package for style, comment cleanup, or refactoring unless the change is directly required by a bug fix or feature task.
- This restriction covers all sub-packages: `proxy.pass`, `proxy.http`, `proxy.event`, `proxy.server`, `proxy.cfg`, `proxy.util`, etc.

## Forward Proxy — LittleProxy Reflection Dependency

- `tricatch.oe.proxy.util.LittleProxyInternals` uses reflection to read two private fields (`org.littleshoot.proxy.FlowContext.clientConnection`, `org.littleshoot.proxy.impl.ClientToProxyConnection.currentServerConnection`) to work around a bug in `littleproxy` 2.9.0/2.9.1: `FullFlowContext.getProxyToServerContext()` always returns null for a raw (non-MITM) HTTP CONNECT tunnel, because the `FullFlowContext` gets cached (via `computeIfAbsent`) the first time any activity-tracking code asks for it — before the proxy-to-server channel exists — permanently freezing a null context into the cached object. Confirmed present in both 2.9.0 and 2.9.1 via a standalone reproduction outside oeHub, and in the upstream GitHub source (no code path invokes the equivalent success callback for a raw CONNECT tunnel), so this is not something a routine version bump fixes.
- Whenever the `littleproxy` dependency version changes (in `build.gradle.kts`), re-verify `LittleProxyInternals` first: confirm the two private field names still exist and still resolve the live proxy-to-server `ChannelHandlerContext` for a raw CONNECT tunnel. A standalone reproduction (outside the app, hitting a real CONNECT request through a real `DefaultHttpProxyServer` instance) is the fastest way to confirm this — testing through the full app makes a reflection failure easy to miss, since it degrades silently (see below).
- If the reflection fails (`NoSuchFieldException`, etc.), `LittleProxyInternals` logs a warning once and returns null rather than throwing — self-loop owner resolution (see `SelfLoopOwnerRegistry`) then simply stops firing; the `X-OeHub-Oid` header and "Use This IP" fallbacks keep working. Do not remove this fallback behavior when touching this class.

## HTML Templates

- Use the Pebble template engine. (`src/main/resources/templates/`)
- Extract shared markup used in two or more places into a separate template file.
- **oeProxy error templates** embed all CSS inline within the HTML file. External stylesheets cannot be loaded in a virtual-host error context, so styles must be self-contained.

## JavaScript

- Scripts used in two or more places must be extracted into a shared file under `src/main/resources/static/js/`.

## i18n — UI Messages

- All user-facing UI strings (button labels, toast messages, tooltips, status text, placeholders, confirm prompts, feedback messages) must be defined in `src/main/resources/i18n/messages_en.properties` and mirrored in `messages_ko.properties`.
- Never hardcode UI strings directly in templates or JavaScript. Always reference a message key.
- In Pebble templates, use `{{ msg['key'] }}`.
- In JavaScript, use `MSG['key']` (available as `window.MSG` injected by the layout).
- When adding a new UI string, add the key to **both** `messages_en.properties` and `messages_ko.properties`, then reference it.
- Language is stored in a `lang` cookie (`en` or `ko`) and switched via `GET /lang/{locale}`.
- The active locale is available in templates as `{{ currentLocale }}` and injected as `window.MSG` for JS.

## Typography

- UI body font: rely on Bootstrap's default system font stack. Do not declare a custom `font-family` for body text.
- Monospace (code, editors, log columns, detail tables): always use `'Consolas', 'Monaco', 'Courier New', monospace`. Do not use bare `monospace` or alternative stacks (`SFMono-Regular`, `Cascadia Code`, etc.).
- No external font services (Google Fonts, CDN typefaces) are used. Keep it that way.

## Icon — [Lucide Icons](https://lucide.dev/icons)

- SVG files: `src/main/resources/static/icon/lucide/`
- CSS definitions: `src/main/resources/static/css/icon.css`
- Class names must be **semantic (role-based)**. File-name-based class names are not allowed.
- To swap an icon, change only the `url()` in `icon.css`. Templates must not be modified.

### Adding a new icon

1. Add the SVG file to `static/icon/lucide/`
2. Add a semantic class in `icon.css` with the `url()` path
3. Use the semantic class name in templates

## Dependencies — License Listing

- Whenever a new third-party library is added to `build.gradle.kts` (backend) or bundled as a static frontend asset, add a corresponding row to `src/main/resources/templates/oehub/licenses.pebble`.
- Each row includes: library name (linked to its homepage/repo), the exact version in use, and a license badge (`license-badge badge-apache`, `badge-mit`, `badge-mpl`, etc., matching the library's actual license).
- Keep rows in alphabetical order by library name.
- When a dependency's version is bumped, update its version cell in the same row.
