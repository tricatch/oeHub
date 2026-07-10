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
    local answer
    answer=$(/usr/bin/osascript -e 'display dialog "동일한 프로필로 실행 중인 Chrome이 있습니다.\n종료하고 다시 실행하시겠습니까?" buttons {"아니오", "예"} default button "예"' 2>/dev/null) || true
    if echo "$answer" | grep -q "예"; then
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
            args_str="${args_str//\~/$HOME}"
            args_str="${args_str//\$HOME/$HOME}"
        else
            echo "[oelink] Chrome base64 decode failed — launching with defaults" >> "$LOG"
            args_str=""
        fi
    fi
    local target_id
    target_id=$(get_oelink_id "$args_str")

    case "$browser" in
        chrome)
            check_oelink_chrome "$target_id" || return
            if [[ -z "$args_str" ]]; then
                echo "[oelink] Launching Chrome with no args" >> "$LOG"
                open -na "Google Chrome" &
            else
                echo "[oelink] Launching Chrome: $args_str" >> "$LOG"
                eval "open -na \"Google Chrome\" --args $args_str" &
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
