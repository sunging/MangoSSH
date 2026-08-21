// Copyright 2026 MangoSSH contributors.
// SPDX-License-Identifier: Apache-2.0

// Package tsnetbridge exposes the minimum gomobile surface MangoSSH needs to
// run an outbound-only embedded Tailscale node.
package tsnetbridge

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"

	"tailscale.com/envknob"
	"tailscale.com/ipn"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/net/netmon"
	"tailscale.com/tsnet"
)

const (
	statusStarting      = "starting"
	statusNeedsLogin    = "needs_login"
	statusNeedsApproval = "needs_approval"
	statusRunning       = "running"
	statusStopped       = "stopped"
	statusError         = "error"

	statusPollInterval = 500 * time.Millisecond
	operationTimeout   = 15 * time.Second
)

var (
	errStartFailed       = errors.New("embedded tsnet start failed")
	errServerStartFailed = errors.New("embedded tsnet server start failed")
	errLocalClientFailed = errors.New("embedded tsnet local client failed")
	errLoopbackFailed    = errors.New("embedded tsnet loopback failed")
	errStatusFailed      = errors.New("embedded tsnet status failed")
	errNotRunning        = errors.New("embedded tsnet is not running")
	errUDPRelayFailed    = errors.New("embedded tsnet UDP relay failed")
	errLogoutFailed      = errors.New("embedded tsnet logout failed")
	errInvalidArgument   = errors.New("embedded tsnet argument is invalid")
	configureProcessOnce sync.Once
)

// StateStore is implemented by Android using device-bound encrypted storage.
//
// Values cross the gomobile boundary as byte arrays so node state never becomes
// a Java String. An empty value means that the key does not exist.
type StateStore interface {
	ReadState(key string) ([]byte, error)
	WriteState(key string, value []byte) error
}

// StatusListener receives only fixed state identifiers and a transient login
// URL. Implementations must never persist or log authURL.
type StatusListener interface {
	OnStatus(state string, authURL string)
}

// NetworkStateSource supplies Android's permission-safe network interface
// snapshot. Go's net.Interfaces uses restricted netlink operations on recent
// Android releases, so the platform must enumerate interfaces through Java.
type NetworkStateSource interface {
	SnapshotJson() (string, error)
}

// Runtime owns one embedded tsnet node and all UDP relays using that identity.
//
// A Runtime cannot be restarted after Close. Android creates a fresh Runtime
// around the same encrypted StateStore whenever on-demand networking resumes.
type Runtime struct {
	mu           sync.Mutex
	stateDir     string
	hostname     string
	store        StateStore
	networkState NetworkStateSource
	listener     StatusListener
	server       *tsnet.Server
	localClient  localStatusClient
	socksAddress string
	socksSecret  string
	pollCancel   context.CancelFunc
	relays       map[*UDPRelay]struct{}
	closed       bool
	lastState    string
	lastAuthURL  string
}

// NewRuntime constructs a stopped embedded node.
func NewRuntime(stateDir, hostname string, store StateStore, networkState NetworkStateSource, listener StatusListener) *Runtime {
	return &Runtime{
		stateDir:     stateDir,
		hostname:     hostname,
		store:        store,
		networkState: networkState,
		listener:     listener,
		relays:       make(map[*UDPRelay]struct{}),
	}
}

