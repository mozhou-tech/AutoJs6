// Package phonetailnet exposes a deliberately small gomobile API for the
// AutoJs6 Phone MCP server. Tailscale remains an implementation detail.
package phonetailnet

import (
	"context"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime"
	"net"
	"net/http"
	"net/url"
	"os"
	"strings"
	"sync"
	"time"

	"tailscale.com/envknob"
	"tailscale.com/ipn"
	"tailscale.com/tsnet"
)

const maxRequestBytes = 2 << 20
const maxConcurrentRequests = 8

// Handler is implemented by Kotlin. It receives one JSON-RPC message and a
// compact, trusted description of the transport peer, and returns JSON-RPC.
type Handler interface {
	Handle(requestJSON string, clientJSON string) string
}

// EncryptedStateStore is implemented by Kotlin. Values cross the gomobile
// boundary as base64 and are encrypted with an Android Keystore key before
// being persisted. Read returns a compact JSON envelope.
type EncryptedStateStore interface {
	Read(key string) string
	Write(key string, valueBase64 string) string
	Delete(key string) string
}

type stateReadResult struct {
	Found bool   `json:"found"`
	Value string `json:"value,omitempty"`
	Error string `json:"error,omitempty"`
}

type mobileStateStore struct {
	backend EncryptedStateStore
}

func (s *mobileStateStore) ReadState(id ipn.StateKey) ([]byte, error) {
	var result stateReadResult
	if err := json.Unmarshal([]byte(s.backend.Read(string(id))), &result); err != nil {
		return nil, fmt.Errorf("encrypted state read returned an invalid response")
	}
	if result.Error != "" {
		return nil, fmt.Errorf("encrypted state read failed: %s", result.Error)
	}
	if !result.Found {
		return nil, ipn.ErrStateNotExist
	}
	value, err := base64.StdEncoding.DecodeString(result.Value)
	if err != nil {
		return nil, fmt.Errorf("encrypted state contained invalid data")
	}
	return value, nil
}

func (s *mobileStateStore) WriteState(id ipn.StateKey, value []byte) error {
	var result string
	if value == nil {
		result = s.backend.Delete(string(id))
	} else {
		result = s.backend.Write(string(id), base64.StdEncoding.EncodeToString(value))
	}
	if result != "" {
		return fmt.Errorf("encrypted state write failed: %s", result)
	}
	return nil
}

type config struct {
	ControlURL    string   `json:"control_url"`
	Hostname      string   `json:"hostname"`
	StateDir      string   `json:"state_dir"`
	AuthKey       string   `json:"auth_key"`
	PairingToken  string   `json:"pairing_token"`
	TailnetPort   int      `json:"tailnet_port"`
	LocalPort     int      `json:"local_port"`
	AllowedOrigin []string `json:"allowed_origins"`
	LocalOnly     bool     `json:"local_only"`
}

type status struct {
	Running      bool     `json:"running"`
	TailnetReady bool     `json:"tailnet_ready"`
	ControlHost  string   `json:"control_host,omitempty"`
	Hostname     string   `json:"hostname,omitempty"`
	TailnetIPs   []string `json:"tailnet_ips,omitempty"`
	TailnetPort  int      `json:"tailnet_port,omitempty"`
	LocalPort    int      `json:"local_port,omitempty"`
	LastError    string   `json:"last_error,omitempty"`
}

// Bridge owns the embedded node and all HTTP listeners.
type Bridge struct {
	mu        sync.RWMutex
	ts        *tsnet.Server
	servers   []*http.Server
	listeners []net.Listener
	status    status
	pairing   string
	origins   map[string]struct{}
	handler   Handler
	requests  chan struct{}
}

// NewBridge returns an idle bridge. One Bridge supports one running node.
func NewBridge() *Bridge { return &Bridge{} }

