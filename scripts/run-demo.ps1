Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$utf8 = New-Object System.Text.UTF8Encoding($false)
$OutputEncoding = $utf8
[Console]::OutputEncoding = $utf8
[Console]::InputEncoding = $utf8
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectRoot
try {
    & mvn -B -ntp -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw 'Maven build failed' }
    & java '-Dfile.encoding=UTF-8' -jar 'target/lsm-tree-1.0.0.jar' demo
    if ($LASTEXITCODE -ne 0) { throw 'Demo failed' }
} finally {
    Pop-Location
}
