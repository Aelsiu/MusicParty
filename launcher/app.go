package main

import (
	"context"
	"embed"
	"fmt"
	"io/fs"
	"launcher/pkg/config"
	"launcher/pkg/process"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
	"time"

	wails_runtime "github.com/wailsapp/wails/v2/pkg/runtime"
)

//go:embed all:bin
var embeddedBin embed.FS

type App struct {
	ctx         context.Context
	cfg         *config.AppConfig
	manager     *process.ServiceManager
	assetsReady chan struct{}
	assetsError error
}

func NewApp() *App {
	return &App{
		cfg:         config.LoadConfig(),
		assetsReady: make(chan struct{}),
	}
}

func (a *App) LoadConfig() *config.AppConfig {
	a.cfg = config.LoadConfig()
	return a.cfg
}

func (a *App) SaveConfig(newConfig config.AppConfig) error {
	a.cfg = &newConfig
	return a.cfg.Save()
}

func (a *App) startup(ctx context.Context) {
	a.ctx = ctx
	// 在后台运行提取，防止阻塞 UI 启动
	go a.extractAssets()
}

func (a *App) extractAssets() {
	defer close(a.assetsReady)
	baseDir := config.GetBaseDir()

	a.logToTerminal("[SYSTEM] Preparing environment assets...")

	// 递归提取嵌入的 bin 目录及其所有内容
	err := fs.WalkDir(embeddedBin, "bin", func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}

		dest := filepath.Join(baseDir, path)

		if d.IsDir() {
			return os.MkdirAll(dest, 0755)
		}

		// 读取文件内容
		data, err := embeddedBin.ReadFile(path)
		if err != nil {
			a.logToTerminal(fmt.Sprintf("[ERROR] Failed to read embedded %s: %v", path, err))
			return err
		}

		// 只有不存在或大小不一致时才写入
		info, err := os.Stat(dest)
		if err == nil && info.Size() == int64(len(data)) {
			return nil
		}

		err = os.WriteFile(dest, data, 0755)
		if err == nil {
			a.logToTerminal(fmt.Sprintf("[SYSTEM] Extracted %s", path))
		} else {
			a.logToTerminal(fmt.Sprintf("[ERROR] Failed to write %s: %v", path, err))
		}
		return err
	})

	if err != nil {
		a.assetsError = err
		a.logToTerminal(fmt.Sprintf("[ERROR] Asset extraction failed: %v", err))
	} else {
		a.logToTerminal("[SYSTEM] Environment ready.")
	}
}

func (a *App) logToTerminal(msg string) {
	if a.ctx != nil {
		wails_runtime.EventsEmit(a.ctx, "log", msg)
	} else {
		fmt.Println(msg)
	}
}

func (a *App) GetServiceStatuses() map[string]bool {
	if a.manager == nil {
		return map[string]bool{
			"NETEASE_API": false,
			"JAVA_SERVER": false,
		}
	}
	return a.manager.GetStatuses()
}

func (a *App) OpenBrowser(url string) {
	var err error
	switch runtime.GOOS {
	case "linux":
		err = exec.Command("xdg-open", url).Start()
	case "windows":
		err = exec.Command("rundll32", "url.dll,FileProtocolHandler", url).Start()
	case "darwin":
		err = exec.Command("open", url).Start()
	default:
		err = fmt.Errorf("unsupported platform")
	}
	if err != nil {
		fmt.Printf("Error opening browser: %v\n", err)
	}
}

