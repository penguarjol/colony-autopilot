#!/usr/bin/env python3
"""Thin Homarr API client shared by the configuration scripts.

Homarr exposes REST under /api and tRPC under /api/trpc, both accepting an
`ApiKey: <id>.<token>` header. Minting that first key is the only step that
needs a browser-style session, so this signs in through the credentials
provider exactly as the UI does and then works with the key.
"""
from __future__ import annotations

import http.cookiejar
import json
import sys
import urllib.error
import urllib.parse
import urllib.request

HOMARR = "http://127.0.0.1:7575"
HOMARR_DB = "/var/lib/docker/volumes/docker_homarr_data/_data/db/db.sqlite"
AGENT_ENV = "/etc/colony-agent/agent.env"


def shared_credentials() -> tuple[str, str]:
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
    return user.lower(), password


class HomarrError(RuntimeError):
    pass


class Homarr:
    def __init__(self) -> None:
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.jar)
        )
        self.api_key: str | None = None

    # ---- transport -------------------------------------------------------

    def _call(self, method: str, path: str, body=None, form=None):
        url = f"{HOMARR}{path}"
        if form is not None:
            data = urllib.parse.urlencode(form).encode()
            content_type = "application/x-www-form-urlencoded"
        elif body is not None:
            data = json.dumps(body).encode()
            content_type = "application/json"
        else:
            data = None
            content_type = "application/json"

        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("Content-Type", content_type)
        req.add_header("Accept", "application/json")
        if self.api_key:
            req.add_header("ApiKey", self.api_key)
        try:
            with self.opener.open(req, timeout=40) as resp:
                raw = resp.read().decode()
        except urllib.error.HTTPError as exc:
            raise HomarrError(
                f"{method} {path} -> {exc.code}: {exc.read().decode()[:300]}"
            ) from exc
        if not raw:
            return None
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            return raw

    # ---- authentication --------------------------------------------------

    def sign_in(self) -> None:
        user, password = shared_credentials()
        csrf = self._call("GET", "/api/auth/csrf")
        token = csrf["csrfToken"] if isinstance(csrf, dict) else None
        if not token:
            raise HomarrError("could not obtain a CSRF token")
        self._call(
            "POST",
            "/api/auth/callback/credentials",
            form={
                "name": user,
                "password": password,
                "csrfToken": token,
                "callbackUrl": HOMARR,
                "json": "true",
            },
        )
        session = self._call("GET", "/api/auth/session")
        if not isinstance(session, dict) or not session.get("user"):
            raise HomarrError(f"sign-in failed for {user}")

    KEY_FILE = "/etc/colony-agent/homarr-api.key"

    def ensure_api_key(self) -> str:
        """Return a usable plaintext API key, holding a session alongside it.

        Two traps here, both of which produced silent failures earlier. The
        `apiKey.api_key` column stores a *hash*, so reading it out of the
        database yields a token the MCP endpoint rejects; the plaintext is
        returned exactly once, at creation. And the key alone authenticates
        queries but not mutations, so a signed-in session is required either
        way. Cache the plaintext on disk so repeat runs do not mint keys
        endlessly.
        """
        import os

        self.sign_in()

        if os.path.exists(self.KEY_FILE):
            cached = open(self.KEY_FILE, encoding="utf-8").read().strip()
            if cached and self._key_works(cached):
                self.api_key = cached
                return cached

        created = self.trpc_mutate("apiKeys.create", {})
        key = None
        if isinstance(created, str):
            key = created
        elif isinstance(created, dict):
            for field in ("apiKey", "key", "token", "randomToken", "value"):
                if isinstance(created.get(field), str):
                    key = created[field]
                    break
        if not key:
            raise HomarrError(f"unexpected apiKeys.create response: {created!r}")

        key = key.strip()
        try:
            with open(self.KEY_FILE, "w", encoding="utf-8") as handle:
                handle.write(key)
            os.chmod(self.KEY_FILE, 0o640)
        except OSError:
            pass  # not fatal; the key still works for this run
        self.api_key = key
        return key

    def _key_works(self, key: str) -> bool:
        req = urllib.request.Request(
            f"{HOMARR}/api/mcp/mcp",
            data=json.dumps(
                {"jsonrpc": "2.0", "method": "tools/list", "id": 1}
            ).encode(),
            method="POST",
        )
        req.add_header("Content-Type", "application/json")
        req.add_header("Accept", "application/json, text/event-stream")
        req.add_header("ApiKey", key)
        try:
            with urllib.request.urlopen(req, timeout=20) as resp:
                return '"tools"' in resp.read().decode()
        except Exception:  # noqa: BLE001 - probe only
            return False

    # ---- tRPC ------------------------------------------------------------

    def trpc_query(self, procedure: str, payload=None):
        query = ""
        if payload is not None:
            query = "?" + urllib.parse.urlencode(
                {"input": json.dumps({"json": payload})}
            )
        return _unwrap(self._call("GET", f"/api/trpc/{procedure}{query}"))

    def trpc_mutate(self, procedure: str, payload):
        return _unwrap(
            self._call("POST", f"/api/trpc/{procedure}", {"json": payload})
        )


def _unwrap(result):
    if isinstance(result, dict):
        data = result.get("result", {}).get("data", result)
        if isinstance(data, dict) and "json" in data:
            data = data["json"]
        # Homarr validates integrations before saving and reports a failure as
        # an error object inside an HTTP 200. Treating that as success is how
        # six integrations appeared to be created while none persisted.
        if isinstance(data, dict) and isinstance(data.get("error"), dict):
            err = data["error"]
            raise HomarrError(
                f"{err.get('message', 'rejected')} "
                f"({json.dumps(err.get('data', {}))[:160]})"
            )
        return data
    return result