// Start initializes the embedded node. authKey is consumed only for first-time
// enrollment and is not retained by Runtime after startup returns.
func (r *Runtime) Start(authKey string) error {
	r.mu.Lock()
	if r.closed || r.server != nil || r.stateDir == "" || r.hostname == "" || r.store == nil || r.networkState == nil {
		r.mu.Unlock()
		return errInvalidArgument
	}

	// Update both the environment and envknob's registered-value cache before
	// Tailscale starts any goroutine. The pinned tsnet source additionally
	// skips its log identity and filch buffer in this mode.
	configureProcessOnce.Do(func() {
		envknob.SetNoLogsNoSupport()
		envknob.Setenv("TS_LOGS_DIR", r.stateDir)
		// Some platform helpers use Go's process-global standard logger instead
		// of the Server callbacks. Drop raw lines before reading interface data.
		log.SetOutput(io.Discard)
	})
	netmon.RegisterInterfaceGetter(androidInterfaceGetter(r.networkState))

	server := &tsnet.Server{
		Dir:      r.stateDir,
		Store:    encryptedStateStore{delegate: r.store},
		Hostname: r.hostname,
		AuthKey:  authKey,
		// Never forward upstream text. It can include peer and endpoint data.
		UserLogf: discardLog,
		Logf:     discardLog,
	}
	authKey = ""
	r.server = server
	r.mu.Unlock()

	startErr := server.Start()
	// The backend has consumed the one-shot value on every return path.
	server.AuthKey = ""
	if startErr != nil {
		r.failStart(server)
		return errServerStartFailed
	}

	localClient, err := server.LocalClient()
	if err != nil {
		r.failStart(server)
		return errLocalClientFailed
	}
	address, proxySecret, _, err := server.Loopback()
	if err != nil {
		r.failStart(server)
		return errLoopbackFailed
	}

	r.mu.Lock()
	if r.closed || r.server != server {
		r.mu.Unlock()
		_ = server.Close()
		return errStartFailed
	}
	r.localClient = localClient
	r.socksAddress = address
	r.socksSecret = proxySecret
	pollContext, cancel := context.WithCancel(context.Background())
	r.pollCancel = cancel
	r.mu.Unlock()

	r.publish(statusStarting, "")
	go r.pollStatus(pollContext, server, localClient)
	return nil
}

// NotifyNetworkChange asks the running node to refresh Android's network
// snapshot. The platform calls this after default-network transitions.
func (r *Runtime) NotifyNetworkChange() {
	r.mu.Lock()
	server := r.server
	closed := r.closed
	r.mu.Unlock()
	if !closed && server != nil {
		server.NotifyNetworkChange()
	}
}

// SocksAddress returns the loopback host:port for tsnet's authenticated SOCKS5
// endpoint. It is empty before Start succeeds or after Close.
func (r *Runtime) SocksAddress() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.socksAddress
}

// SocksSecret returns the per-Runtime random SOCKS5 password.
//
// Android keeps this value only in the process-local transport object.
func (r *Runtime) SocksSecret() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.socksSecret
}

// StartUDPRelay starts a loopback-only UDP relay to a tailnet destination.
func (r *Runtime) StartUDPRelay(host string, port int) (*UDPRelay, error) {
	if host == "" || port < 1 || port > 65535 {
		return nil, errInvalidArgument
	}

	r.mu.Lock()
	server := r.server
	closed := r.closed
	r.mu.Unlock()
	if closed || server == nil {
		return nil, errNotRunning
	}

	ctx, cancel := context.WithTimeout(context.Background(), operationTimeout)
	defer cancel()
	remote, err := server.Dial(ctx, "udp", net.JoinHostPort(host, strconv.Itoa(port)))
	if err != nil {
		return nil, errUDPRelayFailed
	}
	relay, err := newUDPRelay(remote, r.removeRelay)
	if err != nil {
		_ = remote.Close()
		return nil, errUDPRelayFailed
	}

	r.mu.Lock()
	if r.closed || r.server != server {
		r.mu.Unlock()
		_ = relay.Close()
		return nil, errNotRunning
	}
	r.relays[relay] = struct{}{}
	r.mu.Unlock()
	relay.start()
	return relay, nil
}

// Logout invalidates the active node identity at the control server and then
// closes all process-local networking. Android clears encrypted state only
// after this method returns.
func (r *Runtime) Logout() error {
	r.mu.Lock()
	client := r.localClient
	r.mu.Unlock()
	if client == nil {
		_ = r.Close()
		return errNotRunning
	}

	ctx, cancel := context.WithTimeout(context.Background(), operationTimeout)
	err := client.Logout(ctx)
	cancel()
	_ = r.Close()
	if err != nil {
		return errLogoutFailed
	}
	return nil
}

