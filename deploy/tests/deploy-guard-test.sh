#!/usr/bin/env bash
# Execute publication/health logic with fake transports and tools, never a real host.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK="$(mktemp -d "$ROOT/.deploy-test.XXXXXXXX")"
case "$WORK" in "$ROOT/.deploy-test."*) ;; *) exit 1;; esac
trap 'rm -rf -- "$WORK"' EXIT
mkdir -p "$WORK/bin" "$WORK/www/gmsr/assets"
printf 'fixture known host\n' > "$WORK/known_hosts"
export KNOWN_HOSTS="$WORK/known_hosts"
export WORK ROOT REAL_INSTALL
REAL_INSTALL="$(command -v install)"

cat > "$WORK/bin/npm" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$WORK/npm.log"
exit 0
STUB
cat > "$WORK/bin/scp" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$@" >> "$WORK/scp.log"
exit "${SCP_FAILURE:-0}"
STUB
cat > "$WORK/bin/ssh" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$WORK/ssh.log"
case "$*" in
 *'.gmsr-backend.'*'mktemp'*|*'mktemp'*'.gmsr-backend.'*) printf '/root/gmsr-build/.gmsr-backend.Build1234\n';;
 *'mktemp -d'*) printf '/opt/1panel/www/.gmsr-deploy.Test1234\n';;
 *' bash -s -- /root/gmsr-build/.gmsr-backend.'*)
  cat > "$WORK/backend-body.sh"
  [[ -z "${SSH_BACKEND_FAILURE:-}" ]] || exit "$SSH_BACKEND_FAILURE"
  ;;
esac
STUB
cat > "$WORK/bin/docker" <<'STUB'
#!/usr/bin/env bash
exit 0
STUB
cat > "$WORK/bin/mvn" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
test -f pom.xml
test -d src
test ! -e target/stale.class
test ! -e src/stale.sql
printf '%s\n' "$PWD" >> "$WORK/maven-build.log"
mkdir -p target
printf 'new jar' > target/gmsr-scheduler.jar
STUB
if ! command -v flock > /dev/null; then
 printf '#!/usr/bin/env bash\nexit 0\n' > "$WORK/bin/flock"
fi
cat > "$WORK/bin/install" <<'STUB'
#!/usr/bin/env bash
destination="${@: -1}"
if [[ "${PUBLICATION_TEST:-0}" == 1 ]]; then
 if [[ "$destination" == "$WORK/www/gmsr/assets/new.js" && "${FAIL_ASSET:-0}" == 1 ]]; then exit 19; fi
 if [[ "$destination" == "$WORK/www/gmsr/".index-* ]]; then test -f "$WORK/www/gmsr/assets/new.js"; fi
fi
if [[ "${PUBLICATION_TEST:-0}" == 1 || "${BACKEND_TEST:-0}" == 1 ]]; then
 # Git Bash cannot apply POSIX directory modes to NTFS. Linux CI exercises
 # install -d -m 700 directly; fixtures contain no private application data.
 case "$(uname -s)" in
  MINGW*|MSYS*) for argument in "$@"; do [[ "$argument" != -d ]] || { mkdir -p "$destination"; exit 0; }; done ;;
 esac
 arguments=()
 while [[ $# -gt 0 ]]; do
  case "$1" in -o|-g) shift 2;; *) arguments+=("$1"); shift;; esac
 done
 exec "$REAL_INSTALL" "${arguments[@]}"
fi
exit 0
STUB
for tool in mv systemctl sleep; do
 cat > "$WORK/bin/$tool" <<'STUB'
#!/usr/bin/env bash
if [[ "${PUBLICATION_TEST:-0}" == 1 || "${BACKEND_TEST:-0}" == 1 ]] && [[ "$(basename "$0")" == mv ]]; then
 exec /usr/bin/mv "$@"
