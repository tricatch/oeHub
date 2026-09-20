#!/bin/bash
# oelink:// custom URL scheme installation script
#
# Scheme format:
#   oelink://chrome?<base64_urlsafe(browser argument string)>
#
# Encoding target: arguments passed directly to the browser (use shell quotes for values with spaces)
#
# Example:  --host-resolver-rules="MAP example.com 127.0.0.1" --incognito https://example.com
# → base64 URL-safe encoded → oelink://chrome?<encoded>
#
# How it works:
#   macOS URL schemes are delivered via Apple Events (kAEGetURL) → cannot be received via bash $1
#   The AppleScript 'on open location' handler receives the URL,
#   then the Bash launcher base64 decodes it → launches the target browser

set -e

APP_NAME="oelink"
APP_PATH="/Applications/${APP_NAME}.app"
SCHEME="oelink"
LAUNCHER_PATH="${APP_PATH}/Contents/MacOS/launcher.sh"

# ── 0. Remove existing app ───────────────────────────────────────────────────
echo "🗑  Removing existing app..."
rm -rf "$APP_PATH"

TMP_DIR=$(mktemp -d)
trap "rm -rf '$TMP_DIR'" EXIT

# ── 1. Write Bash launcher ───────────────────────────────────────────────────
#   URL parsing: oelink://<browser>?<base64_urlsafe_args>
#   Browser: chrome | edge
cat > "$TMP_DIR/launcher.sh" << 'BEOF'
#!/bin/bash
# oelink://<browser>?<base64_urlsafe(browser arguments)>
#
# Encoding example:
#   Original:  --host-resolver-rules="MAP host 1.2.3.4" --incognito https://example.com
#   Encode:    printf '%s' '<original>' | base64 | tr -d '\n' | tr '+/' '-_' | tr -d '='

LOG="/tmp/oelink_debug.log"

# ── UI 언어 감지 (macOS 표시 언어 기준, ko 외에는 en) ────────────────────────
get_lang() {
    local raw
    raw=$(defaults read -g AppleLanguages 2>/dev/null | sed -n '2p' | sed -E 's/[^a-zA-Z-]//g' | cut -d'-' -f1)
    if [[ "$raw" == "ko" ]]; then
        echo "ko"
    else
        echo "en"
    fi
}

