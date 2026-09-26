import { invoke } from "@tauri-apps/api/core";

interface VlessConfig {
  id: string;
  subscription_url: string;
  name: string;
  address: string;
  port: number;
  uuid: string;
  transport: string;
  security: string;
  raw_uri: string;
}

interface WarpConfig {
  private_key: string;
  public_key: string;
  address_v4: string;
  address_v6: string;
  endpoint: string;
}

interface Strategy {
  id: string;
  name: string;
  description: string;
  args: string;
  recommended_for: string;
}

interface StrategyBenchmarkResult {
  strategy: Strategy;
  success: boolean;
  latency_ms: number;
}

interface ServiceStatus {
  byedpi_running: boolean;
  vless_running: boolean;
  warp_running: boolean;
  system_proxy_enabled: boolean;
  tun_mode: boolean;
  selected_vless_id?: string;
}

let configs: VlessConfig[] = [];
let subscriptions: string[] = [];
let activeTab: string = "all";
let pingResults: Record<string, number> = {};
let currentWarpFormat: "wireguard" | "amnezia" = "wireguard";
let currentStrategies: Strategy[] = [];
let activeStrategy: Strategy | null = null;

// Navigation
const navDashboard = document.getElementById("nav-dashboard")!;
const navVless = document.getElementById("nav-vless")!;
const navWarp = document.getElementById("nav-warp")!;
const navSettings = document.getElementById("nav-settings")!;

const sectionDashboard = document.getElementById("section-dashboard")!;
const sectionVless = document.getElementById("section-vless")!;
const sectionWarp = document.getElementById("section-warp")!;
const sectionSettings = document.getElementById("section-settings")!;

function switchTab(targetNav: HTMLElement, targetSection: HTMLElement) {
  [navDashboard, navVless, navWarp, navSettings].forEach(n => n.classList.remove("active"));
  [sectionDashboard, sectionVless, sectionWarp, sectionSettings].forEach(s => s.style.display = "none");
  targetNav.classList.add("active");
  targetSection.style.display = "block";
}

navDashboard.addEventListener("click", () => switchTab(navDashboard, sectionDashboard));
navVless.addEventListener("click", () => {
  switchTab(navVless, sectionVless);
  loadConfigsAndTabs();
});
navWarp.addEventListener("click", () => {
  switchTab(navWarp, sectionWarp);
  renderWarpConfig();
});
navSettings.addEventListener("click", () => {
  switchTab(navSettings, sectionSettings);
});

document.getElementById("btn-open-vless-list")?.addEventListener("click", () => {
  navVless.click();
});

// WARP Config Display & Generation
async function renderWarpConfig() {
  const textarea = document.getElementById("warp-config-text") as HTMLTextAreaElement;
  if (!textarea) return;

  try {
    if (currentWarpFormat === "amnezia") {
      const text: string = await invoke("get_warp_amnezia_wg_text");
      textarea.value = text;
    } else {
      const cfg: WarpConfig = await invoke("get_warp_config");
      textarea.value = `[Interface]
PrivateKey = ${cfg.private_key}
Address = ${cfg.address_v4}/32, ${cfg.address_v6}/128
DNS = 1.1.1.1, 8.8.8.8

[Peer]
PublicKey = ${cfg.public_key}
AllowedIPs = 0.0.0.0/0, ::/0
Endpoint = ${cfg.endpoint}`;
      
      const sub = document.getElementById("warp-active-subtitle");
      if (sub) sub.textContent = `WireGuard • ${cfg.endpoint}`;
    }
  } catch (e) {
    console.error("Failed to load warp config", e);
  }
}

// Format toggle
document.getElementById("btn-toggle-warp-format")?.addEventListener("click", () => {
  const btn = document.getElementById("btn-toggle-warp-format")!;
  if (currentWarpFormat === "wireguard") {
    currentWarpFormat = "amnezia";
    btn.textContent = "Формат: AmneziaWG";
  } else {
    currentWarpFormat = "wireguard";
    btn.textContent = "Формат: WireGuard";
  }
  renderWarpConfig();
});

