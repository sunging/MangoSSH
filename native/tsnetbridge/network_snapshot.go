// Copyright 2026 MangoSSH contributors.
// SPDX-License-Identifier: Apache-2.0

package tsnetbridge

import (
	"context"
	"encoding/json"
	"errors"
	"sort"
	"strings"

	"tailscale.com/ipn/ipnstate"
)

var errSnapshotFailed = errors.New("embedded tsnet network snapshot failed")

// networkSnapshot is the only tailnet view Android receives. It is an explicit
// allow-list: keys, endpoints, DERP/relay data, user identities, and SSH host
// key contents never cross the gomobile boundary.
type networkSnapshot struct {
	Self  snapshotSelf   `json:"self"`
	Peers []snapshotPeer `json:"peers"`
}

type snapshotSelf struct {
	HostName string   `json:"hostName"`
	DNSName  string   `json:"dnsName"`
	IPs      []string `json:"ips"`
}

type snapshotPeer struct {
	ID             string   `json:"id"`
	HostName       string   `json:"hostName"`
	DNSName        string   `json:"dnsName"`
	OS             string   `json:"os"`
	IPs            []string `json:"ips"`
	Online         bool     `json:"online"`
	LastSeenUnixMs int64    `json:"lastSeenUnixMs"`
	SSHEnabled     bool     `json:"sshEnabled"`
}

// NetworkSnapshotJson returns this node's name and a sanitized list of tailnet
// peers for display. It performs one full status query and is meant to be
// polled only while the user is looking at the device list.
func (r *Runtime) NetworkSnapshotJson() (string, error) {
	r.mu.Lock()
	client := r.localClient
	closed := r.closed
	r.mu.Unlock()
	if closed || client == nil {
		return "", errNotRunning
	}

	ctx, cancel := context.WithTimeout(context.Background(), operationTimeout)
	defer cancel()
	status, err := client.Status(ctx)
	if err != nil || status == nil {
		return "", errSnapshotFailed
	}
	payload, err := json.Marshal(buildNetworkSnapshot(status))
	if err != nil {
		return "", errSnapshotFailed
	}
	return string(payload), nil
}

func buildNetworkSnapshot(status *ipnstate.Status) networkSnapshot {
	snapshot := networkSnapshot{Peers: []snapshotPeer{}}
	if self := status.Self; self != nil {
		snapshot.Self = snapshotSelf{
			HostName: self.HostName,
			DNSName:  strings.TrimSuffix(self.DNSName, "."),
			IPs:      peerAddresses(self),
		}
	}
	if snapshot.Self.IPs == nil {
		snapshot.Self.IPs = []string{}
	}
	for _, peer := range status.Peer {
		// Sharee nodes are hidden by `tailscale status` too, and Mullvad exit
		// nodes carry a Location but are not SSH targets.
		if peer == nil || peer.ShareeNode || peer.Location != nil || len(peer.TailscaleIPs) == 0 {
			continue
		}
		var lastSeen int64
		if !peer.Online && !peer.LastSeen.IsZero() {
			lastSeen = peer.LastSeen.UnixMilli()
		}
		snapshot.Peers = append(snapshot.Peers, snapshotPeer{
			ID:             string(peer.ID),
			HostName:       peer.HostName,
			DNSName:        strings.TrimSuffix(peer.DNSName, "."),
			OS:             peer.OS,
			IPs:            peerAddresses(peer),
			Online:         peer.Online,
			LastSeenUnixMs: lastSeen,
			SSHEnabled:     len(peer.SSH_HostKeys) > 0,
		})
	}
	// Map iteration order is random; keep the payload stable for callers.
	sort.Slice(snapshot.Peers, func(i, j int) bool {
		return snapshot.Peers[i].ID < snapshot.Peers[j].ID
	})
	return snapshot
}

func peerAddresses(peer *ipnstate.PeerStatus) []string {
	addresses := make([]string, 0, len(peer.TailscaleIPs))
	for _, address := range peer.TailscaleIPs {
		if address.IsValid() {
			addresses = append(addresses, address.String())
		}
	}
	return addresses
}
