[CmdletBinding()]
param([string]$OutputPath)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
if (!$OutputPath) { $OutputPath = Join-Path $root 'target/Planner-source.zip' }
$output = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputPath)
if ([IO.Path]::GetExtension($output) -ine '.zip') { throw 'Output must be a .zip file.' }
if (Test-Path -LiteralPath $output) { throw 'ZIP already exists. Choose another -OutputPath.' }

$paths = @(
    'README.md', 'pom.xml', 'mvnw', 'mvnw.cmd', '.mvn/wrapper/maven-wrapper.properties',
    'setup.ps1', 'run.ps1', 'planner.config.example.json', 'scripts/PlannerEnvironment.ps1',
    'scripts/Pack-Planner.ps1', 'scripts/Compare-PlannerGroqSchemas.ps1',
    'scripts/Test-PlannerGroqSchema.ps1', 'scripts/planner-ai-experiment-inputs.json',
    'src/main/resources/application.properties',
    'src/main/resources/schema.sql', 'src/main/resources/db/bootstrap/app-user.sql',
    'src/main/resources/db/migration/V003__user_ownership.sql',
    'src/main/resources/ai/planner-flat.schema.json', 'src/test/resources/db/fixtures/mvp-baseline.sql'
)
$groups = [ordered]@{
    'src/main/java' = @('.java')
    'src/test/java' = @('.java')
    'src/main/resources/templates' = @('.html')
    'src/main/resources/static' = @('.js', '.css', '.png')
}
foreach ($directory in $groups.Keys) {
    $paths += Get-ChildItem -LiteralPath (Join-Path $root $directory) -Recurse -File |
        Where-Object { $_.Extension -in $groups[$directory] } |
        ForEach-Object { $_.FullName.Substring($root.Length + 1).Replace('\', '/') }
}
foreach ($path in $paths) {
    if (!(Test-Path -LiteralPath (Join-Path $root $path) -PathType Leaf)) { throw "Missing release file: $path" }
}

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $output) | Out-Null
$archive = [IO.Compression.ZipFile]::Open($output, [IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($path in $paths | Sort-Object -Unique) {
        [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, (Join-Path $root $path), "Planner/$path")
    }
} finally {
    $archive.Dispose()
}
Write-Host "Source ZIP: $output"
