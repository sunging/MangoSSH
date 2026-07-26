// Copyright 2026 MangoSSH contributors.
// SPDX-License-Identifier: Apache-2.0

package tsnetbridge

import (
	"net"
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
