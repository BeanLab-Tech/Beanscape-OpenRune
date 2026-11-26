#!/usr/bin/env bash
# Beanscape staging auto-deploy (VM 123). Run by beanscape-staging-deploy.timer every 5 minutes,
# as root. The fork's counterpart of the live overlay's deploy/deploy-vm.sh.
#
# When `main` on GitHub has a new commit whose "build, test & boot" check passed, this:
#   1. installs the newest copy of itself and re-runs it (so a fix to this script applies at once),
#   2. checks out the commit and compiles it next to the running server (a failed build never
#      stops the game),
#   3. backs up the Central / staging Postgres database,
#   4. renders game.yml from deploy/game.staging.yml plus /opt/osrs/openrune/secrets.env,
#   5. stops the game (players are saved), rebuilds the cache, installs the build and starts it.
# Commits that only touch docs are recorded without a restart.
#
# Not deployed here: OpenRune Central (deploy/central, patched and built by hand) and the
# staging webconfig (deploy/staging/make-webconfig.py).
#
# Manual use:  sudo /opt/osrs/openrune/deploy/deploy-staging.sh           (deploy if new)
#              sudo /opt/osrs/openrune/deploy/deploy-staging.sh --force   (redeploy current main)
# Logs:        journalctl -u beanscape-staging-deploy -n 100
set -euo pipefail

API="https://api.github.com/repos/BeanLab-Tech/Beanscape-OpenRune"
CI_CHECK="build, test & boot"            # job name in .github/workflows/beanlab-ci.yml
ROOT=/opt/osrs/openrune
SRV=$ROOT/server                         # this repo's checkout, owned by osrs; the game runs here
BASE=$ROOT/deploy                        # state, logs and the installed copy of this script
SECRETS=$ROOT/secrets.env
BACKUPS=/opt/osrs/backups/openrune
SERVICE=beanscape-staging
FORCE=${1:-}

log() { echo "[staging-deploy] $*"; }
# Repo code and osrs-owned paths are only touched as the game's service user, so a merged commit
# can do no more than the running game already can.
as_osrs() { runuser -u osrs -- env HOME=/opt/osrs GRADLE_USER_HOME=/opt/osrs/.gradle "$@"; }
gradle() { (cd "$SRV" && as_osrs ./gradlew --no-daemon --no-configuration-cache -q "$@"); }

install -d -m 0755 "$BASE"
if [ "${STAGING_DEPLOY_LOCKED:-}" != 1 ]; then
    exec 9>"$BASE/.lock"
    flock -n 9 || { log "another deploy is running"; exit 0; }
    export STAGING_DEPLOY_LOCKED=1
fi

as_osrs git -C "$SRV" fetch -q origin main
target=$(as_osrs git -C "$SRV" rev-parse origin/main)
current=$(cat "$BASE/deployed-sha" 2>/dev/null || echo none)
failed=$(cat "$BASE/failed-sha" 2>/dev/null || echo none)

if [ "$FORCE" != "--force" ]; then
    [ "$target" = "$current" ] && exit 0
    [ "$target" = "$failed" ] && exit 0  # already failed to build; wait for the next push
fi

