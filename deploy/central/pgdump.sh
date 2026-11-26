#!/bin/sh
# Nightly dump of the OpenRune Central / Beanscape staging database. Keeps the 14 newest.
set -eu
. /opt/osrs/openrune/secrets.env
out=/opt/osrs/backups/openrune/openrune_central-$(date +%Y-%m-%d-%H%M%S).dump
PGPASSWORD=$OPENRUNE_DB_PASSWORD pg_dump -h 127.0.0.1 -U openrune -d openrune_central -Fc -f "$out"
chmod 0640 "$out"
ls -1t /opt/osrs/backups/openrune/openrune_central-*.dump | tail -n +15 | xargs -r rm -f
