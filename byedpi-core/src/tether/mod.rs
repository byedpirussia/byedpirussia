use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TetherClient {
    pub ip: String,
    pub hostname: String,
    pub rx_bytes: u64,
    pub tx_bytes: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TetherStatus {
    pub enabled: bool,
    pub interface: String,
    pub listen_port: u16,
    pub connected_devices: u32,
    pub total_rx_mb: f64,
    pub total_tx_mb: f64,
}

pub struct TetherEngine {
    enabled: bool,
    port: u16,
}

impl TetherEngine {
    pub fn new() -> Self {
        Self {
            enabled: false,
            port: 10808,
        }
    }

    pub fn start(&mut self, port: u16) -> TetherStatus {
        self.enabled = true;
        self.port = port;
        TetherStatus {
            enabled: true,
            interface: "wlan0-ap".to_string(),
            listen_port: self.port,
            connected_devices: 0,
            total_rx_mb: 0.0,
            total_tx_mb: 0.0,
        }
    }

    pub fn stop(&mut self) -> TetherStatus {
        self.enabled = false;
        TetherStatus {
            enabled: false,
            interface: "inactive".to_string(),
            listen_port: self.port,
            connected_devices: 0,
            total_rx_mb: 0.0,
            total_tx_mb: 0.0,
        }
    }
}
