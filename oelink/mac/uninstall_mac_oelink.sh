#!/bin/bash
set -e

APP_PATH="/Applications/oelink.app"
SCHEME="com.oelink.urlhandler"

echo "Removing oelink app..."
if [ -d "$APP_PATH" ]; then
    /System/Library/Frameworks/CoreServices.framework/Versions/A/Frameworks/LaunchServices.framework/Versions/A/Support/lsregister \
        -u "$APP_PATH" 2>/dev/null || true
    rm -rf "$APP_PATH"
    echo "  Removed $APP_PATH"
else
    echo "  $APP_PATH not found, skipping."
fi

echo "Resetting automation permissions..."
tccutil reset AppleEvents "$SCHEME" 2>/dev/null || true

echo ""
echo "Uninstall complete."
