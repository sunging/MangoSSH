#!/usr/bin/env bash
# Shared, reproducible identity for the embedded Tailscale release.
TAILSCALE_VERSION="v1.104.4"
TAILSCALE_COMMIT="27047635f237d1f3e0b08c0f487674757f30d2f7"
TAILSCALE_LDFLAGS="-X tailscale.com/version.longStamp=${TAILSCALE_VERSION#v} -X tailscale.com/version.shortStamp=${TAILSCALE_VERSION#v} -X tailscale.com/version.gitCommitStamp=$TAILSCALE_COMMIT"
