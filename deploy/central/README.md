# OpenRune Central on VM 123 (staging)

Installed 2026-11-25 (phase 1, W2). Files here mirror the VM; secrets are not in git.

| Piece | On the VM |
|---|---|
| PostgreSQL 18 (localhost only) | role `openrune`, database `openrune_central` |
| Secrets | `/opt/osrs/openrune/secrets.env` (0640 root:osrs): `OPENRUNE_DB_PASSWORD`, `OPENRUNE_WORLD_KEY` |
| Central | `/opt/osrs/openrune/central/` jar + `central-config.yaml` (VM copy has the DB password), unit `openrune-central` |
| Ports | HTTP 8090, world link 9091; UFW default-deny keeps both local |
| Backups | `openrune-pgdump.timer` 03:30 daily → `/opt/osrs/backups/openrune/*.dump`, 14 kept |
| Realm / world | realm 2 `beanscape-staging` (require_registration 1, dev_mode 0, 150x); world 2, members, whitelist gate on |

Central is built from OpenRune-Central-Server commit `d694191` **plus `patches/`**:

    git clone https://github.com/OpenRune/OpenRune-Central-Server.git && cd OpenRune-Central-Server
    git checkout d694191
    git apply <this repo>/deploy/central/patches/*.patch
    ./gradlew shadowCentralJar        # -> central-app/build/libs/openrune-central-server.jar

Copy the jar to `/opt/osrs/openrune/central/`, then `systemctl restart openrune-central` and
`systemctl restart beanscape-staging` (the game reconnects its world link). The unpatched jar is kept
there as `openrune-central-server.jar.d694191`.

| Patch | Fixes |
|---|---|
| `0001-whitelist-queries-and-link-logging.patch` | **Every login to a whitelisted world failed** ("No reply from loginserver"). `WorldRepository.hasLoginWhitelistEntries` ran `login_whitelist_exists.sql`, which takes two parameters, with one; `isLoginWhitelisted` used the `login_username` column that Central's own V20 renamed to `account_name`. The queries are swapped back. The world-link server also swallowed every handler exception and closed the channel silently; it now logs them. Upstream candidate for OpenRune-Central-Server. |

**Config the VM copy of `central-config.yaml` must keep** (besides the secrets):

    openrune:
      badWords:
        remoteUrl: ""     # 2026-11-26

Central's default remote profanity list (a gist) has short entries that shrink to two letters once
symbols are dropped (`s&m` -> `sm`, `as$` -> `as`), so its world-login check refused names like
smith, thomas and jason. Beanscape sign-up (overlay `web/central_sync.py`) writes every new account
into Central and applies the same rules, also with no list. If you turn a list back on, point
`OPENRUNE_BAD_WORDS_URL` for the web panel and bot at the same URL.

Accounts arrive in Central from the live sign-up (web OAuth and the Discord bot, `/resetpassword`
too). Copy existing live accounts with
`sudo /opt/osrs/web/venv/bin/python /opt/osrs/web/central_sync.py sync --all` (dry run; add `--apply`).

Add a staff account to the staging whitelist:

    INSERT INTO world_login_whitelist (world_id, account_name) VALUES (2, '<name>');
