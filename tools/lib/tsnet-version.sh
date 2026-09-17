#!/usr/bin/env bash
# Shared, reproducible identity for the embedded Tailscale release.
TAILSCALE_VERSION="v1.102.4"
TAILSCALE_COMMIT="bbcd7d1fc2054b9189ebc1531acf74bd880ca0c8"
TAILSCALE_LDFLAGS="-X tailscale.com/version.longStamp=${TAILSCALE_VERSION#v} -X tailscale.com/version.shortStamp=${TAILSCALE_VERSION#v} -X tailscale.com/version.gitCommitStamp=$TAILSCALE_COMMIT"
