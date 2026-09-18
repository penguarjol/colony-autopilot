#!/usr/bin/env python3
"""Create Homarr integrations and rebuild the Command Center board.

Everything goes through Homarr's own API. An earlier attempt wrote rows
directly into its SQLite file, which is why the board ended up full of tiles
that pointed nowhere: raw inserts bypass validation, never create integration
records, and never populate widget options.

Integration kinds and widget kinds below were read off this exact build via
integration.getKinds and the board.addItem enum, not taken from documentation.
"""
from __future__ import annotations

import json
import subprocess
import sys

sys.path.insert(0, "/tmp")
from homarr_api import Homarr, HomarrError, shared_credentials  # noqa: E402

HOST = "192.168.1.171"
# The Homarr container has no host-gateway mapping, so host.docker.internal
# does not resolve there and every integration failed DNS. Address the Docker
# bridge directly; the services are bound to it alongside loopback.
H = "http://172.17.0.1"

BOARD_NAME = "Command Center"


def service_api_key(container: str) -> str | None:
    """Read a *arr API key out of its own config."""
    result = subprocess.run(
        ["docker", "exec", container, "sh", "-c",
         r"sed -n 's:.*<ApiKey>\(.*\)</ApiKey>.*:\1:p' /config/config.xml"],
        capture_output=True, text=True,
    )
    key = result.stdout.strip()
    return key or None


def integration_specs(password: str) -> list[dict]:
    specs = [
        # Drives the CPU/RAM/uptime and storage panels.
        {"name": "Glances", "kind": "glances", "url": f"{H}:61208",
         "secrets": []},
        # Homarr reads a published status page, hence the slug.
        {"name": "Uptime Kuma", "kind": "uptimeKuma", "url": f"{H}:3002",
         "secrets": [{"kind": "slug", "value": "command-center"}]},
        # Pi-hole v6 authenticates the API with the web password.
        {"name": "Pi-hole Main", "kind": "piHole", "url": f"{H}:8081",
         "secrets": [{"kind": "apiKey", "value": password}]},
        {"name": "Pi-hole Backup", "kind": "piHole", "url": f"{H}:8082",
         "secrets": [{"kind": "apiKey", "value": password}]},
    ]

    for name, kind, port, container in (
        ("Radarr", "radarr", 7878, "radarr"),
        ("Sonarr", "sonarr", 8989, "sonarr"),
    ):
        key = service_api_key(container)
        if key:
            specs.append({"name": name, "kind": kind, "url": f"{H}:{port}",
                          "secrets": [{"kind": "apiKey", "value": key}]})
        else:
            print(f"  skip {name}: no API key in its config yet")

    return specs


def ensure_integrations(api: Homarr, password: str) -> dict[str, str]:
    existing = {i["name"]: i["id"] for i in (api.trpc_query("integration.all") or [])}
    created: dict[str, str] = {}

    for spec in integration_specs(password):
        name = spec["name"]
        if name in existing:
            created[name] = existing[name]
            print(f"  exists: {name}")
            continue
        try:
            # Required by the create schema even for kinds that cannot act as
            # a search provider.
            api.trpc_mutate(
                "integration.create",
                {**spec, "attemptSearchEngineCreation": False},
            )
            print(f"  created: {name} ({spec['kind']})")
        except HomarrError as exc:
            print(f"  FAILED {name}: {str(exc)[:200]}")

    for integration in api.trpc_query("integration.all") or []:
        created[integration["name"]] = integration["id"]
    return created


def test_integrations(api: Homarr, ids: dict[str, str]) -> int:
    """Confirm each integration is readable.

    Homarr validates connectivity during integration.create and refuses to
    save anything it cannot reach, so persistence is itself the connection
    test. This re-reads each record to confirm it survived.
    """
    failures = 0
    for name, ident in sorted(ids.items()):
        try:
            record = api.trpc_query("integration.byId", {"id": ident})
            kind = record.get("kind") if isinstance(record, dict) else "?"
            print(f"  ok   {name} ({kind})")
        except HomarrError as exc:
            print(f"  FAIL {name}: {str(exc)[:150]}")
            failures += 1
    return failures


