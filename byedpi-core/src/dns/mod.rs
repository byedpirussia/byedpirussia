use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum DnsProtocol {
    Udp,
    DoH,
    DoQ,
    ObliviousDoH,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DnsResolverConfig {
    pub provider_name: String,
    pub address: String,
    pub protocol: DnsProtocol,
    pub latency_ms: u32,
}

pub struct StealthDnsGuard {
    resolvers: Vec<DnsResolverConfig>,
}

impl StealthDnsGuard {
    pub fn new() -> Self {
        Self {
            resolvers: vec![
                DnsResolverConfig {
                    provider_name: "Cloudflare DoH".to_string(),
                    address: "https://1.1.1.1/dns-query".to_string(),
                    protocol: DnsProtocol::DoH,
                    latency_ms: 22,
                },
                DnsResolverConfig {
                    provider_name: "Google DoH".to_string(),
                    address: "https://dns.google/dns-query".to_string(),
                    protocol: DnsProtocol::DoH,
                    latency_ms: 25,
                },
                DnsResolverConfig {
                    provider_name: "AdGuard DoQ".to_string(),
                    address: "quic://dns.adguard.com".to_string(),
                    protocol: DnsProtocol::DoQ,
                    latency_ms: 18,
                },
            ],
        }
    }

    pub fn get_fastest_resolver(&self) -> &DnsResolverConfig {
        self.resolvers.iter().min_by_key(|r| r.latency_ms).unwrap()
    }
}
