#!/usr/bin/env python3
"""Build the Command Center board: integrations, widgets and app tiles.

Constraints discovered by probing this build rather than reading docs, each of
which previously failed silently:

  * board names are validated against /^[A-Za-z0-9-_]*$/, so no spaces.
  * board.addItem only accepts sections of kind "empty".
  * Homarr reports integration failures inside an HTTP 200 body.
  * Glances 4.x serves only /api/4/ while Homarr's client calls /api/3/.
"""
from __future__ import annotations

import subprocess
import sys

sys.path.insert(0, "/tmp")
from homarr_api import Homarr, HomarrError, shared_credentials  # noqa: E402

HOST = "192.168.1.171"
BRIDGE = "http://172.17.0.1"
BOARD = "command-center"
BOARD_TITLE = "Command Center"
COLUMNS = 12
ICONS = "https://cdn.jsdelivr.net/gh/walkxcode/dashboard-icons/svg"


def arr_key(container: str) -> str | None:
    out = subprocess.run(
        ["docker", "exec", container, "sh", "-c",
         r"sed -n 's:.*<ApiKey>\(.*\)</ApiKey>.*:\1:p' /config/config.xml"],
        capture_output=True, text=True,
    ).stdout.strip()
    return out or None


def build_integrations(api: Homarr, password: str) -> dict[str, str]:
    specs = [
        ("Glances", "glances", f"{BRIDGE}:61208", []),
        ("Uptime Kuma", "uptimeKuma", f"{BRIDGE}:3002",
         [{"kind": "slug", "value": "command-center"}]),
        ("Pi-hole Main", "piHole", f"{BRIDGE}:8081",
         [{"kind": "apiKey", "value": password}]),
        ("Pi-hole Backup", "piHole", f"{BRIDGE}:8082",
         [{"kind": "apiKey", "value": password}]),
    ]
    for label, kind, port, container in (
        ("Radarr", "radarr", 7878, "radarr"),
        ("Sonarr", "sonarr", 8989, "sonarr"),
    ):
        key = arr_key(container)
        if key:
            specs.append((label, kind, f"{BRIDGE}:{port}",
                          [{"kind": "apiKey", "value": key}]))

    existing = {i["name"]: i["id"] for i in (api.trpc_query("integration.all") or [])}
    for name, kind, url, secrets in specs:
        if name in existing:
            print(f"  exists  {name}")
            continue
        try:
            api.trpc_mutate("integration.create", {
                "name": name, "kind": kind, "url": url,
                "secrets": secrets, "attemptSearchEngineCreation": False,
            })
            print(f"  created {name} ({kind})")
        except HomarrError as exc:
            print(f"  FAILED  {name}: {str(exc)[:110]}")
    return {i["name"]: i["id"] for i in (api.trpc_query("integration.all") or [])}


# (name, description, icon, href) grouped the way the reference screenshot is.
APPS: list[tuple[str, str, str, str]] = [
    ("Portainer", "Container management", "portainer", f"https://{HOST}:9443"),
    ("Pterodactyl", "Game server panel", "pterodactyl", f"http://{HOST}:8080"),
    ("Nginx Proxy Manager", "Reverse proxy and SSL", "nginx-proxy-manager", f"http://{HOST}:81"),
    ("Cockpit", "System administration", "cockpit", f"http://{HOST}:9091"),
    ("Uptime Kuma", "Service monitoring", "uptime-kuma", f"http://{HOST}:3001"),
    ("Glances", "Live system metrics", "glances", f"http://{HOST}:61209"),
    ("OpenCode", "Local AI coding agent", "visual-studio-code", f"http://{HOST}:4096"),
    ("Pi-hole", "DNS filtering", "pi-hole", f"http://{HOST}:8181/admin"),
    ("Pi-hole Backup", "Secondary DNS filter", "pi-hole", f"http://{HOST}:8182/admin"),
    ("qBittorrent", "Torrent client", "qbittorrent", f"http://{HOST}:8183"),
    ("Jackett", "Indexer proxy", "jackett", f"http://{HOST}:9118/UI/Dashboard"),
    ("Radarr", "Movie library", "radarr", f"http://{HOST}:7879"),
    ("Sonarr", "TV library", "sonarr", f"http://{HOST}:8990"),
    ("Jellyfin", "Media server", "jellyfin", f"http://{HOST}:8097"),
    ("Bookstack", "Documentation wiki", "bookstack", f"http://{HOST}:6876"),
    ("Vaultwarden", "Password manager", "vaultwarden", f"http://{HOST}:8184"),
    ("Stirling PDF", "PDF toolkit", "stirling-pdf", f"http://{HOST}:8185"),
    ("Web Terminal", "Browser shell", "terminal", f"http://{HOST}:7682"),
    ("Router", "Network gateway", "router", "http://192.168.1.1"),
    ("GamePilot", "Gaming platform", "gamepad-variant", "https://penguarjol.github.io/GamePilot"),
    ("GitHub", "Source repositories", "github", "https://github.com/penguarjol"),
    ("Photopea", "Image editor", "photopea", "https://www.photopea.com"),
]