// Close stops the node and every UDP relay. It is idempotent.
func (r *Runtime) Close() error {
	r.mu.Lock()
	if r.closed {
		r.mu.Unlock()
		return nil
	}
	r.closed = true
	cancel := r.pollCancel
	server := r.server
	relays := make([]*UDPRelay, 0, len(r.relays))
	for relay := range r.relays {
		relays = append(relays, relay)
	}
	r.relays = make(map[*UDPRelay]struct{})
	r.server = nil
	r.localClient = nil
	r.socksAddress = ""
	r.socksSecret = ""
	r.mu.Unlock()

	if cancel != nil {
		cancel()
	}
	for _, relay := range relays {
		_ = relay.Close()
	}
	if server != nil {
		_ = server.Close()
	}
	r.publish(statusStopped, "")
	return nil
}

func (r *Runtime) failStart(server *tsnet.Server) {
	r.mu.Lock()
	if r.server == server {
		r.server = nil
	}
	r.mu.Unlock()
	_ = server.Close()
	r.publish(statusError, "")
}

func (r *Runtime) pollStatus(ctx context.Context, server *tsnet.Server, client localStatusClient) {
	ticker := time.NewTicker(statusPollInterval)
	defer ticker.Stop()
	peerMapReady := false
	for {
		r.updateStatus(ctx, server, client, &peerMapReady)
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}

func (r *Runtime) updateStatus(
	ctx context.Context,
	server *tsnet.Server,
	client localStatusClient,
	peerMapReady *bool,
) {
	statusContext, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	var status *ipnstate.Status
	var err error
	if *peerMapReady {
		status, err = client.StatusWithoutPeers(statusContext)
	} else {
		status, err = client.Status(statusContext)
	}
	if err != nil {
		if ctx.Err() == nil {
			r.publish(statusError, "")
		}
		return
	}

	r.mu.Lock()
	current := r.server == server && !r.closed
	r.mu.Unlock()
	if !current {
		return
	}

	state, authURL := mapBackendStatus(status, *peerMapReady)
	if state == statusRunning {
		*peerMapReady = true
	} else {
		*peerMapReady = false
	}
	r.publish(state, authURL)
}

// mapBackendStatus withholds the first "running" until the local netmap
// contains this node's address and at least one peer. BackendState can become
// Running slightly before the engine's PeerForIP map is populated; exposing
// SOCKS5 during that window makes tailnet destinations fall through to the
// physical network instead of netstack. After readiness, lightweight
// peer-less status polls are sufficient for lifecycle changes.
func mapBackendStatus(status *ipnstate.Status, peerMapReady bool) (state, authURL string) {
	switch status.BackendState {
	case "Running":
		if len(status.TailscaleIPs) == 0 || (!peerMapReady && len(status.Peer) == 0) {
			return statusStarting, ""
		}
		return statusRunning, ""
	case "NeedsMachineAuth":
		return statusNeedsApproval, ""
	case "NeedsLogin", "NoState":
		return statusNeedsLogin, status.AuthURL
	case "Stopped":
		return statusStopped, ""
	default:
		return statusStarting, ""
	}
}

func (r *Runtime) publish(state, authURL string) {
	r.mu.Lock()
	if state == r.lastState && (authURL == "" || authURL == r.lastAuthURL) {
		r.mu.Unlock()
		return
	}
	r.lastState = state
	if authURL != "" {
		r.lastAuthURL = authURL
	} else if state != statusNeedsLogin {
		r.lastAuthURL = ""
	}
	listener := r.listener
	r.mu.Unlock()
	if listener != nil {
		listener.OnStatus(state, authURL)
	}
}

func (r *Runtime) removeRelay(relay *UDPRelay) {
	r.mu.Lock()
	delete(r.relays, relay)
	r.mu.Unlock()
}

func discardLog(string, ...any) {}

type androidNetworkSnapshot struct {
	DefaultRoute   string                    `json:"defaultRoute"`
	DefaultGateway string                    `json:"defaultGateway"`
	Interfaces     []androidNetworkInterface `json:"interfaces"`
}

type androidNetworkInterface struct {
	Name         string                  `json:"name"`
	Index        int                     `json:"index"`
	MTU          int                     `json:"mtu"`
	Up           bool                    `json:"up"`
	Broadcast    bool                    `json:"broadcast"`
	Loopback     bool                    `json:"loopback"`
	PointToPoint bool                    `json:"pointToPoint"`
	Multicast    bool                    `json:"multicast"`
	Addrs        []androidNetworkAddress `json:"addrs"`
}

type androidNetworkAddress struct {
	IP        string `json:"ip"`
	PrefixLen int    `json:"prefixLen"`
}

func androidInterfaceGetter(source NetworkStateSource) func() ([]netmon.Interface, error) {
	return func() ([]netmon.Interface, error) {
		payload, err := source.SnapshotJson()
		if err != nil {
			return nil, err
		}
		return parseAndroidNetworkSnapshot(payload)
	}
}

func parseAndroidNetworkSnapshot(payload string) ([]netmon.Interface, error) {
	var snapshot androidNetworkSnapshot
	if err := json.Unmarshal([]byte(payload), &snapshot); err != nil {
		return nil, err
	}
	updateDefaultRouteInterface(snapshot.DefaultRoute)
	updateDefaultGateway(snapshot.DefaultGateway)

	interfaces := make([]netmon.Interface, 0, len(snapshot.Interfaces))
	for _, item := range snapshot.Interfaces {
		if item.Name == "" {
			continue
		}
		flags := net.Flags(0)
		if item.Up {
			flags |= net.FlagUp
		}
		if item.Broadcast {
			flags |= net.FlagBroadcast
		}
		if item.Loopback {
			flags |= net.FlagLoopback
		}
		if item.PointToPoint {
			flags |= net.FlagPointToPoint
		}
		if item.Multicast {
			flags |= net.FlagMulticast
		}
		parsed := netmon.Interface{
			Interface: &net.Interface{
				Name:  item.Name,
				Index: item.Index,
				MTU:   item.MTU,
				Flags: flags,
			},
			AltAddrs: make([]net.Addr, 0, len(item.Addrs)),
		}
		for _, address := range item.Addrs {
			ip, zone := parseAndroidIP(address.IP)
			if ip == nil {
				continue
			}
			if zone != "" {
				parsed.AltAddrs = append(parsed.AltAddrs, &net.IPAddr{IP: ip, Zone: zone})
				continue
			}
			bits := 128
			if ip.To4() != nil {
				ip = ip.To4()
				bits = 32
			}
			if address.PrefixLen < 0 || address.PrefixLen > bits {
				parsed.AltAddrs = append(parsed.AltAddrs, &net.IPAddr{IP: ip})
				continue
			}
			parsed.AltAddrs = append(parsed.AltAddrs, &net.IPNet{
				IP:   ip,
				Mask: net.CIDRMask(address.PrefixLen, bits),
			})
		}
		interfaces = append(interfaces, parsed)
	}
	return interfaces, nil
}

func parseAndroidIP(value string) (net.IP, string) {
	host, zone := value, ""
	if index := strings.LastIndexByte(value, '%'); index >= 0 {
		host, zone = value[:index], value[index+1:]
	}
	return net.ParseIP(host), zone
}

type localStatusClient interface {
	Status(context.Context) (*ipnstate.Status, error)
	StatusWithoutPeers(context.Context) (*ipnstate.Status, error)
	Logout(context.Context) error
}

// encryptedStateStore adapts Android's encrypted callback to ipn.StateStore.
type encryptedStateStore struct {
	delegate StateStore
}

var _ ipn.StateStore = encryptedStateStore{}

func (s encryptedStateStore) ReadState(id ipn.StateKey) ([]byte, error) {
	encoded, err := s.delegate.ReadState(string(id))
	if err != nil {
		return nil, err
	}
	if len(encoded) == 0 {
		return nil, ipn.ErrStateNotExist
	}
	return append([]byte(nil), encoded...), nil
}

func (s encryptedStateStore) WriteState(id ipn.StateKey, value []byte) error {
	return s.delegate.WriteState(string(id), append([]byte(nil), value...))
}
