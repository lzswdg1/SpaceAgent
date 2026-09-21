// Package config manages local SpaceAgent CLI profiles and credentials.
package config

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"unicode/utf8"
)

const (
	DefaultServer       = "http://127.0.0.1:8087"
	legacyDefaultServer = "http://localhost:8080"
	DefaultProfile      = "default"
)

// Config is the resolved configuration for one profile.
type Config struct {
	Profile      string `json:"-"`
	Server       string `json:"server"`
	Token        string `json:"token"`
	RefreshToken string `json:"refreshToken"`
	UserID       string `json:"userId"`
	Username     string `json:"username"`
	Role         string `json:"role"`
	TenantID     string `json:"tenantId"`
	TenantRole   string `json:"tenantRole"`
}

// ProfileInfo is a secret-free profile summary.
type ProfileInfo struct {
	Name     string `json:"name"`
	Active   bool   `json:"active"`
	Server   string `json:"server"`
	Username string `json:"username,omitempty"`
	LoggedIn bool   `json:"loggedIn"`
}

func configDir() (string, error) {
	if configured := strings.TrimSpace(os.Getenv("SPACEAGENT_CONFIG_DIR")); configured != "" {
		return filepath.Clean(configured), nil
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(home, ".spaceagent"), nil
}

// ConfigPath returns the path for the currently resolved profile.
func ConfigPath() (string, error) {
	return ConfigPathForProfile(ResolveProfile(""))
}

// ConfigPathForProfile returns the isolated file path for a named profile.
// The default profile keeps the original config.json path for compatibility.
func ConfigPathForProfile(profile string) (string, error) {
	dir, err := configDir()
	if err != nil {
		return "", err
	}
	profile = normalizeProfile(profile)
	if err := ValidateProfileName(profile); err != nil {
		return "", err
	}
	if profile == DefaultProfile {
		return filepath.Join(dir, "config.json"), nil
	}
	return filepath.Join(dir, "profiles", profile+".json"), nil
}

// Load resolves and loads the current profile.
func Load() (*Config, error) {
	return LoadProfile("")
}

// LoadProfile resolves profiles in this order:
// explicit argument > SPACEAGENT_PROFILE > active-profile file > default.
func LoadProfile(profileOverride string) (*Config, error) {
	profile := ResolveProfile(profileOverride)
	path, err := ConfigPathForProfile(profile)
	if err != nil {
		return nil, err
	}

	migratedLegacyConfig := false
	data, err := os.ReadFile(path)
	if err != nil {
		if !errors.Is(err, os.ErrNotExist) {
			return nil, err
		}
		if profile != DefaultProfile {
			return nil, fmt.Errorf("profile %q does not exist", profile)
		}
		// An explicit config directory is an isolation boundary for CI, agents,
		// and tests. Never import credentials from the user's home directory.
		if strings.TrimSpace(os.Getenv("SPACEAGENT_CONFIG_DIR")) != "" {
			return defaultConfig(), nil
		}
		legacyPath, legacyErr := legacyConfigPath()
		if legacyErr != nil {
			return defaultConfig(), nil
		}
		data, err = os.ReadFile(legacyPath)
		if err != nil {
			return defaultConfig(), nil
		}
		migratedLegacyConfig = true
	}

	var cfg Config
	if err := json.Unmarshal(data, &cfg); err != nil {
		return nil, fmt.Errorf("invalid profile %q config: %w", profile, err)
	}
	cfg.Profile = profile
	if cfg.Server == "" || cfg.Server == legacyDefaultServer {
		cfg.Server = DefaultServer
	}
	if migratedLegacyConfig {
		_ = SaveProfile(profile, &cfg)
	}
	return &cfg, nil
}

func legacyConfigPath() (string, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(home, ".emotion", "config.json"), nil
}

// Save atomically persists the resolved profile.
func Save(cfg *Config) error {
	if cfg == nil {
		return fmt.Errorf("config is nil")
	}
	return SaveProfile(cfg.Profile, cfg)
}

// SaveProfile persists one profile without changing the active selection.
func SaveProfile(profile string, cfg *Config) error {
	if cfg == nil {
		return fmt.Errorf("config is nil")
	}
	profile = normalizeProfile(profile)
	if profile == DefaultProfile && strings.TrimSpace(cfg.Profile) != "" {
		profile = normalizeProfile(cfg.Profile)
	}
	if err := ValidateProfileName(profile); err != nil {
		return err
	}

	path, err := ConfigPathForProfile(profile)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return err
	}
	data, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return err
	}
	cfg.Profile = profile
	return atomicWrite(path, append(data, '\n'), 0600)
}

