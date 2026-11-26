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

## Known issues
- **GE atomicity (audit R6):** GE offer state and inventories are saved in separate transactions;
  a failed GE write after `::ge collect` or a sell can duplicate or lose items/gold. Fix: write
  COLLECTED before handing out items, retry/roll back failed persists, or keep a ledger.
- **Open registration (D4):** Central creates any unknown account on first login. OpenRune loads
  `realms.require_registration` into `RealmConfig` but nothing enforces it. Fix in W3 by honouring it
  in the login path before contacting Central (upstream PR candidate).
- **Flyway vs PostgreSQL 18:** Central's Flyway warns PostgreSQL 18.6 is newer than it was tested
  with (Ubuntu 26.04 only ships 18). Works so far; watch Central upgrades.