// Generate WARP Profile Action
async function handleGenerateWarp() {
  const statusEl = document.getElementById("warp-generator-status");
  const quickBtn = document.getElementById("btn-warp-generate-quick") as HTMLButtonElement;
  const pageBtn = document.getElementById("btn-generate-warp-profile") as HTMLButtonElement;

  if (statusEl) {
    statusEl.style.display = "block";
    statusEl.style.color = "var(--accent-blue)";
    statusEl.textContent = "⏳ Регистрация в сети Cloudflare и подбор быстрого эндпоинта...";
  }
  if (quickBtn) quickBtn.textContent = "⏳ Генерация...";
  if (pageBtn) pageBtn.textContent = "⏳ Генерация...";

  try {
    const newCfg: WarpConfig = await invoke("generate_warp_profile");
    if (statusEl) {
      statusEl.style.color = "var(--accent-green)";
      statusEl.textContent = `✅ Успешно! Сгенерирован профиль: ${newCfg.endpoint} (${newCfg.address_v4})`;
    }
    renderWarpConfig();
    alert(`Новый профиль Cloudflare WARP успешно сгенерирован!\nЭндпоинт: ${newCfg.endpoint}`);
  } catch (e: any) {
    if (statusEl) {
      statusEl.style.color = "#ef4444";
      statusEl.textContent = `❌ Ошибка генерации: ${e}`;
    }
    alert(`Ошибка генерации WARP: ${e}`);
  } finally {
    if (quickBtn) quickBtn.textContent = "✨ Новый ключ";
    if (pageBtn) pageBtn.textContent = "✨ Сгенерировать";
  }
}

document.getElementById("btn-generate-warp-profile")?.addEventListener("click", handleGenerateWarp);
document.getElementById("btn-warp-generate-quick")?.addEventListener("click", handleGenerateWarp);

document.getElementById("btn-copy-warp-conf")?.addEventListener("click", () => {
  const textarea = document.getElementById("warp-config-text") as HTMLTextAreaElement;
  if (textarea) {
    navigator.clipboard.writeText(textarea.value);
    alert("Конфигурация скопирована в буфер обмена!");
  }
});

document.getElementById("btn-reset-warp-conf")?.addEventListener("click", () => {
  renderWarpConfig();
  alert("Конфигурация обновлена!");
});

document.getElementById("btn-open-github-repo")?.addEventListener("click", () => {
  window.open("https://github.com/byedpirussia/byedpirussia");
});

document.getElementById("btn-warp-page-toggle")?.addEventListener("click", () => {
  document.getElementById("btn-toggle-warp")?.click();
});

// ByeDPI Button
const btnToggleByeDpi = document.getElementById("btn-toggle-byedpi")!;
const badgeByeDpi = document.getElementById("badge-byedpi")!;

btnToggleByeDpi.addEventListener("click", async () => {
  try {
    const status: ServiceStatus = await invoke("get_status");
    if (status.byedpi_running) {
      await invoke("stop_byedpi");
    } else {
      await invoke("start_byedpi", { params: null });
    }
    updateStatus();
  } catch (err: any) {
    alert("Ошибка ByeDPI: " + err);
  }
});

// VLESS Button
const btnToggleVless = document.getElementById("btn-toggle-vless")!;

btnToggleVless.addEventListener("click", () => {
  alert("Режим VLESS временно недоступен в Windows-версии. Мы исправим и включим его в следующем релизе!");
});

// WARP Button
const btnToggleWarp = document.getElementById("btn-toggle-warp")!;

btnToggleWarp?.addEventListener("click", () => {
  alert("Подключение WARP временно недоступно в Windows-версии. Мы исправим и включим его в следующем релизе!");
});

// System Proxy & TUN Toggles
const chkSystemProxy = document.getElementById("chk-system-proxy") as HTMLInputElement;
const chkSettingsProxy = document.getElementById("chk-settings-proxy") as HTMLInputElement;
const chkTunMode = document.getElementById("chk-tun-mode") as HTMLInputElement;
const chkSettingsTun = document.getElementById("chk-settings-tun") as HTMLInputElement;

