#!/usr/bin/env python3
"""Rebuild Uptime Kuma monitors and publish a status page.

Homarr's Uptime Kuma integration reads a *status page* slug, not the monitor
list, so a published page has to exist or the widget renders empty.

Monitors are written straight to the SQLite file with the container stopped.
Uptime Kuma has no REST API for monitor creation -- only Socket.IO -- and
driving that from a deploy script is far more fragile than a bounded offline
write.
"""
import json
import sqlite3
import subprocess
import sys
import time

SLUG = "command-center"
AGENT_ENV = "/etc/colony-agent/agent.env"

# Most services bind 127.0.0.1 only, so the Kuma container cannot reach them
# across the Docker bridge -- an earlier revision pointed at the service ports
# directly and every one of them timed out. Monitoring the Caddy-published
# ports instead both works from the container and exercises the same path a
# browser takes, authentication included.
H = "host.docker.internal"

# Redirects need care. Kuma follows them by default, and the final hop either
# lands somewhere meaningless (Jackett bounces through /UI/TestCookie, which
# 400s without a cookie jar) or crosses to another origin and drops the basic
# auth header (Bookstack redirects to its APP_URL and Caddy answers 401). For
# those, stop at the first response and treat the redirect itself as success.
#
# (name, type, url, hostname, port, interval, accepted_codes, ignore_tls,
#  needs_auth, maxredirects)
MONITORS = [
    ("Homarr",              "http", f"http://{H}:3000",                None, None,  60, ["200-299"], 0, 1, 10),
    ("OpenCode Agent",      "http", f"http://{H}:4096",                None, None,  60, ["200-299"], 0, 1, 10),
    ("Uptime Kuma",         "http", f"http://{H}:3001",                None, None, 120, ["200-299", "300-399"], 0, 1, 0),
    ("Cockpit",             "http", f"http://{H}:9091",                None, None, 120, ["200-299", "300-399"], 0, 1, 0),
    ("Pi-hole",             "http", f"http://{H}:8181/admin",          None, None, 120, ["200-299", "300-399"], 0, 1, 0),
    ("Pi-hole Backup",      "http", f"http://{H}:8182/admin",          None, None, 120, ["200-299", "300-399"], 0, 1, 0),
    ("qBittorrent",         "http", f"http://{H}:8183",                None, None, 120, ["200-299"], 0, 1, 0),
    ("Jackett",             "http", f"http://{H}:9118/UI/Dashboard",   None, None, 120, ["200-299", "300-399"], 0, 1, 0),
    # Served over TLS because the web vault needs a secure context; the
    # certificate comes from Caddy's internal CA, so skip verification.
    ("Vaultwarden",         "http", "https://192.168.1.171:8184",       None, None, 120, ["200-299"], 1, 1, 10),
    ("Web Terminal",        "http", f"http://{H}:7682",                None, None, 120, ["200-299"], 0, 1, 10),
    ("Stirling PDF",        "http", f"http://{H}:8185",                None, None, 120, ["200-299", "300-399"], 0, 1, 0),
    ("Glances",             "http", f"http://{H}:61209",               None, None, 120, ["200-299"], 0, 1, 10),
    ("Radarr",              "http", f"http://{H}:7879",                None, None, 120, ["200-299", "300-399"], 0, 1, 0),
    ("Sonarr",              "http", f"http://{H}:8990",                None, None, 120, ["200-299", "300-399"], 0, 1, 0),
    ("Jellyfin",            "http", f"http://{H}:8097",                None, None, 120, ["200-299", "300-399"], 0, 1, 0),
    ("Bookstack",           "http", f"http://{H}:6876/login",          None, None, 120, ["200-299"], 0, 1, 0),
    # Already published on 0.0.0.0 by their own stacks, so no Caddy hop.
    ("Pterodactyl Panel",   "http", f"http://{H}:8080",                None, None, 120, ["200-299"], 0, 0, 10),
    ("Nginx Proxy Manager", "http", f"http://{H}:81",                  None, None, 120, ["200-299"], 0, 0, 10),
    ("Portainer",           "http", f"https://{H}:9443",               None, None, 120, ["200-299", "300-399"], 1, 0, 0),
    ("Ollama",              "http", f"http://{H}:3000/ollama/api/tags", None, None, 60, ["200-299"], 0, 1, 10),
    ("Minecraft Server",    "port", None,                                 H, 25565, 60, ["200-299"], 0, 0, 0),
    ("GamePilot Site",      "http", "https://penguarjol.github.io/GamePilot", None, None, 300, ["200-299"], 0, 0, 10),
    ("GitHub Profile",      "http", "https://github.com/penguarjol",   None, None, 300, ["200-299"], 0, 0, 10),
]