ci=$(curl -fsS -H "Accept: application/vnd.github+json" "$API/commits/$target/check-runs" | python3 -c '
import json, sys
runs = [r for r in json.load(sys.stdin)["check_runs"] if r["name"] == sys.argv[1]]
if not runs or any(r["status"] != "completed" for r in runs): print("pending")
elif all(r["conclusion"] == "success" for r in runs): print("success")
else: print("failure")' "$CI_CHECK")
if [ "$ci" != "success" ]; then
    log "main ${target:0:7}: CI $ci, not deploying"
    exit 0
fi

# 1. Self-update before anything risky: run the target commit's copy of this script.
new_script=$(mktemp)
as_osrs git -C "$SRV" show "$target:deploy/staging/deploy-staging.sh" > "$new_script"
if [ "${STAGING_DEPLOY_UPDATED:-}" != 1 ] && ! cmp -s "$new_script" "$BASE/deploy-staging.sh"; then
    install -m 755 "$new_script" "$BASE/deploy-staging.sh"
    rm -f "$new_script"
    log "updated deploy script from ${target:0:7}; re-running it"
    STAGING_DEPLOY_UPDATED=1 exec "$BASE/deploy-staging.sh" "$@"
fi
rm -f "$new_script"

# Generated .data/gamevals files differ from the commit after every build; they are rewritten
# below, so a forced checkout is safe. Ignored files (game.yml, keys, cache) are untouched.
as_osrs git -C "$SRV" checkout -q --force --detach "$target"
if [ "$current" = none ] || [ "$FORCE" = "--force" ] || ! as_osrs git -C "$SRV" cat-file -e "$current" 2>/dev/null; then
    changed=$(as_osrs git -C "$SRV" ls-files)
else
    changed=$(as_osrs git -C "$SRV" diff --name-only "$current" "$target")
fi
if ! echo "$changed" | grep -qvE '\.md$|^docs/|^\.github/'; then
    log "main ${target:0:7}: docs-only change, recording without restart"
    echo "$target" > "$BASE/deployed-sha"
    exit 0
fi
log "deploying ${current:0:7} -> ${target:0:7}"

# 2. Compile next to the running game. The game runs from server/app/build/install, which only
#    installDist (step 5) replaces; .data/gamevals is read at boot only.
if ! gradle :or-cache:mergePluginGamevals assemble > "$BASE/build.log" 2>&1; then
    grep -E '^e:|What went wrong' -A3 "$BASE/build.log" | head -20 || true
    log "BUILD FAILED for ${target:0:7} (log: $BASE/build.log); game left running on ${current:0:7}"
    echo "$target" > "$BASE/failed-sha"
    exit 1
fi

# 3. Back up Postgres (accounts, characters, beanlab schema); keep the 20 newest pre-deploy dumps.
set -a; . "$SECRETS"; set +a
install -d -m 0750 -o root -g osrs "$BACKUPS"
ts=$(date +%Y-%m-%d-%H%M%S)
dump="$BACKUPS/openrune_central-$ts-pre-deploy.dump"
(umask 077; PGPASSWORD="$OPENRUNE_DB_PASSWORD" pg_dump -h 127.0.0.1 -U openrune -Fc openrune_central > "$dump")
chgrp osrs "$dump" && chmod 0640 "$dump"
ls -1t "$BACKUPS"/openrune_central-*-pre-deploy.dump 2>/dev/null | tail -n +21 | xargs -r rm -f

# 4. game.yml = the repo's staging config with the two secrets filled in.
rendered=$(mktemp)
OUT="$rendered" python3 - "$SRV/deploy/game.staging.yml" <<'PY'
import os, re, sys
text = open(sys.argv[1], encoding="utf-8").read()
for key, env in (("world-key", "OPENRUNE_WORLD_KEY"), ("password", "OPENRUNE_DB_PASSWORD")):
    text, n = re.subn(rf'^(\s*{key}:\s*)"SET_ON_VM"', lambda m: f'{m.group(1)}"{os.environ[env]}"', text, flags=re.M)
    if n != 1:
        sys.exit(f"deploy/game.staging.yml: expected one {key}: \"SET_ON_VM\", found {n}")
open(os.environ["OUT"], "w", encoding="utf-8").write(text)
PY
install -m 0640 -o osrs -g osrs "$rendered" "$SRV/game.yml"
rm -f "$rendered"

# systemd units for the game and this timer, from the repo.
install -m 644 "$SRV/deploy/staging/beanscape-staging.service" \
    "$SRV/deploy/staging/beanscape-staging-deploy.service" \
    "$SRV/deploy/staging/beanscape-staging-deploy.timer" /etc/systemd/system/
install -d /etc/systemd/system/$SERVICE.service.d
install -m 644 "$SRV"/deploy/staging/beanscape-staging.service.d/*.conf /etc/systemd/system/$SERVICE.service.d/
systemctl daemon-reload

# 5. Stop (saves online players), rebuild the cache, install, start.
systemctl stop "$SERVICE"
if ! gradle :or-cache:buildCache :or-cache:mergePluginGamevals :server:app:installDist > "$BASE/install.log" 2>&1; then
    log "INSTALL FAILED for ${target:0:7} (log: $BASE/install.log); starting whatever is installed"
    echo "$target" > "$BASE/failed-sha"
    systemctl start "$SERVICE"
    exit 1
fi
since=$(date '+%Y-%m-%d %H:%M:%S')
systemctl start "$SERVICE"

# Wait for the game to come up so a broken boot shows here, not only in the game's journal.
for _ in $(seq 1 60); do
    journalctl -u "$SERVICE" --since "$since" --no-pager -q | grep -q "Server ready in" && break
    systemctl is-active -q "$SERVICE" || break
    sleep 5
done
if ! journalctl -u "$SERVICE" --since "$since" --no-pager -q | grep -q "Server ready in"; then
    log "${target:0:7} installed but the game did not report ready (journalctl -u $SERVICE)"
    echo "$target" > "$BASE/failed-sha"
    exit 1
fi

echo "$target" > "$BASE/deployed-sha"
rm -f "$BASE/failed-sha"
log "deployed ${target:0:7}"