async function handleProxyChange(checked: boolean) {
  try {
    const enabled: boolean = await invoke("toggle_system_proxy", { enable: checked });
    chkSystemProxy.checked = enabled;
    if (chkSettingsProxy) chkSettingsProxy.checked = enabled;
  } catch (err: any) {
    alert("Не удалось изменить системный прокси: " + err);
  }
}

async function handleTunChange(_checked: boolean) {
  alert("Режим Виртуального TUN (Wintun) временно отключен на доработку. Мы исправим и включим его в следующем релизе!");
  if (chkTunMode) chkTunMode.checked = false;
  if (chkSettingsTun) chkSettingsTun.checked = false;
}

chkSystemProxy?.addEventListener("change", () => handleProxyChange(chkSystemProxy.checked));
chkSettingsProxy?.addEventListener("change", () => handleProxyChange(chkSettingsProxy.checked));
chkTunMode?.addEventListener("change", () => handleTunChange(chkTunMode.checked));
chkSettingsTun?.addEventListener("change", () => handleTunChange(chkSettingsTun.checked));

// Telegram Button
document.getElementById("btn-tg-connect")?.addEventListener("click", () => {
  alert("Telegram Proxy для Windows временно отключен на доработку. Мы исправим и включим его в следующем релизе!");
});

// Status Poller
async function updateStatus() {
  try {
    const status: ServiceStatus = await invoke("get_status");

    // ByeDPI
    if (status.byedpi_running) {
      badgeByeDpi.textContent = "🟢 Активно";
      badgeByeDpi.className = "status-badge active";
      btnToggleByeDpi.textContent = "Остановить ByeDPI";
      btnToggleByeDpi.className = "btn btn-active";
    } else {
      badgeByeDpi.textContent = "⚪ Отключено";
      badgeByeDpi.className = "status-badge inactive";
      btnToggleByeDpi.textContent = "Включить ByeDPI";
      btnToggleByeDpi.className = "btn btn-primary";
    }

    chkSystemProxy.checked = status.system_proxy_enabled;
    if (chkSettingsProxy) chkSettingsProxy.checked = status.system_proxy_enabled;
    if (chkTunMode) chkTunMode.checked = false;
    if (chkSettingsTun) chkSettingsTun.checked = false;

    // Update active vless subtitle
    if (status.selected_vless_id) {
      const active = configs.find(c => c.id === status.selected_vless_id);
      const vlessSubtitle = document.getElementById("vless-active-subtitle");
      if (active && vlessSubtitle) {
        vlessSubtitle.textContent = `${active.name} (${active.address}:${active.port})`;
      }
    }
  } catch (e) {
    console.error("Status update error", e);
  }
}

// VLESS Manager Logic
async function loadConfigsAndTabs() {
  configs = await invoke("get_configs");
  subscriptions = await invoke("get_subscriptions");
  renderTabs();
  renderServerList();
}

function renderTabs() {
  const container = document.getElementById("sub-tabs-container")!;
  container.innerHTML = "";

  const allTab = createTabElement("Все", "all", activeTab === "all");
  const keysTab = createTabElement("Ключи", "keys", activeTab === "keys");
  container.appendChild(allTab);
  container.appendChild(keysTab);

  subscriptions.forEach((subUrl, idx) => {
    let title = `Подписка ${idx + 1}`;
    try {
      title = new URL(subUrl).host;
    } catch (_) {}
    const subTab = createTabElement(title, subUrl, activeTab === subUrl);
    container.appendChild(subTab);
  });

  const subActions = document.getElementById("sub-actions")!;
  if (activeTab !== "all" && activeTab !== "keys") {
    subActions.style.display = "flex";
  } else {
    subActions.style.display = "none";
  }
}

function createTabElement(title: string, tabId: string, isActive: boolean) {
  const div = document.createElement("div");
  div.className = `tab ${isActive ? "active" : ""}`;
  div.textContent = title;
  div.addEventListener("click", () => {
    activeTab = tabId;
    renderTabs();
    renderServerList();
  });
  return div;
}

