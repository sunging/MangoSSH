// Copyright 2026 MangoSSH contributors.
// SPDX-License-Identifier: Apache-2.0

package tsnetbridge

import (
	"encoding/json"
	"net/netip"
	"strings"
	"testing"
	"time"

	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tailcfg"
	"tailscale.com/types/key"
)

func TestBuildNetworkSnapshotKeepsOnlyAllowListedFields(t *testing.T) {
	lastSeen := time.UnixMilli(1_700_000_000_000)
	sshPeer := key.NewNode().Public()
	offlinePeer := key.NewNode().Public()
	shareePeer := key.NewNode().Public()
	mullvadPeer := key.NewNode().Public()
	addresslessPeer := key.NewNode().Public()
	status := &ipnstate.Status{
		Self: &ipnstate.PeerStatus{
			HostName:     "mangossh-android-abc",
			DNSName:      "mangossh-android-abc.example.ts.net.",
			TailscaleIPs: []netip.Addr{netip.MustParseAddr("100.64.0.1")},
		},
		Peer: map[key.NodePublic]*ipnstate.PeerStatus{
			sshPeer: {
				ID:           "n2",
				PublicKey:    sshPeer,
				HostName:     "lab",
				DNSName:      "lab.example.ts.net.",
				OS:           "linux",
				TailscaleIPs: []netip.Addr{netip.MustParseAddr("100.64.0.2"), netip.MustParseAddr("fd7a:115c:a1e0::2")},
				Online:       true,
				CurAddr:      "203.0.113.9:41641",
				Relay:        "fra",
				SSH_HostKeys: []string{"ssh-ed25519 AAAAsecret"},
			},
			offlinePeer: {
				ID:           "n1",
				HostName:     "laptop",
				DNSName:      "laptop.example.ts.net.",
				OS:           "windows",
				TailscaleIPs: []netip.Addr{netip.MustParseAddr("100.64.0.3")},
				LastSeen:     lastSeen,
			},
			shareePeer: {
				ID:           "n3",
				ShareeNode:   true,
				TailscaleIPs: []netip.Addr{netip.MustParseAddr("100.64.0.4")},
			},
			mullvadPeer: {
				ID:           "n4",
				Location:     &tailcfg.Location{Country: "Sweden"},
				TailscaleIPs: []netip.Addr{netip.MustParseAddr("100.64.0.5")},
			},
			addresslessPeer: {ID: "n5"},
		},
	}

	snapshot := buildNetworkSnapshot(status)

	if snapshot.Self.DNSName != "mangossh-android-abc.example.ts.net" || snapshot.Self.HostName != "mangossh-android-abc" {
		t.Fatalf("self = %+v", snapshot.Self)
	}
	if len(snapshot.Peers) != 2 || snapshot.Peers[0].ID != "n1" || snapshot.Peers[1].ID != "n2" {
		t.Fatalf("peers = %+v", snapshot.Peers)
	}
	offline, online := snapshot.Peers[0], snapshot.Peers[1]
	if offline.Online || offline.SSHEnabled || offline.LastSeenUnixMs != lastSeen.UnixMilli() {
		t.Fatalf("offline peer = %+v", offline)
	}
	if !online.Online || !online.SSHEnabled || online.LastSeenUnixMs != 0 || online.DNSName != "lab.example.ts.net" {
		t.Fatalf("online peer = %+v", online)
	}
	if len(online.IPs) != 2 || online.IPs[0] != "100.64.0.2" {
		t.Fatalf("online peer addresses = %v", online.IPs)
	}

	payload, err := json.Marshal(snapshot)
	if err != nil {
		t.Fatal(err)
	}
	for _, leaked := range []string{"AAAAsecret", "203.0.113.9", "fra", "nodekey", "Sweden"} {
		if strings.Contains(string(payload), leaked) {
			t.Fatalf("snapshot leaked %q: %s", leaked, payload)
		}
	}
}

func TestBuildNetworkSnapshotWithoutSelfUsesEmptyLists(t *testing.T) {
	payload, err := json.Marshal(buildNetworkSnapshot(&ipnstate.Status{}))
	if err != nil {
		t.Fatal(err)
	}
	want := `{"self":{"hostName":"","dnsName":"","ips":[]},"peers":[]}`
	if string(payload) != want {
		t.Fatalf("payload = %s", payload)
	}
}

func TestNetworkSnapshotRequiresRunningNode(t *testing.T) {
	runtime := NewRuntime("", "", "", nil, nil, nil)
	if _, err := runtime.NetworkSnapshotJson(); err != errNotRunning {
		t.Fatalf("NetworkSnapshotJson() error = %v", err)
	}
}