// Start creates the loopback endpoint and, unless local_only is true, joins
// the configured Headscale network and creates a tailnet-only listener.
func (b *Bridge) Start(configJSON string, handler Handler, encryptedStore EncryptedStateStore) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.status.Running {
		return errors.New("phone tailnet bridge is already running")
	}
	var cfg config
	if err := json.Unmarshal([]byte(configJSON), &cfg); err != nil {
		return fmt.Errorf("invalid configuration: %w", err)
	}
	if handler == nil {
		return errors.New("handler is required")
	}
	if len(cfg.PairingToken) < 24 {
		return errors.New("pairing token must contain at least 24 characters")
	}
	if cfg.LocalPort < 1 || cfg.LocalPort > 65535 || cfg.TailnetPort < 1 || cfg.TailnetPort > 65535 {
		return errors.New("ports must be between 1 and 65535")
	}
	if !cfg.LocalOnly {
		if encryptedStore == nil {
			return errors.New("encrypted state store is required")
		}
		control, err := validateControlURL(cfg.ControlURL)
		if err != nil {
			return err
		}
		if strings.TrimSpace(cfg.Hostname) == "" || strings.TrimSpace(cfg.StateDir) == "" {
			return errors.New("hostname and state_dir are required")
		}
		cfg.ControlURL = control.String()
		if err := os.MkdirAll(cfg.StateDir, 0700); err != nil {
			return fmt.Errorf("create state directory: %w", err)
		}
		b.status.ControlHost = control.Hostname()
	}

	b.handler = handler
	b.requests = make(chan struct{}, maxConcurrentRequests)
	b.pairing = cfg.PairingToken
	b.origins = make(map[string]struct{}, len(cfg.AllowedOrigin))
	for _, origin := range cfg.AllowedOrigin {
		b.origins[origin] = struct{}{}
	}
	b.status = status{
		Running:     true,
		ControlHost: b.status.ControlHost,
		Hostname:    cfg.Hostname,
		TailnetPort: cfg.TailnetPort,
		LocalPort:   cfg.LocalPort,
	}

	localListener, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", cfg.LocalPort))
	if err != nil {
		b.resetLocked()
		return fmt.Errorf("listen on loopback: %w", err)
	}
	b.serveLocked(localListener, "loopback", nil)

	if cfg.LocalOnly {
		return nil
	}

	// This is a self-hosted Headscale integration. Do not upload diagnostic
	// logs to Tailscale's logtail service or emit login URLs into Android logs.
	envknob.SetNoLogsNoSupport()
	discardLog := func(string, ...any) {}
	server := &tsnet.Server{
		Dir:        cfg.StateDir,
		Store:      &mobileStateStore{backend: encryptedStore},
		Hostname:   cfg.Hostname,
		AuthKey:    cfg.AuthKey,
		ControlURL: cfg.ControlURL,
		Logf:       discardLog,
		UserLogf:   discardLog,
	}
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	st, err := server.Up(ctx)
	if err != nil {
		_ = server.Close()
		b.stopServersLocked()
		b.status.LastError = sanitizeError(err)
		b.status.Running = false
		return fmt.Errorf("connect to Headscale: %w", err)
	}
	b.ts = server
	for _, ip := range st.TailscaleIPs {
		b.status.TailnetIPs = append(b.status.TailnetIPs, ip.String())
	}
	listener, err := server.Listen("tcp", fmt.Sprintf(":%d", cfg.TailnetPort))
	if err != nil {
		_ = server.Close()
		b.ts = nil
		b.stopServersLocked()
		b.status.LastError = sanitizeError(err)
		b.status.Running = false
		return fmt.Errorf("listen on tailnet: %w", err)
	}
	lc, err := server.LocalClient()
	if err != nil {
		_ = listener.Close()
		_ = server.Close()
		b.ts = nil
		b.stopServersLocked()
		b.status.LastError = sanitizeError(err)
		b.status.Running = false
		return fmt.Errorf("open tailscale LocalAPI: %w", err)
	}
	b.status.TailnetReady = true
	b.serveLocked(listener, "tailnet", func(ctx context.Context, remote string) map[string]any {
		peer := map[string]any{"source_address": remote}
		who, err := lc.WhoIs(ctx, remote)
		if err != nil {
			peer["identity_error"] = sanitizeError(err)
			return peer
		}
		peer["node_id"] = who.Node.ID
		peer["node_name"] = who.Node.ComputedName
		peer["user_login"] = who.UserProfile.LoginName
		return peer
	})
	return nil
}

type peerLookup func(context.Context, string) map[string]any

func (b *Bridge) serveLocked(listener net.Listener, transport string, lookup peerLookup) {
	h := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b.handleHTTP(w, r, transport, lookup)
	})
	srv := &http.Server{
		Handler:           h,
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       60 * time.Second,
		WriteTimeout:      60 * time.Second,
		IdleTimeout:       90 * time.Second,
		MaxHeaderBytes:    32 << 10,
	}
	b.listeners = append(b.listeners, listener)
	b.servers = append(b.servers, srv)
	go func() { _ = srv.Serve(listener) }()
}

