#!/usr/bin/env python3
"""Configure Homarr board and fix Uptime Kuma monitors. Run as root."""
import json
import sqlite3
import subprocess
import sys
import time
import uuid
import urllib.request
import urllib.error
import base64

HOMARR_DB = "/var/lib/docker/volumes/docker_homarr_data/_data/db/db.sqlite"
KUMA_VOLUME_CMD = [
    "docker", "volume", "inspect",
    "command-center_uptime-kuma-data",
    "--format", "{{.Mountpoint}}",
]

def rid():
    return uuid.uuid4().hex[:24]

APPS = [
    # (name, icon_url, href, description, ping_url, section, x, y)
    ("Portainer", "https://cdn.jsdelivr.net/gh/walkxcode/dashboard-icons@master/svg/portainer.svg",
     "https://192.168.1.171:9443", "Container Management", "https://192.168.1.171:9443", "Infrastructure", 0, 0),
    ("Pterodactyl", "https://cdn.jsdelivr.net/gh/walkxcode/dashboard-icons@master/svg/pterodactyl.svg",
     "http://192.168.1.171:8080", "Game Server Panel", "http://192.168.1.171:8080", "Infrastructure", 1, 0),
    ("Nginx Proxy Manager", "https://cdn.jsdelivr.net/gh/walkxcode/dashboard-icons@master/svg/nginx-proxy-manager.svg",
     "http://192.168.1.171:81", "Reverse Proxy & SSL", "http://192.168.1.171:81", "Infrastructure", 2, 0),
    ("Cockpit", "https://cdn.jsdelivr.net/gh/walkxcode/dashboard-icons@master/svg/cockpit.svg",
     "https://192.168.1.171:9090", "System Admin", "https://192.168.1.171:9090", "Infrastructure", 3, 0),
    ("Uptime Kuma", "https://cdn.jsdelivr.net/gh/walkxcode/dashboard-icons@master/svg/uptime-kuma.svg",
     "http://192.168.1.171:3001", "Monitoring", "http://192.168.1.171:3001", "Infrastructure", 4, 0),
    ("OpenCode", "https://cdn.jsdelivr.net/gh/walkxcode/dashboard-icons@master/svg/code-server.svg",
     "http://192.168.1.171:4096", "AI Coding Agent", "http://192.168.1.171:4096", "Development", 0, 0),
    ("Ollama", "https://cdn.jsdelivr.net/gh/walkxcode/dashboard-icons@master/svg/ollama.svg",
     "http://192.168.1.171:3000/ollama/api/tags", "LLM API", "", "Development", 1, 0),
    ("Colony Autopilot", "https://cdn.jsdelivr.net/gh/walkxcode/dashboard-icons@master/svg/home-assistant.svg",
     "", "Minecraft Automation", "", "Development", 2, 0),
    ("Minecraft", "https://cdn.jsdelivr.net/gh/walkxcode/dashboard-icons@master/svg/minecraft.svg",
     "", "MineColonies :25565", "", "Gaming & Projects", 0, 0),
    ("GamePilot", "https://cdn.jsdelivr.net/gh/walkxcode/dashboard-icons@master/svg/gamepad-variant.svg",
     "https://penguarjol.github.io/GamePilot", "Gaming Platform", "https://penguarjol.github.io/GamePilot", "Gaming & Projects", 1, 0),
    ("GitHub", "https://cdn.jsdelivr.net/gh/walkxcode/dashboard-icons@master/svg/github.svg",
     "https://github.com/penguarjol", "Repositories", "https://github.com/penguarjol", "Gaming & Projects", 2, 0),
]

