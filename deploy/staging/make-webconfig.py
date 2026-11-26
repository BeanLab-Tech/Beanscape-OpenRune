#!/usr/bin/env python3
"""Writes the staging client config next to the live one on VM 123.

Usage: make-webconfig.py <central jav_config.ws> <out dir>
  curl -sf http://127.0.0.1:8090/jav_config.ws > /tmp/central_jav.ws
  python3 make-webconfig.py /tmp/central_jav.ws /opt/osrs/config/webconfig/staging

osrs-webconfig serves that folder at https://runescape-server.tail5475a4.ts.net/staging/. Central's
own jav_config points the client at 127.0.0.1, so this rewrites:
  codebase  -> the VM's tailnet name (the client logs in to the codebase host)
  param 12  -> 2 (world id)
  param 15  -> 1 (build). With a non-zero build the client connects to 40000 + world = 40002
               (fallback 50000 + world) instead of 43594, so staging can share the VM's IP with live.
  param 17  -> the staging world list below
"""

import struct
import sys

HOST = "runescape-server.tail5475a4.ts.net"
WORLD = 2
ACTIVITY = "Beanscape Staging (242)"


def main(src: str, out: str) -> None:
    replace = {"title": "Beanscape Staging", "codebase": f"http://{HOST}/"}
    params = {"12": str(WORLD), "15": "1", "17": f"https://{HOST}/staging/world_list.ws"}
    lines = []
    for line in open(src, encoding="utf-8").read().splitlines():
        key, _, value = line.partition("=")
        if key in replace:
            line = f"{key}={replace[key]}"
        elif key == "param":
            pkey, _, _ = value.partition("=")
            if pkey in params:
                line = f"param={pkey}={params[pkey]}"
        lines.append(line)
    with open(f"{out}/jav_config.ws", "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")

    # world_list.ws: int length, short count, then per world: short id, int flags (1 = members),
    # host\0, activity\0, byte location, short population.
    body = struct.pack(">H", 1)
    body += struct.pack(">Hi", WORLD, 1) + HOST.encode() + b"\0" + ACTIVITY.encode() + b"\0"
    body += struct.pack(">bH", 0, 0)
    with open(f"{out}/world_list.ws", "wb") as f:
        f.write(struct.pack(">i", len(body)) + body)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
