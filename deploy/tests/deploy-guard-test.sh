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
exit "${SCP_FAILURE:-0}"
STUB
cat > "$WORK/bin/ssh" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$WORK/ssh.log"
case "$*" in
 *'mktemp -d'*) printf '/opt/1panel/www/.gmsr-deploy.Test1234\n';;
 *' bash -s') cat > "$WORK/backend-body.sh"; [[ -z "${SSH_BACKEND_FAILURE:-}" ]] || exit "$SSH_BACKEND_FAILURE";;
esac
STUB
cat > "$WORK/bin/docker" <<'STUB'
#!/usr/bin/env bash
exit 0
STUB
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

# Backend rejection in all mode must not proceed to a frontend build or upload.
rm -f "$WORK/npm.log"
: > "$WORK/ssh.log"
set +e
SSH_BACKEND_FAILURE=27 bash "$ROOT/deploy/deploy.sh" all > "$WORK/all.log" 2>&1
rc=$?
set -e
test "$rc" = 27
test ! -f "$WORK/npm.log"
! grep -q 'mktemp -d' "$WORK/ssh.log"

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
bash "$ROOT/deploy/deploy.sh" backend > "$WORK/backend.log" 2>&1
test -s "$WORK/backend-body.sh"
sed -e "s|/opt/gmsr/app|$WORK/app|g" -e "s|/root/gmsr-build/backend/target/gmsr-scheduler.jar|$WORK/build.jar|g" "$WORK/backend-body.sh" > "$WORK/backend-fixture.sh"
mkdir -p "$WORK/app"
printf 'new jar' > "$WORK/build.jar"
for state in error down; do
 printf 'old jar' > "$WORK/app/gmsr-scheduler.jar"
 if BACKEND_TEST=1 HEALTH="$state" bash "$WORK/backend-fixture.sh" > "$WORK/health.log" 2>&1; then
  echo "Unhealthy response was accepted: $state" >&2; exit 1
 fi
 test "$(cat "$WORK/app/gmsr-scheduler.jar")" = 'old jar'
 grep -q '已恢复上一版本' "$WORK/health.log" || { cat "$WORK/health.log"; exit 1; }
done
printf 'old jar' > "$WORK/app/gmsr-scheduler.jar"
if BACKEND_TEST=1 SYSTEMCTL_FAIL=1 HEALTH=up bash "$WORK/backend-fixture.sh" > "$WORK/health.log" 2>&1; then echo 'Restart failure was accepted' >&2; exit 1; fi
test "$(cat "$WORK/app/gmsr-scheduler.jar")" = 'old jar'
BACKEND_TEST=1 HEALTH=up bash "$WORK/backend-fixture.sh" > "$WORK/health.log" 2>&1
grep -q '后端已就绪' "$WORK/health.log"
test "$(cat "$WORK/app/gmsr-scheduler.jar")" = 'new jar'
test "$(cat "$WORK/app/.rollback/gmsr-scheduler-previous.jar")" = 'old jar'
grep -q 'microphone=(self)' "$ROOT/deploy/security-headers.inc"
grep -q 'install .*deploy/backup-attachments.sh' "$ROOT/deploy/RESTORE.md"
echo 'DEPLOY_GUARD_PASS: interrupted upload/publication preserves live entry, old assets/helpers remain, HTTP errors/DOWN/restart failure roll back, successful upgrade keeps previous JAR, voice policy and backup installation checked'
