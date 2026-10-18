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

// %USERPROFILE% (from resolveUDD) is cmd syntax that PowerShell leaves alone, and Chrome given the
// literal text cannot use it as a profile path: it hands the launch to the already-running default
// Chrome instead. Expand such arguments when the command runs.
function winExpandEnv(quoted) {
  return `([Environment]::ExpandEnvironmentVariables(${quoted}))`;
}

function winLaunchArg(p) {
  return p.includes('%') ? winExpandEnv(winArgQuote(p)) : winArgQuote(p);
}

/** Renders the win/mac direct-launch command line from an already-built flag list. */
function buildChromeCommandLine(os, parts) {
  if (os === 'win') {
    const argList = parts.map(winLaunchArg).join(', ');
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
 * Renders the one-line script that closes the Chrome a fresh launch would collide with, then
 * launches: a running Chrome on the same --user-data-dir ignores new launch flags and just opens a
 * window in itself. With a user data dir only the browser process started on that exact dir is
 * targeted; without one there is no way to tell instances apart, so every Chrome is.
 *
 * When such a Chrome is running the script asks first and does nothing unless the answer is y.
 * It then asks Chrome to quit (WM_CLOSE via taskkill on Windows, SIGTERM on macOS) so tabs and the
 * session are saved, waits up to 10 seconds, and force-kills only what is still alive.
 * Kept on one line on purpose: a multi-line paste into a shell would feed the next pasted line
 * to the prompt as its answer.
 */
function buildChromeKillLaunchCommand(os, launchCmd, userDataDir) {
  const prompt = MSG['modal.chrome.cmd.kill.prompt'] + ' [y/N] ';
  if (os === 'win') {
    // Only the browser process: helpers carry --type=, and the browser closes them itself.
    let filter = "$_.CommandLine -notmatch '--type='";
    if (userDataDir) {
      // Chrome's command line carries the expanded path, so expand and escape it at run time.
      const dir = `[regex]::Escape(${winExpandEnv(winArgQuote(userDataDir))})`;
      filter += ` -and $_.CommandLine -match ('--user-data-dir="?' + ${dir} + '(?:"|\\s|$)')`;
    }
    return `$p = @(Get-CimInstance Win32_Process -Filter "Name='chrome.exe'" | Where-Object { ${filter} }); `
      + `if ($p.Count -eq 0 -or (Read-Host ${winArgQuote(prompt.trimEnd())}) -eq 'y') { `
      + `if ($p) { $p | ForEach-Object { taskkill /PID $_.ProcessId *> $null }; `
      + 'Wait-Process -Id $p.ProcessId -Timeout 10 -ErrorAction SilentlyContinue; '
      + 'Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue; Start-Sleep -Seconds 1 }; '
      + `${launchCmd} }`;
  }
  let sel, pattern;
  if (userDataDir) {
    // The pattern goes inside a double-quoted shell string: $HOME must still expand there (the
    // launch command relies on the same expansion), every other character is matched literally.
    // "MacOS/Google Chrome " (with the space) is the browser's own binary, not a Helper.
    const dir = userDataDir.split('$HOME')
      .map(seg => regexLiteral(seg).replace(/["`$]/g, '\\$&'))
      .join('$HOME');
    sel = '-f --';
    pattern = `"MacOS/Google Chrome .*--user-data-dir=${dir}( |\\$)"`;
  } else {
    sel = '-x';
    pattern = '"Google Chrome"';
  }
  const quoted = "'" + prompt.replace(/'/g, "'\\''") + "'";
  return `p=${pattern}; `
    + `if ! pgrep ${sel} "$p" >/dev/null || { printf '%s' ${quoted}; read a; [ "$a" = y ] || [ "$a" = Y ]; }; then `
    + `if pkill ${sel} "$p"; then i=0; while pgrep ${sel} "$p" >/dev/null && [ $i -lt 20 ]; do sleep 0.5; i=$((i+1)); done; `
    + `pkill -9 ${sel} "$p"; sleep 1; fi; `
    + `${launchCmd}; fi`;
}
