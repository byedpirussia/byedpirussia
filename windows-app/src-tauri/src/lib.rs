use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::fs::File;
use std::io::Write;
use std::path::PathBuf;
use std::process::{Child, Command};
use std::sync::Mutex;
use std::time::{Duration, Instant};
use tauri::{AppHandle, Manager, State};
use url::Url;
use winreg::enums::*;
use winreg::RegKey;

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct WarpConfig {
    pub private_key: String,
    pub public_key: String,
    pub address_v4: String,
    pub address_v6: String,
    pub endpoint: String,
    pub reserved: Option<Vec<u8>>,
}

impl Default for WarpConfig {
    fn default() -> Self {
        Self {
            private_key: "Wli/uh1ka24/qHSysIhhvdIqOKz58Gid0cEwlI0Nfhc=".to_string(),
            public_key: "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=".to_string(),
            address_v4: "172.16.0.2".to_string(),
            address_v6: "2606:4700:110:8700:31d8:595c:36c3:8014".to_string(),
            endpoint: "188.114.98.8:854".to_string(),
            reserved: None,
        }
    }
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct Strategy {
    pub id: String,
    pub name: String,
    pub description: String,
    pub args: String,
    pub recommended_for: String,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct StrategyBenchmarkResult {
    pub strategy: Strategy,
    pub success: bool,
    pub latency_ms: i64,
}

pub struct AppState {
    pub byedpi_process: Mutex<Option<Child>>,
    pub vless_process: Mutex<Option<Child>>,
    pub warp_process: Mutex<Option<Child>>,
    pub tun_process: Mutex<Option<Child>>,
    pub configs: Mutex<Vec<VlessConfig>>,
    pub selected_config_id: Mutex<Option<String>>,
    pub subscriptions: Mutex<Vec<String>>,
    pub warp_config: Mutex<WarpConfig>,
    pub active_strategy_args: Mutex<Option<String>>,
    pub tun_mode: Mutex<bool>,
}

impl Default for AppState {
    fn default() -> Self {
        Self {
            byedpi_process: Mutex::new(None),
            vless_process: Mutex::new(None),
            warp_process: Mutex::new(None),
            tun_process: Mutex::new(None),
            configs: Mutex::new(Vec::new()),
            selected_config_id: Mutex::new(None),
            subscriptions: Mutex::new(Vec::new()),
            warp_config: Mutex::new(WarpConfig::default()),
            active_strategy_args: Mutex::new(None),
            tun_mode: Mutex::new(false),
        }
    }
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct VlessConfig {
    pub id: String,
    pub subscription_url: String,
    pub name: String,
    pub protocol: String, // "vless" or "hysteria2"
    pub address: String,
    pub port: u16,
    pub uuid: String,
    pub flow: String,
    pub encryption: String,
    pub transport: String,
    pub security: String,
    pub sni: String,
    pub pbk: String,
    pub sid: String,
    pub fp: String,
    pub path: String,
    pub host: String,
    pub service_name: String,
    pub obfs: String,
    pub obfs_password: String,
    pub allow_insecure: bool,
    pub raw_uri: String,
}

#[derive(Serialize, Deserialize, Debug)]
pub struct ServiceStatus {
    pub byedpi_running: bool,
    pub vless_running: bool,
    pub warp_running: bool,
    pub system_proxy_enabled: bool,
    pub tun_mode: bool,
    pub selected_vless_id: Option<String>,
}

fn get_bin_dir(app: &AppHandle) -> PathBuf {
    if let Ok(resource_dir) = app.path().resource_dir() {
        let bin_path = resource_dir.join("bin");
        if bin_path.exists() {
            return bin_path;
        }
    }
    std::env::current_dir()
        .map(|d| d.join("src-tauri").join("bin"))
        .unwrap_or_else(|_| PathBuf::from("bin"))
}

// Windows Registry System Proxy Helper
fn set_windows_proxy(enable: bool, proxy_server: &str) -> Result<(), String> {
    let hkcu = RegKey::predef(HKEY_CURRENT_USER);
    let internet_settings = hkcu
        .open_subkey_with_flags(
            "Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings",
            KEY_SET_VALUE | KEY_QUERY_VALUE,
        )
        .map_err(|e| format!("Failed to open registry: {}", e))?;

    if enable {
        internet_settings
            .set_value("ProxyEnable", &1u32)
            .map_err(|e| format!("Failed to enable proxy: {}", e))?;
        internet_settings
            .set_value("ProxyServer", &proxy_server)
            .map_err(|e| format!("Failed to set proxy server: {}", e))?;
        internet_settings
            .set_value("ProxyOverride", &"<local>")
            .map_err(|e| format!("Failed to set proxy override: {}", e))?;
    } else {
        internet_settings
            .set_value("ProxyEnable", &0u32)
            .map_err(|e| format!("Failed to disable proxy: {}", e))?;
    }

    #[link(name = "wininet")]
    extern "system" {
        fn InternetSetOptionW(
            h_internet: *mut std::ffi::c_void,
            dw_option: u32,
            lp_buffer: *mut std::ffi::c_void,
            dw_buffer_length: u32,
        ) -> i32;
    }

    unsafe {
        InternetSetOptionW(std::ptr::null_mut(), 39, std::ptr::null_mut(), 0);
        InternetSetOptionW(std::ptr::null_mut(), 37, std::ptr::null_mut(), 0);
    }

    Ok(())
}

fn is_windows_proxy_enabled() -> bool {
    let hkcu = RegKey::predef(HKEY_CURRENT_USER);
    if let Ok(internet_settings) = hkcu.open_subkey_with_flags(
        "Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings",
        KEY_QUERY_VALUE,
    ) {
        if let Ok(enabled) = internet_settings.get_value::<u32, _>("ProxyEnable") {
            return enabled == 1;
        }
    }
    false
}

#[tauri::command]
fn get_status(state: State<'_, AppState>) -> ServiceStatus {
    let byedpi_running = state.byedpi_process.lock().unwrap().is_some();
    let vless_running = state.vless_process.lock().unwrap().is_some();
    let warp_running = state.warp_process.lock().unwrap().is_some();
    let system_proxy_enabled = is_windows_proxy_enabled();
    let tun_mode = *state.tun_mode.lock().unwrap();
    let selected_vless_id = state.selected_config_id.lock().unwrap().clone();

    ServiceStatus {
        byedpi_running,
        vless_running,
        warp_running,
        system_proxy_enabled,
        tun_mode,
        selected_vless_id,
    }
}

// TUN Device Helper (tun2socks + wintun)
fn start_tun_device(app: &AppHandle, state: &AppState, socks_addr: &str) -> Result<(), String> {
    let mut tun_lock = state.tun_process.lock().unwrap();
    if tun_lock.is_some() {
        return Ok(());
    }

    let bin_dir = get_bin_dir(app);
    let tun_exe = bin_dir.join("tun2socks.exe");

    if !tun_exe.exists() {
        return Err(format!("tun2socks.exe не найден: {:?}", tun_exe));
    }

    #[cfg(windows)]
    use std::os::windows::process::CommandExt;
    const CREATE_NO_WINDOW: u32 = 0x08000000;

    let proxy_url = format!("socks5://{}", socks_addr);
    let child = Command::new(&tun_exe)
        .args(&[
            "-device", "tun://byedpi-tun",
            "-proxy", &proxy_url,
            "-loglevel", "warning"
        ])
        .current_dir(&bin_dir)
        .creation_flags(CREATE_NO_WINDOW)
        .spawn()
        .map_err(|e| format!("Ошибка запуска tun2socks (требуются права Администратора): {}", e))?;

    *tun_lock = Some(child);

    // Даем Wintun время на регистрацию интерфейса
    std::thread::sleep(Duration::from_millis(800));

    // Настраиваем IP адрес и шлюз на адаптере byedpi-tun
    let _ = Command::new("netsh")
        .args(&["interface", "ip", "set", "address", "name=byedpi-tun", "static", "198.18.0.1", "255.255.0.0", "none"])
        .creation_flags(CREATE_NO_WINDOW)
        .output();

    // Устанавливаем DNS на адаптере
    let _ = Command::new("netsh")
        .args(&["interface", "ip", "set", "dns", "name=byedpi-tun", "static", "1.1.1.1"])
        .creation_flags(CREATE_NO_WINDOW)
        .output();

    // Маршрутизируем интернет-трафик в TUN
    let _ = Command::new("route")
        .args(&["add", "0.0.0.0", "mask", "128.0.0.0", "198.18.0.1", "metric", "1"])
        .creation_flags(CREATE_NO_WINDOW)
        .output();

    let _ = Command::new("route")
        .args(&["add", "128.0.0.0", "mask", "128.0.0.0", "198.18.0.1", "metric", "1"])
        .creation_flags(CREATE_NO_WINDOW)
        .output();

    Ok(())
}

fn stop_tun_device(state: &AppState) {
    let mut tun_lock = state.tun_process.lock().unwrap();
    if let Some(mut child) = tun_lock.take() {
        let _ = child.kill();
    }

    #[cfg(windows)]
    use std::os::windows::process::CommandExt;
    const CREATE_NO_WINDOW: u32 = 0x08000000;

    let _ = Command::new("route")
        .args(&["delete", "0.0.0.0", "mask", "128.0.0.0", "198.18.0.1"])
        .creation_flags(CREATE_NO_WINDOW)
        .output();

    let _ = Command::new("route")
        .args(&["delete", "128.0.0.0", "mask", "128.0.0.0", "198.18.0.1"])
        .creation_flags(CREATE_NO_WINDOW)
        .output();
}

#[tauri::command]
fn start_byedpi(app: AppHandle, state: State<'_, AppState>, params: Option<String>) -> Result<String, String> {
    let mut lock = state.byedpi_process.lock().unwrap();
    if lock.is_some() {
        return Ok("ByeDPI уже запущен".into());
    }

    let bin_dir = get_bin_dir(&app);
    let ciadpi_exe = bin_dir.join("ciadpi.exe");

    if !ciadpi_exe.exists() {
        return Err(format!("Исполняемый файл не найден: {:?}", ciadpi_exe));
    }

    let active_strat = state.active_strategy_args.lock().unwrap().clone();
    let default_args = "-i 127.0.0.1 -p 1080 -d1 -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -r1+s".to_string();

    let args_str = match params {
        Some(p) if !p.trim().is_empty() => p,
        _ => {
            if let Some(strat_args) = active_strat {
                format!("-i 127.0.0.1 -p 1080 {}", strat_args)
            } else {
                default_args
            }
        }
    };
    let args: Vec<&str> = args_str.split_whitespace().collect();

    #[cfg(windows)]
    use std::os::windows::process::CommandExt;
    const CREATE_NO_WINDOW: u32 = 0x08000000;

    let child = Command::new(&ciadpi_exe)
        .args(&args)
        .creation_flags(CREATE_NO_WINDOW)
        .spawn()
        .map_err(|e| format!("Ошибка запуска ciadpi.exe: {}", e))?;

    *lock = Some(child);

    let is_tun = *state.tun_mode.lock().unwrap();
    if is_tun {
        let _ = start_tun_device(&app, &state, "127.0.0.1:1080");
    } else {
        let _ = set_windows_proxy(true, "socks=127.0.0.1:1080");
    }

    Ok("ByeDPI успешно запущен (127.0.0.1:1080)".into())
}

#[tauri::command]
fn stop_byedpi(state: State<'_, AppState>) -> Result<String, String> {
    let mut lock = state.byedpi_process.lock().unwrap();
    if let Some(mut child) = lock.take() {
        let _ = child.kill();
    }
    stop_tun_device(&state);
    if state.vless_process.lock().unwrap().is_none() && state.warp_process.lock().unwrap().is_none() {
        let _ = set_windows_proxy(false, "");
    }
    Ok("ByeDPI остановлен".into())
}

// ---------------- ByeDPI Strategy Auto-Check & Catalog ----------------

#[tauri::command]
fn get_byedpi_strategies() -> Vec<Strategy> {
    vec![
        Strategy {
            id: "cascade_10".into(),
            name: "Каскадный сплит (10 шагов + TLSrec)".into(),
            description: "Глубокая фрагментация пакетов на 10 интервалах с разворотом очереди и сплитом TLS записи.".into(),
            args: "-d1 -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -r1+s".into(),
            recommended_for: "YouTube, Discord".into(),
        },
        Strategy {
            id: "cascade_10_md5".into(),
            name: "Каскадный сплит 10 + MD5Sig + Fake UDP".into(),
            description: "10 интервалов каскада с подменой контрольной суммы MD5 и фейковыми UDP пакетами.".into(),
            args: "-d1 -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -r1+s -S -a1".into(),
            recommended_for: "ТСПУ, YouTube, Discord".into(),
        },
        Strategy {
            id: "cascade_double_auto".into(),
            name: "Двойной каскад с автопереключением (Auto TLS)".into(),
            description: "Двухуровневый каскад с автоматическим переходом на резервный профиль при ошибках TLS/RST.".into(),
            args: "-d1 -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -r1+s -S -a1 -As -d1 -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -S -a1".into(),
            recommended_for: "YouTube 4K, Ростелеком, Дом.ru".into(),
        },
        Strategy {
            id: "multilevel_cascade".into(),
            name: "Многоуровневый каскад (Disorder + Split)".into(),
            description: "Чередование сплита и перестановки на возрастающих смещениях от начала SNI.".into(),
            args: "-d1 -s1+s -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s".into(),
            recommended_for: "YouTube, Discord".into(),
        },
        Strategy {
            id: "double_cascade".into(),
            name: "Двойной каскад".into(),
            description: "Комбинированный каскадный сплит с малыми шагами для сложных DPI-фильтров.".into(),
            args: "-d1 -s1+s -d1+s -s3+s -d6+s -s12+s -d14+s -s20+s -d24+s -s30+s".into(),
            recommended_for: "YouTube 4K, Telegram".into(),
        },
        Strategy {
            id: "progressive_split".into(),
            name: "Прогрессивный мультисплит".into(),
            description: "Последовательное разбиение пакета на 8 сегментов после заголовка SNI.".into(),
            args: "-d1 -s1+s -s3+s -s6+s -s9+s -s12+s -s15+s -s20+s -s30+s".into(),
            recommended_for: "YouTube, Браузер".into(),
        },
        Strategy {
            id: "fast_tlsrec".into(),
            name: "Быстрый TLSrec (YouTube / Discord)".into(),
            description: "Разбиение TLS записи с последующей перестановкой фрагментов SNI. Высокая скорость.".into(),
            args: "-s1 -d3+s -r1+s".into(),
            recommended_for: "Discord, Голос, YouTube".into(),
        },
        Strategy {
            id: "rhythmic_disoob_tlsrec".into(),
            name: "Ритмичный Disoob + TLSrec + HTTP mod".into(),
            description: "Чередование коротких сплитов, TLS записей и модификаций HTTP-заголовков.".into(),
            args: "-q2 -s2 -s3+s -r3 -s4 -r4 -s5+s -r5+s -s6 -s7+s -r8 -s9+s -Mh,d,r -a1 -At,r -s2+s -r2 -d2 -s3 -r3 -r4 -s4 -d5+s -r5 -d6 -s7+s -d7 -a1".into(),
            recommended_for: "Discord, Голосовые каналы, Twitch".into(),
        },
        Strategy {
            id: "fake_ttl8".into(),
            name: "Fake SNI (Google) + TTL 8".into(),
            description: "Отправка фиктивного ClientHello с SNI google.com с последующим сплитом TLS записи.".into(),
            args: "-f-1 -n www.google.com -s2+s -r3 -t4".into(),
            recommended_for: "ТСПУ обход (все сервисы)".into(),
        },
        Strategy {
            id: "fake_tlsrec_ttl8".into(),
            name: "Фейк + TLSrec (TTL 8)".into(),
            description: "Фейковый пакет с TTL 8 и фрагментацией TLS записи.".into(),
            args: "-d1 -s1+s -r1+s -f-1 -t8".into(),
            recommended_for: "ТСПУ, YouTube".into(),
        },
        Strategy {
            id: "disoob_minimal".into(),
            name: "Минимальный Disoob + Disorder + Split".into(),
            description: "Легковесная комбинация с минимальным оверхедом и высокой пропускной способностью.".into(),
            args: "-d1 -s1 -q1 -a1".into(),
            recommended_for: "Высокая скорость, Игры, Звонки".into(),
        },
        Strategy {
            id: "ultra_cascade_fibonacci".into(),
            name: "Ультра-каскад 12 шагов (Фибоначчи)".into(),
            description: "Разбиение по возрастающей шкале смещений (1, 2, 3, 5, 8, 12, 17, 23, 30, 40, 50).".into(),
            args: "-d1 -s1+s -d2+s -s3+s -d5+s -s8+s -d12+s -s17+s -d23+s -s30+s -d40+s -s50+s -r1+s -a1".into(),
            recommended_for: "YouTube, ТСПУ".into(),
        },
    ]
}

#[tauri::command]
fn set_active_strategy(args: String, state: State<'_, AppState>) -> Result<(), String> {
    let mut strat = state.active_strategy_args.lock().unwrap();
    *strat = Some(args);
    Ok(())
}

#[tauri::command]
async fn benchmark_strategy(app: AppHandle, strategy: Strategy) -> StrategyBenchmarkResult {
    let bin_dir = get_bin_dir(&app);
    let ciadpi_exe = bin_dir.join("ciadpi.exe");

    if !ciadpi_exe.exists() {
        return StrategyBenchmarkResult {
            strategy,
            success: false,
            latency_ms: -1,
        };
    }

    const TEST_PORT: u16 = 11080;
    let full_args = format!("-i 127.0.0.1 -p {} -X {}", TEST_PORT, strategy.args);
    let args: Vec<&str> = full_args.split_whitespace().collect();

    #[cfg(windows)]
    use std::os::windows::process::CommandExt;
    const CREATE_NO_WINDOW: u32 = 0x08000000;

    let child = Command::new(&ciadpi_exe)
        .args(&args)
        .creation_flags(CREATE_NO_WINDOW)
        .spawn();

    let mut child = match child {
        Ok(c) => c,
        Err(_) => {
            return StrategyBenchmarkResult {
                strategy,
                success: false,
                latency_ms: -1,
            }
        }
    };

    tokio::time::sleep(Duration::from_millis(150)).await;

    let start = Instant::now();
    let targets = vec!["142.251.152.4:443", "162.159.137.232:443"];
    let mut total_latency = 0i64;
    let mut successes = 0;

    for target in targets {
        let proxy_addr = format!("127.0.0.1:{}", TEST_PORT);
        if let Ok(mut stream) = tokio::net::TcpStream::connect(&proxy_addr).await {
            use tokio::io::{AsyncReadExt, AsyncWriteExt};
            if stream.write_all(&[0x05, 0x01, 0x00]).await.is_ok() {
                let mut buf = [0u8; 2];
                if stream.read_exact(&mut buf).await.is_ok() && buf[0] == 0x05 && buf[1] == 0x00 {
                    let parts: Vec<&str> = target.split(':').collect();
                    if parts.len() == 2 {
                        let ip_parts: Vec<u8> = parts[0].split('.').filter_map(|p| p.parse().ok()).collect();
                        let port: u16 = parts[1].parse().unwrap_or(443);
                        if ip_parts.len() == 4 {
                            let mut req = vec![0x05, 0x01, 0x00, 0x01];
                            req.extend_from_slice(&ip_parts);
                            req.extend_from_slice(&port.to_be_bytes());
                            if stream.write_all(&req).await.is_ok() {
                                let mut resp = [0u8; 10];
                                if stream.read_exact(&mut resp).await.is_ok() && resp[1] == 0x00 {
                                    successes += 1;
                                    total_latency += start.elapsed().as_millis() as i64;
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    let _ = child.kill();

    if successes > 0 {
        StrategyBenchmarkResult {
            strategy,
            success: true,
            latency_ms: total_latency / successes,
        }
    } else {
        StrategyBenchmarkResult {
            strategy,
            success: false,
            latency_ms: -1,
        }
    }
}

// ---------------- Proxy Parsers & Sing-Box Config Generator ----------------

fn parse_proxy_uri(uri_str: &str, subscription_url: &str) -> Option<VlessConfig> {
    let trimmed = uri_str.trim();
    
    if trimmed.starts_with("hy2://") || trimmed.starts_with("hysteria2://") {
        let url = Url::parse(trimmed).ok()?;
        let password = url.username().to_string();
        let address = url.host_str()?.to_string();
        let port = url.port().unwrap_or(443);
        let fragment = url.fragment().unwrap_or(&address).to_string();
        let name = percent_encoding::percent_decode_str(&fragment)
            .decode_utf8_lossy()
            .to_string();

        let query_pairs: HashMap<_, _> = url.query_pairs().into_owned().collect();
        let sni = query_pairs.get("sni").or_else(|| query_pairs.get("peer")).cloned().unwrap_or_else(|| address.clone());
        let obfs = query_pairs.get("obfs").cloned().unwrap_or_default();
        let obfs_password = query_pairs.get("obfs-password").or_else(|| query_pairs.get("obfs_password")).cloned().unwrap_or_default();
        let insecure = query_pairs.get("insecure").map(|v| v == "1" || v.eq_ignore_ascii_case("true")).unwrap_or(false);

        return Some(VlessConfig {
            id: uuid::Uuid::new_v4().to_string(),
            subscription_url: subscription_url.to_string(),
            name: if name.is_empty() { format!("Hysteria2 - {}:{}", address, port) } else { name },
            protocol: "hysteria2".to_string(),
            address,
            port,
            uuid: password,
            flow: String::new(),
            encryption: String::new(),
            transport: "udp".to_string(),
            security: "tls".to_string(),
            sni,
            pbk: String::new(),
            sid: String::new(),
            fp: "chrome".to_string(),
            path: String::new(),
            host: String::new(),
            service_name: String::new(),
            obfs,
            obfs_password,
            allow_insecure: insecure,
            raw_uri: trimmed.to_string(),
        });
    }

    if trimmed.starts_with("vless://") {
        let url = Url::parse(trimmed).ok()?;
        let uuid = url.username().to_string();
        let address = url.host_str()?.to_string();
        let port = url.port().unwrap_or(443);
        let fragment = url.fragment().unwrap_or(&address).to_string();
        let name = percent_encoding::percent_decode_str(&fragment)
            .decode_utf8_lossy()
            .to_string();

        let query_pairs: HashMap<_, _> = url.query_pairs().into_owned().collect();

        let flow = query_pairs.get("flow").cloned().unwrap_or_default();
        let encryption = query_pairs.get("encryption").cloned().unwrap_or_else(|| "none".to_string());
        let transport = query_pairs.get("type").cloned().unwrap_or_else(|| "tcp".to_string());
        let security = query_pairs.get("security").cloned().unwrap_or_else(|| "none".to_string());
        let sni = query_pairs
            .get("sni")
            .or_else(|| query_pairs.get("peer"))
            .cloned()
            .unwrap_or_default();
        let pbk = query_pairs.get("pbk").cloned().unwrap_or_default();
        let sid = query_pairs.get("sid").cloned().unwrap_or_default();
        let fp = query_pairs.get("fp").cloned().unwrap_or_else(|| "chrome".to_string());
        let path = query_pairs.get("path").cloned().unwrap_or_default();
        let host = query_pairs.get("host").cloned().unwrap_or_default();
        let service_name = query_pairs.get("serviceName").cloned().unwrap_or_default();
        let insecure = query_pairs.get("allowInsecure").map(|v| v == "1" || v.eq_ignore_ascii_case("true")).unwrap_or(false);

        return Some(VlessConfig {
            id: uuid::Uuid::new_v4().to_string(),
            subscription_url: subscription_url.to_string(),
            name: if name.is_empty() { format!("{}:{}", address, port) } else { name },
            protocol: "vless".to_string(),
            address,
            port,
            uuid,
            flow,
            encryption,
            transport,
            security,
            sni,
            pbk,
            sid,
            fp,
            path,
            host,
            service_name,
            obfs: String::new(),
            obfs_password: String::new(),
            allow_insecure: insecure,
            raw_uri: trimmed.to_string(),
        });
    }

    None
}

fn generate_singbox_config(cfg: &VlessConfig, mixed_port: u16, http_port: u16) -> serde_json::Value {
    let mut outbounds = Vec::new();

    if cfg.protocol == "hysteria2" {
        let server_name = if !cfg.sni.is_empty() { &cfg.sni } else { &cfg.address };
        let mut hy2_obj = serde_json::json!({
            "type": "hysteria2",
            "tag": "proxy",
            "server": &cfg.address,
            "server_port": cfg.port,
            "password": &cfg.uuid,
            "tls": {
                "enabled": true,
                "server_name": server_name,
                "insecure": cfg.allow_insecure
            }
        });

        if !cfg.obfs.is_empty() {
            hy2_obj["obfs"] = serde_json::json!({
                "type": &cfg.obfs,
                "password": &cfg.obfs_password
            });
        }
        outbounds.push(hy2_obj);
    } else {
        // VLESS
        let is_reality = cfg.security.eq_ignore_ascii_case("reality") || !cfg.pbk.is_empty();
        let is_tls = is_reality || cfg.security.eq_ignore_ascii_case("tls") || !cfg.sni.is_empty() || cfg.port == 443;
        let server_name = if !cfg.sni.is_empty() {
            &cfg.sni
        } else if !cfg.host.is_empty() {
            &cfg.host
        } else {
            &cfg.address
        };

        let mut tls_json = serde_json::json!({
            "enabled": is_tls,
            "server_name": server_name,
            "insecure": cfg.allow_insecure,
            "utls": {
                "enabled": true,
                "fingerprint": if !cfg.fp.is_empty() { &cfg.fp } else { "chrome" }
            }
        });

        if is_reality {
            tls_json["reality"] = serde_json::json!({
                "enabled": true,
                "public_key": &cfg.pbk,
                "short_id": &cfg.sid
            });
        }

        let mut vless_obj = serde_json::json!({
            "type": "vless",
            "tag": "proxy",
            "server": &cfg.address,
            "server_port": cfg.port,
            "uuid": &cfg.uuid,
            "flow": if !cfg.flow.is_empty() { &cfg.flow } else { "" },
            "tls": tls_json
        });

        if cfg.transport.eq_ignore_ascii_case("ws") {
            vless_obj["transport"] = serde_json::json!({
                "type": "ws",
                "path": &cfg.path,
                "headers": {
                    "Host": if !cfg.host.is_empty() { &cfg.host } else { server_name }
                }
            });
        } else if cfg.transport.eq_ignore_ascii_case("grpc") {
            vless_obj["transport"] = serde_json::json!({
                "type": "grpc",
                "service_name": &cfg.service_name
            });
        }

        outbounds.push(vless_obj);
    }

    outbounds.push(serde_json::json!({
        "type": "direct",
        "tag": "direct"
    }));

    serde_json::json!({
        "log": {
            "level": "warn"
        },
        "inbounds": [
            {
                "type": "mixed",
                "tag": "mixed-in",
                "listen": "127.0.0.1",
                "listen_port": mixed_port
            },
            {
                "type": "http",
                "tag": "http-in",
                "listen": "127.0.0.1",
                "listen_port": http_port
            }
        ],
        "outbounds": outbounds
    })
}

#[tauri::command]
fn start_vless(app: AppHandle, state: State<'_, AppState>) -> Result<String, String> {
    let mut vless_lock = state.vless_process.lock().unwrap();
    if vless_lock.is_some() {
        return Ok("Сервер уже запущен".into());
    }

    let configs = state.configs.lock().unwrap();
    let selected_id = state.selected_config_id.lock().unwrap().clone();
    let active_config = match selected_id {
        Some(id) => configs.iter().find(|c| c.id == id).cloned(),
        None => configs.first().cloned(),
    };

    let active_config = match active_config {
        Some(cfg) => cfg,
        None => return Err("Нет выбранного сервера VLESS / Hysteria2".into()),
    };

    let bin_dir = get_bin_dir(&app);
    let singbox_exe = bin_dir.join("sing-box.exe");

    if !singbox_exe.exists() {
        return Err(format!("sing-box.exe не найден: {:?}", singbox_exe));
    }

    // Config: Mixed SOCKS5+HTTP on 10808, HTTP on 10809
    let singbox_json = generate_singbox_config(&active_config, 10808, 10809);
    let temp_config_path = std::env::temp_dir().join("byedpi_proxy_config.json");
    let mut file = File::create(&temp_config_path)
        .map_err(|e| format!("Не удалось создать конфиг: {}", e))?;
    file.write_all(serde_json::to_string_pretty(&singbox_json).unwrap().as_bytes())
        .map_err(|e| format!("Ошибка записи конфига: {}", e))?;

    #[cfg(windows)]
    use std::os::windows::process::CommandExt;
    const CREATE_NO_WINDOW: u32 = 0x08000000;

    let child = Command::new(&singbox_exe)
        .args(&["run", "-c", temp_config_path.to_str().unwrap()])
        .creation_flags(CREATE_NO_WINDOW)
        .spawn()
        .map_err(|e| format!("Ошибка запуска sing-box: {}", e))?;

    *vless_lock = Some(child);

    let is_tun = *state.tun_mode.lock().unwrap();
    if is_tun {
        let _ = start_tun_device(&app, &state, "127.0.0.1:10808");
    } else {
        let _ = set_windows_proxy(true, "http=127.0.0.1:10809;https=127.0.0.1:10809;socks=127.0.0.1:10808");
    }

    Ok(format!("{}: {}", if active_config.protocol == "hysteria2" { "Hysteria2" } else { "VLESS" }, active_config.name))
}

#[tauri::command]
fn stop_vless(state: State<'_, AppState>) -> Result<String, String> {
    let mut lock = state.vless_process.lock().unwrap();
    if let Some(mut child) = lock.take() {
        let _ = child.kill();
    }
    stop_tun_device(&state);
    if state.byedpi_process.lock().unwrap().is_none() && state.warp_process.lock().unwrap().is_none() {
        let _ = set_windows_proxy(false, "");
    }
    Ok("Соединение остановлено".into())
}

// ---------------- WARP (WireGuard) Commands (Xray Core) ----------------

fn generate_warp_xray_config(warp_cfg: &WarpConfig, http_port: u16, socks_port: u16) -> serde_json::Value {
    let mut peer_json = serde_json::json!({
        "publicKey": warp_cfg.public_key,
        "endpoint": warp_cfg.endpoint
    });

    if let Some(ref res) = warp_cfg.reserved {
        peer_json["reserved"] = serde_json::json!(res);
    }

    let endpoint_host = warp_cfg.endpoint.split(':').next().unwrap_or(&warp_cfg.endpoint);

    serde_json::json!({
        "log": {
            "loglevel": "warning"
        },
        "inbounds": [
            {
                "tag": "socks-in",
                "port": socks_port,
                "listen": "127.0.0.1",
                "protocol": "socks",
                "settings": {
                    "auth": "noauth",
                    "udp": true
                },
                "sniffing": {
                    "enabled": true,
                    "destOverride": ["http", "tls", "quic"]
                }
            },
            {
                "tag": "http-in",
                "port": http_port,
                "listen": "127.0.0.1",
                "protocol": "http",
                "sniffing": {
                    "enabled": true,
                    "destOverride": ["http", "tls", "quic"]
                }
            }
        ],
        "outbounds": [
            {
                "tag": "proxy",
                "protocol": "wireguard",
                "settings": {
                    "secretKey": warp_cfg.private_key,
                    "address": [
                        format!("{}/32", warp_cfg.address_v4),
                        format!("{}/128", warp_cfg.address_v6)
                    ],
                    "peers": [peer_json]
                }
            },
            {
                "tag": "direct",
                "protocol": "freedom"
            }
        ],
        "dns": {
            "servers": [
                "1.1.1.1",
                "8.8.8.8"
            ]
        },
        "routing": {
            "domainStrategy": "IPIfNonMatch",
            "rules": [
                {
                    "type": "field",
                    "outboundTag": "direct",
                    "ip": ["geoip:private"]
                },
                {
                    "type": "field",
                    "outboundTag": "direct",
                    "ip": [endpoint_host]
                },
                {
                    "type": "field",
                    "outboundTag": "proxy",
                    "network": "tcp,udp"
                }
            ]
        }
    })
}

#[tauri::command]
fn get_warp_config(state: State<'_, AppState>) -> WarpConfig {
    state.warp_config.lock().unwrap().clone()
}

#[tauri::command]
fn get_warp_amnezia_wg_text(state: State<'_, AppState>) -> String {
    let cfg = state.warp_config.lock().unwrap();
    format!(
        r#"[Interface]
PrivateKey = {}
Address = {}/32, {}/128
DNS = 1.1.1.1, 1.0.0.1, 2606:4700:4700::1111, 2606:4700:4700::1001
MTU = 1280
S1 = 0
S2 = 0
S3 = 0
S4 = 0
Jc = 4
Jmin = 40
Jmax = 70
H1 = 1
H2 = 2
H3 = 3
H4 = 4

[Peer]
PublicKey = {}
AllowedIPs = 0.0.0.0/0, ::/0
Endpoint = {}"#,
        cfg.private_key, cfg.address_v4, cfg.address_v6, cfg.public_key, cfg.endpoint
    )
}

#[tauri::command]
async fn generate_warp_profile(state: State<'_, AppState>) -> Result<WarpConfig, String> {
    let endpoints = [
        "https://www.warp-generator.workers.dev/",
        "https://warp.sub-aggregator.workers.dev/",
        "https://warp-gen.netlify.app/",
        "https://warp-vercel-chi.vercel.app/api/warp-data",
        "https://warp-vercel-murex.vercel.app/api/warp-data",
    ];

    let prefixes = [
        "162.159.192.", "162.159.195.", "188.114.96.", "188.114.97.", "188.114.98."
    ];
    let ports = [500, 854, 859, 864, 878, 880, 890, 891, 894, 903, 908, 928, 934, 939, 4500, 5279];

    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(8))
        .build()
        .map_err(|e| e.to_string())?;

    let mut last_err = String::new();
    let mut fetched_json: Option<serde_json::Value> = None;

    for ep in endpoints {
        match client.get(ep)
            .header("X-Client", "WARP")
            .header("User-Agent", "WARP-Generator/2.0")
            .send()
            .await 
        {
            Ok(resp) => {
                if resp.status().is_success() {
                    if let Ok(body) = resp.text().await {
                        if let Ok(json) = serde_json::from_str::<serde_json::Value>(&body) {
                            if json.get("privKey").is_some() || json.get("private_key").is_some() {
                                fetched_json = Some(json);
                                break;
                            }
                        }
                    }
                }
            }
            Err(e) => {
                last_err = e.to_string();
            }
        }
    }

    let json = match fetched_json {
        Some(j) => j,
        None => return Err(format!("Не удалось получить ключи WARP от серверов: {}", last_err)),
    };

    let priv_key = json.get("privKey")
        .or_else(|| json.get("private_key"))
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();

    let peer_pub = json.get("peer_pub")
        .or_else(|| json.get("public_key"))
        .and_then(|v| v.as_str())
        .unwrap_or("bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=")
        .to_string();

    let ipv4 = json.get("client_ipv4")
        .or_else(|| json.get("v4"))
        .and_then(|v| v.as_str())
        .unwrap_or("172.16.0.2")
        .to_string();

    let ipv6 = json.get("client_ipv6")
        .or_else(|| json.get("v6"))
        .and_then(|v| v.as_str())
        .unwrap_or("2606:4700:110:8700:31d8:595c:36c3:8014")
        .to_string();

    let now_nanos = Instant::now().elapsed().as_nanos() as usize;
    let prefix = prefixes[now_nanos % prefixes.len()];
    let port = ports[(now_nanos / 7) % ports.len()];
    let host_num = ((now_nanos / 13) % 10) + 1;
    let endpoint = format!("{}{}:{}", prefix, host_num, port);

    let new_warp = WarpConfig {
        private_key: priv_key,
        public_key: peer_pub,
        address_v4: ipv4,
        address_v6: ipv6,
        endpoint,
        reserved: None,
    };

    let mut cur = state.warp_config.lock().unwrap();
    *cur = new_warp.clone();

    Ok(new_warp)
}

#[tauri::command]
fn start_warp(app: AppHandle, state: State<'_, AppState>) -> Result<String, String> {
    let mut warp_lock = state.warp_process.lock().unwrap();
    if warp_lock.is_some() {
        return Ok("WARP уже запущен".into());
    }

    let bin_dir = get_bin_dir(&app);
    let xray_exe = bin_dir.join("xray.exe");

    if !xray_exe.exists() {
        return Err(format!("xray.exe не найден: {:?}", xray_exe));
    }

    let warp_cfg = state.warp_config.lock().unwrap().clone();
    let warp_json = generate_warp_xray_config(&warp_cfg, 10819, 10818);
    let temp_config_path = std::env::temp_dir().join("byedpi_warp_config.json");
    let mut file = File::create(&temp_config_path)
        .map_err(|e| format!("Не удалось создать конфиг: {}", e))?;
    file.write_all(serde_json::to_string_pretty(&warp_json).unwrap().as_bytes())
        .map_err(|e| format!("Ошибка записи конфига: {}", e))?;

    #[cfg(windows)]
    use std::os::windows::process::CommandExt;
    const CREATE_NO_WINDOW: u32 = 0x08000000;

    let child = Command::new(&xray_exe)
        .args(&["run", "-c", temp_config_path.to_str().unwrap()])
        .creation_flags(CREATE_NO_WINDOW)
        .spawn()
        .map_err(|e| format!("Ошибка запуска WARP: {}", e))?;

    *warp_lock = Some(child);

    let is_tun = *state.tun_mode.lock().unwrap();
    if is_tun {
        let _ = start_tun_device(&app, &state, "127.0.0.1:10818");
    } else {
        let _ = set_windows_proxy(true, "http=127.0.0.1:10819;https=127.0.0.1:10819;socks=127.0.0.1:10818");
    }

    Ok(format!("Cloudflare WARP подключен ({})", warp_cfg.endpoint))
}

#[tauri::command]
fn stop_warp(state: State<'_, AppState>) -> Result<String, String> {
    let mut lock = state.warp_process.lock().unwrap();
    if let Some(mut child) = lock.take() {
        let _ = child.kill();
    }
    stop_tun_device(&state);
    if state.byedpi_process.lock().unwrap().is_none() && state.vless_process.lock().unwrap().is_none() {
        let _ = set_windows_proxy(false, "");
    }
    Ok("WARP остановлен".into())
}

#[tauri::command]
fn add_vless_key(uri: String, state: State<'_, AppState>) -> Result<VlessConfig, String> {
    let cfg = parse_proxy_uri(&uri, "")
        .ok_or_else(|| "Неверный формат ссылки (поддерживаются vless://, hy2://, hysteria2://)".to_string())?;

    let mut configs = state.configs.lock().unwrap();
    configs.retain(|c| c.raw_uri != cfg.raw_uri);
    configs.insert(0, cfg.clone());

    let mut sel = state.selected_config_id.lock().unwrap();
    if sel.is_none() {
        *sel = Some(cfg.id.clone());
    }

    Ok(cfg)
}

#[tauri::command]
async fn fetch_subscription(url: String, state: State<'_, AppState>) -> Result<Vec<VlessConfig>, String> {
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(10))
        .build()
        .map_err(|e| e.to_string())?;

    let res = client
        .get(&url)
        .header("User-Agent", "ByeDPI-Russia/1.1.5")
        .send()
        .await
        .map_err(|e| format!("Ошибка сети: {}", e))?;

    let text = res.text().await.map_err(|e| e.to_string())?;

    let decoded = if let Ok(bytes) = base64::Engine::decode(&base64::engine::general_purpose::STANDARD, text.trim()) {
        String::from_utf8_lossy(&bytes).to_string()
    } else {
        text
    };

    let mut parsed_configs = Vec::new();
    for line in decoded.lines() {
        let line = line.trim();
        if let Some(cfg) = parse_proxy_uri(line, &url) {
            parsed_configs.push(cfg);
        }
    }

    if parsed_configs.is_empty() {
        return Err("В подписке не найдено серверов VLESS / Hysteria2".into());
    }

    {
        let mut subs = state.subscriptions.lock().unwrap();
        if !subs.contains(&url) {
            subs.push(url.clone());
        }
    }

    {
        let mut configs = state.configs.lock().unwrap();
        configs.retain(|c| c.subscription_url != url);
        configs.extend(parsed_configs.clone());

        let mut sel = state.selected_config_id.lock().unwrap();
        if sel.is_none() && !configs.is_empty() {
            *sel = Some(configs[0].id.clone());
        }
    }

    Ok(parsed_configs)
}

#[tauri::command]
fn get_configs(state: State<'_, AppState>) -> Vec<VlessConfig> {
    state.configs.lock().unwrap().clone()
}

#[tauri::command]
fn select_config(id: String, state: State<'_, AppState>) -> Result<(), String> {
    let mut sel = state.selected_config_id.lock().unwrap();
    *sel = Some(id);
    Ok(())
}

#[tauri::command]
fn get_subscriptions(state: State<'_, AppState>) -> Vec<String> {
    state.subscriptions.lock().unwrap().clone()
}

#[tauri::command]
fn delete_subscription(url: String, state: State<'_, AppState>) -> Result<(), String> {
    state.subscriptions.lock().unwrap().retain(|s| s != &url);
    state.configs.lock().unwrap().retain(|c| c.subscription_url != url);
    Ok(())
}

#[tauri::command]
fn delete_config(id: String, state: State<'_, AppState>) -> Result<(), String> {
    state.configs.lock().unwrap().retain(|c| c.id != id);
    let mut sel = state.selected_config_id.lock().unwrap();
    if sel.as_deref() == Some(&id) {
        *sel = state.configs.lock().unwrap().first().map(|c| c.id.clone());
    }
    Ok(())
}

#[tauri::command]
fn toggle_system_proxy(enable: bool, state: State<'_, AppState>) -> Result<bool, String> {
    if enable {
        let byedpi_running = state.byedpi_process.lock().unwrap().is_some();
        let vless_running = state.vless_process.lock().unwrap().is_some();
        let warp_running = state.warp_process.lock().unwrap().is_some();

        let proxy_str = if vless_running {
            "http=127.0.0.1:10809;https=127.0.0.1:10809;socks=127.0.0.1:10808"
        } else if warp_running {
            "http=127.0.0.1:10819;https=127.0.0.1:10819;socks=127.0.0.1:10818"
        } else if byedpi_running {
            "socks=127.0.0.1:1080"
        } else {
            "socks=127.0.0.1:1080"
        };
        set_windows_proxy(true, proxy_str)?;
    } else {
        set_windows_proxy(false, "")?;
    }
    Ok(is_windows_proxy_enabled())
}

#[tauri::command]
async fn ping_server(address: String, port: u16) -> i64 {
    let start = Instant::now();
    let socket_addr = format!("{}:{}", address, port);
    match tokio::time::timeout(Duration::from_millis(3000), tokio::net::TcpStream::connect(&socket_addr)).await {
        Ok(Ok(_)) => start.elapsed().as_millis() as i64,
        _ => -1,
    }
}

#[tauri::command]
fn toggle_tun_mode(app: AppHandle, state: State<'_, AppState>, enable: bool) -> Result<bool, String> {
    let mut tun_mode = state.tun_mode.lock().unwrap();
    *tun_mode = enable;
    let enabled = *tun_mode;
    drop(tun_mode);

    let byedpi_running = state.byedpi_process.lock().unwrap().is_some();
    let vless_running = state.vless_process.lock().unwrap().is_some();
    let warp_running = state.warp_process.lock().unwrap().is_some();

    if byedpi_running || vless_running || warp_running {
        if enabled {
            let _ = set_windows_proxy(false, "");
            let socks_port = if byedpi_running {
                "127.0.0.1:1080"
            } else if vless_running {
                "127.0.0.1:10808"
            } else {
                "127.0.0.1:10818"
            };
            start_tun_device(&app, &state, socks_port)?;
        } else {
            stop_tun_device(&state);
            let proxy_str = if byedpi_running {
                "socks=127.0.0.1:1080"
            } else if vless_running {
                "http=127.0.0.1:10809;https=127.0.0.1:10809;socks=127.0.0.1:10808"
            } else {
                "http=127.0.0.1:10819;https=127.0.0.1:10819;socks=127.0.0.1:10818"
            };
            let _ = set_windows_proxy(true, proxy_str);
        }
    }

    Ok(enabled)
}

#[tauri::command]
fn get_telegram_proxy_port(state: State<'_, AppState>) -> u16 {
    if state.vless_process.lock().unwrap().is_some() {
        10808
    } else if state.warp_process.lock().unwrap().is_some() {
        10818
    } else {
        1080
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .manage(AppState::default())
        .plugin(tauri_plugin_opener::init())
        .invoke_handler(tauri::generate_handler![
            get_status,
            start_byedpi,
            stop_byedpi,
            get_byedpi_strategies,
            set_active_strategy,
            benchmark_strategy,
            start_vless,
            stop_vless,
            start_warp,
            stop_warp,
            get_warp_config,
            get_warp_amnezia_wg_text,
            generate_warp_profile,
            add_vless_key,
            fetch_subscription,
            get_configs,
            select_config,
            get_subscriptions,
            delete_subscription,
            delete_config,
            toggle_system_proxy,
            toggle_tun_mode,
            ping_server,
            get_telegram_proxy_port
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
