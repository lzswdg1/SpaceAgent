package config

import (
	"os"
	"path/filepath"
	"testing"
)

func TestWorkspaceBridgeStoreIsProfileScopedAndMode0600(t *testing.T) {
	t.Setenv("SPACEAGENT_CONFIG_DIR", t.TempDir())
	registration := WorkspaceBridgeRegistration{
		BridgeID: "bridge-1", RootHandle: "root_abcdefgh",
		LocalPath: "/local/only/project", BridgeToken: "brg_secret",
		DisplayName: "Project", DeviceID: "device-1",
	}
	if err := SaveWorkspaceBridge("team", registration); err != nil {
		t.Fatal(err)
	}
	loaded, err := FindWorkspaceBridge("team", "bridge-1")
	if err != nil {
		t.Fatal(err)
	}
	if loaded.LocalPath != registration.LocalPath || loaded.BridgeToken != registration.BridgeToken {
		t.Fatalf("unexpected registration: %+v", loaded)
	}
	path, err := WorkspaceBridgeStorePath("team")
	if err != nil {
		t.Fatal(err)
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if info.Mode().Perm() != 0600 {
		t.Fatalf("mode = %o", info.Mode().Perm())
	}
	if filepath.Base(path) != "team.json" {
		t.Fatalf("unexpected path: %s", path)
	}
	if err := RemoveWorkspaceBridge("team", "bridge-1"); err != nil {
		t.Fatal(err)
	}
	if _, err := FindWorkspaceBridge("team", "bridge-1"); err == nil {
		t.Fatal("expected removed bridge")
	}
}
