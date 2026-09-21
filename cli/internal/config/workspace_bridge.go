package config

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

// WorkspaceBridgeRegistration is local-only secret state. LocalPath and BridgeToken
// never appear in a platform HTTP payload together and are stored mode 0600.
type WorkspaceBridgeRegistration struct {
	BridgeID    string `json:"bridgeId"`
	RootHandle  string `json:"rootHandle"`
	LocalPath   string `json:"localPath"`
	BridgeToken string `json:"bridgeToken"`
	DisplayName string `json:"displayName"`
	DeviceID    string `json:"deviceId"`
}

type WorkspaceBridgeStore struct {
	Bridges []WorkspaceBridgeRegistration `json:"bridges"`
}

func WorkspaceBridgeStorePath(profile string) (string, error) {
	dir, err := configDir()
	if err != nil {
		return "", err
	}
	profile = normalizeProfile(profile)
	if err := ValidateProfileName(profile); err != nil {
		return "", err
	}
	return filepath.Join(dir, "workspace-bridges", profile+".json"), nil
}

func LoadWorkspaceBridges(profile string) (*WorkspaceBridgeStore, error) {
	path, err := WorkspaceBridgeStorePath(profile)
	if err != nil {
		return nil, err
	}
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return &WorkspaceBridgeStore{}, nil
	}
	if err != nil {
		return nil, err
	}
	var store WorkspaceBridgeStore
	if err := json.Unmarshal(data, &store); err != nil {
		return nil, fmt.Errorf("invalid workspace bridge store: %w", err)
	}
	sort.Slice(store.Bridges, func(i, j int) bool { return store.Bridges[i].BridgeID < store.Bridges[j].BridgeID })
	return &store, nil
}

func SaveWorkspaceBridge(profile string, registration WorkspaceBridgeRegistration) error {
	if strings.TrimSpace(registration.BridgeID) == "" ||
		strings.TrimSpace(registration.RootHandle) == "" ||
		strings.TrimSpace(registration.LocalPath) == "" ||
		strings.TrimSpace(registration.BridgeToken) == "" {
		return fmt.Errorf("workspace bridge registration is incomplete")
	}
	store, err := LoadWorkspaceBridges(profile)
	if err != nil {
		return err
	}
	updated := false
	for index := range store.Bridges {
		if store.Bridges[index].BridgeID == registration.BridgeID {
			store.Bridges[index] = registration
			updated = true
		}
	}
	if !updated {
		store.Bridges = append(store.Bridges, registration)
	}
	return saveWorkspaceBridgeStore(profile, store)
}

func RemoveWorkspaceBridge(profile, bridgeID string) error {
	store, err := LoadWorkspaceBridges(profile)
	if err != nil {
		return err
	}
	filtered := store.Bridges[:0]
	for _, bridge := range store.Bridges {
		if bridge.BridgeID != bridgeID {
			filtered = append(filtered, bridge)
		}
	}
	store.Bridges = filtered
	return saveWorkspaceBridgeStore(profile, store)
}

func FindWorkspaceBridge(profile, bridgeID string) (WorkspaceBridgeRegistration, error) {
	store, err := LoadWorkspaceBridges(profile)
	if err != nil {
		return WorkspaceBridgeRegistration{}, err
	}
	for _, bridge := range store.Bridges {
		if bridge.BridgeID == bridgeID {
			return bridge, nil
		}
	}
	return WorkspaceBridgeRegistration{}, fmt.Errorf("workspace bridge %s is not registered locally", bridgeID)
}

func saveWorkspaceBridgeStore(profile string, store *WorkspaceBridgeStore) error {
	path, err := WorkspaceBridgeStorePath(profile)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return err
	}
	data, err := json.MarshalIndent(store, "", "  ")
	if err != nil {
		return err
	}
	return atomicWrite(path, append(data, '\n'), 0600)
}
