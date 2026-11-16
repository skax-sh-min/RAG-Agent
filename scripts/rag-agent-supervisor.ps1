# rag-agent-supervisor.ps1 - runs rag-agent.jar and applies updates that the app staged.
# ASCII ONLY: Windows PowerShell 5.1 reads a BOM-less file as the ANSI code page, so non-ASCII text would be garbled.
# Same protocol as rag-agent-supervisor.sh (read that header for the full story):
#   the app downloads + verifies the new jar, takes a DB backup, writes <UPDATE_DIR>\pending.properties and exits with code 42.
#   This script re-verifies the SHA-256, swaps the jar (old one kept as rag-agent.jar.prev), starts the new version and waits
#   until GET /api/v1/health answers 200. If it never gets healthy (exits first, or no answer within RAG_HEALTH_TIMEOUT s)
#   it restores rag-agent.jar.prev AND the pre-update DB backup (Flyway migrations only go forward), records the failure in
#   result.properties and starts the old version. It also restarts the app after a crash (gives up after RAG_CRASH_LIMIT
#   crashes within RAG_CRASH_WINDOW seconds).
#
# Run from the install directory (rag-agent.jar, .env, data\). From a "scripts" folder it moves up one level:
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\rag-agent-supervisor.ps1     (or windows_run_supervised.cmd)
# Settings (env or .env): JAVA_BIN, JAVA_OPTS, RAG_JAR, DATA_DIR, UPDATE_DIR, SERVER_PORT, RAG_HEALTH_URL,
#   RAG_HEALTH_TIMEOUT (180), RAG_HEALTH_INTERVAL (2), RAG_RESTART_DELAY (5), RAG_CRASH_LIMIT (5), RAG_CRASH_WINDOW (300)
# Exit codes: 0 stopped normally, 1 gave up (crash loop, missing jar, rollback impossible)

$ErrorActionPreference = 'Continue'

if ((Split-Path -Leaf (Get-Location).Path) -eq 'scripts') { Set-Location .. }
$Root = (Get-Location).Path
[System.IO.Directory]::SetCurrentDirectory($Root)

function Log($message) { Write-Host "[supervisor] $message" }

function Get-EnvOr($name, $default) {
    $v = [Environment]::GetEnvironmentVariable($name)
    if ([string]::IsNullOrEmpty($v)) { return $default }
    return $v
}

# .env: KEY=VALUE lines, '#' comments, one pair of surrounding quotes is removed (same result as the shell scripts).
if (Test-Path -LiteralPath '.env') {
    foreach ($line in (Get-Content -LiteralPath '.env' -Encoding UTF8)) {
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
            $key = $Matches[1]
            $value = $Matches[2].Trim()
            if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) {
                $value = $value.Substring(1, $value.Length - 2)
            }
            [Environment]::SetEnvironmentVariable($key, $value, 'Process')
        }
    }
}

function Resolve-Full($path) { return [System.IO.Path]::GetFullPath((Join-Path $Root $path)) }

$JavaBin = Get-EnvOr 'JAVA_BIN' 'java'
$JavaOpts = Get-EnvOr 'JAVA_OPTS' ''
$Jar = Resolve-Full (Get-EnvOr 'RAG_JAR' '.\rag-agent.jar')
$DataDir = Get-EnvOr 'DATA_DIR' '.\data'
$UpdateDir = Resolve-Full (Get-EnvOr 'UPDATE_DIR' (Join-Path $DataDir 'updates'))
$Port = Get-EnvOr 'SERVER_PORT' '8080'
$HealthUrl = Get-EnvOr 'RAG_HEALTH_URL' "http://127.0.0.1:$Port/api/v1/health"
$HealthTimeout = [int](Get-EnvOr 'RAG_HEALTH_TIMEOUT' '180')
$HealthInterval = [int](Get-EnvOr 'RAG_HEALTH_INTERVAL' '2')
$RestartDelay = [int](Get-EnvOr 'RAG_RESTART_DELAY' '5')
$CrashLimit = [int](Get-EnvOr 'RAG_CRASH_LIMIT' '5')
$CrashWindow = [int](Get-EnvOr 'RAG_CRASH_WINDOW' '300')

$env:RAG_SUPERVISED = '1'
$env:UPDATE_DIR = $UpdateDir
New-Item -ItemType Directory -Force -Path $UpdateDir | Out-Null
$Pending = Join-Path $UpdateDir 'pending.properties'
$Trial = Join-Path $UpdateDir 'trial.properties'
$Result = Join-Path $UpdateDir 'result.properties'
$JarPrev = "$Jar.prev"
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