# ── --oelink=<id> 값 추출 ────────────────────────────────────────────────────
#    값이 있는 형태(--oelink=abc123)만 추출; 값 없는 --oelink 플래그는 무시
get_oelink_id() {
    local cmdline="$1"
    if [[ "$cmdline" =~ --oelink=([^[:space:]\"]+) ]]; then
        printf '%s' "${BASH_REMATCH[1]}"
    fi
}

# ── 충돌하는 Chrome PID 목록 ─────────────────────────────────────────────────
#    --oelink=0        → --user-data-dir 없는 Chrome 탐색
#    --oelink=<crc32>  → --oelink 값이 같은 Chrome 탐색
#    * oelink 없이 일반 실행된 Chrome은 감지 대상 외 (고려하지 않음)
get_conflicting_pids() {
    local target_id="$1"
    local line pid rest oid
    while IFS= read -r line; do
        [[ "$line" != *"Google Chrome.app/Contents/MacOS/Google Chrome"* ]] && continue
        [[ "$line" == *--type=* ]] && continue
        read -r pid rest <<< "$line"
        [[ -z "$pid" ]] && continue
        if [[ "$target_id" == "0" ]]; then
            [[ "$rest" != *"--user-data-dir"* ]] && echo "$pid"
        else
            oid=$(get_oelink_id "$rest")
            # oelink 없이 실행된 Chrome(일반 실행 등)은 감지 대상 외
            [[ "$oid" == "$target_id" ]] && echo "$pid"
        fi
    done <<< "$(ps axwwo pid=,args=)"
}

# ── 동일 프로필의 oelink Chrome 종료 ─────────────────────────────────────────
stop_oelink_chrome() {
    local target_id="$1"
    local pids
    pids=$(get_conflicting_pids "$target_id") || true
    [[ -z "$pids" ]] && return

    echo "[oelink] Closing Chrome (oelink='$target_id', PIDs: $pids)" >> "$LOG"
    echo "$pids" | xargs kill -TERM 2>/dev/null || true
    sleep 3

    local remaining
    remaining=$(while read -r p; do [[ -z "$p" ]] && continue; kill -0 "$p" 2>/dev/null && echo "$p"; done <<< "$pids") || true
    if [[ -n "$remaining" ]]; then
        echo "[oelink] Force-killing Chrome PIDs: $remaining" >> "$LOG"
        echo "$remaining" | xargs kill -9 2>/dev/null || true
    fi
}

# ── 동일 프로필의 Chrome 실행 여부 확인 ──────────────────────────────────────
check_oelink_chrome() {
    local target_id="$1"
    local root_pids
    root_pids=$(get_conflicting_pids "$target_id") || true
    [[ -z "$root_pids" ]] && return 0

    echo "[oelink] Found running Chrome (oelink='$target_id', PIDs: $root_pids)" >> "$LOG"

    local lang msg btn_no btn_yes
    lang=$(get_lang)
    if [[ "$lang" == "ko" ]]; then
        msg="동일한 프로필로 실행 중인 Chrome이 있습니다.\n종료하고 다시 실행하시겠습니까?"
        btn_no="아니오"; btn_yes="예"
    else
        msg="Chrome is already running with the same profile.\nClose it and relaunch?"
        btn_no="No"; btn_yes="Yes"
    fi

    local as_cmd
    as_cmd="display dialog \"$msg\" with title \"oelink\" buttons {\"$btn_no\", \"$btn_yes\"} default button \"$btn_yes\""
    local answer
    answer=$(/usr/bin/osascript -e "$as_cmd" 2>/dev/null) || true
    if echo "$answer" | grep -q "$btn_yes"; then
        stop_oelink_chrome "$target_id"
        return 0
    fi
    return 1
}

# ── base64 URL-safe decode ───────────────────────────────────────────────────
decode_b64() {
    local encoded="$1"
    local b64="${encoded//-/+}"
    b64="${b64//_//}"
    local rem=$(( ${#b64} % 4 ))
    (( rem == 2 )) && b64="${b64}=="
    (( rem == 3 )) && b64="${b64}="
    printf '%s' "$b64" | base64 --decode 2>/dev/null
}

# ── Launch-argument parsing and validation ───────────────────────────────────
#    The argument string arrives from a link anyone can craft (a shared profile, a pasted oelink://
#    URL), so it is never handed to a shell: it is split into words by hand (double quotes group,
#    backslash escapes \ $ " ` inside them, nothing is expanded) and every word is checked before
#    Chrome is started. One rejected word cancels the whole launch.

# Flag names (without the leading --) that run programs, weaken the sandbox, open a debugging
# channel or redirect traffic. A trailing * matches any suffix.
DENIED_FLAGS=(
    'renderer-cmd-prefix' 'gpu-launcher' 'utility-cmd-prefix' 'zygote-cmd-prefix'
    'plugin-launcher' 'ppapi-plugin-launcher' 'nacl-gdb' 'nacl-gdb-script' 'browser-subprocess-path'
    'load-extension' 'disable-extensions-except' 'load-component-extension'
    'remote-debugging-*' 'remote-allow-origins' 'enable-automation'
    'no-sandbox' 'disable-gpu-sandbox' 'disable-setuid-sandbox' 'disable-web-security'
    'disable-site-isolation-trials' 'allow-file-access-from-files' 'allow-running-insecure-content'
    'ignore-certificate-errors*' 'proxy-pac-url' 'proxy-auto-detect' 'js-flags'
    'utility-and-browser-sandbox-cmd-prefix' 'ppapi-flash-path' 'enable-logging' 'log-file'
)

ARGV=()
split_args() {
    ARGV=()
    local s="$1" n=${#1} i=0 c nx cur="" inq=0 have=0
    while (( i < n )); do
        c="${s:i:1}"
        if (( inq )); then
            if [[ "$c" == '\' ]] && (( i + 1 < n )); then
                nx="${s:i+1:1}"
                if [[ "$nx" == '\' || "$nx" == '$' || "$nx" == '"' || "$nx" == '`' ]]; then
                    cur+="$nx"
                    i=$(( i + 2 ))
                    continue
                fi
                cur+="$c"
            elif [[ "$c" == '"' ]]; then
                inq=0
            else
                cur+="$c"
            fi
        elif [[ "$c" == '"' ]]; then
            inq=1
            have=1
        elif [[ "$c" == ' ' || "$c" == $'\t' ]]; then
            if (( have )); then ARGV+=("$cur"); cur=""; have=0; fi
        else
            cur+="$c"
            have=1
        fi
        i=$(( i + 1 ))
    done
    (( inq )) && return 1
    (( have )) && ARGV+=("$cur")
    return 0
}

# 0 when the word is a permitted flag or a permitted start URL.
arg_allowed() {
    local tok="$1"
    [[ "$tok" =~ [[:cntrl:]] ]] && return 1
    if [[ "$tok" =~ ^--([A-Za-z0-9][A-Za-z0-9-]*)(=|$) ]]; then
        local name pat
        name=$(printf '%s' "${BASH_REMATCH[1]}" | tr '[:upper:]' '[:lower:]')
        for pat in "${DENIED_FLAGS[@]}"; do
            # shellcheck disable=SC2053
            [[ "$name" == $pat ]] && return 1
        done
        return 0
    fi
    [[ "$tok" =~ ^(https?://|about:|chrome://) ]] && return 0
    # The delayed-redirect page hosts.pebble builds (wrapDelayedUrl in util.js).
    if [[ "$tok" == "data:text/html,<script>setTimeout(()=>location.replace('"* \
        && "$tok" == *"'),1000)</script>" ]]; then
        return 0
    fi
    return 1
}

# Tells the user a link was turned away; nothing is launched.
reject_launch() {
    local msg
    if [[ "$(get_lang)" == "ko" ]]; then
        msg="허용되지 않는 실행 옵션이 포함되어 있어 실행하지 않았습니다."
    else
        msg="The link contains a launch option that is not allowed, so nothing was started."
    fi
    /usr/bin/osascript -e "display dialog \"$msg\" with title \"oelink\" buttons {\"OK\"} default button \"OK\" with icon stop" >/dev/null 2>&1 || true
}

# ── Main handler ─────────────────────────────────────────────────────────────
handle() {
    local raw_url="${1:-}"
    echo "[oelink] Received: $raw_url" >> "$LOG"

    # Remove scheme prefix → <browser>?<encoded> or <browser>
    local body="${raw_url#oelink://}"

    # Parse browser type (part before ?)
    local browser="${body%%\?*}"
    local encoded=""
    if [[ "$body" == *"?"* ]]; then
        encoded="${body#*\?}"
    fi

    browser=$(echo "$browser" | tr '[:upper:]' '[:lower:]')

    local args_str=""
    if [[ -n "$encoded" ]]; then
        if args_str=$(decode_b64 "$encoded"); then
            :
        else
            echo "[oelink] Chrome base64 decode failed — launching with defaults" >> "$LOG"
            args_str=""
        fi
    fi

    # Split into words and vet every one before anything runs.
    local chrome_args=()
    if [[ -n "$args_str" ]]; then
        if ! split_args "$args_str"; then
            echo "[oelink] Refused: unbalanced quotes in: $args_str" >> "$LOG"
            reject_launch
            return
        fi
        local word
        for word in "${ARGV[@]}"; do
            if ! arg_allowed "$word"; then
                echo "[oelink] Refused: argument not allowed: $word" >> "$LOG"
                reject_launch
                return
            fi
            word="${word//\~/$HOME}"
            word="${word//\$HOME/$HOME}"
            chrome_args+=("$word")
        done
    fi
    local target_id
    target_id=$(get_oelink_id "$args_str")

    case "$browser" in
        chrome)
            check_oelink_chrome "$target_id" || return
            if (( ${#chrome_args[@]} == 0 )); then
                echo "[oelink] Launching Chrome with no args" >> "$LOG"
                open -na "Google Chrome" &
            else
                echo "[oelink] Launching Chrome: ${chrome_args[*]}" >> "$LOG"
                open -na "Google Chrome" --args "${chrome_args[@]}" &
            fi
            ;;

        *)
            echo "[oelink] Unknown browser: '$browser' — falling back to Chrome" >> "$LOG"
            check_oelink_chrome "$target_id" || return
            open -na "Google Chrome" &
            ;;
    esac
}

handle "${1:-}"
BEOF

chmod +x "$TMP_DIR/launcher.sh"

# ── 2. Write AppleScript handler ─────────────────────────────────────────────
cat > "$TMP_DIR/handler.applescript" << ASEOF
on open location this_URL
    set launcherPath to "${LAUNCHER_PATH}"
    try
        do shell script "nohup /bin/bash " & quoted form of launcherPath & " " & quoted form of this_URL & " >/dev/null 2>&1 &"
    on error errMsg
        do shell script "nohup /usr/bin/open -a 'Google Chrome' >/dev/null 2>&1 &"
    end try
end open location

on run
    do shell script "nohup /bin/bash ${LAUNCHER_PATH} >/dev/null 2>&1 &"
end run
ASEOF

# ── 3. Compile AppleScript → .app ────────────────────────────────────────────
echo "⚙️  Compiling AppleScript..."
osacompile -o "$APP_PATH" "$TMP_DIR/handler.applescript"

# ── 4. Place Bash launcher inside app bundle ─────────────────────────────────
cp "$TMP_DIR/launcher.sh" "$LAUNCHER_PATH"
chmod +x "$LAUNCHER_PATH"

# ── 5. Modify Info.plist (URL Scheme + metadata) ─────────────────────────────
echo "📝 Updating Info.plist..."
PLIST="$APP_PATH/Contents/Info.plist"
PB="/usr/libexec/PlistBuddy"

$PB -c "Set :CFBundleName oelink"                              "$PLIST" 2>/dev/null || \
$PB -c "Add :CFBundleName string oelink"                       "$PLIST"

$PB -c "Set :CFBundleIdentifier com.oelink.urlhandler"         "$PLIST" 2>/dev/null || \
$PB -c "Add :CFBundleIdentifier string com.oelink.urlhandler"  "$PLIST"

$PB -c "Set :CFBundleVersion 5.0.0"                            "$PLIST" 2>/dev/null || \
$PB -c "Add :CFBundleVersion string 5.0.0"                     "$PLIST"

$PB -c "Add :CFBundleURLTypes array"                           "$PLIST" 2>/dev/null || true
$PB -c "Add :CFBundleURLTypes:0 dict"                          "$PLIST" 2>/dev/null || true
$PB -c "Add :CFBundleURLTypes:0:CFBundleURLName string 'oelink URL Handler'" \
                                                                "$PLIST" 2>/dev/null || true
$PB -c "Add :CFBundleURLTypes:0:CFBundleURLSchemes array"      "$PLIST" 2>/dev/null || true
$PB -c "Add :CFBundleURLTypes:0:CFBundleURLSchemes:0 string ${SCHEME}" \
                                                                "$PLIST" 2>/dev/null || true

$PB -c "Add :LSUIElement bool true"                            "$PLIST" 2>/dev/null || \
$PB -c "Set :LSUIElement true"                                 "$PLIST"

$PB -c "Add :NSAppleEventsUsageDescription string 'Required to restart the browser before launching the oelink:// scheme.'" \
                                                                "$PLIST" 2>/dev/null || \
$PB -c "Set :NSAppleEventsUsageDescription 'Required to restart the browser before launching the oelink:// scheme.'" \
                                                                "$PLIST"

# ── 6. Reset Automation permissions (re-show popup on reinstall) ─────────────
tccutil reset AppleEvents com.oelink.urlhandler 2>/dev/null || true

# ── 7. Register with Launch Services database ────────────────────────────────
echo "🔗 Registering with Launch Services..."
/System/Library/Frameworks/CoreServices.framework/Versions/A/Frameworks/LaunchServices.framework/Versions/A/Support/lsregister \
    -f "$APP_PATH"

# ── 8. Generate test URLs ─────────────────────────────────────────────────────
# --oelink=<id> 규칙:
#   0          → --user-data-dir 없음 (기본 프로필)
#   <crc32hex> → --user-data-dir 경로의 CRC32 (8자리 소문자 hex, 0x 접두사 없음)
crc32_str() {
    printf '%s' "$1" | python3 -c "import sys,binascii; print('%08x' % (binascii.crc32(sys.stdin.buffer.read()) & 0xFFFFFFFF))" 2>/dev/null \
        || printf '%s' "$1" | cksum | awk '{printf "%08x\n", $1}'
}

make_url() {
    local browser="$1"
    local args="$2"
    local enc
    enc=$(printf '%s' "$args" | base64 | tr -d '\n' | tr '+/' '-_' | tr -d '=')
    echo "oelink://${browser}?${enc}"
}

echo ""
echo "✅ Installation complete!  App location: ${APP_PATH}"
echo ""

UDD_DISPLAY='$HOME/oe-chrome'
UDD_CRC=$(crc32_str '$HOME/oe-chrome')

# ── Case 1: --user-data-dir 없음 → --oelink=0 ────────────────────────────────
ARGS_NO_UDD_NORMAL='--oelink=0 --host-resolver-rules="MAP example.com 127.0.0.1" https://example.com'
ARGS_NO_UDD_INCOGNITO='--oelink=0 --host-resolver-rules="MAP example.com 127.0.0.1" --incognito https://example.com'

# ── Case 2: --user-data-dir 있음 → --oelink=<crc32> ─────────────────────────
ARGS_UDD_NORMAL="--oelink=${UDD_CRC} --host-resolver-rules=\"MAP example.com 127.0.0.1\" --user-data-dir=\"\$HOME/oe-chrome\" https://example.com"
ARGS_UDD_INCOGNITO="--oelink=${UDD_CRC} --host-resolver-rules=\"MAP example.com 127.0.0.1\" --incognito --user-data-dir=\"\$HOME/oe-chrome\" https://example.com"

echo "────────────────────────────────────────────────────────────"
echo " Supported schemes"
echo "────────────────────────────────────────────────────────────"
echo " oelink://chrome?<base64>   → Launch Google Chrome"
echo ""
echo " --oelink=<id> 값 규칙"
echo "   0          : --user-data-dir 없음 (기본 프로필)"
echo "   <crc32hex> : --user-data-dir 경로의 CRC32 (8자리 소문자 hex)"
echo ""
echo "────────────────────────────────────────────────────────────"
echo " Test URLs (run the commands below in your terminal)"
echo "────────────────────────────────────────────────────────────"
echo ""
echo " [Case 1: No --user-data-dir  (--oelink=0)]"
echo "   Normal:   open '$(make_url chrome "$ARGS_NO_UDD_NORMAL")'"
echo "   Incognito: open '$(make_url chrome "$ARGS_NO_UDD_INCOGNITO")'"
echo ""
echo " [Case 2: --user-data-dir=${UDD_DISPLAY}  (--oelink=${UDD_CRC})]"
echo "   Normal:   open '$(make_url chrome "$ARGS_UDD_NORMAL")'"
echo "   Incognito: open '$(make_url chrome "$ARGS_UDD_INCOGNITO")'"
echo ""
echo "────────────────────────────────────────────────────────────"
echo " * If you see a Gatekeeper warning on first launch:"
echo "   Right-click the app in Finder → Open → Click Open"
echo "────────────────────────────────────────────────────────────"
