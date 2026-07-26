// Copyright 2026 MangoSSH contributors.
// SPDX-License-Identifier: Apache-2.0

package tsnetbridge

import (
	"errors"
	"net"
	"net/netip"
	"testing"

	"tailscale.com/ipn"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/types/key"
)

type memoryStateStore struct {
	values map[string][]byte
}

type staticNetworkState struct {
	payload string
}

func (s staticNetworkState) SnapshotJson() (string, error) {
	return s.payload, nil
}

func (s *memoryStateStore) ReadState(key string) ([]byte, error) {
	return append([]byte(nil), s.values[key]...), nil
}

func (s *memoryStateStore) WriteState(key string, value []byte) error {
	s.values[key] = append([]byte(nil), value...)
	return nil
}

func TestEncryptedStateStoreRoundTrip(t *testing.T) {
	delegate := &memoryStateStore{values: make(map[string][]byte)}
	store := encryptedStateStore{delegate: delegate}
	want := []byte{0, 1, 2, 0xff}
	if err := store.WriteState("node", want); err != nil {
		t.Fatal(err)
	}
	want[0] = 9
	got, err := store.ReadState("node")
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != string([]byte{0, 1, 2, 0xff}) {
		t.Fatalf("ReadState() = %v", got)
	}
	got[0] = 7
	if delegate.values["node"][0] != 0 {
		t.Fatal("ReadState exposed the delegate's backing array")
	}
}

func TestEncryptedStateStoreMissing(t *testing.T) {
	delegate := &memoryStateStore{values: make(map[string][]byte)}
	store := encryptedStateStore{delegate: delegate}
	if _, err := store.ReadState("missing"); !errors.Is(err, ipn.ErrStateNotExist) {
		t.Fatalf("missing state error = %v", err)
	}
}

func TestRuntimeRejectsInvalidStart(t *testing.T) {
	runtime := NewRuntime("", "", nil, nil, nil)
	if err := runtime.Start(""); !errors.Is(err, errInvalidArgument) {
		t.Fatalf("Start() error = %v", err)
	}
	if err := runtime.Close(); err != nil {
		t.Fatal(err)
	}
	if err := runtime.Close(); err != nil {
		t.Fatal("Close must be idempotent")
	}
}

func TestRunningStatusWaitsForDialReadyPeerMap(t *testing.T) {
	status := &ipnstate.Status{
		BackendState: "Running",
		TailscaleIPs: []netip.Addr{netip.MustParseAddr("100.64.0.1")},
	}
	if state, _ := mapBackendStatus(status, false); state != statusStarting {
		t.Fatalf("status without peers = %q", state)
	}
	var peerKey key.NodePublic
	status.Peer = map[key.NodePublic]*ipnstate.PeerStatus{peerKey: nil}
	if state, _ := mapBackendStatus(status, false); state != statusRunning {
		t.Fatalf("dial-ready status = %q", state)
	}
	status.Peer = nil
	if state, _ := mapBackendStatus(status, true); state != statusRunning {
		t.Fatalf("peer-less status after readiness = %q", state)
	}
}

func TestParseAndroidNetworkSnapshot(t *testing.T) {
	interfaces, err := parseAndroidNetworkSnapshot(`{
		"defaultRoute":"wlan0",
		"interfaces":[{
			"name":"wlan0",
			"index":7,
			"mtu":1500,
			"up":true,
			"broadcast":true,
			"loopback":false,
			"pointToPoint":false,
			"multicast":true,
			"addrs":[
				{"ip":"192.0.2.10","prefixLen":24},
				{"ip":"fe80::1%wlan0","prefixLen":64},
				{"ip":"not-an-ip","prefixLen":1}
			]
		}]
	}`)
	if err != nil {
		t.Fatal(err)
	}
	if len(interfaces) != 1 {
		t.Fatalf("interfaces = %d", len(interfaces))
	}
	got := interfaces[0]
	if got.Name != "wlan0" || got.Index != 7 || got.MTU != 1500 {
		t.Fatalf("interface metadata = %#v", got.Interface)
	}
	if got.Flags&net.FlagUp == 0 || got.Flags&net.FlagBroadcast == 0 || got.Flags&net.FlagMulticast == 0 {
		t.Fatalf("interface flags = %v", got.Flags)
	}
	if len(got.AltAddrs) != 2 {
		t.Fatalf("addresses = %#v", got.AltAddrs)
	}
	if got.AltAddrs[0].String() != "192.0.2.10/24" {
		t.Fatalf("IPv4 address = %v", got.AltAddrs[0])
	}
	if got.AltAddrs[1].String() != "fe80::1%wlan0" {
		t.Fatalf("zoned IPv6 address = %v", got.AltAddrs[1])
	}
}

func TestParseAndroidNetworkSnapshotRejectsMalformedJSON(t *testing.T) {
	if _, err := parseAndroidNetworkSnapshot("{"); err == nil {
		t.Fatal("malformed snapshot accepted")
	}
}

func TestAuthorizationValueIsClearedAfterLoginState(t *testing.T) {
	runtime := &Runtime{}
	runtime.publish(statusNeedsLogin, "transient")
	if runtime.lastAuthURL == "" {
		t.Fatal("authorization value was not retained for event de-duplication")
	}

	runtime.publish(statusRunning, "")
	if runtime.lastAuthURL != "" {
		t.Fatal("authorization value survived the login state")
	}
}
