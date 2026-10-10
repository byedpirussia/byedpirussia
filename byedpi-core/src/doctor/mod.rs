use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TargetServiceHealth {
    pub service_name: String,
    pub domain: String,
    pub is_blocked: bool,
    pub recommended_engine: String,
    pub current_latency_ms: u32,
    pub detected_issue: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DiagnosisReport {
    pub timestamp_ms: u64,
    pub overall_status: String,
    pub tspu_throttling_detected: bool,
    pub telegram_blocked: bool,
    pub youtube_blocked: bool,
    pub discord_blocked: bool,
    pub services: Vec<TargetServiceHealth>,
    pub recommended_fix: String,
}

pub struct TrafficDoctor {
    services_to_probe: Vec<(&'static str, &'static str)>,
}

impl TrafficDoctor {
    pub fn new() -> Self {
        Self {
            services_to_probe: vec![
                ("YouTube (Video CDN)", "googlevideo.com"),
                ("Discord Voice & Gateway", "discord.gg"),
                ("Telegram MTProto & Media", "telegram.org"),
                ("X / Twitter Media", "x.com"),
                ("Instagram CDN", "cdninstagram.com"),
            ],
        }
    }

    /// Evaluates network condition based on simulated or measured probe metrics
    pub fn diagnose(&self, probe_results: &[(String, u32, bool)]) -> DiagnosisReport {
        let mut services = Vec::new();
        let mut tspu_throttling = false;
        let mut tg_blocked = false;
        let mut yt_blocked = false;
        let mut dc_blocked = false;

        for (name, domain) in &self.services_to_probe {
            // Find if there is a probe for this domain
            let probe = probe_results.iter().find(|(d, _, _)| d == *domain);
            let (latency, failed) = match probe {
                Some((_, lat, fail)) => (*lat, *fail),
                None => (25, false),
            };

            let is_blocked = failed || latency > 650;
            if latency > 500 {
                tspu_throttling = true;
            }

            if *domain == "telegram.org" && is_blocked {
                tg_blocked = true;
            }
            if *domain == "googlevideo.com" && is_blocked {
                yt_blocked = true;
            }
            if *domain == "discord.gg" && is_blocked {
                dc_blocked = true;
            }

            let (rec_engine, issue) = if is_blocked {
                if *domain == "telegram.org" {
                    ("telegram_proxy".to_string(), Some("TSPU MTProto dropping detected".to_string()))
                } else if *domain == "googlevideo.com" || *domain == "discord.gg" {
                    ("byedpi".to_string(), Some("TLS SNI RST injection detected".to_string()))
                } else {
                    ("warp".to_string(), Some("IP CIDR blocking detected".to_string()))
                }
            } else {
                ("direct".to_string(), None)
            };

            services.push(TargetServiceHealth {
                service_name: name.to_string(),
                domain: domain.to_string(),
                is_blocked,
                recommended_engine: rec_engine,
                current_latency_ms: latency,
                detected_issue: issue,
            });
        }

        let overall = if tg_blocked || yt_blocked || dc_blocked || tspu_throttling {
            "THROTTLED"
        } else {
            "OPTIMAL"
        };

        let rec_fix = if tg_blocked && yt_blocked {
            "Enable Dual Pipeline: ByeDPI (Split=2) -> WARP Port 500 & activate TG Proxy"
        } else if yt_blocked {
            "Switch to ByeDPI with Fake SNI (desync: split at 2)"
        } else if tg_blocked {
            "Activate Local TLS WebSocket Telegram Proxy"
        } else if tspu_throttling {
            "Switch WARP endpoint to port 500 and lower MTU to 1280"
        } else {
            "Direct routing is stable"
        };

        DiagnosisReport {
            timestamp_ms: 0,
            overall_status: overall.to_string(),
            tspu_throttling_detected: tspu_throttling,
            telegram_blocked: tg_blocked,
            youtube_blocked: yt_blocked,
            discord_blocked: dc_blocked,
            services,
            recommended_fix: rec_fix.to_string(),
        }
    }
}
