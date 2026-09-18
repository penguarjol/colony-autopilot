#!/usr/bin/env python3
"""Prove each board panel returns real data.

Placing a widget only means Homarr accepted its options. This calls the same
widget data routes the browser calls, so a panel that renders an error in the
UI fails here too.
"""
from __future__ import annotations

import json
import sys

sys.path.insert(0, "/tmp")
from homarr_api import Homarr, HomarrError  # noqa: E402

HOST = "192.168.1.171"


def main() -> None:
    api = Homarr()
    api.ensure_api_key()

    integrations = {i["name"]: i for i in (api.trpc_query("integration.all") or [])}
    board = api.trpc_query("board.getHomeBoard")

    # board.getHomeBoard returns sections without their items nested, so count
    # placements at the source instead of reporting a misleading zero.
    import sqlite3

    conn = sqlite3.connect(
        "/var/lib/docker/volumes/docker_homarr_data/_data/db/db.sqlite"
    )
    try:
        kinds = dict(conn.execute(
            "SELECT kind, COUNT(*) FROM item WHERE board_id=? GROUP BY kind",
            (board["id"],),
        ).fetchall())
    finally:
        conn.close()

    print(f"board: {board['name']} ({board['id']})")
    print(f"items: {sum(kinds.values())}")
    for kind, count in sorted(kinds.items()):
        print(f"  {kind} x{count}")

    print(f"\nintegrations: {len(integrations)}")
    for name, record in sorted(integrations.items()):
        print(f"  {name:<18} {record['kind']}")

    pihole = [i["id"] for n, i in integrations.items() if n.startswith("Pi-hole")]
    glances = [i["id"] for n, i in integrations.items() if n == "Glances"]

    # Widget data routes are namespaced under widget.*; the bare names 404.
    checks: list[tuple[str, str, dict]] = []
    if pihole:
        checks.append(("DNS Hole Summary", "widget.dnsHole.summary",
                       {"integrationIds": pihole}))
    if glances:
        checks.append((
            "Health Monitoring",
            "widget.healthMonitoring.getSystemHealthStatus",
            {"integrationIds": glances},
        ))
    checks.append(("Docker Containers", "docker.getContainers", {}))

    print("\n=== panel data ===")
    failures = 0
    for label, procedure, payload in checks:
        try:
            result = api.trpc_query(procedure, payload or None)
            blob = json.dumps(result)
            if result in (None, [], {}):
                print(f"  EMPTY {label}: returned nothing")
                failures += 1
            else:
                print(f"  ok    {label}: {blob[:150]}")
        except HomarrError as exc:
            print(f"  FAIL  {label}: {str(exc)[:170]}")
            failures += 1

    print(f"\npanel failures: {failures}")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
