param(
    [string]$Name = ([string][char]0x59D3 + [char]0x540D),
    [switch]$Force
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$utf8 = New-Object System.Text.UTF8Encoding($false)
$OutputEncoding = $utf8
[Console]::OutputEncoding = $utf8
$projectRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
if ([string]::IsNullOrWhiteSpace($Name) -or $Name.IndexOfAny([IO.Path]::GetInvalidFileNameChars()) -ge 0 `
    -or $Name -in @('.', '..') -or $Name.EndsWith('.') -or $Name.EndsWith(' ') `
    -or $Name -match '^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$') {
    throw 'Name must be a valid Windows filename without an extension.'
}
Push-Location -LiteralPath $projectRoot
try {
    $commit = & git rev-parse HEAD
    if ($LASTEXITCODE -ne 0) { throw 'Commit the repository before packaging.' }
    $gitDirectory = Join-Path $projectRoot '.git'
    if (-not (Test-Path -LiteralPath $gitDirectory -PathType Container)) {
        throw 'Packaging requires a standalone Git checkout with a .git directory.'
    }
    $jarRelative = 'target/lsm-tree-1.0.0.jar'
    if (-not (Test-Path -LiteralPath (Join-Path $projectRoot $jarRelative))) {
        throw 'Run mvn clean verify before packaging.'
    }
    $tracked = @(& git -c core.quotepath=false ls-files)
    if ($LASTEXITCODE -ne 0) { throw 'Could not enumerate tracked files.' }
    $relativePaths = New-Object 'System.Collections.Generic.List[string]'
    foreach ($path in $tracked) { $relativePaths.Add([string]$path) }
    Get-ChildItem -LiteralPath $gitDirectory -Recurse -Force -File | ForEach-Object {
        if ($_.Name -notlike '*.lock') {
            $relativePaths.Add($_.FullName.Substring($projectRoot.Length + 1).Replace('\', '/'))
        }
    }
    $relativePaths.Add($jarRelative)
    $outputDirectory = Join-Path $projectRoot 'submission'
    New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
    $zipPath = [IO.Path]::GetFullPath((Join-Path $outputDirectory ($Name + '.zip')))
    $expectedParent = [IO.Path]::GetFullPath($outputDirectory)
    if ([IO.Path]::GetDirectoryName($zipPath) -ne $expectedParent) { throw 'Invalid archive destination.' }
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $mode = if ($Force) { [IO.FileMode]::Create } else { [IO.FileMode]::CreateNew }
    $stream = [IO.File]::Open($zipPath, $mode, [IO.FileAccess]::Write)
    try {
        $archive = New-Object IO.Compression.ZipArchive($stream, [IO.Compression.ZipArchiveMode]::Create)
        try {
            foreach ($relative in $relativePaths) {
                $source = [IO.Path]::GetFullPath((Join-Path $projectRoot $relative))
                if (-not $source.StartsWith($projectRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
                    throw 'A source file resolved outside the repository.'
                }
                $entryName = 'TestAnswer/' + $relative.Replace('\', '/')
                [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $source, $entryName,
                    [IO.Compression.CompressionLevel]::Optimal) | Out-Null
            }
            $entry = $archive.CreateEntry('TestAnswer/SUBMISSION_INFO.txt')
            $writer = New-Object IO.StreamWriter($entry.Open(), $utf8)
            try {
                $writer.WriteLine('Problem 3 - Java 17 LSM-Tree')
                $writer.WriteLine('Name: ' + $Name)
                $writer.WriteLine('Git commit: ' + $commit)
                $writer.WriteLine('Packaged at: ' + [DateTimeOffset]::Now.ToString('o'))
                $writer.WriteLine('Includes source, .git history, documentation, run logs and executable JAR.')
                $writer.WriteLine('Excluded: dependency cache, generated databases and other build intermediates.')
            } finally { $writer.Dispose() }
        } finally { $archive.Dispose() }
    } finally { $stream.Dispose() }
    $check = [IO.Compression.ZipFile]::OpenRead($zipPath)
    try {
        foreach ($required in @('TestAnswer/.git/HEAD', 'TestAnswer/pom.xml', 'TestAnswer/README.md',
                'TestAnswer/src/main/java/cn/testanswer/lsm/LsmStore.java', ('TestAnswer/' + $jarRelative))) {
            if ($null -eq $check.GetEntry($required)) { throw "Missing ZIP entry: $required" }
        }
        Write-Host ('ZIP entries: ' + $check.Entries.Count)
    } finally { $check.Dispose() }
    Write-Host ('Archive: ' + $zipPath)
    Write-Host ('SHA256: ' + (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash)
} finally {
    Pop-Location
}
