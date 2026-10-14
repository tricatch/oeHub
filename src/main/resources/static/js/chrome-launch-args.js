// Shared Chrome launch-flag building logic for the oeHosts editor (hosts.pebble) and its public
// share page (hosts-share.pebble) - keeps the flag order/masking rules in exactly one place since
// both pages must stay in sync.

// Flags (without the leading --) that run programs, weaken the sandbox, open a debugging channel or
// redirect traffic. The oelink launchers (oelink/mac/install_mac_oelink.sh and
// oelink/win/_internal/_oelink_exe.ps1) refuse the same names: keep the three lists in step.
// A trailing * matches any suffix.
const DENIED_CHROME_FLAGS = [
  'renderer-cmd-prefix', 'gpu-launcher', 'utility-cmd-prefix', 'zygote-cmd-prefix',
  'plugin-launcher', 'ppapi-plugin-launcher', 'nacl-gdb', 'nacl-gdb-script', 'browser-subprocess-path',
  'load-extension', 'disable-extensions-except', 'load-component-extension',
  'remote-debugging-*', 'remote-allow-origins', 'enable-automation',
  'no-sandbox', 'disable-gpu-sandbox', 'disable-setuid-sandbox', 'disable-web-security',
  'disable-site-isolation-trials', 'allow-file-access-from-files', 'allow-running-insecure-content',
  'ignore-certificate-errors*', 'proxy-pac-url', 'proxy-auto-detect', 'js-flags',
  'utility-and-browser-sandbox-cmd-prefix', 'ppapi-flash-path', 'enable-logging', 'log-file',
];

function isDeniedChromeFlag(line) {
  const m = /^--([A-Za-z0-9][A-Za-z0-9-]*)(?:=|$)/.exec(line);
  if (!m) return false;
  const name = m[1].toLowerCase();
  return DENIED_CHROME_FLAGS.some(p => p.endsWith('*') ? name.startsWith(p.slice(0, -1)) : name === p);
}

// A quote or control character would end the quoted value early and let the rest be read as
// further launch arguments; no legitimate path, user agent or proxy address contains one.
function cleanArgValue(v) {
  return String(v).replace(/["\u0000-\u001f]/g, '');
}

function splitExtraArgsLines(text) {
  return text.split('\n').map(l => cleanArgValue(l.trim())).filter(l => l && !l.startsWith('//') && !isDeniedChromeFlag(l));
}

function chromeOelinkValue(uddEnabled, userDataDir) {
  return (uddEnabled && userDataDir) ? crc32(userDataDir) : 0;
}

/**
 * Builds Chrome's launch flags in the order shared by every "real" launcher (the oelink scheme
 * string and the direct win/mac command). Returns the flags as an array, sorted except for the
 * leading --oelink and trailing URL entries the caller adds itself.
 */
function buildChromeArgParts(opts) {
  const parts = [];
  if (opts.devTools) parts.push('--auto-open-devtools-for-tabs');
  if (opts.extraArgsEnabled) splitExtraArgsLines(opts.extraArgsVal).forEach(l => parts.push(l));
  if (opts.hrrEnabled && opts.rulesStr) parts.push(`--host-resolver-rules="${cleanArgValue(opts.rulesStr)}"`);
  if (opts.proxyServerEnabled && opts.proxyServerValue) parts.push(`--proxy-server="${cleanArgValue(opts.proxyServerValue)}"`);
  if (opts.incognito) parts.push('--incognito');
  if (opts.uddEnabled && opts.userDataDir) parts.push(`--user-data-dir="${cleanArgValue(opts.userDataDir)}"`);
  if (opts.uaEnabled && opts.userAgent) parts.push(`--user-agent="${cleanArgValue(opts.userAgent)}"`);
  parts.sort();
  return parts;
}

/**
 * Builds the flag-per-line preview list shown in the UI - unlike buildChromeArgParts, this masks
 * --host-resolver-rules to a fixed placeholder since the resolved mapping can be long.
 */
function buildChromeArgLines(opts) {
  const lines = [];
  if (opts.devTools) lines.push('--auto-open-devtools-for-tabs');
  if (opts.extraArgsEnabled) splitExtraArgsLines(opts.extraArgsVal).forEach(l => lines.push(l));
  if (opts.hrrEnabled && opts.hasRules) lines.push('--host-resolver-rules "MAP ..."');
  if (opts.proxyServerEnabled && opts.proxyServerValue) lines.push(`--proxy-server="${cleanArgValue(opts.proxyServerValue)}"`);
  if (opts.incognito) lines.push('--incognito');
  if (opts.uddEnabled && opts.userDataDir) lines.push(`--user-data-dir="${opts.userDataDir}"`);
  if (opts.uaEnabled && opts.userAgent) lines.push(`--user-agent="${opts.userAgent}"`);
  lines.sort();
  const oelinkValue = chromeOelinkValue(opts.uddEnabled, opts.userDataDir);
  if (opts.oelinkEnabled) lines.unshift(`--oelink=${oelinkValue}`);
  if (opts.urlEnabled && opts.url) lines.push('  ' + opts.url);
  return lines;
}

/** Renders the win/mac direct-launch command line from an already-built flag list. */
function buildChromeCommandLine(os, parts) {
  if (os === 'win') {
    const argList = parts.map(winArgQuote).join(', ');
    return parts.length > 0
      ? `Start-Process "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe" -ArgumentList ${argList}`
      : 'Start-Process "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe"';
  }
  return parts.length > 0
    ? 'open -na "Google Chrome" --args ' + parts.map(macArgQuote).join(' ')
    : 'open -na "Google Chrome"';
}

function regexLiteral(s) {
  return String(s).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * Renders the command that force-closes the Chrome a fresh launch would collide with: a running
 * Chrome on the same --user-data-dir ignores new launch flags and just opens a window in itself.
 * With a user data dir only the processes started on that exact dir are closed; without one there
 * is no way to tell instances apart, so every Chrome is closed.
 */
function buildChromeKillCommand(os, userDataDir) {
  if (os === 'win') {
    if (!userDataDir) return 'Stop-Process -Name chrome -Force -ErrorAction SilentlyContinue';
    const pattern = '--user-data-dir="?' + regexLiteral(userDataDir) + '(?:"|\\s|$)';
    return `Get-CimInstance Win32_Process -Filter "Name='chrome.exe'" | Where-Object { $_.CommandLine -match ${winArgQuote(pattern)} } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }`;
  }
  if (!userDataDir) return 'pkill -x "Google Chrome"';
  // The pattern goes inside a double-quoted shell string: $HOME must still expand there (the
  // launch command relies on the same expansion), every other character is matched literally.
  const dir = userDataDir.split('$HOME')
    .map(seg => regexLiteral(seg).replace(/["`$]/g, '\\$&'))
    .join('$HOME');
  return `pkill -f -- "--user-data-dir=${dir}( |\\$)"`;
}

/** Joins the kill command and the launch command, with a pause so the profile lock is released. */
function buildChromeKillLaunchCommand(os, launchCmd, userDataDir) {
  const pause = os === 'win' ? 'Start-Sleep -Seconds 1' : 'sleep 1';
  return `${buildChromeKillCommand(os, userDataDir)}; ${pause}; ${launchCmd}`;
}
