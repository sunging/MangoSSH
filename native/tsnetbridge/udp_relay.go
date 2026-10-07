// Copyright 2026 MangoSSH contributors.
// SPDX-License-Identifier: Apache-2.0

package tsnetbridge

import (
	"net"
	"sync"
	"time"
)

const maxDatagramSize = 64 * 1024

// clientHandoverSilence is how long the owning sender must stay quiet before
// another loopback sender may take the relay over. Mosh sends an empty ack at
// least every 3 s while it is alive, and hops to a fresh local port after 10 s
// without a round trip, so this hands over between two hops but never away
// from a client that is still talking.
const clientHandoverSilence = 5 * time.Second

// UDPRelay forwards datagrams between one loopback client and one tsnet
// connection. One local sender owns the relay at a time; once the owner falls
// silent, the next sender takes over. That handover is what lets mosh-client
// recover from an outage: it roams to a new source port and then only sends
// from there.
type UDPRelay struct {
	local          *net.UDPConn
	remote         net.Conn
	onClose        func(*UDPRelay)
	now            func() time.Time
	closeOne       sync.Once
	clientMu       sync.RWMutex
	client         *net.UDPAddr
	clientLastSeen time.Time
}

func newUDPRelay(remote net.Conn, onClose func(*UDPRelay)) (*UDPRelay, error) {
	local, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		return nil, err
	}
	return &UDPRelay{
		local:   local,
		remote:  remote,
		onClose: onClose,
		now:     time.Now,
	}, nil
}

func (r *UDPRelay) start() {
	go r.copyToRemote()
	go r.copyToLocal()
}

// LocalPort is the loopback UDP port supplied to the native Mosh client.
func (r *UDPRelay) LocalPort() int {
	address, ok := r.local.LocalAddr().(*net.UDPAddr)
	if !ok {
		return 0
	}
	return address.Port
}

// Close terminates both directions of the relay. It is idempotent.
func (r *UDPRelay) Close() error {
	r.closeOne.Do(func() {
		_ = r.local.Close()
		_ = r.remote.Close()
		if r.onClose != nil {
			r.onClose(r)
		}
	})
	return nil
}

func (r *UDPRelay) copyToRemote() {
	defer r.Close()
	buffer := make([]byte, maxDatagramSize)
	for {
		count, sender, err := r.local.ReadFromUDP(buffer)
		if err != nil {
			return
		}
		if !r.acceptSender(sender) {
			continue
		}
		if _, err := r.remote.Write(buffer[:count]); err != nil {
			return
		}
	}
}

func (r *UDPRelay) copyToLocal() {
	defer r.Close()
	buffer := make([]byte, maxDatagramSize)
	for {
		count, err := r.remote.Read(buffer)
		if err != nil {
			return
		}
		r.clientMu.RLock()
		client := r.client
		r.clientMu.RUnlock()
		if client == nil {
			continue
		}
		if _, err := r.local.WriteToUDP(buffer[:count], client); err != nil {
			return
		}
	}
}

func (r *UDPRelay) acceptSender(sender *net.UDPAddr) bool {
	r.clientMu.Lock()
	defer r.clientMu.Unlock()
	now := r.now()
	owner := r.client != nil && r.client.IP.Equal(sender.IP) && r.client.Port == sender.Port
	if !owner && r.client != nil && now.Sub(r.clientLastSeen) < clientHandoverSilence {
		return false
	}
	r.client = sender
	r.clientLastSeen = now
	return true
}
