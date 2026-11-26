# Staging world on VM 123

OpenRune rev 242, world 2, next to the live rev 233 server. Installed 2026-11-25.

| Piece | On the VM |
|---|---|
| Checkout | `/opt/osrs/openrune/server` (this repo, `main`), owned by `osrs` |
| Config | `game.yml` = `deploy/game.staging.yml` with `world-key` and the DB password filled in from `/opt/osrs/openrune/secrets.env` (0640 osrs) |
| Keys | `.data/game.key` (private, 0600), `.data/client.key` (public modulus; the launcher's staging entry pins it) |
| Service | `beanscape-staging` (this folder), port **40002**, UFW: tailnet only |
| Client config | `/opt/osrs/config/webconfig/staging/` from `make-webconfig.py`, served at `https://runescape-server.tail5475a4.ts.net/staging/jav_config.ws` |
| Accounts | Central `accounts` (registration required), plus `world_login_whitelist` for world 2 |

Build and restart (stop first: replacing jars under a running JVM breaks its shutdown save):

    cd /opt/osrs/openrune/server && sudo -u osrs git pull --ff-only
    sudo -u osrs ./gradlew --no-daemon --no-configuration-cache :server:app:installDist
    sudo systemctl restart beanscape-staging

A fresh checkout first needs `./gradlew --no-daemon :or-cache:freshCache :or-cache:mergePluginGamevals`
and `./gradlew --no-daemon generateRsa` (a new key means a new modulus in the launcher).

Let someone in:

    sudo -u postgres psql -d openrune_central -c \
      "INSERT INTO world_login_whitelist (world_id, account_name) VALUES (2, '<name>')"

Live accounts can be copied with their Argon2 hash (Central verifies `$argon2i$` hashes), so the
password stays the same. Staff rank is `accounts.rights = 'ADMINISTRATOR'` (or `MOD`).