async function renderServerList() {
  const container = document.getElementById("server-list-container")!;
  container.innerHTML = "";

  const status: ServiceStatus = await invoke("get_status");
  const selectedId = status.selected_vless_id;

  let filtered = configs;
  if (activeTab === "keys") {
    filtered = configs.filter(c => !c.subscription_url);
  } else if (activeTab !== "all") {
    filtered = configs.filter(c => c.subscription_url === activeTab);
  }

  if (filtered.length === 0) {
    container.innerHTML = `<div style="text-align: center; color: var(--text-secondary); padding: 40px;">Нет добавленных серверов</div>`;
    return;
  }

  filtered.forEach(cfg => {
    const isSelected = cfg.id === selectedId;
    const item = document.createElement("div");
    item.className = `server-item ${isSelected ? "selected" : ""}`;

    const ping = pingResults[cfg.id as any];
    let pingHtml = "";
    if (ping !== undefined) {
      if (ping >= 0) {
        const cls = ping < 150 ? "ping-good" : ping < 350 ? "ping-medium" : "ping-bad";
        pingHtml = `<span class="ping-badge ${cls}">⚡ ${ping} ms</span>`;
      } else {
        pingHtml = `<span class="ping-badge ping-bad">❌ Офлайн</span>`;
      }
    }

    item.innerHTML = `
      <div class="server-info">
        <h4>${isSelected ? "🔘 " : "⚪ "}${cfg.name}</h4>
        <span>${cfg.address}:${cfg.port} • ${cfg.security} • ${cfg.transport}</span>
      </div>
      <div class="server-meta">
        ${pingHtml}
        <button class="btn btn-secondary" style="padding: 6px 10px; font-size: 11px; color: #ef4444;" data-del="${cfg.id}">🗑️</button>
      </div>
    `;

    item.addEventListener("click", async (e) => {
      if ((e.target as HTMLElement).tagName === "BUTTON") return;
      await invoke("select_config", { id: cfg.id });
      renderServerList();
      updateStatus();
    });

    item.querySelector(`[data-del="${cfg.id}"]`)?.addEventListener("click", async () => {
      await invoke("delete_config", { id: cfg.id });
      loadConfigsAndTabs();
    });

    container.appendChild(item);
  });
}

// Modal Handlers
const modalAddKey = document.getElementById("modal-add-key")!;
const modalAddSub = document.getElementById("modal-add-sub")!;

document.getElementById("btn-add-key")?.addEventListener("click", () => {
  modalAddKey.style.display = "flex";
});
document.getElementById("btn-cancel-add-key")?.addEventListener("click", () => {
  modalAddKey.style.display = "none";
});
document.getElementById("btn-confirm-add-key")?.addEventListener("click", async () => {
  const input = (document.getElementById("input-vless-key") as HTMLInputElement).value.trim();
  if (input) {
    try {
      await invoke("add_vless_key", { uri: input });
      modalAddKey.style.display = "none";
      (document.getElementById("input-vless-key") as HTMLInputElement).value = "";
      loadConfigsAndTabs();
    } catch (e: any) {
      alert("Ошибка добавления ключа: " + e);
    }
  }
});

document.getElementById("btn-add-sub")?.addEventListener("click", () => {
  modalAddSub.style.display = "flex";
});
document.getElementById("btn-cancel-add-sub")?.addEventListener("click", () => {
  modalAddSub.style.display = "none";
});
document.getElementById("btn-confirm-add-sub")?.addEventListener("click", async () => {
  const input = (document.getElementById("input-sub-url") as HTMLInputElement).value.trim();
  if (input) {
    try {
      const btn = document.getElementById("btn-confirm-add-sub")!;
      btn.textContent = "Загрузка...";
      await invoke("fetch_subscription", { url: input });
      btn.textContent = "Загрузить";
      modalAddSub.style.display = "none";
      (document.getElementById("input-sub-url") as HTMLInputElement).value = "";
      loadConfigsAndTabs();
    } catch (e: any) {
      alert("Ошибка загрузки подписки: " + e);
      document.getElementById("btn-confirm-add-sub")!.textContent = "Загрузить";
    }
  }
});

