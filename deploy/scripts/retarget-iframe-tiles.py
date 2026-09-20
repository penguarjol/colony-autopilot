#!/usr/bin/env python3
"""Repoint embedded panels at the origin the board is served from.

The tiles were authored against the IP. Once the board moved to a hostname
for the sake of the session cookie, those panels became cross-origin: the
cookie is scoped to the hostname and is never sent to the IP, and the frame
allowance did not list the new parent. Both show up as an empty panel.

Edits the two iframe rows in place rather than rebuilding the board, so any
arrangement made in the UI survives. Idempotent.
"""
from __future__ import annotations

import json
import sqlite3
import sys

DB = "/var/lib/docker/volumes/docker_homarr_data/_data/db/db.sqlite"
ORIGIN = "http://192.168.1.171.sslip.io:3000"
STALE_PREFIXES = ("http://192.168.1.171:3000", "http://127.0.0.1:3000")


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
            for prefix in STALE_PREFIXES:
                if url.startswith(prefix):
                    inner["embedUrl"] = ORIGIN + url[len(prefix):]
                    con.execute(
                        "UPDATE item SET options=? WHERE id=?",
                        (json.dumps(opts), item_id),
                    )
                    print(f"  {url} -> {inner['embedUrl']}")
                    changed += 1
                    break
        con.commit()

        print(f"\n{changed} panel(s) repointed; verifying:")
        bad = 0
        for (raw,) in con.execute("SELECT options FROM item WHERE kind='iframe'"):
            opts = json.loads(raw)
            url = opts.get("json", opts).get("embedUrl", "")
            ok = url.startswith(ORIGIN)
            print(f"  {'ok  ' if ok else 'BAD '} {url}")
            bad += 0 if ok else 1
        return 1 if bad else 0
    finally:
        con.close()


if __name__ == "__main__":
    sys.exit(main())
