$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'scripts/PlannerEnvironment.ps1')
Assert-PlannerTools

if (!(Test-Path -LiteralPath $PlannerConfigPath -PathType Leaf)) {
    throw 'Run setup.ps1 first to configure Planner for this Windows account.'
}
try {
    $settings = Get-Content -Raw -LiteralPath $PlannerConfigPath | ConvertFrom-Json
    $password = ConvertTo-SecureString -String $settings.DbPassword
    $apiKey = if ($settings.AiApiKey) { ConvertTo-SecureString -String $settings.AiApiKey } else { $null }
} catch {
    throw 'Cannot read the local configuration. Run setup.ps1 again under this Windows account.'
}
Invoke-Planner $settings $password $apiKey
