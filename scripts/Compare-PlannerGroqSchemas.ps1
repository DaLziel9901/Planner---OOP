[CmdletBinding()]
param(
    [ValidateSet(7, 30, 120)]
    [int[]]$CaseDays = @(7, 30, 120),
    [ValidateRange(1, 20)]
    [int]$Trials = 1,
    [switch]$PayloadOnly,
    [string]$MavenCommand
)

$ErrorActionPreference = 'Stop'
$model = $env:AI_MODEL
if ($model -cne 'openai/gpt-oss-120b') {
    throw 'Set AI_MODEL=openai/gpt-oss-120b for both arms of this experiment.'
}
$apiKey = $env:AI_API_KEY
if (!$PayloadOnly -and [string]::IsNullOrWhiteSpace($apiKey)) {
    throw 'Set AI_API_KEY in this terminal.'
}

$utf8 = [Text.UTF8Encoding]::new($false, $true)
$project = Split-Path -Parent $PSScriptRoot
$controlPath = Join-Path $project 'target/planner-ai-diagnostic-request.json'
$exportArgs = @{ PayloadOnly = $true }
if ($MavenCommand) { $exportArgs.MavenCommand = $MavenCommand }
& (Join-Path $PSScriptRoot 'Test-PlannerGroqSchema.ps1') @exportArgs | Out-Host
Write-Host "The export above used a mock. Both comparison arms use AI_MODEL=$model."
$control = $utf8.GetString([IO.File]::ReadAllBytes($controlPath)) | ConvertFrom-Json
$schema = $utf8.GetString([IO.File]::ReadAllBytes((Join-Path $project 'src/main/resources/ai/planner-flat.schema.json'))) | ConvertFrom-Json
$cases = $utf8.GetString([IO.File]::ReadAllBytes((Join-Path $PSScriptRoot 'planner-ai-experiment-inputs.json'))) | ConvertFrom-Json

$systemA = $control.messages[0].content
$shapeLines = [regex]'(?m)^(?:Plan|SubPlan|Task|SubTask) fields: [^\r\n]+\r?\n'
if ($shapeLines.Matches($systemA).Count -ne 4) {
    throw 'The Planner prompt changed. Review the A/B representation instructions before running.'
}
$systemB = $shapeLines.Replace($systemA, '') + @'
Return {"nodes":[...]} in draft order. Each node has id, parentId, type, title, details, date and endDate.
Use short positive integer IDs. Only the single Plan has a null parentId.
SubPlan belongs to Plan or SubPlan; Task belongs to Plan or SubPlan; SubTask belongs to Task.
Use details for the Plan goal or other nodes' description. Use date for the start date, or the scheduled date for a SubTask.
Use null for unknown details or dates. SubTask endDate is always null.
'@

$comparisons = [ordered]@{
    A = @{ System = $systemA; Format = $control.response_format }
    B = @{ System = $systemB; Format = @{
        type = 'json_schema'
        json_schema = @{ name = 'planner_flat_draft'; strict = $true; schema = $schema }
    } }
}
$payloads = @{}
foreach ($case in $cases | Where-Object { $_.durationDays -in $CaseDays }) {
    $inputJson = ConvertTo-Json -InputObject $case -Compress
    foreach ($arm in $comparisons.Keys) {
        $variant = $comparisons[$arm]
        $requestBody = [ordered]@{
            model = $model
            messages = @(
                @{ role = 'system'; content = $variant.System }
                @{ role = 'user'; content = $inputJson }
            )
            response_format = $variant.Format
        }
        $bytes = $utf8.GetBytes((ConvertTo-Json -InputObject $requestBody -Depth 40 -Compress))
        $payloads["$arm-$($case.durationDays)"] = $bytes
        if ($PayloadOnly) {
            $path = Join-Path $project "target/planner-ai-$arm-$($case.durationDays).json"
            [IO.File]::WriteAllBytes($path, $bytes)
            Write-Host "Exported $arm/$($case.durationDays): $($bytes.Length) bytes -> $path"
        }
    }
}
if ($PayloadOnly) { return }

Add-Type -AssemblyName System.Net.Http
$handler = [System.Net.Http.HttpClientHandler]::new()
$handler.AllowAutoRedirect = $false
$client = [System.Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromSeconds(35)
$endpoint = 'https://api.groq.com/openai/v1/chat/completions'
$knownCodes = @('json_validate_failed', 'invalid_api_key', 'model_not_found', 'model_permission_blocked',
    'rate_limit_exceeded', 'context_length_exceeded', 'invalid_request', 'invalid_value', 'internal_server_error')
try {
    for ($trial = 1; $trial -le $Trials; $trial++) {
        $order = if ($trial % 2 -eq 1) { 'A', 'B' } else { 'B', 'A' }
        foreach ($days in $CaseDays | Sort-Object -Unique) {
            foreach ($arm in $order) {
                $row = [ordered]@{ Days = $days; Trial = $trial; Arm = $arm; Model = $model
                    Status = $null; ErrorCode = 'absent'; ResponseMs = $null; OutputBytes = $null
                    FinishReason = 'absent'; Parse = 'not_attempted' }
                $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, $endpoint)
                $response = $null
                $watch = [Diagnostics.Stopwatch]::new()
                try {
                    $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $apiKey)
                    $request.Content = [System.Net.Http.ByteArrayContent]::new($payloads["$arm-$days"])
                    $request.Content.Headers.ContentType =
                        [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse('application/json; charset=utf-8')
                    $watch.Start()
                    $response = $client.SendAsync($request).GetAwaiter().GetResult()
                    $watch.Stop()
                    $row.Status = [int]$response.StatusCode
                    $envelope = $null
                    try {
                        $raw = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
                        $envelope = ConvertFrom-Json -InputObject ($utf8.GetString($raw))
                    } catch { $row.Parse = 'invalid_envelope' }
                    if ($row.Status -ne 200) {
                        $code = $envelope.error.code
                        if ($code) {
                            $row.ErrorCode = if ($code -is [string] -and $code -cin $knownCodes) { $code } else { 'unrecognized' }
                        }
                    } elseif ($null -ne $envelope) {
                        $choice = $envelope.choices | Select-Object -First 1
                        if ($choice.finish_reason -cin @('stop', 'length', 'content_filter', 'tool_calls')) {
                            $row.FinishReason = $choice.finish_reason
                        } elseif ($choice.finish_reason) { $row.FinishReason = 'unrecognized' }
                        $content = $choice.message.content
                        if ($content -is [string]) { $row.OutputBytes = $utf8.GetByteCount($content) }
                        $row.Parse = 'missing_content'
                        if ($content -is [string] -and ![string]::IsNullOrWhiteSpace($content)) {
                            try {
                                $parsed = ConvertFrom-Json -InputObject $content
                                $shapeField = if ($arm -eq 'A') { 'subPlans' } else { 'nodes' }
                                $row.Parse = if ($content.TrimStart().StartsWith('{') -and
                                    $parsed.PSObject.Properties[$shapeField].Value -is [array]) {
                                    'json_expected_root'
                                } else { 'json_unexpected_root' }
                            } catch { $row.Parse = 'invalid_json' }
                        }
                    }
                } catch {
                    $row.ErrorCode = 'transport_or_timeout'
                } finally {
                    $watch.Stop()
                    $row.ResponseMs = $watch.ElapsedMilliseconds
                    if ($null -ne $response) { $response.Dispose() }
                    $request.Dispose()
                }
                [pscustomobject]$row
            }
        }
    }
} finally {
    $client.Dispose()
}
