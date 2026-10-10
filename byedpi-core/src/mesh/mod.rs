use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MeshNode {
    pub node_id: String,
    pub name: String,
    pub address: String,
    pub ping_ms: u32,
    pub is_exit_node: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MeshNetworkStatus {
    pub active_nodes: Vec<MeshNode>,
    pub selected_exit_node: Option<String>,
    pub is_relay_enabled: bool,
}

pub struct MeshNetwork {
    nodes: Vec<MeshNode>,
    exit_node_id: Option<String>,
}

impl MeshNetwork {
    pub fn new() -> Self {
        Self {
            nodes: vec![
                MeshNode {
                    node_id: "node-desktop-home".to_string(),
                    name: "Домашний ПК (Windows 2.0 Client)".to_string(),
                    address: "192.168.1.120:51820".to_string(),
                    ping_ms: 12,
                    is_exit_node: true,
                },
                MeshNode {
                    node_id: "node-vps-eu".to_string(),
                    name: "Приватный Egress узел (EU)".to_string(),
                    address: "185.120.45.10:443".to_string(),
                    ping_ms: 45,
                    is_exit_node: true,
                },
            ],
            exit_node_id: Some("node-desktop-home".to_string()),
        }
    }

    pub fn get_status(&self) -> MeshNetworkStatus {
        MeshNetworkStatus {
            active_nodes: self.nodes.clone(),
            selected_exit_node: self.exit_node_id.clone(),
            is_relay_enabled: true,
        }
    }
}
