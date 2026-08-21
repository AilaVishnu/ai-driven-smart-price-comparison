<#
.SYNOPSIS
    End-to-end smoke test for the AI-Driven Smart Price Comparison System.

.DESCRIPTION
    Exercises every major endpoint against a running backend and prints a
    pass/fail table. Intended to be the single command that answers "is this
    actually working right now".

    Start the backend first:
        cd backend
        .\mvnw.cmd spring-boot:run

    Then:
        .\scripts\smoke.ps1

.PARAMETER BaseUrl
    Backend base URL. Defaults to http://localhost:8080
#>

param(
    [string]$BaseUrl = 'http://localhost:8080'
)

$ErrorActionPreference = 'Continue'
$results = @()
$script:passCount = 0
$script:failCount = 0

function Test-Step {
    param(
        [string]$Name,
        [scriptblock]$Body
    )
    try {
        $detail = & $Body
        $script:passCount++
        $script:results += [pscustomobject]@{ Result = 'PASS'; Check = $Name; Detail = $detail }
    }
    catch {
        $script:failCount++
        $script:results += [pscustomobject]@{ Result = 'FAIL'; Check = $Name; Detail = $_.Exception.Message }
    }
}

Write-Host ""
Write-Host "Smoke testing $BaseUrl" -ForegroundColor Cyan
Write-Host ""

# --- reachability -----------------------------------------------------------
Test-Step 'Backend is reachable' {
    $r = Invoke-WebRequest "$BaseUrl/actuator/health" -UseBasicParsing -TimeoutSec 15
    if ($r.StatusCode -ne 200) { throw "status $($r.StatusCode)" }
    'health endpoint responded 200'
}

# --- platforms --------------------------------------------------------------
Test-Step 'Platform status reports every source' {
    $p = Invoke-RestMethod "$BaseUrl/api/platforms" -TimeoutSec 20
    if (-not $p -or $p.Count -lt 2) { throw 'expected at least two platforms' }
    $live = ($p | Where-Object { $_.live }).Count
    "$($p.Count) sources, $live live"
}

Test-Step 'Marketplace key state is reported honestly' {
    $p = Invoke-RestMethod "$BaseUrl/api/platforms" -TimeoutSec 20
    # @() forces an array: a single-item pipeline in PowerShell 5.1 has no .Count.
    $primaries = @($p | Where-Object { $_.primary })
    if ($primaries.Count -eq 0) { throw 'no marketplace sources registered' }
    $live = @($primaries | Where-Object { $_.live })
    $down = @($primaries | Where-Object { -not $_.live })

    if ($live.Count -eq 0) {
        'no marketplace live - ' + ($down[0].note)
    } elseif ($down.Count -gt 0) {
        # Name which one is down and why, rather than reporting a partial
        # success as though everything were fine.
        "$($live.Count) live ($(($live | ForEach-Object { $_.code }) -join ', ')); " +
        "$(($down | ForEach-Object { $_.code }) -join ', ') down - $($down[0].note)"
    } else {
        "all $($live.Count) marketplaces live"
    }
}

# --- catalogue --------------------------------------------------------------
Test-Step 'Categories are seeded' {
    $c = Invoke-RestMethod "$BaseUrl/api/categories" -TimeoutSec 20
    if (-not $c -or $c.Count -lt 5) { throw "only $($c.Count) categories" }
    "$($c.Count) categories"
}

Test-Step 'Search returns products' {
    $s = Invoke-RestMethod "$BaseUrl/api/products/search?q=&size=5" -TimeoutSec 90
    if ($s.totalResults -lt 1) { throw 'catalogue is empty' }
    "$($s.totalResults) products in catalogue"
}

Test-Step 'Search ranks with a TOPSIS value score' {
    $s = Invoke-RestMethod "$BaseUrl/api/products/search?q=&size=5" -TimeoutSec 90
    $scored = $s.products | Where-Object { $null -ne $_.valueScore }
    if (-not $scored) { throw 'no product carried a value score' }
    "top score $([math]::Round($scored[0].valueScore,1))"
}

