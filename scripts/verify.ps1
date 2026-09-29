Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$utf8 = New-Object System.Text.UTF8Encoding($false)
$OutputEncoding = $utf8
[Console]::OutputEncoding = $utf8
[Console]::InputEncoding = $utf8
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectRoot
try {
    $resultsDirectory = Join-Path $projectRoot 'docs/results'
    New-Item -ItemType Directory -Path $resultsDirectory -Force | Out-Null
    $buildOutput = @(& mvn -B -ntp clean verify 2>&1)
    $buildCode = $LASTEXITCODE
    $buildOutput | ForEach-Object { Write-Host $_ }
    [IO.File]::WriteAllLines((Join-Path $resultsDirectory 'build-output.txt'), [string[]]$buildOutput, $utf8)
    if ($buildCode -ne 0) { throw "Maven verification failed with exit code $buildCode" }

    $jar = Join-Path $projectRoot 'target/lsm-tree-1.0.0.jar'
    $demoOutput = @(& java '-Dfile.encoding=UTF-8' -jar $jar demo 2>&1)
    $demoCode = $LASTEXITCODE
    $demoOutput | ForEach-Object { Write-Host $_ }
    [IO.File]::WriteAllLines((Join-Path $resultsDirectory 'demo-output.txt'), [string[]]$demoOutput, $utf8)
    if ($demoCode -ne 0) { throw "Demo failed with exit code $demoCode" }

    $smokeDirectory = Join-Path $projectRoot ('data/smoke-' + [Guid]::NewGuid().ToString())
    $cliOutput = New-Object 'System.Collections.Generic.List[string]'
    function Invoke-Lsm {
        param([string[]]$Arguments, [int]$ExpectedExit = 0)
        $output = @(& java '-Dfile.encoding=UTF-8' -jar $jar --dir $smokeDirectory @Arguments 2>&1)
        $code = $LASTEXITCODE
        $cliOutput.Add('> ' + ($Arguments -join ' '))
        foreach ($line in $output) { $cliOutput.Add([string]$line) }
        $cliOutput.Add('exit=' + $code)
        if ($code -ne $ExpectedExit) { throw "CLI command failed: $Arguments (exit=$code)" }
        return (($output -join "`n") | ConvertFrom-Json)
    }
    Invoke-Lsm -Arguments @('put', 'user:001', 'Alice') | Out-Null
    Invoke-Lsm -Arguments @('put', 'user:002', 'Bob') | Out-Null
    Invoke-Lsm -Arguments @('flush') | Out-Null
    Invoke-Lsm -Arguments @('put', 'user:001', 'Alice-updated') | Out-Null
    $get = Invoke-Lsm -Arguments @('get', 'user:001')
    if ($get.value -ne 'Alice-updated') { throw 'Unexpected value after restart' }
    Invoke-Lsm -Arguments @('delete', 'user:002') | Out-Null
    Invoke-Lsm -Arguments @('compact') | Out-Null
    $scan = Invoke-Lsm -Arguments @('scan', '--from', 'user:001', '--to', 'user:003')
    if ($scan.count -ne 1 -or $scan.entries[0].value -ne 'Alice-updated') { throw 'Unexpected scan result' }
    Invoke-Lsm -Arguments @('get', 'user:002') -ExpectedExit 3 | Out-Null
    $stats = Invoke-Lsm -Arguments @('stats')
    if ($stats.sstableCount -ne 1 -or $stats.sstableEntries -ne 1) { throw 'Compaction did not reclaim old records' }
    $cliOutput.Add('CLI smoke assertions passed.')
    $cliOutput | ForEach-Object { Write-Host $_ }
    [IO.File]::WriteAllLines((Join-Path $resultsDirectory 'cli-output.txt'), $cliOutput.ToArray(), $utf8)
    Write-Host 'Verification complete. UTF-8 logs: docs/results/'
} finally {
    Pop-Location
}