func (a *App) StartServices() error {
	select {
	case <-a.assetsReady:
		if a.assetsError != nil {
			return fmt.Errorf("运行环境提取失败，请检查启动器所在目录")
		}
	case <-time.After(time.Minute):
		return fmt.Errorf("运行环境仍在准备，请稍后重试")
	}
	baseDir := config.GetBaseDir()
	configDir := filepath.Join(baseDir, "config")
	if err := os.MkdirAll(configDir, 0755); err != nil {
		return fmt.Errorf("无法创建服务端配置目录")
	}
	serverConfig := filepath.Join(configDir, "application.properties")
	if _, err := os.Stat(serverConfig); os.IsNotExist(err) {
		if err := os.WriteFile(serverConfig, []byte("# Configure a unique 8-16 character root license, then restart\napp.rooms.root-key=\n"), 0600); err != nil {
			return fmt.Errorf("无法创建服务端配置文件")
		}
	}
	content, err := os.ReadFile(serverConfig)
	if err != nil {
		return fmt.Errorf("无法读取服务端配置文件")
	}
	rootConfigured := false
	for _, line := range strings.Split(string(content), "\n") {
		parts := strings.SplitN(strings.TrimSpace(line), "=", 2)
		if len(parts) == 2 && strings.TrimSpace(parts[0]) == "app.rooms.root-key" {
			rootConfigured = regexp.MustCompile(`^[!-~]{8,16}$`).MatchString(strings.TrimSuffix(parts[1], "\r"))
		}
	}
	if !rootConfigured {
		return fmt.Errorf("请直接编辑 config/application.properties 中的 app.rooms.root-key，须为 8–16 位无空白字符，保存后重新启动")
	}
	if a.manager != nil {
		a.StopServices()
	}

	a.manager = process.NewServiceManager()
	a.manager.WorkingDirectory = baseDir

	go func() {
		for logMsg := range a.manager.LogChannel {
			wails_runtime.EventsEmit(a.ctx, "log", logMsg)
		}
	}()

	binDir := a.getBinDir()

	// 1. 启动 Netease API
	apiExe := filepath.Join(binDir, "node.exe")
	if runtime.GOOS != "windows" {
		apiExe = filepath.Join(binDir, "node")
	}
	a.manager.StartProcess("NETEASE_API", apiExe, filepath.Join(binDir, "netease-api", "app.js"))

	// 2. 启动 Java 后端
	javaExe := filepath.Join(binDir, "jre", "bin", "java.exe")
	if _, err := os.Stat(javaExe); err != nil {
		javaExe = "java"
	}

	jarPath := filepath.Join(binDir, "server.jar")
	os.Setenv("PATH", binDir+string(os.PathListSeparator)+os.Getenv("PATH"))

	args := []string{
		"-jar", jarPath,
		fmt.Sprintf("--server.address=%s", a.cfg.ServerIP),
		fmt.Sprintf("--server.port=%s", a.cfg.ServerPort),
		fmt.Sprintf("--app.music-api.base-url=%s", a.cfg.BaseURL),
		fmt.Sprintf("--app.music-api.author-name=%s", a.cfg.AuthorName),
		fmt.Sprintf("--app.music-api.back-words=%s", a.cfg.BackWords),
		fmt.Sprintf("--app.music-api.netease.base-url=http://127.0.0.1:3000"),
		fmt.Sprintf("--app.music-api.netease.quality=%s", a.cfg.NeteaseQuality),
		fmt.Sprintf("--app.music-api.netease.enabled=%v", a.cfg.NeteaseEnabled),
		fmt.Sprintf("--app.music-api.bilibili.enabled=%v", a.cfg.BilibiliEnabled),
		fmt.Sprintf("--app.music-api.queue.max-size=%d", a.cfg.QueueMaxSize),
		fmt.Sprintf("--app.music-api.queue.history-size=%d", a.cfg.QueueHistorySize),
		fmt.Sprintf("--app.music-api.queue.max-user-songs=%d", a.cfg.QueueMaxUserSongs),
		fmt.Sprintf("--app.music-api.player.max-playlist-import-size=%d", a.cfg.MaxPlaylistImportSize),
		fmt.Sprintf("--app.music-api.chat.max-history-size=%d", a.cfg.ChatMaxHistorySize),
		fmt.Sprintf("--app.music-api.chat.min-interval-ms=%d", a.cfg.ChatMinIntervalMs),
		fmt.Sprintf("--app.music-api.chat.max-message-length=%d", a.cfg.ChatMaxMessageLength),
		fmt.Sprintf("--app.music-api.cache.max-size=%s", a.cfg.CacheMaxSize),
		fmt.Sprintf("--app.music-api.auth.rate-limit.enabled=%v", a.cfg.AuthRateLimitEnabled),
		fmt.Sprintf("--app.music-api.auth.rate-limit.max-attempts=%d", a.cfg.AuthMaxAttempts),
		fmt.Sprintf("--app.music-api.auth.rate-limit.window-seconds=%d", a.cfg.AuthWindowSeconds),
		fmt.Sprintf("--app.music-api.auth.rate-limit.block-duration-seconds=%d", a.cfg.AuthBlockDuration),
		"--logging.level.root=INFO",
		"--logging.level.org.thornex.musicparty=DEBUG",
	}

	a.manager.StartProcess("JAVA_SERVER", javaExe, args...)
	return nil
}

func (a *App) getBinDir() string {
	return filepath.Join(config.GetBaseDir(), "bin")
}

func (a *App) StopServices() {
	if a.manager != nil {
		a.manager.StopAll()
	}
}

func (a *App) beforeClose(ctx context.Context) (prevent bool) {
	a.StopServices()
	return false
}