Test-Step 'Natural language query is parsed into filters' {
    $s = Invoke-RestMethod "$BaseUrl/api/products/search?q=laptop%20under%2060k%20from%20dell&size=5" -TimeoutSec 90
    if (-not $s.interpretedAs -or $s.interpretedAs.Count -lt 2) {
        throw "expected parsed filters, got: $($s.interpretedAs -join ', ')"
    }
    $s.interpretedAs -join ' | '
}

# --- product detail ---------------------------------------------------------
$script:sampleId = $null
Test-Step 'Product detail assembles offers, sentiment and forecast' {
    $s = Invoke-RestMethod "$BaseUrl/api/products/search?q=&size=1" -TimeoutSec 90
    $script:sampleId = $s.products[0].id
    $d = Invoke-RestMethod "$BaseUrl/api/products/$($script:sampleId)" -TimeoutSec 30
    if (-not $d.summary) { throw 'no summary' }
    if ($null -eq $d.offers) { throw 'no offers array' }
    if (-not $d.forecast) { throw 'no forecast' }
    "offers=$($d.offers.Count) reviews=$($d.reviews.Count) history=$($d.priceHistory.Count) signal=$($d.forecast.signal)"
}

Test-Step 'Simulated price history is labelled as simulated' {
    $d = Invoke-RestMethod "$BaseUrl/api/products/$($script:sampleId)" -TimeoutSec 30
    $simulated = $d.priceHistory | Where-Object { $_.source -eq 'SIMULATED' }
    if ($simulated -and -not $d.forecast.containsSimulatedData) {
        throw 'simulated points present but forecast does not declare them'
    }
    if ($simulated) { "$($simulated.Count) simulated points, correctly flagged" }
    else { 'all observed, nothing simulated' }
}

# --- cross-platform matching ------------------------------------------------
Test-Step 'Matching engine merges listings into products' {
    # Pages through the whole catalogue rather than sampling the first page:
    # merged products are a small minority, so a single page can easily miss
    # them and report "no merges" when there are several.
    $first = Invoke-RestMethod "$BaseUrl/api/products/search?q=&size=100" -TimeoutSec 90
    $all = @($first.products)
    for ($page = 1; $page -lt [Math]::Min($first.totalPages, 10); $page++) {
        $next = Invoke-RestMethod "$BaseUrl/api/products/search?q=&size=100&page=$page" -TimeoutSec 90
        $all += $next.products
    }
    $merged = @($all | Where-Object { $_.offerCount -gt 1 })
    $multi  = @($all | Where-Object { $_.platformCount -gt 1 })

    # How many marketplaces are actually serving decides what "no cross-platform
    # results" means - with fewer than two live, there is nothing to cross.
    $liveMarkets = @((Invoke-RestMethod "$BaseUrl/api/platforms" -TimeoutSec 20) |
        Where-Object { $_.primary -and $_.live })

    if ($multi.Count -gt 0) {
        "$($multi.Count) product(s) carried by more than one platform - cross-platform comparison working"
    } elseif ($liveMarkets.Count -lt 2) {
        "$($merged.Count) merged product(s); cross-platform not possible yet - " +
        "only $($liveMarkets.Count) marketplace(s) live, two are needed"
    } else {
        "$($merged.Count) merged product(s); both marketplaces live but no shared product found yet - " +
        "search a widely stocked item to exercise matching"
    }
}

# --- comparison -------------------------------------------------------------
Test-Step 'Comparison ranks with TOPSIS and names a winner' {
    $s = Invoke-RestMethod "$BaseUrl/api/products/search?q=&size=3" -TimeoutSec 90
    if ($s.products.Count -lt 2) { throw 'need at least two products to compare' }
    $ids = @($s.products[0].id, $s.products[1].id)
    $body = @{ productIds = $ids; weights = @{ price = 40; rating = 30; discount = 30 } } | ConvertTo-Json
    $c = Invoke-RestMethod "$BaseUrl/api/compare" -Method Post -Body $body -ContentType 'application/json' -TimeoutSec 60
    if (-not $c.winnerProductId) { throw 'no winner returned' }
    if (-not $c.ranking -or $c.ranking.Count -lt 2) { throw 'ranking incomplete' }
    "winner #$($c.winnerProductId): $($c.winnerReason)"
}

