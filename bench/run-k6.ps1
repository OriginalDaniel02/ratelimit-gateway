# Runs the three k6 scenarios back to back and writes bench/results/k6-*.json.
#
# Needs: a Redis you can reach, k6 on PATH (or C:\Program Files\k6), and `mvn package -DskipTests` already done.
#   -RedisUri        Redis for the gateway (default redis://127.0.0.1:6379)
#   -RedisContainer  optional Docker container name of that Redis; if given, the script verifies the limiter really
#                    wrote keys there during the "with-limiter" run (and none during "proxy-only")
param(
    [int]$Vus = 32,
    [string]$Duration = '30s',
    [string]$Warmup = '40s',
    [string]$RedisUri = 'redis://127.0.0.1:6379',
    [string]$RedisContainer = '',
    # The production default is 100ms. On a slow machine, or Redis behind Docker Desktop port forwarding, calls under
    # load exceed that, the limiter degrades to fail-open and the run measures nothing useful (the script then aborts).
    [string]$CommandTimeout = '2s'
)

$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$k6 = if (Get-Command k6 -ErrorAction SilentlyContinue) { 'k6' } else { 'C:\Program Files\k6\k6.exe' }
$jar = 'target\ratelimit-gateway-0.1.0.jar'

function Test-PortOpen($port) {
    [bool](Test-NetConnection localhost -Port $port -InformationLevel Quiet -WarningAction SilentlyContinue)
}

function Wait-Port($port) {
    for ($i = 0; $i -lt 120; $i++) {
        if (Test-PortOpen $port) { return }
        Start-Sleep -Milliseconds 500
    }
    throw "port $port never opened"
}

# On Windows the java launcher can hand over to a child process, so the PID Start-Process returns is not the server's.
# Always stop servers by the port they listen on.
function Stop-Port($port) {
    Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
        ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue }
    for ($i = 0; $i -lt 40 -and (Test-PortOpen $port); $i++) { Start-Sleep -Milliseconds 250 }
}

function Assert-PortFree($port) {
    if (Test-PortOpen $port) {
        throw "port $port is already in use. Stop whatever is listening there first, otherwise k6 would measure the wrong server."
    }
}

# How many limiter scripts (EVALSHA) Redis has run since the counters were reset. Keys cannot be used as proof:
# they expire seconds after the last request, so they are gone by the time the run ends.
function Redis-LimiterCalls() {
    if (-not $RedisContainer) { return $null }
    $line = docker exec $RedisContainer redis-cli info commandstats | Select-String 'cmdstat_evalsha:calls=(\d+)'
    if ($line) { [long]$line.Matches[0].Groups[1].Value } else { 0 }
}

function Run-K6($name, $target) {
    Write-Host "== $name ($target)"
    & $k6 run -e TARGET=$target -e VUS=$Vus -e DURATION=$Duration --summary-export "bench/results/k6-$name.json" bench/gateway.js
}

Assert-PortFree 9000
Assert-PortFree 8080
Start-Process java -ArgumentList '-cp', 'target\classes', 'io.ratelimit.dev.DummyService', '9000' -WindowStyle Hidden | Out-Null
try {
    Wait-Port 9000
    & $k6 run -q -e TARGET=http://localhost:9000 -e VUS=16 -e DURATION=$Warmup bench/gateway.js | Out-Null
    Run-K6 'direct' 'http://localhost:9000'

    foreach ($mode in @(@('proxy-only', 'bench/policies-none.yml', $false), @('with-limiter', 'bench/policies-bench.yml', $true))) {
        Assert-PortFree 8080
        if ($RedisContainer) { docker exec $RedisContainer redis-cli config resetstat | Out-Null }
        Start-Process java -ArgumentList '-jar', $jar, "--gateway.policy-file=$($mode[1])", '--gateway.upstream=http://localhost:9000', "--gateway.redis-uri=$RedisUri", "--gateway.redis.command-timeout=$CommandTimeout" -WindowStyle Hidden | Out-Null
        try {
            Wait-Port 8080
            Start-Sleep -Seconds 3
            # Warm up so JIT compilation, class loading and the Redis connection are not part of the measurement.
            # On a slow CPU this takes a while: throughput kept rising for the first ~30s in our runs.
            & $k6 run -q -e TARGET=http://localhost:8080 -e VUS=16 -e DURATION=$Warmup bench/gateway.js | Out-Null
            Run-K6 $mode[0] 'http://localhost:8080'

            $status = (Invoke-WebRequest http://localhost:8080/_gateway/status -UseBasicParsing).Content
            Write-Host "status after run: $status"
            if ($status -match '"degradedChecks":([1-9]\d*)') { throw "$($mode[0]): Redis checks were degraded, results are not valid" }
            $calls = Redis-LimiterCalls
            if ($null -ne $calls) {
                Write-Host "limiter calls seen by Redis: $calls"
                if ($mode[2] -and $calls -lt 1000) { throw "with-limiter: Redis saw only $calls limiter calls, the limiter was not on the request path" }
                if (-not $mode[2] -and $calls -gt 0) { throw "proxy-only: Redis saw $calls limiter calls, the limiter was unexpectedly used" }
            }
        } finally {
            Stop-Port 8080
        }
    }
} finally {
    Stop-Port 9000
    Stop-Port 8080
}
