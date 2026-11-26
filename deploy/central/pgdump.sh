#!/bin/sh
# Nightly dump of the OpenRune Central / Beanscape staging database. Keeps the 14 newest.
set -eu
# Create the dump owner-only from the start (no window where it is world-readable).
umask 077
. /opt/osrs/openrune/secrets.env
install -d -m 0750 -o root -g osrs /opt/osrs/backups/openrune
out=/opt/osrs/backups/openrune/openrune_central-$(date +%Y-%m-%d-%H%M%S).dump
PGPASSWORD=$OPENRUNE_DB_PASSWORD pg_dump -h 127.0.0.1 -U openrune -d openrune_central -Fc -f "$out"
chgrp osrs "$out" && chmod 0640 "$out"
ls -1t /opt/osrs/backups/openrune/openrune_central-*.dump | tail -n +15 | xargs -r rm -f