def build_apps(api: Homarr) -> dict[str, str]:
    existing = {a["name"]: a["id"] for a in (api.trpc_query("app.all") or [])}
    for name, description, icon, href in APPS:
        payload = {
            "name": name, "description": description,
            "iconUrl": f"{ICONS}/{icon}.svg", "href": href,
            "pingUrl": href,
        }
        try:
            if name in existing:
                api.trpc_mutate("app.update", {"id": existing[name], **payload})
            else:
                api.trpc_mutate("app.create", payload)
        except HomarrError as exc:
            print(f"  app {name}: {str(exc)[:90]}")
    return {a["name"]: a["id"] for a in (api.trpc_query("app.all") or [])}


def main() -> None:
    _, password = shared_credentials()
    api = Homarr()
    api.ensure_api_key()

    print("=== integrations ===")
    integrations = build_integrations(api, password)

    print("\n=== apps ===")
    apps = build_apps(api)
    print(f"  {len(apps)} apps in registry")

    boards = {b["name"]: b["id"] for b in (api.trpc_query("board.getAllBoards") or [])}
    if BOARD in boards:
        api.trpc_mutate("board.deleteBoard", {"id": boards[BOARD]})
    created = api.trpc_mutate("board.createBoard", {
        "name": BOARD, "columnCount": COLUMNS, "isPublic": False,
    })
    board_id = created["boardId"]
    for procedure in ("board.setHomeBoard", "board.setMobileHomeBoard"):
        try:
            api.trpc_mutate(procedure, {"id": board_id})
        except HomarrError:
            pass
    try:
        api.trpc_mutate("board.savePartialBoardSettings", {
            "id": board_id, "pageTitle": BOARD_TITLE, "metaTitle": BOARD_TITLE,
        })
    except HomarrError:
        pass

    board = api.trpc_query("board.getHomeBoard")
    sections = board.get("sections") or []
    section_id = next(
        (s["id"] for s in sections if s.get("kind") == "empty"),
        sections[0]["id"] if sections else None,
    )
    print(f"\n=== board {board_id} ===")

    pihole = [integrations[n] for n in ("Pi-hole Main", "Pi-hole Backup")
              if n in integrations]
    glances = [integrations[n] for n in ("Glances",) if n in integrations]

    # Top band mirrors the reference: clock, weather, DNS stats and controls,
    # then live host health.
    widgets: list[tuple[str, dict, int, int]] = [
        ("clock", {}, 2, 2),
        ("weather", {}, 2, 2),
        ("dnsHoleSummary", {"integrationIds": pihole}, 4, 2),
        ("dnsHoleControls", {"integrationIds": pihole}, 4, 2),
    ]
    if glances:
        widgets += [
            ("healthMonitoring", {"integrationIds": glances}, 6, 4),
        ]
    widgets += [
        ("dockerContainers", {}, 6, 4),
        ("minecraftServerStatus",
         {"title": "Minecraft", "domain": HOST, "port": 25565, "isBedrockServer": False},
         3, 2),
        ("rssFeed", {"feedUrls": ["https://www.phoronix.com/rss.php"],
                     "textLinesClamp": 3}, 4, 4),
        # Agent sessions are created and managed without leaving the board.
        ("iframe", {"embedUrl": f"http://{HOST}:4096", "allowFullScreen": True},
         8, 6),
    ]

    placed = failed = 0
    x = y = 0

    def add(kind: str, options: dict, w: int, h: int) -> None:
        nonlocal placed, failed, x, y
        payload = {
            "boardId": board_id, "kind": kind, "options": options,
            "xOffset": x, "yOffset": y, "width": w, "height": h,
        }
        if section_id:
            payload["sectionId"] = section_id
        try:
            api.trpc_mutate("board.addItem", payload)
            placed += 1
        except HomarrError as exc:
            print(f"  FAILED {kind}: {str(exc)[:130]}")
            failed += 1
        x += w
        if x >= COLUMNS:
            x = 0
            y += h

    for kind, options, w, h in widgets:
        add(kind, options, w, h)
    print(f"  widgets placed: {placed}")

    # Then the app grid, which is the bulk of the reference screenshot.
    x = 0
    y += 6
    before = placed
    for name, _, _, _ in APPS:
        if name in apps:
            add("app", {"appId": apps[name], "openInNewTab": True,
                        "showTitle": True, "showDescriptionTooltip": True},
                2, 2)
    print(f"  app tiles placed: {placed - before}")
    print(f"\ntotal placed={placed} failed={failed}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
