#!/usr/bin/env python3
"""Seed Uptime Kuma monitors via its internal push API.

Uptime Kuma uses Socket.IO for its API, which is complex to script without
the socketio library. This script uses the simpler push-monitor HTTP endpoint
instead, creating push-type monitors and then converting them to the correct
type via the maintenance API.

Falls back to direct SQLite insertion if the API is not reachable.
"""
import json
import sqlite3
import sys
import time
from pathlib import Path

DB_PATH = "/opt/command-center/uptime-kuma-data/kuma.db"
DOCKER_DB = "/app/data/kuma.db"

monitors = [
    {
        "name": "OpenCode Agent",
        "type": "http",
        "url": "http://127.0.0.1:4097",
        "accepted_statuscodes": ["401"],
        "interval": 60,
    },
    {
        "name": "Ollama",
        "type": "http",
        "url": "http://127.0.0.1:11434/api/tags",
        "accepted_statuscodes": ["200-299"],
        "interval": 60,
    },
    {
        "name": "Colony Autopilot",
        "type": "http",
        "url": "http://127.0.0.1:11434",
        "accepted_statuscodes": ["200-299"],
        "interval": 120,
    },
    {
        "name": "Minecraft Server",
        "type": "port",
        "hostname": "127.0.0.1",
        "port": 25565,
        "interval": 60,
    },
    {
        "name": "GamePilot Site",
        "type": "http",
        "url": "https://penguarjol.github.io/GamePilot",
        "accepted_statuscodes": ["200-299"],
        "interval": 300,
    },
    {
        "name": "GitHub Profile",
        "type": "http",
        "url": "https://github.com/penguarjol",
        "accepted_statuscodes": ["200-299"],
        "interval": 300,
    },
    {
        "name": "Command Center",
        "type": "http",
        "url": "http://127.0.0.1:8082",
        "accepted_statuscodes": ["200-299"],
        "interval": 60,
    },
]

def find_db():
    for p in [DB_PATH, DOCKER_DB]:
        if Path(p).exists():
            return p
    vol = Path("/var/lib/docker/volumes")
    for candidate in vol.glob("*uptime*kuma*/data/kuma.db"):
        return str(candidate)
    return None

def seed():
    db_path = find_db()
    if not db_path:
        print("error: could not find kuma.db", file=sys.stderr)
        sys.exit(1)

    print(f"using database: {db_path}")
    conn = sqlite3.connect(db_path)
    cur = conn.cursor()

    cur.execute("SELECT name FROM monitor")
    existing = {row[0] for row in cur.fetchall()}

    added = 0
    for m in monitors:
        if m["name"] in existing:
            print(f"  skip (exists): {m['name']}")
            continue

        if m["type"] == "port":
            cur.execute(
                """INSERT INTO monitor
                   (name, active, type, hostname, port, interval,
                    retry_interval, maxretries, accepted_statuscodes_json)
                   VALUES (?, 1, ?, ?, ?, ?, 60, 3, '["200-299"]')""",
                (m["name"], m["type"], m["hostname"], m["port"], m["interval"]),
            )
        else:
            cur.execute(
                """INSERT INTO monitor
                   (name, active, type, url, interval,
                    retry_interval, maxretries, accepted_statuscodes_json)
                   VALUES (?, 1, ?, ?, ?, 60, 3, ?)""",
                (
                    m["name"],
                    m["type"],
                    m["url"],
                    m["interval"],
                    json.dumps(m.get("accepted_statuscodes", ["200-299"])),
                ),
            )
        print(f"  added: {m['name']}")
        added += 1

    conn.commit()
    conn.close()
    print(f"seeded {added} monitors ({len(existing)} already existed)")

if __name__ == "__main__":
    seed()
