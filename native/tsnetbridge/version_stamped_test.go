// Copyright 2026 MangoSSH contributors.
// SPDX-License-Identifier: Apache-2.0

//go:build mangossh_versioncheck

package tsnetbridge

import (
	"strings"
	"testing"

	tailscaleroot "tailscale.com"
	"tailscale.com/hostinfo"
	"tailscale.com/version"
)

// TestStampedVersion exercises the version functions used by control-plane
// Hostinfo, including GOPATH builds where Go module build info is unavailable.
func TestStampedVersion(t *testing.T) {
	want := strings.TrimSpace(tailscaleroot.VersionDotTxt)
	for name, got := range map[string]string{
		"long": version.Long(), "short": version.Short(),
		"control-plane Hostinfo": hostinfo.New().IPNVersion,
	} {
		if got != want {
			t.Errorf("%s version = %q, want %q", name, got, want)
		}
	}
}
