// Package factory provides lazily initialized dependencies for CLI commands.
package factory

import (
	"context"
	"io"
	"os"
	"strings"

	"github.com/lzswdg1/SpaceAgent/cli/internal/client"
	"github.com/lzswdg1/SpaceAgent/cli/internal/config"
)

// InvocationContext contains values selected by global flags or environment.
type InvocationContext struct {
	Profile string
	Format  string
}

// Factory holds shared, replaceable dependencies for commands.
type Factory struct {
	ConfigFunc func() (*config.Config, error)
	ClientFunc func() (*client.Client, error)
	IOStreams  *IOStreams
	Invocation InvocationContext
}

type IOStreams struct {
	In     io.Reader
	Out    io.Writer
	ErrOut io.Writer
}

func New() *Factory {
	f := &Factory{
		IOStreams: &IOStreams{
			In:     os.Stdin,
			Out:    os.Stdout,
			ErrOut: os.Stderr,
		},
		Invocation: InvocationContext{
			Profile: strings.TrimSpace(os.Getenv("SPACEAGENT_PROFILE")),
			Format:  strings.TrimSpace(os.Getenv("SPACEAGENT_FORMAT")),
		},
	}
	var cachedConfig *config.Config
	var cachedClient *client.Client
	f.bindLoaders(&cachedConfig, &cachedClient)
	return f
}

func (f *Factory) Config() (*config.Config, error) {
	return f.ConfigFunc()
}

func (f *Factory) Client() (*client.Client, error) {
	return f.ClientFunc()
}

func (f *Factory) ReloadConfig() {
	var cachedConfig *config.Config
	var cachedClient *client.Client
	f.bindLoaders(&cachedConfig, &cachedClient)
}

func (f *Factory) bindLoaders(cachedConfig **config.Config, cachedClient **client.Client) {
	f.ConfigFunc = func() (*config.Config, error) {
		if *cachedConfig != nil {
			return *cachedConfig, nil
		}
		cfg, err := config.LoadProfile(f.Invocation.Profile)
		if err != nil {
			return nil, err
		}
		*cachedConfig = cfg
		return cfg, nil
	}

	f.ClientFunc = func() (*client.Client, error) {
		if *cachedClient != nil {
			return *cachedClient, nil
		}
		cfg, err := f.ConfigFunc()
		if err != nil {
			return nil, err
		}
		*cachedClient = client.NewWithRefresh(
			cfg.Server,
			cfg.Token,
			func(ctx context.Context) (string, error) {
				return refreshAndPersist(ctx, cfg)
			},
		)
		return *cachedClient, nil
	}
}

func refreshAndPersist(ctx context.Context, cfg *config.Config) (string, error) {
	if strings.TrimSpace(cfg.RefreshToken) == "" {
		return "", &client.APIError{
			StatusCode: 401,
			Code:       "REFRESH_TOKEN_MISSING",
			Msg:        "refresh token is missing; login again",
		}
	}

	refreshClient := client.New(cfg.Server, "")
	var response client.ApiResponse[client.AuthResponse]
	if err := refreshClient.PostWithContext(
		ctx,
		"/api/v1/auth/refresh",
		client.RefreshTokenRequest{RefreshToken: cfg.RefreshToken},
		&response,
	); err != nil {
		return "", err
	}

	cfg.Token = response.Data.Token
	cfg.RefreshToken = response.Data.RefreshToken
	cfg.UserID = response.Data.UserID
	cfg.Username = response.Data.Username
	cfg.Role = response.Data.Role
	cfg.TenantID = response.Data.TenantID
	cfg.TenantRole = response.Data.TenantRole
	if err := config.Save(cfg); err != nil {
		return "", err
	}
	return cfg.Token, nil
}
