#!/usr/bin/env bash
# Shared, reproducible identity for the embedded Tailscale release.
TAILSCALE_VERSION="v1.104.2"
TAILSCALE_COMMIT="e26db7a5797683ec99ad1516c40b628c7f2dfd71"
TAILSCALE_LDFLAGS="-X tailscale.com/version.longStamp=${TAILSCALE_VERSION#v} -X tailscale.com/version.shortStamp=${TAILSCALE_VERSION#v} -X tailscale.com/version.gitCommitStamp=$TAILSCALE_COMMIT"