# First run from a source checkout: take the freshest build as the installed jar.
if (-not (Test-Path -LiteralPath $Jar)) {
    $built = Get-ChildItem -Path (Join-Path $Root 'target') -Filter 'rag-agent-*.jar' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($built) {
        Log "no $Jar - installing $($built.FullName)"
        Copy-Item -LiteralPath $built.FullName -Destination $Jar -Force
    } else {
        Log "ERROR: $Jar not found (copy rag-agent-<version>.jar there, or build with: mvn clean package -DskipTests)"
        exit 1
    }
}

function Get-Prop($file, $key) {
    if (-not (Test-Path -LiteralPath $file)) { return '' }
    foreach ($l in (Get-Content -LiteralPath $file -Encoding UTF8)) {
        if ($l.StartsWith("$key=")) { return $l.Substring($key.Length + 1).Trim() }
    }
    return ''
}

function Get-NowUtc { return (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ') }

function Write-Text($path, $text) {
    # no BOM: the Java side parses "status=..." and a BOM would turn the first key into something else
    [System.IO.File]::WriteAllText($path, $text, $Utf8NoBom)
}

function Write-Result($status, $version, $reason, $dbRestored) {
    $text = "status=$status`nversion=$version`nreason=$reason`ndbRestored=$dbRestored`nat=$(Get-NowUtc)`n"
    Write-Text "$Result.tmp" $text
    Move-Item -LiteralPath "$Result.tmp" -Destination $Result -Force
}

function Get-Sha256($path) { return (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash.ToLower() }

function Reject($version, $reason) {
    Remove-Item -LiteralPath $Pending -Force -ErrorAction SilentlyContinue
    Write-Result 'rejected' $version $reason 'false'
    Log "update to $version rejected: $reason (still running the current jar)"
}

function Apply-Pending {
    $ver = Get-Prop $Pending 'version'
    $newJar = Get-Prop $Pending 'jar'
    $want = (Get-Prop $Pending 'sha256').ToLower()
    $dbBackup = Get-Prop $Pending 'dbBackup'
    $dbFile = Get-Prop $Pending 'dbFile'
    Log "pending update to $ver"
    if ([string]::IsNullOrEmpty($newJar) -or -not (Test-Path -LiteralPath $newJar)) { Reject $ver 'missing-jar'; return }
    $actual = Get-Sha256 $newJar
    if ([string]::IsNullOrEmpty($want) -or $actual -ne $want) { Reject $ver 'checksum-mismatch'; return }
    try {
        Copy-Item -LiteralPath $Jar -Destination $JarPrev -Force -ErrorAction Stop
        Copy-Item -LiteralPath $newJar -Destination "$Jar.new" -Force -ErrorAction Stop
        Move-Item -LiteralPath "$Jar.new" -Destination $Jar -Force -ErrorAction Stop
    } catch {
        Remove-Item -LiteralPath "$Jar.new" -Force -ErrorAction SilentlyContinue
        if (Test-Path -LiteralPath $JarPrev) { Copy-Item -LiteralPath $JarPrev -Destination $Jar -Force -ErrorAction SilentlyContinue }
        Reject $ver 'copy-failed'
        return
    }
    Write-Text $Trial ("version=$ver`nprevJar=$JarPrev`ndbBackup=$dbBackup`ndbFile=$dbFile`nstartedAt=$(Get-NowUtc)`n")
    Remove-Item -LiteralPath $Pending, $newJar -Force -ErrorAction SilentlyContinue
    Log "jar replaced with $ver (previous kept as $JarPrev)"
}

function Start-Java {
    $argList = @()
    if (-not [string]::IsNullOrWhiteSpace($JavaOpts)) {
        $argList += @($JavaOpts -split '\s+' | Where-Object { $_ })
    }
    $argList += @('-jar', ('"' + $Jar + '"'))
    $p = Start-Process -FilePath $JavaBin -ArgumentList $argList -WorkingDirectory $Root -NoNewWindow -PassThru
    $null = $p.Handle    # keeps ExitCode readable after the process ends
    return $p
}

function Stop-Java($proc) {
    if ($null -eq $proc) { return }
    # taskkill /T ends the whole tree: JAVA_BIN may be a wrapper (.cmd) whose child would otherwise keep running
    # and keep the port and the jar locked while we roll back
    try { if (-not $proc.HasExited) { & taskkill.exe /PID $proc.Id /T /F 2>&1 | Out-Null } } catch { }
    try { if (-not $proc.HasExited) { $proc.Kill() } } catch { }
    try { $null = $proc.WaitForExit(30000) } catch { }
}

function Test-Health {
    try {
        $req = [System.Net.WebRequest]::Create($HealthUrl)
        $req.Timeout = 3000
        $res = $req.GetResponse()
        try {
            if ($res -is [System.Net.HttpWebResponse]) { return ([int]$res.StatusCode -eq 200) }
            return $true
        } finally { $res.Close() }
    } catch { return $false }
}

# 0 = healthy, 1 = process died first, 2 = timed out
function Wait-Healthy($proc) {
    $waited = 0
    while ($waited -lt $HealthTimeout) {
        if ($proc.HasExited) { return 1 }
        if (Test-Health) { return 0 }
        Start-Sleep -Seconds $HealthInterval
        $waited += $HealthInterval
    }
    return 2
}

function Invoke-Rollback($proc, $reason) {
    $ver = Get-Prop $Trial 'version'
    $prev = Get-Prop $Trial 'prevJar'
    $dbBackup = Get-Prop $Trial 'dbBackup'
    $dbFile = Get-Prop $Trial 'dbFile'
    Log "version $ver did not become healthy ($reason) - rolling back"
    Stop-Java $proc
    $restoredJar = $false
    if (-not [string]::IsNullOrEmpty($prev) -and (Test-Path -LiteralPath $prev)) {
        try { Copy-Item -LiteralPath $prev -Destination $Jar -Force -ErrorAction Stop; $restoredJar = $true } catch { }
    }
    if (-not $restoredJar) {
        Log "ERROR: previous jar '$prev' is missing - cannot roll back"
        Write-Result 'rolled-back' $ver $reason 'false'
        Remove-Item -LiteralPath $Trial -Force -ErrorAction SilentlyContinue
        exit 1
    }
    $restored = 'false'
    if (-not [string]::IsNullOrEmpty($dbBackup) -and (Test-Path -LiteralPath $dbBackup) -and -not [string]::IsNullOrEmpty($dbFile)) {
        # the -wal/-shm files must go, or SQLite replays the new version's WAL on top of the restored file
        Remove-Item -LiteralPath $dbFile, "$dbFile-wal", "$dbFile-shm" -Force -ErrorAction SilentlyContinue
        try {
            Copy-Item -LiteralPath $dbBackup -Destination $dbFile -Force -ErrorAction Stop
            $restored = 'true'
            Log "database restored from $dbBackup"
        } catch {
            Log "ERROR: could not restore the database from $dbBackup"
        }
    } else {
        Log 'no database backup recorded - database left as it is'
    }
    Write-Result 'rolled-back' $ver $reason $restored
    Remove-Item -LiteralPath $Trial -Force -ErrorAction SilentlyContinue
}

$crashTimes = New-Object System.Collections.ArrayList
$proc = $null

Log "supervising $Jar (updates: $UpdateDir, health: $HealthUrl)"
try {
    while ($true) {
        if (Test-Path -LiteralPath $Pending) { Apply-Pending }
        $onTrial = Test-Path -LiteralPath $Trial

        $proc = Start-Java
        if ($onTrial) {
            $rc = Wait-Healthy $proc
            if ($rc -eq 0) {
                $ver = Get-Prop $Trial 'version'
                Remove-Item -LiteralPath $Trial -Force -ErrorAction SilentlyContinue
                Write-Result 'ok' $ver '' 'false'
                Log "version $ver is healthy - update complete"
            } else {
                $reason = 'exited-early'
                if ($rc -eq 2) { $reason = 'unhealthy-timeout' }
                Invoke-Rollback $proc $reason
                continue
            }
        }

        $proc.WaitForExit()
        $code = $proc.ExitCode
        $proc = $null
        if ($code -eq 0) {
            if (Test-Path -LiteralPath $Pending) { continue }
            Log 'application stopped normally'
            exit 0
        }
        if ($code -eq 42) {
            Log 'restart requested for an update'
            continue
        }
        $now = Get-Date
        $recent = @($crashTimes | Where-Object { ($now - $_).TotalSeconds -lt $CrashWindow })
        $crashTimes.Clear()
        foreach ($t in $recent) { [void]$crashTimes.Add($t) }
        [void]$crashTimes.Add($now)
        if ($crashTimes.Count -ge $CrashLimit) {
            Log "ERROR: the application crashed $($crashTimes.Count) times within ${CrashWindow}s (last exit code $code) - giving up"
            exit 1
        }
        Log "application exited with code $code - restarting in ${RestartDelay}s"
        Start-Sleep -Seconds $RestartDelay
    }
} finally {
    if ($null -ne $proc) { Stop-Java $proc }
}
