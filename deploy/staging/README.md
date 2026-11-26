# Staging world on VM 123

OpenRune rev 242, world 2, next to the live rev 233 server. Installed 2026-11-25.

| Piece | On the VM |
|---|---|
| Checkout | `/opt/osrs/openrune/server` (this repo, `main`), owned by `osrs` |
| Config | `game.yml` = `deploy/game.staging.yml` with `world-key` and the DB password filled in from `/opt/osrs/openrune/secrets.env` (0640 osrs); rewritten on every deploy, so edit the repo file, not the VM copy |
| Keys | `.data/game.key` (private, 0600), `.data/client.key` (public modulus; the launcher's staging entry pins it) |
| Service | `beanscape-staging` (this folder), port **40002**, UFW: tailnet only |
| Client config | `/opt/osrs/config/webconfig/staging/` from `make-webconfig.py`, served at `https://runescape-server.tail5475a4.ts.net/staging/jav_config.ws` |
| Accounts | Central `accounts` (registration required), plus `world_login_whitelist` for world 2 |

Deploys are automatic: `beanscape-staging-deploy.timer` runs `deploy-staging.sh` every 5 minutes.
A new commit on `main` whose **build, test & boot** check passed is compiled next to the running
game, Postgres is dumped to `/opt/osrs/backups/openrune/*-pre-deploy.dump` (20 kept), `game.yml` is
rendered from `deploy/game.staging.yml` + `secrets.env`, then the game is stopped (players saved),
the cache rebuilt, the build installed and the game started. The script updates itself from the
commit first. Docs-only commits are recorded without a restart. A failed build leaves the game
running on the old commit and is not retried until the next push.

    journalctl -u beanscape-staging-deploy -n 100         # what it did
    sudo /opt/osrs/openrune/deploy/deploy-staging.sh --force   # redeploy current main now

State lives in `/opt/osrs/openrune/deploy/` (`deployed-sha`, `failed-sha`, `build.log`,
`install.log`). OpenRune Central (`deploy/central`) and the staging webconfig (`make-webconfig.py`)
are not deployed by it.

Install the timer once (after that it keeps its own units current):

    cd /opt/osrs/openrune/server
    sudo install -d /opt/osrs/openrune/deploy
    sudo install -m 755 deploy/staging/deploy-staging.sh /opt/osrs/openrune/deploy/
    sudo install -m 644 deploy/staging/beanscape-staging-deploy.{service,timer} /etc/systemd/system/
    sudo systemctl daemon-reload && sudo systemctl enable --now beanscape-staging-deploy.timer

Manual build (stop first: replacing jars under a running JVM breaks its shutdown save):

    cd /opt/osrs/openrune/server && sudo -u osrs git pull --ff-only
    sudo -u osrs ./gradlew --no-daemon --no-configuration-cache :server:app:installDist
    sudo systemctl restart beanscape-staging

A fresh checkout first needs `./gradlew --no-daemon :or-cache:freshCache :or-cache:mergePluginGamevals`
and `./gradlew --no-daemon generateRsa` (a new key means a new modulus in the launcher).

The auto-deploy rebuilds the cache on every deploy. For a manual build after a change to a `pack`
module (cache content, e.g. the Market Hub's floors in `beanlab-hub/pack`), stop the service and
rebuild the cache before `installDist`; back up `.data/cache` first:

    sudo systemctl stop beanscape-staging
    sudo -u osrs cp -a .data/cache .data/cache.bak-$(date +%Y%m%d-%H%M)
    sudo -u osrs ./gradlew --no-daemon --no-configuration-cache :or-cache:buildCache

Let someone in:

    sudo -u postgres psql -d openrune_central -c \
      "INSERT INTO world_login_whitelist (world_id, account_name) VALUES (2, '<name>')"

Live accounts can be copied with their Argon2 hash (Central verifies `$argon2i$` hashes), so the
password stays the same. Staff rank is `accounts.rights = 'ADMINISTRATOR'` (or `MOD`).