KUMA_MONITORS = [
    ("OpenCode Agent", "http", "http://host.docker.internal:4097", None, None, 60, ["401"]),
    ("Ollama", "http", "http://host.docker.internal:11434/api/tags", None, None, 60, ["200-299"]),
    ("Minecraft Server", "port", None, "host.docker.internal", 25565, 60, ["200-299"]),
    ("Pterodactyl Panel", "http", "http://host.docker.internal:8080", None, None, 120, ["200-299"]),
    ("Nginx Proxy Manager", "http", "http://host.docker.internal:81", None, None, 120, ["200-299"]),
    ("Portainer", "http", "https://host.docker.internal:9443", None, None, 120, ["200-299"]),
    ("Homarr", "http", "http://host.docker.internal:7575", None, None, 60, ["200-299"]),
    ("Uptime Kuma", "http", "http://host.docker.internal:3002", None, None, 60, ["200-299"]),
    ("Cockpit", "http", "https://host.docker.internal:9090", None, None, 120, ["200-299"]),
    ("GamePilot Site", "http", "https://penguarjol.github.io/GamePilot", None, None, 300, ["200-299"]),
    ("GitHub Profile", "http", "https://github.com/penguarjol", None, None, 300, ["200-299"]),
]


def setup_homarr():
    print("=== Homarr Board Setup ===")
    conn = sqlite3.connect(HOMARR_DB)
    cur = conn.cursor()

    user = cur.execute("SELECT id FROM user WHERE name='foorebel'").fetchone()
    if not user:
        print("  error: foorebel user not found")
        conn.close()
        return False
    owner_id = user[0]

    board_id = "cmd_center_board_001"
    layout_id = "cmd_center_layout_001"

    # Clean up any previous attempt
    cur.execute("DELETE FROM item_layout WHERE layout_id=?", (layout_id,))
    cur.execute("DELETE FROM section_layout WHERE layout_id=?", (layout_id,))
    cur.execute("DELETE FROM item WHERE board_id=?", (board_id,))
    cur.execute("DELETE FROM section WHERE board_id=?", (board_id,))
    cur.execute("DELETE FROM layout WHERE board_id=?", (board_id,))
    cur.execute("DELETE FROM board WHERE id=?", (board_id,))

    # Create board
    cur.execute(
        "INSERT INTO board (id, name, is_public, creator_id, page_title, meta_title, "
        "logo_image_url, favicon_image_url, background_image_url, "
        "background_image_attachment, background_image_repeat, background_image_size, "
        "primary_color, secondary_color, opacity, custom_css, disable_status, item_radius, icon_color) "
        "VALUES (?,?,0,?,?,?,'','','','fixed','no-repeat','cover',?,?,100,'',0,'md','')",
        (board_id, "Command Center", owner_id, "Command Center", "Command Center",
         "#fa5252", "#fd7e14"),
    )
    print(f"  board created: {board_id}")

    # Create layout
    cur.execute(
        "INSERT INTO layout (id, name, board_id, column_count, breakpoint) VALUES (?,?,?,10,0)",
        (layout_id, "Base", board_id),
    )

    # Create sections
    section_map = {}
    for i, (sname, sy) in enumerate([("Infrastructure", 0), ("Development", 1), ("Gaming & Projects", 2)]):
        sid = f"cmd_sec_{i:03d}"
        section_map[sname] = sid
        cur.execute(
            "INSERT INTO section (id, board_id, kind, x_offset, y_offset, name, options) "
            "VALUES (?,?,'category',0,?,?,'{\"json\":{}}')",
            (sid, board_id, sy, sname),
        )
        cur.execute(
            "INSERT INTO section_layout (section_id, layout_id, parent_section_id, x_offset, y_offset, width, height) "
            "VALUES (?,?,NULL,0,?,10,1)",
            (sid, layout_id, sy),
        )

    # Create apps and items
    for name, icon_url, href, desc, ping_url, section, x, y in APPS:
        app_id = rid()
        item_id = rid()
        sid = section_map[section]

        # Insert or update app
        existing_app = cur.execute("SELECT id FROM app WHERE name=?", (name,)).fetchone()
        if existing_app:
            app_id = existing_app[0]
            cur.execute("UPDATE app SET icon_url=?, href=?, description=?, ping_url=? WHERE id=?",
                        (icon_url, href, desc, ping_url, app_id))
        else:
            cur.execute("INSERT INTO app (id, name, description, icon_url, href, ping_url) VALUES (?,?,?,?,?,?)",
                        (app_id, name, desc, icon_url, href, ping_url))

        # Create item
        options = json.dumps({"json": {"appId": app_id, "openInNewTab": True, "showTitle": True}})
        cur.execute("INSERT INTO item (id, board_id, kind, options, advanced_options) VALUES (?,?,'app',?,'{\"json\":{}}')",
                    (item_id, board_id, options))

        # Create item layout
        cur.execute(
            "INSERT INTO item_layout (item_id, section_id, layout_id, x_offset, y_offset, width, height) "
            "VALUES (?,?,?,?,?,1,1)",
            (item_id, sid, layout_id, x, y),
        )
        print(f"  {section}: {name}")

    # Set as home board
    cur.execute("UPDATE user SET home_board_id=? WHERE id=?", (board_id, owner_id))

    conn.commit()
    conn.close()
    print("  board setup complete")
    return True


