use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PipelineStep {
    pub name: String,
    pub order: u8,
    pub action: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DualPipelineConfig {
    pub enabled: bool,
    pub primary_desync_engine: String, // "byedpi" or "openflux"
    pub tunnel_engine: String,         // "warp_awg" or "vless"
    pub warp_port: u16,                // 500, 4500, 1701, 2408
    pub split_offset: usize,
    pub steps: Vec<PipelineStep>,
}

impl Default for DualPipelineConfig {
    fn default() -> Self {
        Self {
            enabled: true,
            primary_desync_engine: "byedpi".to_string(),
            tunnel_engine: "warp_awg".to_string(),
            warp_port: 500,
            split_offset: 2,
            steps: vec![
                PipelineStep {
                    name: "TCP/TLS Fragmentation (Stage 1)".to_string(),
                    order: 1,
                    action: "Split client hello at position 2 to bypass TSPU SNI filter".to_string(),
                },
                PipelineStep {
                    name: "Encapsulation Tunnel (Stage 2)".to_string(),
                    order: 2,
                    action: "Pack packets into AmneziaWG (Jc=4, Jmin=40, Jmax=70) over UDP:500".to_string(),
                },
                PipelineStep {
                    name: "Egress Delivery (Stage 3)".to_string(),
                    order: 3,
                    action: "Send to Cloudflare Clean IP with 0 packet loss".to_string(),
                },
            ],
        }
    }
}

pub struct DualPipelineManager {
    pub config: DualPipelineConfig,
}

impl DualPipelineManager {
    pub fn new() -> Self {
        Self {
            config: DualPipelineConfig::default(),
        }
    }

    pub fn get_pipeline_description(&self) -> String {
        format!(
            "Dual Pipeline Active: {} (Split={}) -> {} (Port {})",
            self.config.primary_desync_engine.to_uppercase(),
            self.config.split_offset,
            self.config.tunnel_engine.to_uppercase(),
            self.config.warp_port
        )
    }
}
