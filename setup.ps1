$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'scripts/PlannerEnvironment.ps1')
Assert-PlannerTools

$settings = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'planner.config.example.json') | ConvertFrom-Json
Write-Host 'Create an empty PostgreSQL database named planner_release, owned by a dedicated LOGIN role without SUPERUSER.'
Write-Host 'Create only the database and role. This script creates the tables. See README.md for steps.'

$value = Read-Host "PostgreSQL host [$($settings.DatabaseHost)]"
if ($value) { $settings.DatabaseHost = $value.Trim() }
$value = Read-Host "PostgreSQL port [$($settings.Port)]"
if ($value) { $settings.Port = $value.Trim() }
$value = Read-Host "PostgreSQL role [$($settings.Role)]"
if ($value) { $settings.Role = $value.Trim() }
Assert-PlannerConnectionSettings $settings

$password = Read-Host 'PostgreSQL password' -AsSecureString
$apiKey = Read-Host 'Groq API key (Enter to skip AI)' -AsSecureString
Invoke-Planner $settings $password $apiKey -Bootstrap

$settings | Add-Member -NotePropertyName DbPassword -NotePropertyValue (ConvertFrom-SecureString $password)
$key = if ($apiKey.Length -gt 0) { ConvertFrom-SecureString $apiKey } else { $null }
$settings | Add-Member -NotePropertyName AiApiKey -NotePropertyValue $key
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $PlannerConfigPath) | Out-Null
$settings | ConvertTo-Json | Set-Content -LiteralPath $PlannerConfigPath -Encoding UTF8
Write-Host 'Setup complete. Run .\run.ps1 from this directory.'
