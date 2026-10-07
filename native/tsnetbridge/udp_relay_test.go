// Copyright 2026 MangoSSH contributors.
// SPDX-License-Identifier: Apache-2.0

package tsnetbridge

import (
	"net"
	"sync/atomic"
	"testing"
	"time"
)

func TestUDPRelayRoundTripAndClose(t *testing.T) {
	server, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	remote, err := net.DialUDP("udp4", nil, server.LocalAddr().(*net.UDPAddr))
	if err != nil {
		t.Fatal(err)
	}
	relay, err := newUDPRelay(remote, nil)
	if err != nil {
		t.Fatal(err)
	}
	relay.start()
	defer relay.Close()

	go func() {
		buffer := make([]byte, 128)
		count, sender, readErr := server.ReadFromUDP(buffer)
		if readErr == nil {
			_, _ = server.WriteToUDP(buffer[:count], sender)
		}
	}()

	client, err := net.DialUDP(
		"udp4",
		nil,
		&net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: relay.LocalPort()},
	)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	_ = client.SetDeadline(time.Now().Add(3 * time.Second))
	if _, err := client.Write([]byte("mosh")); err != nil {
		t.Fatal(err)
	}
	buffer := make([]byte, 16)
	count, err := client.Read(buffer)
	if err != nil {
		t.Fatal(err)
	}
	if string(buffer[:count]) != "mosh" {
		t.Fatalf("reply = %q", buffer[:count])
	}
	if err := relay.Close(); err != nil {
		t.Fatal(err)
	}
	if err := relay.Close(); err != nil {
		t.Fatal("Close must be idempotent")
	}
}

// startEchoRelay starts a relay in front of an echo server and returns it with
// a fake clock the test advances instead of sleeping.
func startEchoRelay(t *testing.T) (*UDPRelay, *atomic.Int64) {
	t.Helper()
	server, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = server.Close() })
	go func() {
		buffer := make([]byte, 128)
		for {
			count, sender, readErr := server.ReadFromUDP(buffer)
			if readErr != nil {
				return
			}
			_, _ = server.WriteToUDP(buffer[:count], sender)
		}
	}()
	remote, err := net.DialUDP("udp4", nil, server.LocalAddr().(*net.UDPAddr))
	if err != nil {
		t.Fatal(err)
	}
	relay, err := newUDPRelay(remote, nil)
	if err != nil {
		t.Fatal(err)
	}
	elapsed := new(atomic.Int64)
	base := time.Now()
	relay.now = func() time.Time { return base.Add(time.Duration(elapsed.Load())) }
	relay.start()
	t.Cleanup(func() { _ = relay.Close() })
	return relay, elapsed
}

func dialRelay(t *testing.T, relay *UDPRelay) *net.UDPConn {
	t.Helper()
	client, err := net.DialUDP("udp4", nil, &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: relay.LocalPort()})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = client.Close() })
	return client
}

// roundTrip reports whether the payload came back through the relay in time.
func roundTrip(t *testing.T, client *net.UDPConn, payload string, wait time.Duration) bool {
	t.Helper()
	if _, err := client.Write([]byte(payload)); err != nil {
		t.Fatal(err)
	}
	_ = client.SetReadDeadline(time.Now().Add(wait))
	buffer := make([]byte, 16)
	count, err := client.Read(buffer)
	if err != nil {
		return false
	}
	if string(buffer[:count]) != payload {
		t.Fatalf("reply = %q, want %q", buffer[:count], payload)
	}
	return true
}

func TestUDPRelayRejectsSecondSenderWhileOwnerActive(t *testing.T) {
	relay, elapsed := startEchoRelay(t)
	owner := dialRelay(t, relay)
	other := dialRelay(t, relay)

	if !roundTrip(t, owner, "owner", 3*time.Second) {
		t.Fatal("owner got no reply")
	}
	elapsed.Add(int64(clientHandoverSilence - time.Second))
	if roundTrip(t, other, "other", 300*time.Millisecond) {
		t.Fatal("a second sender took over while the owner was still active")
	}
	if !roundTrip(t, owner, "again", 3*time.Second) {
		t.Fatal("owner lost the relay to a rejected sender")
	}
}

// A mosh-client that hops ports after an outage only sends from its new
// socket; the relay must follow it once the old socket has gone quiet.
func TestUDPRelayHandsOverAfterOwnerSilence(t *testing.T) {
	relay, elapsed := startEchoRelay(t)
	oldPort := dialRelay(t, relay)
	newPort := dialRelay(t, relay)

	if !roundTrip(t, oldPort, "before", 3*time.Second) {
		t.Fatal("first sender got no reply")
	}
	elapsed.Add(int64(clientHandoverSilence))
	if !roundTrip(t, newPort, "hopped", 3*time.Second) {
		t.Fatal("the relay did not follow the client's new port")
	}
	if roundTrip(t, oldPort, "stale", 300*time.Millisecond) {
		t.Fatal("the previous owner still reached the relay after handover")
	}
}
