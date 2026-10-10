pub mod desync;
pub mod dns;
pub mod doctor;
pub mod ffi;
pub mod mesh;
pub mod parser;
pub mod pipeline;
pub mod quantum;
pub mod tether;

pub use desync::{DesyncConfig, DesyncEngine, DesyncMethod};
pub use dns::{DnsProtocol, DnsResolverConfig, StealthDnsGuard};
pub use doctor::{DiagnosisReport, TargetServiceHealth, TrafficDoctor};
pub use mesh::{MeshNetwork, MeshNetworkStatus, MeshNode};
pub use parser::{TlsClientHelloInfo, TlsParser};
pub use pipeline::{DualPipelineConfig, DualPipelineManager, PipelineStep};
pub use quantum::{QuantumRouter, RouteDecision, RouteEngine};
pub use tether::{TetherClient, TetherEngine, TetherStatus};

/// Returns library version
pub fn version() -> &'static str {
    env!("CARGO_PKG_VERSION")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_quantum_router() {
        let router = QuantumRouter::new();
        let yt = router.evaluate_route("rr1---sn-h0je6n7z.googlevideo.com");
        assert_eq!(yt.engine, RouteEngine::ByeDpi);

        let tg = router.evaluate_route("web.telegram.org");
        assert_eq!(tg.engine, RouteEngine::TelegramProxy);

        let sber = router.evaluate_route("online.sberbank.ru");
        assert_eq!(sber.engine, RouteEngine::Direct);
    }

    #[test]
    fn test_desync_fragmentation() {
        let desync = DesyncEngine::new(DesyncConfig::default());
        let payload = b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n";
        let (first, second) = desync.fragment_payload(payload);
        
        assert_eq!(first.len(), 2);
        assert_eq!(second.len(), payload.len() - 2);
    }

    #[test]
    fn test_traffic_doctor_diagnosis() {
        let doctor = TrafficDoctor::new();
        let probes = vec![
            ("googlevideo.com".to_string(), 900, true),
            ("telegram.org".to_string(), 950, true),
        ];
        let report = doctor.diagnose(&probes);
        assert_eq!(report.overall_status, "THROTTLED");
        assert!(report.youtube_blocked);
        assert!(report.telegram_blocked);
    }

    #[test]
    fn test_dual_pipeline() {
        let pipeline = DualPipelineManager::new();
        assert_eq!(pipeline.config.warp_port, 500);
        assert_eq!(pipeline.config.split_offset, 2);
        assert!(pipeline.config.steps.len() >= 3);
    }

    #[test]
    fn test_mesh_network() {
        let mesh = MeshNetwork::new();
        let status = mesh.get_status();
        assert_eq!(status.active_nodes.len(), 2);
        assert!(status.is_relay_enabled);
    }

    #[test]
    fn test_tether_engine() {
        let mut tether = TetherEngine::new();
        let started = tether.start(10808);
        assert!(started.enabled);
        assert_eq!(started.listen_port, 10808);

        let stopped = tether.stop();
        assert!(!stopped.enabled);
    }
}
