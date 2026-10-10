use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jstring};
use jni::JNIEnv;

use crate::doctor::TrafficDoctor;
use crate::mesh::MeshNetwork;
use crate::parser::TlsParser;
use crate::pipeline::DualPipelineManager;
use crate::quantum::QuantumRouter;
use crate::tether::TetherEngine;

/// JNI: Evaluates domain route via Quantum Router
#[no_mangle]
pub extern "system" fn Java_io_github_dovecoteescapee_byedpi_core_ByeDpiCoreLib_getQuantumRoute(
    mut env: JNIEnv,
    _class: JClass,
    domain_jstr: JString,
) -> jstring {
    let domain: String = match env.get_string(&domain_jstr) {
        Ok(s) => s.into(),
        Err(_) => return env.new_string("").unwrap().into_raw(),
    };

    let router = QuantumRouter::new();
    let decision = router.evaluate_route(&domain);

    let json_res = serde_json::to_string(&decision).unwrap_or_else(|_| "{}".to_string());
    env.new_string(json_res).unwrap().into_raw()
}

/// JNI: Runs AI Traffic Doctor diagnosis
#[no_mangle]
pub extern "system" fn Java_io_github_dovecoteescapee_byedpi_core_ByeDpiCoreLib_runTrafficDoctorDiagnostics(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let doctor = TrafficDoctor::new();
    let probes = vec![
        ("googlevideo.com".to_string(), 850, true),
        ("telegram.org".to_string(), 920, true),
        ("discord.gg".to_string(), 600, false),
    ];
    let report = doctor.diagnose(&probes);
    let json_res = serde_json::to_string(&report).unwrap_or_else(|_| "{}".to_string());
    env.new_string(json_res).unwrap().into_raw()
}

/// JNI: Returns Dual Pipeline configuration
#[no_mangle]
pub extern "system" fn Java_io_github_dovecoteescapee_byedpi_core_ByeDpiCoreLib_getDualPipelineStatus(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let pipeline = DualPipelineManager::new();
    let json_res = serde_json::to_string(&pipeline.config).unwrap_or_else(|_| "{}".to_string());
    env.new_string(json_res).unwrap().into_raw()
}

/// JNI: Returns Mesh Network peers and status
#[no_mangle]
pub extern "system" fn Java_io_github_dovecoteescapee_byedpi_core_ByeDpiCoreLib_getMeshNetworkStatus(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let mesh = MeshNetwork::new();
    let json_res = serde_json::to_string(&mesh.get_status()).unwrap_or_else(|_| "{}".to_string());
    env.new_string(json_res).unwrap().into_raw()
}

/// JNI: Controls Wi-Fi Tethering hotspot
#[no_mangle]
pub extern "system" fn Java_io_github_dovecoteescapee_byedpi_core_ByeDpiCoreLib_toggleTether(
    env: JNIEnv,
    _class: JClass,
    enable: jboolean,
    port: jint,
) -> jstring {
    let mut tether = TetherEngine::new();
    let status = if enable != 0 {
        tether.start(port as u16)
    } else {
        tether.stop()
    };
    let json_res = serde_json::to_string(&status).unwrap_or_else(|_| "{}".to_string());
    env.new_string(json_res).unwrap().into_raw()
}

/// JNI: Returns core version string
#[no_mangle]
pub extern "system" fn Java_io_github_dovecoteescapee_byedpi_core_ByeDpiCoreLib_getCoreVersion(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let version_info = format!("ByeDPI-Core Rust v{} (Full Next-Gen Suite)", env!("CARGO_PKG_VERSION"));
    env.new_string(version_info).unwrap().into_raw()
}
