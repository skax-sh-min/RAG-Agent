#!/bin/sh
# rag-agent-supervisor.sh - runs rag-agent.jar and applies updates that the app staged.
# (Windows: rag-agent-supervisor.ps1 / windows_run_supervised.cmd - same protocol.)
#
# WHY: a running JVM cannot replace its own jar (and on Windows the file is locked), and nobody is left to
# undo a bad update. So the app only DOWNLOADS + VERIFIES the new jar, takes a DB backup, writes
# $UPDATE_DIR/pending.properties and exits with code 42. This script then:
#   1. re-verifies the SHA-256 and swaps the jar   (old jar kept as rag-agent.jar.prev)
#   2. starts the new version and waits until GET /api/v1/health answers 200   (the "trial")
#   3. if it never gets healthy (crashes, or no answer within RAG_HEALTH_TIMEOUT seconds):
#        restores rag-agent.jar.prev AND the pre-update DB backup, records the failure, starts the old version.
#      DB restore matters: Flyway migrations only go forward, so the old jar may not run on a migrated DB.
#      It is safe because a version that never became healthy has served no user traffic.
# It also restarts the app after a crash (gives up after RAG_CRASH_LIMIT crashes within RAG_CRASH_WINDOW seconds).
#
# Run it from the install directory (rag-agent.jar, .env, data/). From a "scripts" folder it moves up one level.
# Files in $UPDATE_DIR (default ./data/updates, shared with the app through the UPDATE_DIR env var):
#   pending.properties  written by the app          version, jar, sha256, dbBackup, dbFile ...
#   trial.properties    written here while a new version is on trial
#   result.properties   written here: status=ok|rolled-back|rejected, version, reason, dbRestored, at
# Settings (env or .env): JAVA_BIN, JAVA_OPTS, RAG_JAR, DATA_DIR, UPDATE_DIR, SERVER_PORT, RAG_HEALTH_URL,
#   RAG_HEALTH_TIMEOUT (180), RAG_HEALTH_INTERVAL (2), RAG_RESTART_DELAY (5), RAG_CRASH_LIMIT (5), RAG_CRASH_WINDOW (300)
# Exit codes: 0 stopped normally / signal, 1 gave up (crash loop, missing jar, rollback impossible)

if [ "$(basename "$(pwd)")" = "scripts" ]; then
  cd .. || exit 1
fi

log() { echo "[supervisor] $*"; }

# Same convention as the other run scripts: .env is read by the shell, so values with spaces need quotes.
if [ -f .env ]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
fi

JAVA_BIN="${JAVA_BIN:-java}"
JAR="${RAG_JAR:-./rag-agent.jar}"
UPDATE_DIR="${UPDATE_DIR:-${DATA_DIR:-./data}/updates}"
PORT="${SERVER_PORT:-8080}"
HEALTH_URL="${RAG_HEALTH_URL:-http://127.0.0.1:${PORT}/api/v1/health}"
HEALTH_TIMEOUT="${RAG_HEALTH_TIMEOUT:-180}"
HEALTH_INTERVAL="${RAG_HEALTH_INTERVAL:-2}"
RESTART_DELAY="${RAG_RESTART_DELAY:-5}"
CRASH_LIMIT="${RAG_CRASH_LIMIT:-5}"
CRASH_WINDOW="${RAG_CRASH_WINDOW:-300}"

export RAG_SUPERVISED=1
export UPDATE_DIR
mkdir -p "$UPDATE_DIR" || exit 1
PENDING="$UPDATE_DIR/pending.properties"
TRIAL="$UPDATE_DIR/trial.properties"
RESULT="$UPDATE_DIR/result.properties"

# First run from a source checkout: take the freshest build as the installed jar.
if [ ! -f "$JAR" ]; then
  built=$(ls -t target/rag-agent-*.jar 2>/dev/null | head -n 1)
  if [ -n "$built" ]; then
    log "no $JAR - installing $built"
    cp -f "$built" "$JAR" || exit 1
  else
    log "ERROR: $JAR not found (copy rag-agent-<version>.jar there, or build with: mvn clean package -DskipTests)"
    exit 1
  fi