// ClearAuth removes credentials from the resolved profile.
func ClearAuth() error {
	cfg, err := Load()
	if err != nil {
		return err
	}
	cfg.Token = ""
	cfg.RefreshToken = ""
	cfg.UserID = ""
	cfg.Username = ""
	cfg.Role = ""
	cfg.TenantID = ""
	cfg.TenantRole = ""
	return Save(cfg)
}

func (c *Config) IsLoggedIn() bool {
	return c != nil && c.Token != ""
}

func (c *Config) IsAdmin() bool {
	return c != nil && c.Role == "ADMIN"
}

// ResolveProfile applies CLI-style profile precedence.
func ResolveProfile(profileOverride string) string {
	if profile := strings.TrimSpace(profileOverride); profile != "" {
		return profile
	}
	if profile := strings.TrimSpace(os.Getenv("SPACEAGENT_PROFILE")); profile != "" {
		return profile
	}
	if profile, err := readActiveProfile(); err == nil && profile != "" {
		return profile
	}
	return DefaultProfile
}

// ActiveProfile returns the persisted selection without env/flag overrides.
func ActiveProfile() string {
	if profile, err := readActiveProfile(); err == nil && profile != "" {
		return profile
	}
	return DefaultProfile
}

// SetActiveProfile changes the default for future invocations.
func SetActiveProfile(profile string) error {
	profile = normalizeProfile(profile)
	if err := ValidateProfileName(profile); err != nil {
		return err
	}
	exists, err := ProfileExists(profile)
	if err != nil {
		return err
	}
	if !exists {
		return fmt.Errorf("profile %q does not exist", profile)
	}
	dir, err := configDir()
	if err != nil {
		return err
	}
	if err := os.MkdirAll(dir, 0700); err != nil {
		return err
	}
	return atomicWrite(filepath.Join(dir, "active-profile"), []byte(profile+"\n"), 0600)
}

// CreateProfile creates an unauthenticated isolated profile.
func CreateProfile(profile, server string) (*Config, error) {
	profile = normalizeProfile(profile)
	if err := ValidateProfileName(profile); err != nil {
		return nil, err
	}
	exists, err := ProfileExists(profile)
	if err != nil {
		return nil, err
	}
	if exists {
		return nil, fmt.Errorf("profile %q already exists", profile)
	}
	if strings.TrimSpace(server) == "" {
		server = DefaultServer
	}
	cfg := &Config{
		Profile: profile,
		Server:  strings.TrimRight(strings.TrimSpace(server), "/"),
	}
	if err := SaveProfile(profile, cfg); err != nil {
		return nil, err
	}
	return cfg, nil
}

// RemoveProfile deletes a non-default, inactive profile.
func RemoveProfile(profile string) error {
	profile = normalizeProfile(profile)
	if profile == DefaultProfile {
		return fmt.Errorf("the default profile cannot be removed")
	}
	if ActiveProfile() == profile {
		return fmt.Errorf("profile %q is active; switch profiles before removing it", profile)
	}
	path, err := ConfigPathForProfile(profile)
	if err != nil {
		return err
	}
	if err := os.Remove(path); err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return fmt.Errorf("profile %q does not exist", profile)
		}
		return err
	}
	return nil
}

