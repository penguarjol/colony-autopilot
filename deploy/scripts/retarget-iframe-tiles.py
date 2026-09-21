#!/usr/bin/env python3
"""Repoint embedded panels at the origin the board is served from.

Each panel needs its own port. Served under a subpath of the board, an app
that calls absolute /api routes reaches Homarr's API instead of its own and
renders blank. The host must be the name rather than the IP, because the
session cookie is scoped to the name and is never sent to an IP.

Edits the two iframe rows in place rather than rebuilding the board, so any
arrangement made in the UI survives. Idempotent.
"""
from __future__ import annotations

import json
import sqlite3
import sys

DB = "/var/lib/docker/volumes/docker_homarr_data/_data/db/db.sqlite"
HOST = "192.168.1.171.sslip.io"
# Old subpath and IP forms, mapped to the port each panel now owns.
RETARGET = {
    "/opencode/": f"http://{HOST}:4096/",
    "/terminal/": f"http://{HOST}:7682/",
}


def main() -> int:
    con = sqlite3.connect(DB)
    changed = 0
    try:
        for item_id, raw in con.execute(
            "SELECT id, options FROM item WHERE kind='iframe'"
        ).fetchall():
            opts = json.loads(raw)
            inner = opts.get("json", opts)
            url = inner.get("embedUrl", "")
            for marker, target in RETARGET.items():
                if marker in url and url != target:
                    inner["embedUrl"] = target
                    con.execute(
                        "UPDATE item SET options=? WHERE id=?",
                        (json.dumps(opts), item_id),
                    )
                    print(f"  {url} -> {target}")
                    changed += 1
                    break
        con.commit()

        print(f"\n{changed} panel(s) repointed; verifying:")
        bad = 0
        for (raw,) in con.execute("SELECT options FROM item WHERE kind='iframe'"):
            opts = json.loads(raw)
            url = opts.get("json", opts).get("embedUrl", "")
            ok = url in RETARGET.values()
            print(f"  {'ok  ' if ok else 'BAD '} {url}")
            bad += 0 if ok else 1
        return 1 if bad else 0
    finally:
        con.close()


if __name__ == "__main__":
    sys.exit(main())