fi

JAVA_PID=""

prop() {  # prop <file> <key>
  [ -f "$1" ] || return 0
  sed -n "s/^$2=//p" "$1" | head -n 1 | tr -d '\r'
}

now_utc() { date -u +%Y-%m-%dT%H:%M:%SZ; }

write_result() {  # write_result <status> <version> <reason> <dbRestored>
  {
    echo "status=$1"
    echo "version=$2"
    echo "reason=$3"
    echo "dbRestored=$4"
    echo "at=$(now_utc)"
  } > "$RESULT.tmp" && mv -f "$RESULT.tmp" "$RESULT"
}

# The file goes in through stdin on purpose: given a NAME, GNU sha256sum prefixes the hash with a backslash when the
# name contains one (a Windows path), and the comparison below would fail for a perfectly good jar.
sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum < "$1" | cut -d' ' -f1
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 < "$1" | cut -d' ' -f1
  else
    openssl dgst -sha256 < "$1" | sed 's/^.*= //'
  fi
}

lower() { tr 'A-F' 'a-f'; }

reject() {  # reject <version> <reason>
  rm -f "$PENDING"
  write_result rejected "$1" "$2" false
  log "update to $1 rejected: $2 (still running the current jar)"
}

apply_pending() {
  ver=$(prop "$PENDING" version)
  newjar=$(prop "$PENDING" jar)
  want=$(prop "$PENDING" sha256 | lower)
  db_backup=$(prop "$PENDING" dbBackup)
  db_file=$(prop "$PENDING" dbFile)
  log "pending update to $ver"
  if [ -z "$newjar" ] || [ ! -f "$newjar" ]; then
    reject "$ver" missing-jar
    return 0
  fi
  actual=$(sha256_of "$newjar" | lower)
  if [ -z "$want" ] || [ "$actual" != "$want" ]; then
    reject "$ver" checksum-mismatch
    return 0
  fi
  if ! cp -f "$JAR" "$JAR.prev"; then
    reject "$ver" copy-failed
    return 0
  fi
  if ! cp -f "$newjar" "$JAR.new" || ! mv -f "$JAR.new" "$JAR"; then
    rm -f "$JAR.new"
    cp -f "$JAR.prev" "$JAR"
    reject "$ver" copy-failed
    return 0
  fi
  {
    echo "version=$ver"
    echo "prevJar=$JAR.prev"
    echo "dbBackup=$db_backup"
    echo "dbFile=$db_file"
    echo "startedAt=$(now_utc)"
  } > "$TRIAL"
  rm -f "$PENDING" "$newjar"
  log "jar replaced with $ver (previous kept as $JAR.prev)"
}

start_java() {
  # JAVA_OPTS is split on purpose (several -D options)
  # shellcheck disable=SC2086
  "$JAVA_BIN" $JAVA_OPTS -jar "$JAR" &
  JAVA_PID=$!
}

stop_java() {
  [ -n "$JAVA_PID" ] || return 0
  kill "$JAVA_PID" 2>/dev/null
  i=0
  while kill -0 "$JAVA_PID" 2>/dev/null && [ "$i" -lt 30 ]; do
    sleep 1
    i=$((i + 1))
  done
  kill -9 "$JAVA_PID" 2>/dev/null
  wait "$JAVA_PID" 2>/dev/null
}

# 0 = answered 200, 1 = did not, 2 = no HTTP tool available
health_ok() {
  if command -v curl >/dev/null 2>&1; then
    curl -fsS -o /dev/null --max-time 3 "$HEALTH_URL" >/dev/null 2>&1
    return $?
  elif command -v wget >/dev/null 2>&1; then
    wget -q -O /dev/null -T 3 "$HEALTH_URL" >/dev/null 2>&1
    return $?
  fi
  return 2
}