def shared_credentials():
    user = password = None
    with open(AGENT_ENV, encoding="utf-8") as handle:
        for line in handle:
            key, _, value = line.strip().partition("=")
            if key == "OPENCODE_SERVER_USERNAME":
                user = value
            elif key == "OPENCODE_SERVER_PASSWORD":
                password = value
    if not user or not password:
        sys.exit(f"could not read credentials from {AGENT_ENV}")
    return user, password


def sh(*args):
    return subprocess.run(args, capture_output=True, text=True)


def volume_path():
    r = sh("docker", "volume", "inspect",
           "command-center_uptime-kuma-data", "--format", "{{.Mountpoint}}")
    if r.returncode != 0:
        sys.exit(f"cannot locate kuma volume: {r.stderr.strip()}")
    return r.stdout.strip()


def columns(cur, table):
    # Quoted because Uptime Kuma has a table literally named "group".
    return {r[1] for r in cur.execute(f'PRAGMA table_info("{table}")')}


def main():
    db = f"{volume_path()}/kuma.db"
    print(f"database: {db}")

    sh("docker", "stop", "uptime-kuma")
    time.sleep(2)

    # Any failure past this point must not leave the container stopped.
    try:
        configure(db)
    finally:
        sh("docker", "start", "uptime-kuma")
        print("uptime-kuma restarted")


def configure(db):
    conn = sqlite3.connect(db)
    cur = conn.cursor()

    user = cur.execute("SELECT id FROM user LIMIT 1").fetchone()
    if not user:
        sys.exit("no Uptime Kuma user exists; finish its first-run setup first")
    user_id = user[0]

    cur.execute("DELETE FROM heartbeat")
    cur.execute("DELETE FROM monitor_group")
    cur.execute("DELETE FROM monitor")
    print("cleared previous monitors")

    cc_user, cc_pass = shared_credentials()
    mon_cols = columns(cur, "monitor")
    ids = {}
    for (name, mtype, url, host, port, interval, codes,
         ignore_tls, needs_auth, maxredirects) in MONITORS:
        fields = {
            "name": name,
            "active": 1,
            "type": mtype,
            "interval": interval,
            "retry_interval": 60,
            "maxretries": 2,
            "accepted_statuscodes_json": json.dumps(codes),
            "upside_down": 0,
            "user_id": user_id,
        }
        if "ignore_tls" in mon_cols:
            fields["ignore_tls"] = ignore_tls
        if needs_auth and "basic_auth_user" in mon_cols:
            fields["auth_method"] = "basic"
            fields["basic_auth_user"] = cc_user
            fields["basic_auth_pass"] = cc_pass
        if mtype == "port":
            fields["hostname"] = host
            fields["port"] = port
        else:
            fields["url"] = url
            fields["maxredirects"] = maxredirects
            fields["method"] = "GET"
        cols = ", ".join(fields)
        marks = ", ".join("?" * len(fields))
        cur.execute(f"INSERT INTO monitor ({cols}) VALUES ({marks})",
                    list(fields.values()))
        ids[name] = cur.lastrowid
    print(f"created {len(ids)} monitors")

    # Homarr reads the status page, so it must exist and be published.
    cur.execute("DELETE FROM status_page WHERE slug=?", (SLUG,))
    sp_cols = columns(cur, "status_page")
    sp = {
        "slug": SLUG,
        "title": "Command Center",
        "published": 1,
        "show_tags": 0,
        "theme": "dark",
        # NOT NULL with no default in the schema.
        "icon": "/icon.svg",
    }
    if "description" in sp_cols:
        sp["description"] = "Home server service health"
    cols = ", ".join(sp)
    marks = ", ".join("?" * len(sp))
    cur.execute(f"INSERT INTO status_page ({cols}) VALUES ({marks})",
                list(sp.values()))
    page_id = cur.lastrowid

    group_cols = columns(cur, "group")
    g = {"name": "Services", "public": 1, "active": 1,
         "weight": 1, "status_page_id": page_id}
    cols = ", ".join(f'"{c}"' for c in g)
    marks = ", ".join("?" * len(g))
    cur.execute(f'INSERT INTO "group" ({cols}) VALUES ({marks})',
                list(g.values()))
    group_id = cur.lastrowid

    for order, name in enumerate(ids):
        cur.execute(
            "INSERT INTO monitor_group (monitor_id, group_id, weight) VALUES (?,?,?)",
            (ids[name], group_id, order),
        )
    print(f"published status page /status/{SLUG} with {len(ids)} monitors")

    conn.commit()
    conn.close()


if __name__ == "__main__":
    main()