// Ping Test
document.getElementById("btn-test-ping")?.addEventListener("click", async () => {
  const btn = document.getElementById("btn-test-ping")!;
  btn.textContent = "Тестирование...";
  
  for (const cfg of configs) {
    try {
      const ping: number = await invoke("ping_server", { address: cfg.address, port: cfg.port });
      pingResults[cfg.id as any] = ping;
    } catch (_) {
      pingResults[cfg.id as any] = -1;
    }
  }

  // Sort configs by ping
  configs.sort((a, b) => {
    const pa = pingResults[a.id as any] ?? 999999;
    const pb = pingResults[b.id as any] ?? 999999;
    const valA = pa >= 0 ? pa : 999990;
    const valB = pb >= 0 ? pb : 999990;
    return valA - valB;
  });

  btn.textContent = "⚡ Тест пинга";
  renderServerList();
});

// Update & Delete subscription
document.getElementById("btn-refresh-sub")?.addEventListener("click", async () => {
  if (activeTab && activeTab !== "all" && activeTab !== "keys") {
    try {
      await invoke("fetch_subscription", { url: activeTab });
      loadConfigsAndTabs();
      alert("Подписка успешно обновлена!");
    } catch (e: any) {
      alert("Ошибка обновления: " + e);
    }
  }
});

document.getElementById("btn-delete-sub")?.addEventListener("click", async () => {
  if (activeTab && activeTab !== "all" && activeTab !== "keys") {
    if (confirm("Удалить эту подписку и все её серверы?")) {
      await invoke("delete_subscription", { url: activeTab });
      activeTab = "all";
      loadConfigsAndTabs();
    }
  }
});

// ---------------- ByeDPI Strategies & Auto-Check ----------------

const modalStrategies = document.getElementById("modal-strategies")!;
const modalAutocheck = document.getElementById("modal-autocheck")!;
const strategiesContainer = document.getElementById("strategies-list-container")!;
const autocheckResultsContainer = document.getElementById("autocheck-results-container")!;
const autocheckProgressBar = document.getElementById("autocheck-progress-bar")!;
const autocheckCount = document.getElementById("autocheck-count")!;
const autocheckStatusDesc = document.getElementById("autocheck-status-desc")!;
const btnApplyBestStrategy = document.getElementById("btn-apply-best-strategy")!;
const byedpiSubtitle = document.getElementById("byedpi-active-subtitle")!;

let isAutocheckRunning = false;
let bestAutocheckStrategy: Strategy | null = null;

async function loadStrategies() {
  try {
    currentStrategies = await invoke("get_byedpi_strategies");
    if (!activeStrategy && currentStrategies.length > 0) {
      activeStrategy = currentStrategies[0];
    }
  } catch (e) {
    console.error("Failed to load strategies", e);
  }
}

function renderStrategiesModal() {
  strategiesContainer.innerHTML = "";
  currentStrategies.forEach(s => {
    const item = document.createElement("div");
    const isActive = activeStrategy?.id === s.id;
    item.className = `strategy-item ${isActive ? "active" : ""}`;
    item.innerHTML = `
      <div style="flex: 1; padding-right: 12px;">
        <div class="strategy-title">${s.name} ${isActive ? '<span style="color: var(--accent-green); font-size: 11px;">(Активна)</span>' : ''}</div>
        <div class="strategy-desc">${s.description}</div>
        <div class="strategy-badge">🎯 ${s.recommended_for}</div>
      </div>
      <button class="btn btn-secondary" style="font-size: 12px; white-space: nowrap;">${isActive ? '✓ Выбрана' : 'Выбрать'}</button>
    `;
    item.addEventListener("click", async () => {
      activeStrategy = s;
      await invoke("set_active_strategy", { args: s.args });
      if (byedpiSubtitle) {
        byedpiSubtitle.textContent = `SOCKS5 127.0.0.1:1080 • ${s.name}`;
      }
      modalStrategies.style.display = "none";
      alert(`Стратегия "${s.name}" выбрана! Перезапустите ByeDPI для применения.`);
    });
    strategiesContainer.appendChild(item);
  });
}

document.getElementById("btn-byedpi-strategies-list")?.addEventListener("click", async () => {
  if (currentStrategies.length === 0) {
    await loadStrategies();
  }
  renderStrategiesModal();
  modalStrategies.style.display = "flex";
});

document.getElementById("btn-close-strategies")?.addEventListener("click", () => {
  modalStrategies.style.display = "none";
});

