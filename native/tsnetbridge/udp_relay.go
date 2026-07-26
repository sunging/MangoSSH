// Copyright 2026 MangoSSH contributors.
// SPDX-License-Identifier: Apache-2.0

package tsnetbridge

import (
	"net"
	"sync"
)

const maxDatagramSize = 64 * 1024

// UDPRelay forwards datagrams between one loopback client and one tsnet
// connection. The first local sender owns the relay for its lifetime.
type UDPRelay struct {
	local    *net.UDPConn
	remote   net.Conn
	onClose  func(*UDPRelay)
	closeOne sync.Once
	clientMu sync.RWMutex
	client   *net.UDPAddr
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
	if r.client == nil {
		r.client = sender
		return true
	}
	return r.client.IP.Equal(sender.IP) && r.client.Port == sender.Port
}
