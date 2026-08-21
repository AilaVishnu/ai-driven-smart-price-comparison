<#
.SYNOPSIS
    Tests whether a RapidAPI host and path work with your configured key.

.DESCRIPTION
    Subscribing to the right RapidAPI listing is fiddly: several Flipkart APIs
    have near-identical names, and some advertise search on their overview page
    while gating it behind a paid plan. This tells you which situation you are
    in, in one call, without restarting the application.

    Reads the key from application-local.properties and never prints it.

.EXAMPLE
    .\scripts\test-provider.ps1
    Tests every known Flipkart candidate plus your current configuration.

.EXAMPLE
    .\scripts\test-provider.ps1 -ApiHost "flipkart-apis.p.rapidapi.com" -Path "/product-search?q=iphone"
    Tests one specific host and path.
#>

param(
    [string]$ApiHost,
    [string]$Path = '/product-search?q=iphone',
    [switch]$All
)

$ErrorActionPreference = 'Continue'

$propsPath = Join-Path $PSScriptRoot '..\backend\src\main\resources\application-local.properties'
if (-not (Test-Path $propsPath)) {
    Write-Host "Cannot find application-local.properties at $propsPath" -ForegroundColor Red
    exit 1
}
$key = ((Get-Content $propsPath | Where-Object { $_ -match '^\s*providers\.rapidapi-key\s*=' }) `
        -replace '^\s*providers\.rapidapi-key\s*=\s*','').Trim()
if (-not $key) {
    Write-Host "No providers.rapidapi-key set in application-local.properties" -ForegroundColor Red
    exit 1
}
Write-Host "Using the key from application-local.properties (length $($key.Length))." -ForegroundColor DarkGray
Write-Host ""

function Test-Host {
    param([string]$TargetHost, [string]$TargetPath)

    $headers = @{ 'x-rapidapi-key' = $key; 'x-rapidapi-host' = $TargetHost }
    try {
        $r = Invoke-WebRequest "https://$TargetHost$TargetPath" -Headers $headers -UseBasicParsing -TimeoutSec 25
        [pscustomobject]@{
            Host    = $TargetHost
            Status  = $r.StatusCode
            Verdict = 'WORKS'
            Detail  = "$($r.RawContentLength) bytes returned - usable"
        }
    }
    catch {
        $code = $_.Exception.Response.StatusCode.value__
        $body = ''
        try {
            $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
            $body = $reader.ReadToEnd()
        } catch { }

        # RapidAPI distinguishes these clearly, and each has a different fix.
        $verdict, $detail = switch -Regex ($body) {
            'not subscribed'                { 'NOT SUBSCRIBED', 'Subscribe to this API on RapidAPI (free plan) to use it' }
            'disabled for your subscription'{ 'PAYWALLED',      'Subscribed, but this endpoint needs a paid plan - try a different API' }
            "API doesn't exist"             { 'NO SUCH HOST',   'That host is not a RapidAPI API' }
            'does not exist'                { 'WRONG PATH',     'Host is correct, the path is not - check the endpoint name' }
            'Too many requests'             { 'RATE LIMITED',   'Wait a few seconds and retry' }
            default                         { "HTTP $code",     ($body -replace '\s+',' ').Trim() }
        }
        [pscustomobject]@{ Host = $TargetHost; Status = $code; Verdict = $verdict; Detail = $detail }
    }
}

$results = @()

if ($ApiHost) {
    $results += Test-Host -TargetHost $ApiHost -TargetPath $Path
}
else {
    # Known Flipkart candidates on RapidAPI, plus whatever is configured now.
    $candidates = @(
        'real-time-flipkart-data2.p.rapidapi.com',
        'real-time-flipkart-api.p.rapidapi.com',
        'flipkart-apis.p.rapidapi.com',
        'product-search-api.p.rapidapi.com',
        'ecommerce-api3.p.rapidapi.com'
    )
    foreach ($c in $candidates) {
        $results += Test-Host -TargetHost $c -TargetPath $Path
        # Free plans throttle hard; space the calls out.
        Start-Sleep -Milliseconds 1700
    }
}

$results | Format-Table -AutoSize Host, Status, Verdict, Detail | Out-String -Width 200 | Write-Host

$working = @($results | Where-Object { $_.Verdict -eq 'WORKS' })
Write-Host ""
if ($working.Count -gt 0) {
    Write-Host "USABLE: $($working[0].Host)" -ForegroundColor Green
    Write-Host ""
    Write-Host "Put this in backend/src/main/resources/application-local.properties," -ForegroundColor Green
    Write-Host "then restart the backend:" -ForegroundColor Green
    Write-Host ""
    Write-Host "  providers.sources.flipkart.host=$($working[0].Host)"
    Write-Host "  providers.sources.flipkart.search-path=$($Path -replace 'iphone','{q}')"
}
else {
    Write-Host "Nothing usable yet." -ForegroundColor Yellow
    $notSubscribed = @($results | Where-Object { $_.Verdict -eq 'NOT SUBSCRIBED' })
    if ($notSubscribed.Count -gt 0) {
        Write-Host "These exist and would work if you subscribe to their free plan:" -ForegroundColor Yellow
        $notSubscribed | ForEach-Object { Write-Host "  - $($_.Host)" }
        Write-Host ""
        Write-Host "Find them at https://rapidapi.com/search/flipkart - subscribe to Basic/free, then re-run this."
    }
}
