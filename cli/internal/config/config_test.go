package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestLoadMigratesLegacyEmotionConfig(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	legacyDir := filepath.Join(home, ".emotion")
	if err := os.MkdirAll(legacyDir, 0700); err != nil {
		t.Fatal(err)
	}
	legacy := `{"server":"http://127.0.0.1:8087","token":"jwt","username":"demo"}`
	if err := os.WriteFile(filepath.Join(legacyDir, "config.json"), []byte(legacy), 0600); err != nil {
		t.Fatal(err)
	}

	cfg, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Token != "jwt" || cfg.Username != "demo" {
		t.Fatalf("legacy config was not loaded: %#v", cfg)
	}
	if _, err := os.Stat(filepath.Join(home, ".spaceagent", "config.json")); err != nil {
		t.Fatalf("new config was not created: %v", err)
	}
}

func TestNamedProfilesAreIsolatedAndSelectable(t *testing.T) {
	configDir := t.TempDir()
	t.Setenv("SPACEAGENT_CONFIG_DIR", configDir)
	t.Setenv("SPACEAGENT_PROFILE", "")

	work, err := CreateProfile("work", "https://gateway.example.com/")
	if err != nil {
		t.Fatal(err)
	}
	work.Token = "work-token"
	work.Username = "alice"
	if err := Save(work); err != nil {
		t.Fatal(err)
	}
	if err := SetActiveProfile("work"); err != nil {
		t.Fatal(err)
	}

	resolved, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if resolved.Profile != "work" || resolved.Token != "work-token" {
		t.Fatalf("unexpected resolved profile: %#v", resolved)
	}
	if resolved.Server != "https://gateway.example.com" {
		t.Fatalf("expected normalized server, got %q", resolved.Server)
	}

	profiles, err := ListProfiles()
	if err != nil {
		t.Fatal(err)
	}
	if len(profiles) != 2 {
		t.Fatalf("expected implicit default and work profiles, got %#v", profiles)
	}
	if profiles[1].Name != "work" || !profiles[1].Active || !profiles[1].LoggedIn {
		t.Fatalf("unexpected work profile summary: %#v", profiles[1])
	}
}

func TestProfileResolutionPrecedence(t *testing.T) {
	configDir := t.TempDir()
	t.Setenv("SPACEAGENT_CONFIG_DIR", configDir)
	if _, err := CreateProfile("active", DefaultServer); err != nil {
		t.Fatal(err)
	}
	if _, err := CreateProfile("environment", DefaultServer); err != nil {
		t.Fatal(err)
	}
	if err := SetActiveProfile("active"); err != nil {
		t.Fatal(err)
	}

	t.Setenv("SPACEAGENT_PROFILE", "environment")
	if got := ResolveProfile(""); got != "environment" {
		t.Fatalf("expected environment profile, got %q", got)
	}
	if got := ResolveProfile("explicit"); got != "explicit" {
		t.Fatalf("expected explicit profile, got %q", got)
	}
}

func TestValidateProfileNameRejectsShellMetacharacters(t *testing.T) {
	for _, name := range []string{"", "two words", "../escape", "prod;rm"} {
		if err := ValidateProfileName(name); err == nil {
			t.Fatalf("expected %q to be rejected", name)
		}
	}
	if err := ValidateProfileName("字节-demo_1"); err != nil {
		t.Fatalf("expected localized profile name to be accepted: %v", err)
	}
}

func TestMalformedProfileIsReported(t *testing.T) {
	configDir := t.TempDir()
	t.Setenv("SPACEAGENT_CONFIG_DIR", configDir)
	t.Setenv("SPACEAGENT_PROFILE", "")
	if err := os.WriteFile(filepath.Join(configDir, "config.json"), []byte("{broken"), 0600); err != nil {
		t.Fatal(err)
	}
	_, err := LoadProfile("default")
	if err == nil || !strings.Contains(err.Error(), "invalid profile") {
		t.Fatalf("expected malformed config error, got %v", err)
	}
}

func TestExplicitConfigDirDoesNotImportLegacyCredentials(t *testing.T) {
	home := t.TempDir()
	isolated := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("SPACEAGENT_CONFIG_DIR", isolated)
	t.Setenv("SPACEAGENT_PROFILE", "")

	legacyDir := filepath.Join(home, ".emotion")
	if err := os.MkdirAll(legacyDir, 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(
		filepath.Join(legacyDir, "config.json"),
		[]byte(`{"server":"https://legacy.example.com","token":"secret","username":"legacy"}`),
		0600,
	); err != nil {
		t.Fatal(err)
	}

	cfg, err := LoadProfile(DefaultProfile)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Token != "" || cfg.Username != "" || cfg.Server != DefaultServer {
		t.Fatalf("isolated config imported legacy credentials: %#v", cfg)
	}
}