# 0 = healthy, 1 = process died first, 2 = timed out
wait_healthy() {
  waited=0
  while [ "$waited" -lt "$HEALTH_TIMEOUT" ]; do
    if ! kill -0 "$JAVA_PID" 2>/dev/null; then
      return 1
    fi
    health_ok
    rc=$?
    if [ "$rc" -eq 0 ]; then
      return 0
    fi
    # without curl/wget we cannot ask - accept "still alive after 60 s" instead
    if [ "$rc" -eq 2 ] && [ "$waited" -ge 60 ]; then
      return 0
    fi
    sleep "$HEALTH_INTERVAL"
    waited=$((waited + HEALTH_INTERVAL))
  done
  return 2
}

rollback() {  # rollback <reason>
  ver=$(prop "$TRIAL" version)
  prev=$(prop "$TRIAL" prevJar)
  db_backup=$(prop "$TRIAL" dbBackup)
  db_file=$(prop "$TRIAL" dbFile)
  log "version $ver did not become healthy ($1) - rolling back"
  stop_java
  if [ -z "$prev" ] || [ ! -f "$prev" ] || ! cp -f "$prev" "$JAR"; then
    log "ERROR: previous jar '$prev' is missing - cannot roll back"
    write_result rolled-back "$ver" "$1" false
    rm -f "$TRIAL"
    exit 1
  fi
  restored=false
  if [ -n "$db_backup" ] && [ -f "$db_backup" ] && [ -n "$db_file" ]; then
    # restore procedure of the operator manual: the -wal/-shm files must go, or SQLite replays the new version's WAL
    rm -f "$db_file" "$db_file-wal" "$db_file-shm"
    if cp -f "$db_backup" "$db_file"; then
      restored=true
      log "database restored from $db_backup"
    else
      log "ERROR: could not restore the database from $db_backup"
    fi
  else
    log "no database backup recorded - database left as it is"
  fi
  write_result rolled-back "$ver" "$1" "$restored"
  rm -f "$TRIAL"
}

on_signal() {
  log "signal received - stopping the application"
  if [ -n "$JAVA_PID" ]; then
    kill "$JAVA_PID" 2>/dev/null
    wait "$JAVA_PID" 2>/dev/null
  fi
  exit 0
}
trap on_signal INT TERM

crash_times=""

log "supervising $JAR (updates: $UPDATE_DIR, health: $HEALTH_URL)"
while :; do
  if [ -f "$PENDING" ]; then
    apply_pending
  fi
  on_trial=0
  [ -f "$TRIAL" ] && on_trial=1

  start_java
  if [ "$on_trial" -eq 1 ]; then
    wait_healthy
    rc=$?
    if [ "$rc" -eq 0 ]; then
      ver=$(prop "$TRIAL" version)
      rm -f "$TRIAL"
      write_result ok "$ver" "" false
      log "version $ver is healthy - update complete"
    else
      reason=exited-early
      [ "$rc" -eq 2 ] && reason=unhealthy-timeout
      rollback "$reason"
      continue
    fi
  fi

  wait "$JAVA_PID"
  code=$?
  JAVA_PID=""
  case "$code" in
    0)
      if [ -f "$PENDING" ]; then
        continue
      fi
      log "application stopped normally"
      exit 0
      ;;
    42)
      log "restart requested for an update"
      continue
      ;;
    *)
      now=$(date +%s)
      recent=""
      count=1
      for t in $crash_times; do
        if [ $((now - t)) -lt "$CRASH_WINDOW" ]; then
          recent="$recent $t"
          count=$((count + 1))
        fi
      done
      crash_times="$recent $now"
      if [ "$count" -ge "$CRASH_LIMIT" ]; then
        log "ERROR: the application crashed $count times within ${CRASH_WINDOW}s (last exit code $code) - giving up"
        exit 1
      fi
      log "application exited with code $code - restarting in ${RESTART_DELAY}s"
      sleep "$RESTART_DELAY"
      ;;
  esac
done