def setup_uptime_kuma():
    print("\n=== Uptime Kuma Setup ===")
    try:
        vol = subprocess.check_output(KUMA_VOLUME_CMD, text=True).strip()
    except Exception as e:
        print(f"  error: {e}")
        return False

    db_path = f"{vol}/kuma.db"
    subprocess.run(["docker", "stop", "uptime-kuma"], capture_output=True)
    time.sleep(2)

    conn = sqlite3.connect(db_path)
    cur = conn.cursor()
    cur.execute("DELETE FROM heartbeat")
    cur.execute("DELETE FROM monitor")
    print("  cleared old monitors")

    for name, mtype, url, host, port, interval, codes in KUMA_MONITORS:
        if mtype == "port":
            cur.execute(
                "INSERT INTO monitor (name,active,type,hostname,port,interval,"
                "retry_interval,maxretries,accepted_statuscodes_json,"
                "upside_down,maxredirects,method,dns_resolve_type) "
                "VALUES (?,1,?,?,?,?,60,3,?,0,10,'GET','A')",
                (name, mtype, host, port, interval, json.dumps(codes)))
        else:
            cur.execute(
                "INSERT INTO monitor (name,active,type,url,interval,"
                "retry_interval,maxretries,accepted_statuscodes_json,"
                "upside_down,maxredirects,method,dns_resolve_type) "
                "VALUES (?,1,?,?,?,60,3,?,0,10,'GET','A')",
                (name, mtype, url, interval, json.dumps(codes)))
        print(f"  monitor: {name}")

    conn.commit()
    conn.close()

    subprocess.run(["docker", "start", "uptime-kuma"], capture_output=True)
    print("  kuma restarted")
    return True


def verify():
    print("\n=== Verification ===")
    time.sleep(8)
    creds = base64.b64encode(b"FooRebel:NewLifestyle2026!").decode()
    ok = True
    for name, url, expect in [
        ("Dashboard", "http://192.168.1.171:3000/", [200, 302]),
        ("OpenCode", "http://192.168.1.171:4096/", [200]),
        ("Uptime Kuma", "http://192.168.1.171:3001/", [200, 302]),
    ]:
        try:
            req = urllib.request.Request(url)
            req.add_header("Authorization", f"Basic {creds}")
            resp = urllib.request.urlopen(req, timeout=10)
            code = resp.getcode()
        except urllib.error.HTTPError as e:
            code = e.code
        except Exception as e:
            code = 0
            print(f"  {name}: FAIL ({e})")
            ok = False
            continue
        status = "ok" if code in expect else "FAIL"
        if status == "FAIL":
            ok = False
        print(f"  {name}: {code} ({status})")
    return ok


if __name__ == "__main__":
    r1 = setup_homarr()
    r2 = setup_uptime_kuma()
    subprocess.run(["docker", "restart", "homarr"], capture_output=True)
    r3 = verify()
    print(f"\nResult: {'PASSED' if all([r1,r2,r3]) else 'PARTIAL — review above'}")
    sys.exit(0 if all([r1,r2,r3]) else 1)
