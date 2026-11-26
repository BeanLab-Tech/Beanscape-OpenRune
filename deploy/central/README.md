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

Central is built from OpenRune-Central-Server (`./gradlew shadowCentralJar`,
`central-app/build/libs/openrune-central-server.jar`), commit `d694191`.

Add a staff account to the staging whitelist:

    INSERT INTO world_login_whitelist (world_id, account_name) VALUES (2, '<name>');