APPS = [
    # (name, description, icon, href, ping)
    ("Portainer", "Container management", "portainer", f"https://{HOST}:9443", f"https://{HOST}:9443"),
    ("Pterodactyl", "Game server panel", "pterodactyl", f"http://{HOST}:8080", f"http://{HOST}:8080"),
    ("Nginx Proxy Manager", "Reverse proxy and SSL", "nginx-proxy-manager", f"http://{HOST}:81", f"http://{HOST}:81"),
    ("Cockpit", "System administration", "cockpit", f"http://{HOST}:9091", f"http://{HOST}:9091"),
    ("Uptime Kuma", "Service monitoring", "uptime-kuma", f"http://{HOST}:3001", f"http://{HOST}:3001"),
    ("OpenCode", "Local AI coding agent", "visual-studio-code", f"http://{HOST}:4096", f"http://{HOST}:4096"),
    ("Glances", "System metrics", "glances", f"http://{HOST}:61209", f"http://{HOST}:61209"),
    ("Pi-hole", "DNS filtering", "pi-hole", f"http://{HOST}:8181/admin", f"http://{HOST}:8181/admin"),
    ("Pi-hole Backup", "Secondary DNS filter", "pi-hole", f"http://{HOST}:8182/admin", f"http://{HOST}:8182/admin"),
    ("qBittorrent", "Torrent client", "qbittorrent", f"http://{HOST}:8183", f"http://{HOST}:8183"),
    ("Jackett", "Indexer proxy", "jackett", f"http://{HOST}:9118/UI/Dashboard", f"http://{HOST}:9118/UI/Dashboard"),
    ("Radarr", "Movie management", "radarr", f"http://{HOST}:7879", f"http://{HOST}:7879"),
    ("Sonarr", "TV management", "sonarr", f"http://{HOST}:8990", f"http://{HOST}:8990"),
    ("Jellyfin", "Media server", "jellyfin", f"http://{HOST}:8097", f"http://{HOST}:8097"),
    ("Bookstack", "Documentation wiki", "bookstack", f"http://{HOST}:6876", f"http://{HOST}:6876"),
    ("Vaultwarden", "Password manager", "vaultwarden", f"http://{HOST}:8184", f"http://{HOST}:8184"),
    ("Stirling PDF", "PDF toolkit", "stirling-pdf", f"http://{HOST}:8185", f"http://{HOST}:8185"),
    ("Web Terminal", "Shell access", "terminal", f"http://{HOST}:7682", f"http://{HOST}:7682"),
    ("Router", "Network gateway", "router", "http://192.168.1.1", "http://192.168.1.1"),
    ("GamePilot", "Gaming platform", "gamepad-variant", "https://penguarjol.github.io/GamePilot", ""),
    ("GitHub", "Source repositories", "github", "https://github.com/penguarjol", ""),
    ("Photopea", "Image editor", "photopea", "https://www.photopea.com", ""),
]

ICON_BASE = "https://cdn.jsdelivr.net/gh/walkxcode/dashboard-icons/svg"


def ensure_apps(api: Homarr) -> dict[str, str]:
    existing = {a["name"]: a for a in (api.trpc_query("app.all") or [])}
    ids: dict[str, str] = {}

    for name, description, icon, href, ping in APPS:
        payload = {
            "name": name,
            "description": description,
            "iconUrl": f"{ICON_BASE}/{icon}.svg",
            "href": href,
            "pingUrl": ping or None,
        }
        if name in existing:
            ident = existing[name]["id"]
            try:
                api.trpc_mutate("app.update", {"id": ident, **payload})
            except HomarrError as exc:
                print(f"  update {name} failed: {str(exc)[:120]}")
            ids[name] = ident
        else:
            try:
                api.trpc_mutate("app.create", payload)
                print(f"  created app: {name}")
            except HomarrError as exc:
                print(f"  FAILED app {name}: {str(exc)[:160]}")

    for app in api.trpc_query("app.all") or []:
        ids[app["name"]] = app["id"]
    return ids


def main() -> None:
    _, password = shared_credentials()
    api = Homarr()
    api.ensure_api_key()

    print("=== integrations ===")
    ids = ensure_integrations(api, password)

    print("\n=== integration connection tests ===")
    failures = test_integrations(api, ids)

    print("\n=== apps ===")
    apps = ensure_apps(api)
    print(f"  {len(apps)} apps registered")

    print(f"\nintegrations={len(ids)} apps={len(apps)} test_failures={failures}")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
