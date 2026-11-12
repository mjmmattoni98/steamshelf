#!/usr/bin/env bash
# Publishes SteamShelf on your tailnet with HTTPS, using `tailscale serve` on this machine (the Pi).
#
# It uses https://<machine>.<tailnet>.ts.net/ (port 443) when nothing else is served there, and port 8443
# otherwise, so it won't replace another service you already publish. The setting survives reboots.
# Requires MagicDNS and HTTPS certificates to be turned on in the Tailscale admin console (DNS page);
# if they aren't, tailscale prints a link to turn them on.
#
# To undo: sudo tailscale serve --https=<port> off
set -euo pipefail

# Same port as docker-compose.yml: STEAMSHELF_HOST_PORT from the environment or the .env file, else 8080.
env_file="$(dirname "$0")/../.env"
if [ -z "${STEAMSHELF_HOST_PORT:-}" ] && [ -f "$env_file" ]; then
  STEAMSHELF_HOST_PORT="$(grep -E '^STEAMSHELF_HOST_PORT=' "$env_file" | tail -1 | cut -d= -f2- | tr -d '"'"'"' ')"
fi
host_port="${STEAMSHELF_HOST_PORT:-8080}"
target="http://127.0.0.1:${host_port}"

if ! command -v tailscale >/dev/null; then
  echo "tailscale isn't installed on this machine: https://tailscale.com/download/linux" >&2
  exit 1
fi
if ! curl -fsS "${target}/health" >/dev/null 2>&1; then
  echo "SteamShelf isn't answering on ${target}. Start it first: docker compose up -d --build" >&2
  exit 1
fi

status="$(sudo tailscale serve status --json 2>/dev/null || echo '{}')"
served_on() { grep -q "\"$1\"" <<<"$status"; }

if grep -q "${target}" <<<"$status"; then
  echo "SteamShelf is already published:"
  sudo tailscale serve status
  exit 0
fi

https_port=443
if served_on 443; then
  https_port=8443
  if served_on 8443; then
    echo "Ports 443 and 8443 are both in use by tailscale serve. Pick another one, e.g.:" >&2
    echo "  sudo tailscale serve --bg --https=10443 ${target}" >&2
    exit 1
  fi
  echo "Port 443 already serves something else; using 8443 for SteamShelf."
fi

sudo tailscale serve --bg --https="${https_port}" "${target}"

dns_name="$(tailscale status --json | grep -m1 '"DNSName"' | cut -d'"' -f4 | sed 's/\.$//')"
url="https://${dns_name}"
[ "${https_port}" = 443 ] || url="${url}:${https_port}"
echo
echo "SteamShelf is on your tailnet at: ${url}"
echo "Open it on any device signed in to Tailscale and sign in through Steam."
