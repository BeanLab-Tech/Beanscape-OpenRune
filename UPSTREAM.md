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

## Upstream position
| Date | OpenRune commit | Revision |
|---|---|---|
| 2026-11-25 | `3bcc0168d` | 242 |

## Beanscape modules
| Module | What | Notes |
|---|---|---|
| `beanlab-core` | `[beanlab] loaded` marker; `BeanlabSchema` migrations for the `beanlab` Postgres schema | |
| `beanlab-ge` | Grand Exchange (`::ge buy/sell/offers/cancel/collect/price`) | Ported from rev 233; tables in `beanlab.ge_*` |
| `beanlab-admin-api` | Admin console API for the Beanscape Admin plugin (loopback HTTP, `ADMIN_API_PORT`), `::admintoken` (owners) | Ported from rev 233; tokens/audit in `beanlab.admin_*`; owners in `beanlab.owners` (Central rights stop at ADMINISTRATOR) |

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
