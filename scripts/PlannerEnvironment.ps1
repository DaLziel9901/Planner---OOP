$PlannerRoot = Split-Path -Parent $PSScriptRoot
$PlannerConfigPath = Join-Path $env:LOCALAPPDATA 'Planner/settings.json'

function Assert-PlannerTools {
    foreach ($file in 'mvnw.cmd', '.mvn/wrapper/maven-wrapper.properties', 'pom.xml') {
        if (!(Test-Path -LiteralPath (Join-Path $PlannerRoot $file) -PathType Leaf)) {
            throw "Missing $file. Extract the complete Planner source ZIP."
        }
    }
    if ($env:JAVA_HOME) {
        $javac = Join-Path $env:JAVA_HOME 'bin/javac.exe'
        if (!(Test-Path -LiteralPath $javac -PathType Leaf)) { throw 'JAVA_HOME must point to JDK 17.' }
    } else {
        $command = Get-Command javac.exe -ErrorAction SilentlyContinue
        if (!$command) { throw 'Install JDK 17 and set JAVA_HOME or add its bin directory to PATH.' }
        $javac = $command.Source
    }
    $version = & $javac -version
    if ($LASTEXITCODE -ne 0 -or $version -notmatch '^javac 17(?:\.|\s|$)') {
        throw 'Planner setup expects JDK 17. Check JAVA_HOME and javac -version.'
    }
}

function Assert-PlannerConnectionSettings($Settings) {
    if ($Settings.DatabaseHost -notmatch '^(?:[A-Za-z0-9][A-Za-z0-9.-]*|\[[0-9A-Fa-f:]+\])$' -or
        "$($Settings.Port)" -notmatch '^\d{1,5}$' -or [int]$Settings.Port -lt 1 -or [int]$Settings.Port -gt 65535 -or
        $Settings.Role -cnotmatch '^[a-z_][a-z0-9_]{0,62}$' -or $Settings.Role -match '^pg_|^postgres$') {
        throw 'Set a PostgreSQL host, port and dedicated non-superuser role. Run setup.ps1 to update them.'
    }
}

function Invoke-Planner($Settings, [securestring]$Password, [securestring]$ApiKey, [switch]$Bootstrap) {
    Assert-PlannerConnectionSettings $Settings
    if (!$Password -or $Password.Length -eq 0) { throw 'PostgreSQL password is required.' }
    $config = @{
        DB_URL = "jdbc:postgresql://$($Settings.DatabaseHost):$($Settings.Port)/planner_release"
        DB_USERNAME = $Settings.Role
        DB_PASSWORD = [Net.NetworkCredential]::new('', $Password).Password
        AI_BASE_URL = $Settings.AiBaseUrl
        AI_MODEL = $Settings.AiModel
        AI_STRICT_SCHEMA = ([string]$Settings.AiStrictSchema).ToLowerInvariant()
        AI_FLAT_SCHEMA = ([string]$Settings.AiFlatSchema).ToLowerInvariant()
        AI_API_KEY = if ($ApiKey) { [Net.NetworkCredential]::new('', $ApiKey).Password } else { $null }
    }
    $saved = @{}
    Push-Location -LiteralPath $PlannerRoot
    try {
        foreach ($name in $config.Keys) {
            $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
            [Environment]::SetEnvironmentVariable($name, $config[$name], 'Process')
        }
        $mavenArgs = @('spring-boot:run')
        if ($Bootstrap) {
            $mavenArgs += '-Dspring-boot.run.arguments=--planner-bootstrap'
        } else {
            Write-Host 'When Spring Boot is ready, open http://localhost:8080/'
        }
        & (Join-Path $PlannerRoot 'mvnw.cmd') @mavenArgs
        if ($LASTEXITCODE -ne 0) {
            throw 'Planner failed. Check the Maven message, database name, role/password and PostgreSQL service.'
        }
    } finally {
        foreach ($name in $saved.Keys) {
            [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process')
        }
        Pop-Location
    }
}