fi
if [[ "$(basename "$0")" == systemctl ]]; then
 printf '%s\n' "$*" >> "$WORK/systemctl.log"
 if [[ "${SYSTEMCTL_FAIL:-0}" == 1 && "$(cat "$WORK/app/gmsr-scheduler.jar")" == 'new jar' ]]; then exit 1; fi
fi
exit 0
STUB
done
cat > "$WORK/bin/curl" <<'STUB'
#!/usr/bin/env bash
if [[ "${BACKEND_TEST:-0}" == 1 && "$(cat "$WORK/app/gmsr-scheduler.jar")" == 'old jar' ]]; then printf '{"status":"UP"}\n'
elif [[ "$HEALTH" == error ]]; then
 [[ " $* " == *' -fsS '* ]] && exit 22
 printf '{"status":"UP"}\n'
elif [[ "$HEALTH" == down ]]; then printf '{"status":"DOWN"}\n'
else printf '{"status":"UP"}\n'; fi
STUB
chmod +x "$WORK/bin/"*
export PATH="$WORK/bin:$PATH"

# A failed transfer must leave the live site entirely alone.
set +e
SCP_FAILURE=23 bash "$ROOT/deploy/deploy.sh" frontend > "$WORK/upload.log" 2>&1
rc=$?
set -e
test "$rc" = 23
test "$(wc -l < "$WORK/ssh.log")" -eq 1
! grep -q 'rm -rf.*gmsr/\*' "$WORK/ssh.log"
if KNOWN_HOSTS="$WORK/missing-hosts" bash "$ROOT/deploy/deploy.sh" frontend > "$WORK/known-hosts.log" 2>&1; then
 echo 'An absent pinned-host file was accepted' >&2; exit 1
fi
grep -q 'KNOWN_HOSTS=' "$WORK/known-hosts.log"
test "$(wc -l < "$WORK/ssh.log")" -eq 1

# A backend transfer failure cleans up its build directory before remote execution.
: > "$WORK/ssh.log"
set +e
SCP_FAILURE=23 bash "$ROOT/deploy/deploy.sh" backend > "$WORK/backend-upload.log" 2>&1
rc=$?
set -e
test "$rc" = 23
grep -q 'rm -rf -- /root/gmsr-build/\.gmsr-backend\.Build1234$' "$WORK/ssh.log"
test ! -e "$WORK/backend-body.sh"

# Backend rejection in all mode must not proceed to a frontend build or upload.
rm -f "$WORK/npm.log"
: > "$WORK/ssh.log"
set +e
SSH_BACKEND_FAILURE=27 bash "$ROOT/deploy/deploy.sh" all > "$WORK/all.log" 2>&1
rc=$?
set -e
test "$rc" = 27
test ! -f "$WORK/npm.log"
! grep -q '\.gmsr-deploy\.' "$WORK/ssh.log"
grep -q '\.gmsr-backend\.' "$WORK/ssh.log"

# Extract the actual remote publication body and map only its root into our fixture.
awk '/<<\047REMOTE\047/ {copy=1; next} /^REMOTE$/ {copy=0} copy {print}' "$ROOT/deploy/deploy.sh" |
 sed "s|/opt/1panel/www|$WORK/www|g" > "$WORK/publish.sh"
test -s "$WORK/publish.sh"
printf 'old index' > "$WORK/www/gmsr/index.html"
printf 'old bundle' > "$WORK/www/gmsr/assets/old.js"
printf 'certificate helper' > "$WORK/www/gmsr/assets/ca-check.js"
make_stage() {
 mkdir -p "$WORK/www/.gmsr-deploy.Test1234/assets"
 printf 'new index' > "$WORK/www/.gmsr-deploy.Test1234/index.html"
 printf 'new bundle' > "$WORK/www/.gmsr-deploy.Test1234/assets/new.js"
}
make_stage
if PUBLICATION_TEST=1 FAIL_ASSET=1 bash "$WORK/publish.sh" "$WORK/www/.gmsr-deploy.Test1234" > "$WORK/publish.log" 2>&1; then
 echo 'Asset failure was accepted' >&2; exit 1