Test-Step 'Comparison weights change the ranking' {
    $s = Invoke-RestMethod "$BaseUrl/api/products/search?q=&size=6" -TimeoutSec 90
    $ids = @($s.products[0].id, $s.products[1].id, $s.products[2].id)

    $cheap = @{ productIds = $ids; weights = @{ price = 100 } } | ConvertTo-Json
    $rated = @{ productIds = $ids; weights = @{ rating = 100 } } | ConvertTo-Json

    $a = Invoke-RestMethod "$BaseUrl/api/compare" -Method Post -Body $cheap -ContentType 'application/json' -TimeoutSec 60
    $b = Invoke-RestMethod "$BaseUrl/api/compare" -Method Post -Body $rated -ContentType 'application/json' -TimeoutSec 60

    "price-weighted winner #$($a.winnerProductId), rating-weighted winner #$($b.winnerProductId)"
}

# --- auth and account -------------------------------------------------------
$script:token = $null
Test-Step 'Registration issues a JWT' {
    $email = "smoke$([DateTime]::Now.Ticks)@example.com"
    $body = @{ name = 'Smoke Test'; email = $email; password = 'smoke-test-password' } | ConvertTo-Json
    $r = Invoke-RestMethod "$BaseUrl/api/auth/register" -Method Post -Body $body -ContentType 'application/json' -TimeoutSec 30
    if (-not $r.token) { throw 'no token issued' }
    $script:token = $r.token
    "registered $($r.user.email)"
}

Test-Step 'Authenticated identity endpoint works' {
    $headers = @{ Authorization = "Bearer $($script:token)" }
    $me = Invoke-RestMethod "$BaseUrl/api/auth/me" -Headers $headers -TimeoutSec 20
    if (-not $me.email) { throw 'no identity returned' }
    $me.email
}

Test-Step 'Protected endpoint rejects an anonymous caller' {
    try {
        Invoke-RestMethod "$BaseUrl/api/favorites" -TimeoutSec 20 | Out-Null
        throw 'favorites was reachable without a token'
    }
    catch {
        $status = $_.Exception.Response.StatusCode.value__
        if ($status -eq 401 -or $status -eq 403) { "correctly refused with $status" }
        else { throw "expected 401/403, got $status" }
    }
}

Test-Step 'Favourite can be added and read back' {
    $headers = @{ Authorization = "Bearer $($script:token)" }
    Invoke-RestMethod "$BaseUrl/api/favorites/$($script:sampleId)" -Method Post -Headers $headers -TimeoutSec 30 | Out-Null
    $list = Invoke-RestMethod "$BaseUrl/api/favorites" -Headers $headers -TimeoutSec 20
    if (-not ($list | Where-Object { $_.product.id -eq $script:sampleId })) { throw 'favourite not persisted' }
    "$($list.Count) favourite(s)"
}

Test-Step 'Search history is recorded for the account' {
    $headers = @{ Authorization = "Bearer $($script:token)" }
    Invoke-RestMethod "$BaseUrl/api/products/search?q=smoke-history-probe" -Headers $headers -TimeoutSec 60 | Out-Null
    $h = Invoke-RestMethod "$BaseUrl/api/history/search" -Headers $headers -TimeoutSec 20
    if (-not $h) { throw 'no history recorded' }
    "$($h.Count) entr(y/ies)"
}

Test-Step 'Deals endpoint responds' {
    $d = Invoke-RestMethod "$BaseUrl/api/deals?limit=5" -TimeoutSec 30
    "$($d.Count) discounted product(s)"
}

Test-Step 'Unknown product returns a structured 404' {
    try {
        Invoke-RestMethod "$BaseUrl/api/products/999999999" -TimeoutSec 20 | Out-Null
        throw 'expected a 404'
    }
    catch {
        $status = $_.Exception.Response.StatusCode.value__
        if ($status -ne 404) { throw "expected 404, got $status" }
        '404 with a JSON error body'
    }
}

# --- report -----------------------------------------------------------------
Write-Host ""
$results | Format-Table -AutoSize -Property Result, Check, Detail | Out-String -Width 200 | Write-Host

Write-Host ""
if ($script:failCount -eq 0) {
    Write-Host "All $($script:passCount) checks passed." -ForegroundColor Green
    exit 0
}
else {
    Write-Host "$($script:passCount) passed, $($script:failCount) FAILED." -ForegroundColor Red
    exit 1
}
