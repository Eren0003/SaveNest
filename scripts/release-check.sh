#!/usr/bin/env bash
# SaveNest release checks. Run from anywhere: ./scripts/release-check.sh
# Requires: Java 21, Maven, Node 20+. Integration tests need Docker or TEST_DATABASE_URL (empty, disposable DB).
# Set BENCHMARK=true to also run the 10,000-bookmark benchmark (needs its own disposable DB in TEST_DATABASE_URL).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
fail=0
step() { printf '\n== %s\n' "$1"; }
bad() { echo "FAIL: $1"; fail=1; }

step "Secrets and private files"
grep -qxF ".env" .gitignore || bad ".env missing from .gitignore"
grep -qxF ".env*.local" .gitignore || bad ".env*.local missing from .gitignore"
if grep -rInE --exclude-dir={node_modules,.next,target} --exclude='*.lock' --exclude=package-lock.json \
    -e '-----BEGIN [A-Z ]*PRIVATE KEY-----' -e 'AKIA[0-9A-Z]{16}' -e 'ghp_[A-Za-z0-9]{36}' -e 'sk_live_[A-Za-z0-9]{20,}' \
    --exclude=.env --exclude=.env.local . ; then
  bad "possible secret committed"
fi
grep -q 'replace-with' .env.example || bad ".env.example should contain placeholders only"

step "Production config defaults"
grep -q 'secure: ${COOKIE_SECURE:true}' backend/src/main/resources/application.yml || bad "cookie secure must default to true"
grep -q 'include-stacktrace: never' backend/src/main/resources/application.yml || bad "stack traces must not be exposed"
grep -q 'include: health' backend/src/main/resources/application.yml || bad "only the health actuator may be exposed"

step "Backend tests"
(cd backend && mvn -B -q test) || bad "backend tests"
skipped=$(ls backend/target/surefire-reports/TEST-*.xml | grep -v ApiBenchmarkTest | xargs grep -l 'skipped="[1-9]' | wc -l || true)
[ "$skipped" = "0" ] || echo "WARN: $skipped test class(es) had skipped tests (no Docker / TEST_DATABASE_URL?)"

step "Backend package"
(cd backend && mvn -B -q -DskipTests package) || bad "backend package"

step "Frontend typecheck and build"
command -v npm >/dev/null || bad "npm not on PATH (install Node.js 20+)"
(cd frontend && { [ -d node_modules ] || npm ci; } && npm run -s typecheck && npm run -s build) || bad "frontend build"

if [ "${BENCHMARK:-false}" = "true" ]; then
  step "Benchmark"
  (cd backend && mvn -B -q test -Dtest=ApiBenchmarkTest) || bad "benchmark targets"
fi

echo
[ "$fail" = 0 ] && echo "RELEASE CHECKS PASSED" || { echo "RELEASE CHECKS FAILED"; exit 1; }
