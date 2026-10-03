package config

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestFirstConfigSaveUsesProjectBranding(t *testing.T) {
	path := filepath.Join(t.TempDir(), "launcher_config.json")
	cfg := loadConfig(path)
	if cfg.AuthorName != "ThorNex X Aelsiu" || cfg.BackWords != "MUSIC PARTY" {
		t.Fatal("a fresh installation must use the project attribution and title")
	}
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatal("loading a missing config must not write a file before the user saves it")
	}
	if err := cfg.save(path); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var persisted map[string]json.RawMessage
	if err := json.Unmarshal(data, &persisted); err != nil {
		t.Fatal(err)
	}
	if string(persisted["authorName"]) != `"ThorNex X Aelsiu"` || string(persisted["backWords"]) != `"MUSIC PARTY"` {
		t.Fatal("generated launcher_config.json must persist the project branding")
	}
	reloaded := loadConfig(path)
	if reloaded.AuthorName != cfg.AuthorName || reloaded.BackWords != cfg.BackWords {
		t.Fatal("saved branding must survive reload")
	}
}

func TestExistingConfigBrandingIsPreserved(t *testing.T) {
	for _, tc := range []struct {
		name   string
		author string
		title  string
	}{
		{"custom", "Custom Author", "CUSTOM TITLE"},
		{"empty", "", ""},
		{"legacy", "ThorNex", "THORNEX"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "launcher_config.json")
			original := &AppConfig{AuthorName: tc.author, BackWords: tc.title, ServerPort: "8123", NeteaseCookie: "test-cookie", AutoStart: true}
			data, err := json.Marshal(original)
			if err != nil {
				t.Fatal(err)
			}
			if err := os.WriteFile(path, data, 0600); err != nil {
				t.Fatal(err)
			}
			cfg := loadConfig(path)
			if *cfg != *original {
				t.Fatal("loading an existing configuration must preserve all values")
			}
			afterLoad, err := os.ReadFile(path)
			if err != nil {
				t.Fatal(err)
			}
			if string(afterLoad) != string(data) {
				t.Fatal("loading an existing configuration must not rewrite its file")
			}
			if err := cfg.save(path); err != nil {
				t.Fatal(err)
			}
			if *loadConfig(path) != *original {
				t.Fatal("saving an existing configuration must not replace its branding or other settings")
			}
		})
	}
}
