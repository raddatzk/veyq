// Package clientaddr is the Traefik middleware that tells veyq a client's source port (ADR 0094 decision 7).
//
// Traefik writes the client's address into X-Forwarded-For without its port, and behind carrier-grade NAT an
// address names no subscriber without one. This middleware DELETES the header the app reads (every line, any
// case) and SETS it to the address and port of the connection Traefik accepted, on every request it serves. The
// delete is the security half: Traefik passes an unknown header through, so without it a client could name
// another subscriber's port. The app reads the header only when its own setting says the edge writes it, and its
// routing file attaches this middleware exactly then (TraefikFileIngressSink).
//
// Which header is the app's configuration, handed over in the middleware's `header` option by the routing file the
// app renders, so the name the app reads and the name Traefik overwrites cannot drift apart. No option, no
// middleware: New refuses, the router fails loudly, and the domain answers 404 rather than an unwritten header.
//
// It travels in the release recipe as this flat file and its manifest (traefik-clientaddr.yml), mounted read-only
// under Traefik's local plugin directory, and is interpreted by Traefik's own Yaegi: nothing is fetched.
package clientaddr

import (
	"context"
	"errors"
	"net"
	"net/http"
)

// Config is the middleware's options, as the routing file sets them.
type Config struct {
	Header string `json:"header,omitempty"`
}

// CreateConfig is the empty configuration Traefik fills from the routing file.
func CreateConfig() *Config {
	return &Config{}
}

type clientAddr struct {
	next   http.Handler
	header string
}

// New builds the middleware, or refuses one with no header to write.
func New(_ context.Context, next http.Handler, config *Config, _ string) (http.Handler, error) {
	if config.Header == "" {
		return nil, errors.New("clientaddr: the header option is empty")
	}
	return &clientAddr{next: next, header: config.Header}, nil
}

func (c *clientAddr) ServeHTTP(rw http.ResponseWriter, req *http.Request) {
	req.Header.Del(c.header)
	if host, port, err := net.SplitHostPort(req.RemoteAddr); err == nil {
		req.Header.Set(c.header, net.JoinHostPort(host, port))
	}
	c.next.ServeHTTP(rw, req)
}
