// Copyright 2026 MangoSSH contributors.
// SPDX-License-Identifier: Apache-2.0

//go:build android

package tsnetbridge

import "tailscale.com/net/netmon"

func updateDefaultRouteInterface(name string) {
	netmon.UpdateLastKnownDefaultRouteInterface(name)
}
