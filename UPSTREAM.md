# Beanscape fork of OpenRune

This repository is **BeanLab-Tech/Beanscape-OpenRune**, a fork of
[OpenRune/OpenRune-Server](https://github.com/OpenRune/OpenRune-Server) (ISC). The migration plan
is `docs/OPENRUNE_PHASE1_PLAN.md` in BeanLab-Tech/Beanscape-OSRS-Private-Server.

## Rules
- Beanscape code lives in `content/beanlab/*` and `deploy/`. Avoid editing OpenRune files; when a
  change is unavoidable, keep it small, mark it `// BeanLab:` and prefer sending it upstream.
- Name every module directory `beanlab-<thing>` (e.g. `content/beanlab/beanlab-core`). Gradle
  identifies projects by their last path segment, so a plain `core` collided with `:api:core` and
  was silently dropped from the server.
- Merge OpenRune `main` weekly (`git fetch upstream && git merge upstream/main`), never rebase
  published history.
- OpenRune's own workflows (releases, test-server deploys, wiki and README bots) stay disabled here;
  our CI is `.github/workflows/beanlab-ci.yml`.

## Repositories
| Repo | What | Clone on bean-laptop |
|---|---|---|
| `BeanLab-Tech/Beanscape-OpenRune` (this one) | Server for staging (OpenRune rev 242) | `C:\Users\cfiaschetti\Beanscape-OpenRune` |
| `BeanLab-Tech/Beanscape-OSRS-Private-Server` | Live server (RsMod rev 233), launcher (`client/launcher`, Live/Staging switch), Admin and Companion plugins, web panel, Discord bot; migration plan and status in its `AGENTS.md` | `C:\Users\cfiaschetti\Beanscape-OSRS-Private-Server` |

## Build and test
- Fresh checkout: `./gradlew :or-cache:freshCache :or-cache:mergePluginGamevals`, then
  `cp game.example.yml game.yml` and `./gradlew generateRsa`.
- Our tests: `./gradlew :content:beanlab:<module>:test`. Run locally with `./gradlew run`
  (embedded Postgres + Central, `central.same-instance: true`).
- Add `--no-configuration-cache` for `installDist`/`run` if the configuration cache fails.
- Our tables live in the `beanlab` Postgres schema. Each module ships `V<n>__*.sql` resources and
  applies them with `BeanlabSchema.migrate(conn, module, owner, files)` (recorded in
  `beanlab.schema_migrations`). Never edit an applied file; add a new version.

## Staging on VM 123
| Piece | Where |
|---|---|
| Game | `beanscape-staging` service, `/opt/osrs/openrune/server` (this repo, `main`), world 2, port 40002, tailnet only. Build and restart: `deploy/staging/README.md` |
| Port | The client has no port setting; jav_config `param=15` (build) non-zero makes it connect to `40000 + world`. `deploy/staging/make-webconfig.py` writes that jav_config to `/opt/osrs/config/webconfig/staging/` |
| Central | `openrune-central` service, Postgres `openrune_central` (localhost), HTTP 8090, world link 9091: `deploy/central/README.md` |
| Accounts | Central `public.accounts`; realm 2 requires registration and world 2 has a login whitelist (`world_login_whitelist`). Staff: `accounts.rights` `MOD`/`ADMINISTRATOR`; owners also have a row in `beanlab.owners` |
| Admin API | `beanlab-admin-api` on `127.0.0.1:8088` (`ADMIN_API_PORT`, systemd drop-in), exposed as `https://runescape-server.tail5475a4.ts.net/adminapi-staging` |
| Client | Microbot 2.6.57 (RuneLite 1.13.6), patched by the launcher in the other repo (`ar.class` modulus, `ud.class` host allowlist). The modulus is `.data/client.key` on the VM; a new key means a new modulus in the launcher's `server-config.cjs` |
| Secrets | `/opt/osrs/openrune/secrets.env`, VM copies of `central-config.yaml` and `game.yml`. Never commit them |

## Upstream position
| Date | OpenRune commit | Revision |
|---|---|---|
| 2026-11-25 | `3bcc0168d` | 242 |

## Beanscape modules
| Module | What | Notes |
|---|---|---|
| `beanlab-core` | `[beanlab] loaded` marker; `BeanlabSchema` migrations for the `beanlab` Postgres schema | |
| `beanlab-ge` | Grand Exchange (`::ge buy/sell/offers/cancel/collect/price`) | Ported from rev 233; tables in `beanlab.ge_*` |
| `beanlab-admin-api` | Admin console API for the Beanscape Admin plugin (loopback HTTP, `ADMIN_API_PORT`), `::admintoken` (owners); `GET/PUT /v1/hub/layout` (owners) for the plugin's Hub map tab | Ported from rev 233; tokens/audit in `beanlab.admin_*`; owners in `beanlab.owners` (Central rights stop at ADMINISTRATOR) |
| `beanlab-hub` (+ `pack`) | Market Hub: a clan hall portal on the GE plaza bench tile (3182, 3468) leads to a hub at x 5120-5219, z 3072-3171 (map squares 80..81 x 48..49). Layout in `pack/.../hub-layout.json` (format: `docs/HUB_LAYOUT.md` in the other repo); walls, objects and NPCs spawned at startup; `::hub` admin commands edit it live and save `.data/hub-layout.json` (or `HUB_LAYOUT_FILE`) | Ported from rev 233. Floors are generated from the bundled layout by `HubTerrainTask` during `buildCache`, so a floor change needs a cache rebuild; an override file only changes walls, objects, NPCs, arrival and leave portal (restart) |

## Changes to OpenRune files (upstream candidates)
| Files | Change | Why |
|---|---|---|
| `api/net/.../ConnectionHandler.kt`, `api/account/.../AccountManager.kt`, `AccountLoaderService.kt`, `AccountLoadRequest.kt`, `CharacterAccountRepository.kt` | Enforce `realms.require_registration`: logins use `AccountLoadRequest.SearchOrCreateCharacter`, which never creates an `accounts` row. An unknown name gets "Invalid username or password" before Central auth runs. A registered account (e.g. Discord sign-up) gets its first character on first login, and stays "new" until it has logged in once. `AccountLoadResponseHook` runs the 2FA check for `NewAccount` too, because such an account may already have 2FA. Known limit: the first character row is written before Central checks the password (Central's auth needs a character id), so anyone who knows a registered name can create that empty row; it holds no data and stays "new". | The flag was loaded and documented ("players must have a pre-registered account") but not enforced. Central creates any unknown account on first login, and we need Discord-only sign-up (plan D4). |

## Known issues
- **GE atomicity (audit R6):** GE offer state and inventories are saved in separate transactions;
  a failed GE write after `::ge collect` or a sell can duplicate or lose items/gold. Fix: write
  COLLECTED before handing out items, retry/roll back failed persists, or keep a ledger.
- **Open registration (D4):** fixed; set `realms.require_registration = 1` on the Beanscape realm
  (see "Changes to OpenRune files").
- **Flyway vs PostgreSQL 18:** Central's Flyway warns PostgreSQL 18.6 is newer than it was tested
  with (Ubuntu 26.04 only ships 18). Works so far; watch Central upgrades.
- **Central is patched:** the VM runs Central `d694191` plus `deploy/central/patches/` (the
  world-login whitelist was broken, so no one could log in to a whitelisted world). Rebuilding
  Central without the patches brings that back; see `deploy/central/README.md`.
- **Bots:** OpenRune's cosmetic bots (`gameplay.bots-enabled`) are on for staging since
  2026-11-25. They fill busy areas (Lumbridge etc.) only while a player is near;
  `::bots on|off|count|here` manages them in game.
