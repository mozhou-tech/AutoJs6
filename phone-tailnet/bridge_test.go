package phonetailnet

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"strconv"
	"sync"
	"testing"

	"tailscale.com/ipn"
)

type echoHandler struct{}

func (echoHandler) Handle(requestJSON, clientJSON string) string {
	return `{"jsonrpc":"2.0","id":1,"result":{"ok":true}}`
}

type blockingHandler struct {
	started chan struct{}
	release chan struct{}
}

type fakeEncryptedStore struct {
	values map[string]string
	err    string
}

func (s *fakeEncryptedStore) Read(key string) string {
	if s.err != "" {
		data, _ := json.Marshal(stateReadResult{Error: s.err})
		return string(data)
	}
	value, found := s.values[key]
	data, _ := json.Marshal(stateReadResult{Found: found, Value: value})
	return string(data)
}

func (s *fakeEncryptedStore) Write(key, valueBase64 string) string {
	if s.err != "" {
		return s.err
	}
	s.values[key] = valueBase64
	return ""
}

func (s *fakeEncryptedStore) Delete(key string) string {
	if s.err != "" {
		return s.err
	}
	delete(s.values, key)
	return ""
}

func (h *blockingHandler) Handle(requestJSON, clientJSON string) string {
	h.started <- struct{}{}
	<-h.release
	return `{"jsonrpc":"2.0","id":1,"result":{}}`
}

func TestLocalEndpointSecurityAndDispatch(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	_ = listener.Close()

	cfg, _ := json.Marshal(config{
		PairingToken: "12345678901234567890123456789012",
		TailnetPort:  port,
		LocalPort:    port,
		LocalOnly:    true,
	})
	bridge := NewBridge()
	if err := bridge.Start(string(cfg), echoHandler{}, nil); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = bridge.Stop() })

	endpoint := "http://127.0.0.1:" + strconv.Itoa(port) + "/mcp"
	request := func(origin, bearer string) *http.Response {
		req, err := http.NewRequest(http.MethodPost, endpoint, bytes.NewBufferString(`{"jsonrpc":"2.0","id":1,"method":"ping"}`))
		if err != nil {
			t.Fatal(err)
		}
		if origin != "" {
			req.Header.Set("Origin", origin)
		}
		if bearer != "" {
			req.Header.Set("Authorization", "Bearer "+bearer)
		}
		response, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		return response
	}
	requestWithContentType := func(contentType string) *http.Response {
		req, err := http.NewRequest(http.MethodPost, endpoint, bytes.NewBufferString(`{"jsonrpc":"2.0","id":1,"method":"ping"}`))
		if err != nil {
			t.Fatal(err)
		}
		req.Header.Set("Authorization", "Bearer 12345678901234567890123456789012")
		req.Header.Set("Content-Type", contentType)
		response, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		return response
	}

	unauthorized := request("", "")
	_ = unauthorized.Body.Close()
	if unauthorized.StatusCode != http.StatusUnauthorized {
		t.Fatalf("unauthorized status = %d", unauthorized.StatusCode)
	}

	forbidden := request("https://untrusted.invalid", "12345678901234567890123456789012")
	_ = forbidden.Body.Close()
	if forbidden.StatusCode != http.StatusForbidden {
		t.Fatalf("forbidden status = %d", forbidden.StatusCode)
	}

	unsupported := requestWithContentType("text/plain")
	_ = unsupported.Body.Close()
	if unsupported.StatusCode != http.StatusUnsupportedMediaType {
		t.Fatalf("unsupported media status = %d", unsupported.StatusCode)
	}

	ok := request("", "12345678901234567890123456789012")
	body, err := io.ReadAll(ok.Body)
	_ = ok.Body.Close()
	if err != nil {
		t.Fatal(err)
	}
	if ok.StatusCode != http.StatusOK || !json.Valid(body) {
		t.Fatalf("dispatch status = %d, body = %q", ok.StatusCode, body)
	}
}

func TestLocalEndpointLimitsConcurrentRequests(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	_ = listener.Close()
	cfg, _ := json.Marshal(config{
		PairingToken: "12345678901234567890123456789012",
		TailnetPort:  port,
		LocalPort:    port,
		LocalOnly:    true,
	})
	handler := &blockingHandler{started: make(chan struct{}, maxConcurrentRequests), release: make(chan struct{})}
	bridge := NewBridge()
	if err := bridge.Start(string(cfg), handler, nil); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = bridge.Stop() })

	endpoint := "http://127.0.0.1:" + strconv.Itoa(port) + "/mcp"
	doRequest := func() (*http.Response, error) {
		req, err := http.NewRequest(http.MethodPost, endpoint, bytes.NewBufferString(`{"jsonrpc":"2.0","id":1,"method":"ping"}`))
		if err != nil {
			return nil, err
		}
		req.Header.Set("Authorization", "Bearer 12345678901234567890123456789012")
		req.Header.Set("Content-Type", "application/json")
		return http.DefaultClient.Do(req)
	}

	var workers sync.WaitGroup
	errors := make(chan error, maxConcurrentRequests)
	for range maxConcurrentRequests {
		workers.Add(1)
		go func() {
			defer workers.Done()
			response, err := doRequest()
			if err != nil {
				errors <- err
				return
			}
			_ = response.Body.Close()
		}()
	}
	for range maxConcurrentRequests {
		<-handler.started
	}
	overloaded, err := doRequest()
	if err != nil {
		t.Fatal(err)
	}
	_ = overloaded.Body.Close()
	if overloaded.StatusCode != http.StatusTooManyRequests {
		t.Fatalf("overload status = %d", overloaded.StatusCode)
	}
	close(handler.release)
	workers.Wait()
	close(errors)
	for err := range errors {
		t.Error(err)
	}
}

func TestMobileStateStoreRoundTrip(t *testing.T) {
	backend := &fakeEncryptedStore{values: map[string]string{}}
	store := &mobileStateStore{backend: backend}
	key := ipn.StateKey("profile/test")

	if _, err := store.ReadState(key); !errors.Is(err, ipn.ErrStateNotExist) {
		t.Fatalf("missing state error = %v", err)
	}
	want := []byte(`{"private":"state"}`)
	if err := store.WriteState(key, want); err != nil {
		t.Fatal(err)
	}
	got, err := store.ReadState(key)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, want) {
		t.Fatalf("state = %q, want %q", got, want)
	}
	if err := store.WriteState(key, nil); err != nil {
		t.Fatal(err)
	}
	if _, err := store.ReadState(key); !errors.Is(err, ipn.ErrStateNotExist) {
		t.Fatalf("deleted state error = %v", err)
	}
}

func TestMobileStateStoreSanitizesBackendErrors(t *testing.T) {
	store := &mobileStateStore{backend: &fakeEncryptedStore{values: map[string]string{}, err: "KEYSTORE_FAILURE"}}
	if _, err := store.ReadState(ipn.StateKey("key")); err == nil || err.Error() != "encrypted state read failed: KEYSTORE_FAILURE" {
		t.Fatalf("read error = %v", err)
	}
	if err := store.WriteState(ipn.StateKey("key"), []byte("value")); err == nil || err.Error() != "encrypted state write failed: KEYSTORE_FAILURE" {
		t.Fatalf("write error = %v", err)
	}
}