// Auto-Check Runner
async function runAutoCheck() {
  if (isAutocheckRunning) return;
  if (currentStrategies.length === 0) {
    await loadStrategies();
  }

  isAutocheckRunning = true;
  modalAutocheck.style.display = "flex";
  autocheckResultsContainer.innerHTML = "";
  autocheckProgressBar.style.width = "0%";
  btnApplyBestStrategy.style.display = "none";
  bestAutocheckStrategy = null;

  const total = currentStrategies.length;
  let bestLatency = 999999;
  let successResults: StrategyBenchmarkResult[] = [];

  for (let i = 0; i < total; i++) {
    if (!isAutocheckRunning) break;

    const strat = currentStrategies[i];
    autocheckCount.textContent = `${i + 1}/${total}`;
    autocheckStatusDesc.textContent = `Тестирование: ${strat.name}...`;
    autocheckProgressBar.style.width = `${Math.round(((i + 1) / total) * 100)}%`;

    try {
      const res: StrategyBenchmarkResult = await invoke("benchmark_strategy", { strategy: strat });
      
      const row = document.createElement("div");
      if (res.success) {
        successResults.push(res);
        const isCurrentBest = res.latency_ms < bestLatency;
        if (isCurrentBest) {
          bestLatency = res.latency_ms;
          bestAutocheckStrategy = strat;
        }

        row.className = `benchmark-row success ${isCurrentBest ? "best" : ""}`;
        row.innerHTML = `
          <div>
            <strong>${strat.name}</strong>
            <div style="font-size: 11px; color: var(--text-secondary);">${strat.recommended_for}</div>
          </div>
          <div style="color: var(--accent-green); font-weight: 600;">⚡ ${res.latency_ms} ms ${isCurrentBest ? "🏆" : ""}</div>
        `;
      } else {
        row.className = "benchmark-row fail";
        row.innerHTML = `
          <div>
            <span>${strat.name}</span>
            <div style="font-size: 11px; color: var(--text-secondary);">Пакеты отфильтрованы ТСПУ</div>
          </div>
          <div style="color: #ef4444;">❌ Ошибка</div>
        `;
      }

      autocheckResultsContainer.prepend(row);
    } catch (e) {
      console.error("Strategy test error", e);
    }
  }

  isAutocheckRunning = false;

  if (bestAutocheckStrategy) {
    autocheckStatusDesc.innerHTML = `🏆 <strong>Лучшая стратегия найдена:</strong> ${bestAutocheckStrategy.name} (${bestLatency} ms)`;
    btnApplyBestStrategy.style.display = "block";
    btnApplyBestStrategy.textContent = `Применить (${bestAutocheckStrategy.name})`;
  } else {
    autocheckStatusDesc.textContent = "Не удалось подобрать рабочую стратегию. Попробуйте режим VLESS или WARP.";
  }
}

document.getElementById("btn-byedpi-autocheck")?.addEventListener("click", runAutoCheck);

document.getElementById("btn-cancel-autocheck")?.addEventListener("click", () => {
  isAutocheckRunning = false;
  modalAutocheck.style.display = "none";
});

btnApplyBestStrategy.addEventListener("click", async () => {
  if (bestAutocheckStrategy) {
    activeStrategy = bestAutocheckStrategy;
    await invoke("set_active_strategy", { args: bestAutocheckStrategy.args });
    if (byedpiSubtitle) {
      byedpiSubtitle.textContent = `SOCKS5 127.0.0.1:1080 • ${bestAutocheckStrategy.name}`;
    }
    modalAutocheck.style.display = "none";

    // Restart ByeDPI if currently running to apply winning strategy
    const status: ServiceStatus = await invoke("get_status");
    if (status.byedpi_running) {
      await invoke("stop_byedpi");
      await invoke("start_byedpi", { params: null });
      alert(`Стратегия "${bestAutocheckStrategy.name}" успешно применена и запущена!`);
    } else {
      alert(`Стратегия "${bestAutocheckStrategy.name}" выбрана как активная.`);
    }
  }
});

// Initial boot
loadStrategies();
loadConfigsAndTabs();
renderWarpConfig();
updateStatus();
setInterval(updateStatus, 2000);

