Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$SCRIPT_DIR    = $PSScriptRoot
$SOURCE_SH     = "$SCRIPT_DIR\install_mac_oelink.sh"
$UNINSTALL_SH  = "$SCRIPT_DIR\uninstall_mac_oelink.sh"
$STATIC_DIR    = Resolve-Path "$SCRIPT_DIR\..\..\src\main\resources\static\oelink"
$TAR_PATH      = "$STATIC_DIR\install_macos_oelink_scheme.sh.tar.gz"

if (-not (Test-Path $SOURCE_SH))    { Write-Error "Source not found: $SOURCE_SH";    exit 1 }
if (-not (Test-Path $UNINSTALL_SH)) { Write-Error "Source not found: $UNINSTALL_SH"; exit 1 }

if (Test-Path $TAR_PATH) { Remove-Item $TAR_PATH -Force }

Write-Host "Packaging $TAR_PATH ..." -ForegroundColor Yellow

# Pure PowerShell POSIX ustar tar.gz writer — no external tools required.
# Writes Unix file permissions (mode) into the archive header so that
# Mac/Linux extract with the correct permissions (e.g. "755" = rwxr-xr-x).
function Write-TarGz {
    param(
        [string[]]$SourceFiles,
        [string]$DestPath,
        [string]$UnixMode = "755"   # octal string
    )

    $modeVal = [Convert]::ToInt32($UnixMode, 8)
    $mtime   = [long][DateTimeOffset]::UtcNow.ToUnixTimeSeconds()

    function Make-Header([string]$fileName, [long]$fileSize) {
        $hdr = New-Object byte[] 512

        function Set-Ascii([int]$offset, [int]$maxLen, [string]$val) {
            $bytes = [System.Text.Encoding]::ASCII.GetBytes($val)
            [Array]::Copy($bytes, 0, $hdr, $offset, [Math]::Min($bytes.Length, $maxLen))
        }
        function Set-Octal([int]$offset, [int]$fieldLen, [long]$val) {
            $oct   = [Convert]::ToString($val, 8).PadLeft($fieldLen - 1, '0')
            $bytes = [System.Text.Encoding]::ASCII.GetBytes($oct)
            [Array]::Copy($bytes, 0, $hdr, $offset, $bytes.Length)
        }

        Set-Ascii 0   100 $fileName
        Set-Octal 100 8   $modeVal
        Set-Octal 108 8   0
        Set-Octal 116 8   0
        Set-Octal 124 12  $fileSize
        Set-Octal 136 12  $mtime
        for ($i = 148; $i -lt 156; $i++) { $hdr[$i] = 0x20 }   # checksum placeholder
        $hdr[156] = [byte][char]'0'                              # typeflag: regular file
        Set-Ascii 257 6  "ustar"
        Set-Ascii 263 2  "00"

        $sum = [long]0; foreach ($b in $hdr) { $sum += $b }
        $csBytes = [System.Text.Encoding]::ASCII.GetBytes([Convert]::ToString($sum, 8).PadLeft(6, '0'))
        [Array]::Copy($csBytes, 0, $hdr, 148, 6)
        $hdr[154] = 0x00
        $hdr[155] = 0x20

        return $hdr
    }

    $fs = [System.IO.File]::Create($DestPath)
    try {
        $gz = New-Object System.IO.Compression.GZipStream($fs, [System.IO.Compression.CompressionLevel]::Optimal)
        try {
            foreach ($src in $SourceFiles) {
                $fileName    = Split-Path $src -Leaf
                $fileContent = [System.IO.File]::ReadAllBytes($src)
                $fileSize    = [long]$fileContent.Length
                $padLen      = if ($fileSize % 512 -eq 0) { 0 } else { 512 - ($fileSize % 512) }

                $gz.Write((Make-Header $fileName $fileSize), 0, 512)
                $gz.Write($fileContent, 0, [int]$fileSize)
                if ($padLen -gt 0) { $gz.Write((New-Object byte[] $padLen), 0, $padLen) }
            }
            $gz.Write((New-Object byte[] 1024), 0, 1024)  # end-of-archive: two zero blocks
        } finally { $gz.Close() }
    } finally { $fs.Close() }
}

Write-TarGz -SourceFiles @($SOURCE_SH, $UNINSTALL_SH) -DestPath $TAR_PATH -UnixMode "755"

if (Test-Path $TAR_PATH) {
    Write-Host "Build succeeded: $TAR_PATH" -ForegroundColor Green
} else {
    Write-Error "Build failed: $TAR_PATH was not created."
    exit 1
}
