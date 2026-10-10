/// Parser for TLS ClientHello to extract SNI and cipher suites for precise DPI splitting
pub struct TlsParser;

#[derive(Debug, PartialEq, Eq)]
pub struct TlsClientHelloInfo {
    pub sni: Option<String>,
    pub session_id_len: usize,
    pub sni_offset: usize,
}

impl TlsParser {
    /// Parses raw bytes looking for TLS 1.2 / 1.3 ClientHello (0x16 0x03 0x01/02/03)
    pub fn parse_client_hello(payload: &[u8]) -> Option<TlsClientHelloInfo> {
        // Record Header: ContentType (1 byte = 0x16) + Version (2 bytes) + Length (2 bytes)
        if payload.len() < 5 || payload[0] != 0x16 {
            return None;
        }

        // Handshake Header: HandshakeType (1 byte = 0x01 for ClientHello)
        if payload.len() < 9 || payload[5] != 0x01 {
            return None;
        }

        let mut pos = 9; // Skip handshake header (4 bytes: type + 3 bytes len)
        if payload.len() < pos + 34 {
            return None;
        }

        // Skip client_version (2 bytes) and random (32 bytes)
        pos += 34;

        // Session ID length
        let session_id_len = payload[pos] as usize;
        pos += 1 + session_id_len;

        if payload.len() < pos + 2 {
            return None;
        }

        // Cipher suites length
        let cipher_suites_len = u16::from_be_bytes([payload[pos], payload[pos + 1]]) as usize;
        pos += 2 + cipher_suites_len;

        if payload.len() < pos + 1 {
            return None;
        }

        // Compression methods length
        let comp_len = payload[pos] as usize;
        pos += 1 + comp_len;

        if payload.len() < pos + 2 {
            return None;
        }

        // Extensions length
        let ext_len = u16::from_be_bytes([payload[pos], payload[pos + 1]]) as usize;
        pos += 2;

        let end_ext = pos + ext_len;
        if payload.len() < end_ext {
            return None;
        }

        // Iterate extensions to find Server Name Indication (0x0000)
        while pos + 4 <= end_ext {
            let ext_type = u16::from_be_bytes([payload[pos], payload[pos + 1]]);
            let ext_data_len = u16::from_be_bytes([payload[pos + 2], payload[pos + 3]]) as usize;
            pos += 4;

            if ext_type == 0x0000 {
                // SNI extension found!
                if pos + ext_data_len <= payload.len() && ext_data_len >= 5 {
                    let list_len = u16::from_be_bytes([payload[pos], payload[pos + 1]]) as usize;
                    if list_len >= 3 && payload[pos + 2] == 0x00 { // NameType: host_name
                        let name_len = u16::from_be_bytes([payload[pos + 3], payload[pos + 4]]) as usize;
                        let name_start = pos + 5;
                        if name_start + name_len <= payload.len() {
                            let sni_bytes = &payload[name_start..name_start + name_len];
                            if let Ok(sni_str) = std::str::from_utf8(sni_bytes) {
                                return Some(TlsClientHelloInfo {
                                    sni: Some(sni_str.to_string()),
                                    session_id_len,
                                    sni_offset: name_start,
                                });
                            }
                        }
                    }
                }
            }
            pos += ext_data_len;
        }

        None
    }
}
