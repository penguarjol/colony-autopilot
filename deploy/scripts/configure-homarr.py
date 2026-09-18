#!/usr/bin/env python3
"""Inspect the live Homarr instance so board configuration targets real
procedure names and option schemas rather than guessed ones.

Writing rows straight into Homarr's SQLite file is what produced a board full
of dead tiles: raw inserts skip validation, never create integration records,
and never populate widget options. Everything here goes through the API.
"""
from __future__ import annotations

import json
import sys

sys.path.insert(0, "/tmp")
from homarr_api import Homarr, HomarrError  # noqa: E402


PROBE_QUERIES = [
    "app.all",
    "board.getAllBoards",
    "integration.all",
    "widget.options",
    "serverSettings.getAll",
    "user.getAll",
]


def main() -> None:
    api = Homarr()
    try:
        key = api.ensure_api_key()
        print(f"api key acquired: {key[:12]}... (len {len(key)})")
    except HomarrError as exc:
        sys.exit(f"could not obtain an API key: {exc}")

    print("\n=== tRPC probes ===")
    for procedure in PROBE_QUERIES:
        try:
            result = api.trpc_query(procedure)
            print(f"  {procedure}: {json.dumps(result)[:300]}")
        except HomarrError as exc:
            print(f"  {procedure}: {exc}")

    print("\n=== MCP tool catalogue ===")
    try:
        import urllib.request

        body = json.dumps(
            {"jsonrpc": "2.0", "method": "tools/list", "id": 1}
        ).encode()
        req = urllib.request.Request(
            "http://127.0.0.1:7575/api/mcp/mcp", data=body, method="POST"
        )
        req.add_header("Content-Type", "application/json")
        req.add_header("Accept", "application/json, text/event-stream")
        req.add_header("ApiKey", key)
        with urllib.request.urlopen(req, timeout=40) as resp:
            text = resp.read().decode()
        names = sorted({
            part.split('"')[0]
            for part in text.split('"name":"')[1:]
        })
        print(f"  {len(names)} tools")
        for name in names:
            print(f"    {name}")
    except Exception as exc:  # noqa: BLE001 - diagnostic path
        print(f"  mcp tools/list failed: {exc}")


if __name__ == "__main__":
    main()
