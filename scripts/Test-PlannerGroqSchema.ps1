[CmdletBinding()]
param(
    [switch]$PayloadOnly,
    [string]$MavenCommand
)

$ErrorActionPreference = 'Stop'
$apiKey = $env:AI_API_KEY
if (!$PayloadOnly -and [string]::IsNullOrWhiteSpace($apiKey)) {
    throw 'Set AI_API_KEY in this terminal before calling Groq.'
}

$project = Split-Path -Parent $PSScriptRoot
$payloadPath = Join-Path $project 'target/planner-ai-diagnostic-request.json'
if (!$MavenCommand) {
    $installedMaven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    $MavenCommand = if ($installedMaven) { $installedMaven.Source } else { Join-Path $project 'mvnw.cmd' }
}
Push-Location -LiteralPath $project
try {
    $mavenArgs = @(
        '-Dtest=OpenAiCompatiblePlanProviderTest#sendsStrictSchemaAndReturnsDraftWithNestedSubPlans'
        '-Dsurefire.failIfNoSpecifiedTests=true'
        '-Dplanner.ai.diagnostic.export=true'
        'test'
    )
    & $MavenCommand @mavenArgs
    if ($LASTEXITCODE -ne 0) { throw 'Maven failed to export the synthetic request. Use -MavenCommand to select a working mvn.cmd.' }
    $payload = [IO.File]::ReadAllBytes($payloadPath)
} finally {
    Pop-Location
}

Write-Host "Synthetic Planner payload written to $payloadPath ($($payload.Length) bytes)."
if ($PayloadOnly) { return }

# Send the bytes captured from Planner's HTTP body publisher without reserializing.
Add-Type -AssemblyName System.Net.Http
$handler = [System.Net.Http.HttpClientHandler]::new()
$handler.AllowAutoRedirect = $false
$client = [System.Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromSeconds(35)
$endpoint = 'https://api.groq.com/openai/v1/chat/completions'
$request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, $endpoint)
$response = $null
try {
    try {
        $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $apiKey)
        $request.Content = [System.Net.Http.ByteArrayContent]::new($payload)
        $request.Content.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::new('application/json')
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $raw = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    } catch {
        throw 'Groq request failed at the transport layer or timed out.'
    }

    $parsed = $null
    try {
        $parsed = ConvertFrom-Json -InputObject $raw
    } catch {
        # Proxies can return HTML or an empty message. Never print the body.
    }
    $status = [int]$response.StatusCode
    if ($status -ne 200) {
        $type = if (!$parsed.error.type) { 'absent' } elseif ($parsed.error.type -ceq 'invalid_request_error') {
            'invalid_request_error'
        } else { 'unrecognized' }
        $code = if (!$parsed.error.code) { 'absent' } elseif ($parsed.error.code -ceq 'json_validate_failed') {
            'json_validate_failed'
        } else { 'unrecognized' }
        throw "Groq HTTP error: status=$status type=$type code=$code"
    }

    $choice = $parsed.choices | Select-Object -First 1
    $finishReason = if ($choice.finish_reason -cin @('stop', 'length', 'content_filter', 'tool_calls')) {
        $choice.finish_reason
    } elseif (!$choice.finish_reason) { 'absent' } else { 'unrecognized' }
    $hasContent = $choice.message.content -is [string] -and ![string]::IsNullOrWhiteSpace($choice.message.content)
    $jsonObject = $false
    if ($hasContent) {
        try {
            $jsonObject = (ConvertFrom-Json -InputObject $choice.message.content) -is [pscustomobject]
        } catch {
            # Only report whether the content is a JSON object.
        }
    }
    Write-Host "Groq HTTP status=$status finish_reason=$finishReason content_is_json_object=$jsonObject"
} finally {
    if ($null -ne $response) { $response.Dispose() }
    $request.Dispose()
    $client.Dispose()
}
