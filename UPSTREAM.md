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