func (b *Bridge) handleHTTP(w http.ResponseWriter, r *http.Request, transport string, lookup peerLookup) {
	if r.URL.Path == "/healthz" {
		if r.Method != http.MethodGet {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"ok":true}`)
		return
	}
	if r.URL.Path != "/mcp" {
		http.NotFound(w, r)
		return
	}
	if r.Method == http.MethodGet {
		w.Header().Set("Allow", "POST")
		http.Error(w, "server-sent events are not enabled", http.StatusMethodNotAllowed)
		return
	}
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", "POST")
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !b.validOrigin(r.Header.Get("Origin")) {
		http.Error(w, "origin is not allowed", http.StatusForbidden)
		return
	}
	if !b.validBearer(r.Header.Get("Authorization")) {
		w.Header().Set("WWW-Authenticate", `Bearer realm="phone-mcp"`)
		http.Error(w, "authentication required", http.StatusUnauthorized)
		return
	}
	if contentType := r.Header.Get("Content-Type"); contentType != "" {
		mediaType, _, err := mime.ParseMediaType(contentType)
		if err != nil || mediaType != "application/json" {
			http.Error(w, "content type must be application/json", http.StatusUnsupportedMediaType)
			return
		}
	}
	b.mu.RLock()
	requests := b.requests
	b.mu.RUnlock()
	select {
	case requests <- struct{}{}:
		defer func() { <-requests }()
	default:
		w.Header().Set("Retry-After", "1")
		http.Error(w, "too many concurrent requests", http.StatusTooManyRequests)
		return
	}
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, maxRequestBytes))
	if err != nil {
		http.Error(w, "invalid or oversized request", http.StatusRequestEntityTooLarge)
		return
	}
	if !json.Valid(body) {
		http.Error(w, "request body must be valid JSON", http.StatusBadRequest)
		return
	}
	peer := map[string]any{
		"transport":      transport,
		"source_address": r.RemoteAddr,
	}
	if lookup != nil {
		for key, value := range lookup(r.Context(), r.RemoteAddr) {
			peer[key] = value
		}
	}
	peerJSON, _ := json.Marshal(peer)
	b.mu.RLock()
	handler := b.handler
	b.mu.RUnlock()
	if handler == nil {
		http.Error(w, "server is stopping", http.StatusServiceUnavailable)
		return
	}
	response := handler.Handle(string(body), string(peerJSON))
	if strings.TrimSpace(response) == "" {
		w.WriteHeader(http.StatusAccepted)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	_, _ = io.WriteString(w, response)
}

func (b *Bridge) validOrigin(origin string) bool {
	if origin == "" {
		return true
	}
	b.mu.RLock()
	defer b.mu.RUnlock()
	_, ok := b.origins[origin]
	return ok
}

func (b *Bridge) validBearer(header string) bool {
	const prefix = "Bearer "
	if !strings.HasPrefix(header, prefix) {
		return false
	}
	provided := strings.TrimSpace(strings.TrimPrefix(header, prefix))
	b.mu.RLock()
	expected := b.pairing
	b.mu.RUnlock()
	if len(provided) != len(expected) {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(provided), []byte(expected)) == 1
}

// Stop closes all listeners, active HTTP connections, and the embedded node.
func (b *Bridge) Stop() error {
	b.mu.Lock()
	defer b.mu.Unlock()
	var first error
	b.stopServersLocked()
	if b.ts != nil {
		if err := b.ts.Close(); err != nil && first == nil {
			first = err
		}
	}
	b.resetLocked()
	return first
}

func (b *Bridge) stopServersLocked() {
	for _, srv := range b.servers {
		// Stop is an explicit security boundary. Close immediately instead of
		// waiting while holding b.mu; a handler may itself need the same lock.
		_ = srv.Close()
	}
	for _, listener := range b.listeners {
		_ = listener.Close()
	}
	b.servers = nil
	b.listeners = nil
}

func (b *Bridge) resetLocked() {
	b.ts = nil
	b.handler = nil
	b.pairing = ""
	b.origins = nil
	b.requests = nil
	b.status = status{}
}

// StatusJSON returns a stable, secret-free status document for Kotlin.
func (b *Bridge) StatusJSON() string {
	b.mu.RLock()
	defer b.mu.RUnlock()
	data, _ := json.Marshal(b.status)
	return string(data)
}

func validateControlURL(raw string) (*url.URL, error) {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil || u.Scheme != "https" || u.Host == "" || u.User != nil {
		return nil, errors.New("control_url must be an HTTPS origin")
	}
	if u.RawQuery != "" || u.Fragment != "" || (u.Path != "" && u.Path != "/") {
		return nil, errors.New("control_url must not contain a path, query, user info, or fragment")
	}
	u.Path = ""
	return u, nil
}

func sanitizeError(err error) string {
	if err == nil {
		return ""
	}
	message := err.Error()
	if len(message) > 300 {
		message = message[:300]
	}
	return message
}
