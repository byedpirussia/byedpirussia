use serde::{Deserialize, Serialize};

/// Supported transport engines in ByeDPI Russia
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum RouteEngine {
    Direct,
    ByeDpi,
    Warp,
    OpenFlux,
    Vless,
    TelegramProxy,
}

/// Routing decision returned by Quantum Engine
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RouteDecision {
    pub target: String,
    pub engine: RouteEngine,
    pub latency_ms: u32,
    pub reason: String,
}

/// Intelligent Quantum Router that dynamically selects best engine
pub struct QuantumRouter {
    rules: Vec<RouteRule>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RouteRule {
    pub pattern: String,
    pub preferred_engine: RouteEngine,
}

impl QuantumRouter {
    pub fn new() -> Self {
        Self {
            rules: vec![
                RouteRule {
                    pattern: "googlevideo.com".to_string(),
                    preferred_engine: RouteEngine::ByeDpi,
                },
                RouteRule {
                    pattern: "youtube.com".to_string(),
                    preferred_engine: RouteEngine::ByeDpi,
                },
                RouteRule {
                    pattern: "discord.gg".to_string(),
                    preferred_engine: RouteEngine::ByeDpi,
                },
                RouteRule {
                    pattern: "discordapp.com".to_string(),
                    preferred_engine: RouteEngine::ByeDpi,
                },
                RouteRule {
                    pattern: "telegram.org".to_string(),
                    preferred_engine: RouteEngine::TelegramProxy,
                },
                RouteRule {
                    pattern: "t.me".to_string(),
                    preferred_engine: RouteEngine::TelegramProxy,
                },
                RouteRule {
                    pattern: "instagram.com".to_string(),
                    preferred_engine: RouteEngine::Warp,
                },
                RouteRule {
                    pattern: "x.com".to_string(),
                    preferred_engine: RouteEngine::Warp,
                },
                RouteRule {
                    pattern: "twitter.com".to_string(),
                    preferred_engine: RouteEngine::Warp,
                },
                RouteRule {
                    pattern: "gosuslugi.ru".to_string(),
                    preferred_engine: RouteEngine::Direct,
                },
                RouteRule {
                    pattern: "sberbank.ru".to_string(),
                    preferred_engine: RouteEngine::Direct,
                },
            ],
        }
    }

    /// Evaluates domain and determines optimal route
    pub fn evaluate_route(&self, domain: &str) -> RouteDecision {
        let domain_lower = domain.to_lowercase();

        // 1. Check exact/substring rules
        for rule in &self.rules {
            if domain_lower.contains(&rule.pattern) {
                return RouteDecision {
                    target: domain.to_string(),
                    engine: rule.preferred_engine,
                    latency_ms: 15,
                    reason: format!("Matched pattern rule: {}", rule.pattern),
                };
            }
        }

        // 2. Default Russian domains to Direct, blocked or foreign to ByeDpi / WARP
        if domain_lower.ends_with(".ru") || domain_lower.ends_with(".su") || domain_lower.ends_with(".рф") {
            RouteDecision {
                target: domain.to_string(),
                engine: RouteEngine::Direct,
                latency_ms: 5,
                reason: "Direct route for domestic RU domain".to_string(),
            }
        } else {
            RouteDecision {
                target: domain.to_string(),
                engine: RouteEngine::ByeDpi,
                latency_ms: 25,
                reason: "Default DPI evasion route".to_string(),
            }
        }
    }
}
