param(
    [Parameter(Mandatory = $true)]
    [string]$CertPassword
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$SCRIPT_DIR = $PSScriptRoot
$OUT_DIR    = "$SCRIPT_DIR\.."
$CERT_NAME  = "oelink_local_cert"
$PFX_PATH   = "$OUT_DIR\$CERT_NAME.pfx"
$CER_PATH   = "$OUT_DIR\$CERT_NAME.cer"

$cert = New-SelfSignedCertificate `
    -Type CodeSigningCert `
    -Subject "CN=oelink local cert" `
    -FriendlyName "oelink local cert" `
    -CertStoreLocation Cert:\CurrentUser\My `
    -NotAfter (Get-Date).AddYears(10)

$securePwd = ConvertTo-SecureString -String $CertPassword -AsPlainText -Force
Export-PfxCertificate -Cert $cert -FilePath $PFX_PATH -Password $securePwd | Out-Null
Export-Certificate -Cert $cert -FilePath $CER_PATH | Out-Null

Write-Host "Created: $PFX_PATH" -ForegroundColor Green
Write-Host "Created: $CER_PATH" -ForegroundColor Green
