use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum DesyncMethod {
    Split,
    Fake,
    Disorder,
    Oob,
    Multisplit,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DesyncConfig {
    pub method: DesyncMethod,
    pub split_pos: usize,
    pub split_delay_ms: u64,
    pub fake_sni: Option<String>,
    pub ttl: u8,
}

impl Default for DesyncConfig {
    fn default() -> Self {
        Self {
            method: DesyncMethod::Split,
            split_pos: 2,
            split_delay_ms: 0,
            fake_sni: Some("google.com".to_string()),
            ttl: 8,
        }
    }
}

pub struct DesyncEngine {
    pub config: DesyncConfig,
}

impl DesyncEngine {
    pub fn new(config: DesyncConfig) -> Self {
        Self { config }
    }

    /// Segments TCP payload according to desynchronization rules
    pub fn fragment_payload<'a>(&self, payload: &'a [u8]) -> (Vec<u8>, Vec<u8>) {
        if payload.len() <= self.config.split_pos {
            return (payload.to_vec(), Vec::new());
        }

        let first = payload[..self.config.split_pos].to_vec();
        let second = payload[self.config.split_pos..].to_vec();
        (first, second)
    }

    /// Generates fake SNI packet for TSPU evasion
    pub fn create_fake_packet(&self, fake_host: &str) -> Vec<u8> {
        let mut fake = Vec::with_capacity(64);
        fake.extend_from_slice(b"\x16\x03\x01"); // TLS Record Header
        fake.extend_from_slice(fake_host.as_bytes());
        fake
    }
}