fi
test "$(cat "$WORK/www/gmsr/index.html")" = 'old index'
make_stage
PUBLICATION_TEST=1 bash "$WORK/publish.sh" "$WORK/www/.gmsr-deploy.Test1234"
test "$(cat "$WORK/www/gmsr/index.html")" = 'new index'
test "$(cat "$WORK/www/gmsr/assets/new.js")" = 'new bundle'
test "$(cat "$WORK/www/gmsr/assets/old.js")" = 'old bundle'
test "$(cat "$WORK/www/gmsr/assets/ca-check.js")" = 'certificate helper'

# Capture and execute the real backend readiness command using simulated HTTP results.
: > "$WORK/scp.log"
bash "$ROOT/deploy/deploy.sh" backend > "$WORK/backend.log" 2>&1
test -s "$WORK/backend-body.sh"
grep -Fx "$ROOT/backend/pom.xml" "$WORK/scp.log"
grep -Fx "$ROOT/backend/src" "$WORK/scp.log"
! grep -Fx "$ROOT/backend" "$WORK/scp.log"
grep -q '/root/gmsr-build/\.gmsr-backend\.Build1234' "$WORK/scp.log"
sed -e "s|/opt/gmsr/app|$WORK/app|g" -e "s|/root/gmsr-build|$WORK/build|g" "$WORK/backend-body.sh" > "$WORK/backend-fixture.sh"
mkdir -p "$WORK/app"
mkdir -p "$WORK/build/backend/target" "$WORK/build/backend/src"
touch "$WORK/build/backend/target/stale.class" "$WORK/build/backend/src/stale.sql"
backend_stage="$WORK/build/.gmsr-backend.Build1234"
run_backend() {
 mkdir -p "$backend_stage/src"
 touch "$backend_stage/pom.xml"
 BACKEND_TEST=1 HEALTH="$1" bash "$WORK/backend-fixture.sh" "$backend_stage"
}
for state in error down; do
 printf 'old jar' > "$WORK/app/gmsr-scheduler.jar"
 if run_backend "$state" > "$WORK/health.log" 2>&1; then
  echo "Unhealthy response was accepted: $state" >&2; exit 1
 fi
 test "$(cat "$WORK/app/gmsr-scheduler.jar")" = 'old jar'
 grep -q '已恢复上一版本' "$WORK/health.log" || { cat "$WORK/health.log"; exit 1; }
 test ! -d "$backend_stage"
done
printf 'old jar' > "$WORK/app/gmsr-scheduler.jar"
if SYSTEMCTL_FAIL=1 run_backend up > "$WORK/health.log" 2>&1; then echo 'Restart failure was accepted' >&2; exit 1; fi
test "$(cat "$WORK/app/gmsr-scheduler.jar")" = 'old jar'
test ! -d "$backend_stage"
run_backend up > "$WORK/health.log" 2>&1
grep -q '后端已就绪' "$WORK/health.log"
test "$(cat "$WORK/app/gmsr-scheduler.jar")" = 'new jar'
test "$(cat "$WORK/app/.rollback/gmsr-scheduler-previous.jar")" = 'old jar'
test ! -d "$backend_stage"
test -f "$WORK/build/backend/target/stale.class"
test -f "$WORK/build/backend/src/stale.sql"
test "$(sort -u "$WORK/maven-build.log")" = "$backend_stage"
grep -q 'microphone=(self)' "$ROOT/deploy/security-headers.inc"
grep -q 'install .*deploy/backup-attachments.sh' "$ROOT/deploy/RESTORE.md"
echo 'DEPLOY_GUARD_PASS: isolated backend build excludes stale files, failed readiness or restart rolls back, failed frontend publication preserves entry and older assets'