// ListProfiles returns all stored profiles without exposing tokens.
func ListProfiles() ([]ProfileInfo, error) {
	names := map[string]struct{}{DefaultProfile: {}}
	defaultPath, err := ConfigPathForProfile(DefaultProfile)
	if err != nil {
		return nil, err
	}
	if _, statErr := os.Stat(defaultPath); statErr == nil {
		names[DefaultProfile] = struct{}{}
	} else if !errors.Is(statErr, os.ErrNotExist) {
		return nil, statErr
	}

	dir, err := configDir()
	if err != nil {
		return nil, err
	}
	entries, readErr := os.ReadDir(filepath.Join(dir, "profiles"))
	if readErr != nil && !errors.Is(readErr, os.ErrNotExist) {
		return nil, readErr
	}
	for _, entry := range entries {
		if !entry.IsDir() && filepath.Ext(entry.Name()) == ".json" {
			names[strings.TrimSuffix(entry.Name(), ".json")] = struct{}{}
		}
	}
	sortedNames := make([]string, 0, len(names))
	for name := range names {
		sortedNames = append(sortedNames, name)
	}
	sort.Strings(sortedNames)

	active := ActiveProfile()
	profiles := make([]ProfileInfo, 0, len(sortedNames))
	for _, name := range sortedNames {
		cfg, loadErr := LoadProfile(name)
		if loadErr != nil {
			if name == DefaultProfile && errors.Is(loadErr, os.ErrNotExist) {
				cfg = defaultConfig()
			} else {
				return nil, loadErr
			}
		}
		profiles = append(profiles, ProfileInfo{
			Name:     name,
			Active:   name == active,
			Server:   cfg.Server,
			Username: cfg.Username,
			LoggedIn: cfg.IsLoggedIn(),
		})
	}
	return profiles, nil
}

func ProfileExists(profile string) (bool, error) {
	path, err := ConfigPathForProfile(profile)
	if err != nil {
		return false, err
	}
	_, err = os.Stat(path)
	if err == nil {
		return true, nil
	}
	if errors.Is(err, os.ErrNotExist) {
		return false, nil
	}
	return false, err
}

// ValidateProfileName rejects path and shell metacharacters.
func ValidateProfileName(profile string) error {
	if profile == "" {
		return fmt.Errorf("profile name cannot be empty")
	}
	if utf8.RuneCountInString(profile) > 64 {
		return fmt.Errorf("profile name is too long (max 64 characters)")
	}
	for _, r := range profile {
		if r <= 0x1f || r == 0x7f {
			return fmt.Errorf("profile name contains control characters")
		}
		switch r {
		case ' ', '\t', '/', '\\', '"', '\'', '`', '$', '#', '!', '&', '|', ';',
			'(', ')', '{', '}', '[', ']', '<', '>', '?', '*', '~':
			return fmt.Errorf("profile name contains invalid character %q", r)
		}
	}
	return nil
}

func defaultConfig() *Config {
	return &Config{Profile: DefaultProfile, Server: DefaultServer}
}

func normalizeProfile(profile string) string {
	profile = strings.TrimSpace(profile)
	if profile == "" {
		return DefaultProfile
	}
	return profile
}

func readActiveProfile() (string, error) {
	dir, err := configDir()
	if err != nil {
		return "", err
	}
	data, err := os.ReadFile(filepath.Join(dir, "active-profile"))
	if err != nil {
		return "", err
	}
	profile := strings.TrimSpace(string(data))
	if err := ValidateProfileName(profile); err != nil {
		return "", err
	}
	return profile, nil
}

func atomicWrite(path string, data []byte, mode os.FileMode) error {
	file, err := os.CreateTemp(filepath.Dir(path), ".spaceagent-*.tmp")
	if err != nil {
		return err
	}
	tempPath := file.Name()
	defer os.Remove(tempPath)

	if err := file.Chmod(mode); err != nil {
		_ = file.Close()
		return err
	}
	if _, err := file.Write(data); err != nil {
		_ = file.Close()
		return err
	}
	if err := file.Sync(); err != nil {
		_ = file.Close()
		return err
	}
	if err := file.Close(); err != nil {
		return err
	}
	return os.Rename(tempPath, path)
}
