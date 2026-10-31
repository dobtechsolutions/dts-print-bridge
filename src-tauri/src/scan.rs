//! "Find printers": probes the /24 around this computer for receipt printers on the
//! raw printing port (9100). Printers that speak ESC/POS "GS I" (Epson and most clones)
//! also report their model, e.g. "TM-m30". Nothing is printed.

use serde::Serialize;
use std::net::{Ipv4Addr, UdpSocket};
use std::sync::Arc;
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::sync::Semaphore;
use tokio::time::timeout;

#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct FoundPrinter {
    pub ip: String,
    pub port: u16,
    /// Model reported by the printer, or "" when it doesn't answer GS I.
    pub model: String,
}

/// IPv4 of the interface that would reach the internet. Opening a UDP socket and
/// "connecting" it sends no packets; it only asks the OS which interface it would use.
pub fn local_ipv4() -> Option<Ipv4Addr> {
    let sock = UdpSocket::bind("0.0.0.0:0").ok()?;
    sock.connect("8.8.8.8:80").ok()?;
    match sock.local_addr().ok()?.ip() {
        std::net::IpAddr::V4(v4) if !v4.is_loopback() && !v4.is_unspecified() => Some(v4),
        _ => None,
    }
}

/// Hosts in the /24 around `own`, without `own`, the network and the broadcast address.
pub fn hosts_around(own: Ipv4Addr) -> Vec<Ipv4Addr> {
    let o = own.octets();
    (1u8..=254)
        .map(|h| Ipv4Addr::new(o[0], o[1], o[2], h))
        .filter(|ip| *ip != own)
        .collect()
}

pub fn parse_model(raw: &[u8]) -> String {
    let mut s = String::new();
    for (i, b) in raw.iter().enumerate() {
        if i == 0 && *b == 0x5F {
            continue; // Epson header byte
        }
        if *b == 0 {
            break;
        }
        if (0x20..0x7F).contains(b) {
            s.push(*b as char);
        }
    }
    s.trim().to_string()
}

pub async fn probe(host: Ipv4Addr, port: u16, connect_ms: u64) -> Option<FoundPrinter> {
    let mut stream = timeout(Duration::from_millis(connect_ms), TcpStream::connect((host, port)))
        .await
        .ok()?
        .ok()?;
    let mut model = String::new();
    if stream.write_all(&[0x1D, 0x49, 0x43]).await.is_ok() {
        let mut buf = [0u8; 64];
        if let Ok(Ok(n)) = timeout(Duration::from_millis(700), stream.read(&mut buf)).await {
            model = parse_model(&buf[..n]);
        }
    }
    let _ = stream.shutdown().await;
    Some(FoundPrinter { ip: host.to_string(), port, model })
}

pub async fn scan(hosts: Vec<Ipv4Addr>, port: u16, connect_ms: u64, parallel: usize) -> Vec<FoundPrinter> {
    let sem = Arc::new(Semaphore::new(parallel.max(1)));
    let mut tasks = Vec::with_capacity(hosts.len());
    for h in hosts {
        let sem = sem.clone();
        tasks.push(tokio::spawn(async move {
            let _permit = sem.acquire_owned().await.ok()?;
            probe(h, port, connect_ms).await
        }));
    }
    let mut found = Vec::new();
    for t in tasks {
        if let Ok(Some(f)) = t.await {
            found.push(f);
        }
    }
    found.sort_by_key(|f| f.ip.parse::<Ipv4Addr>().map(u32::from).unwrap_or(0));
    found
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::net::TcpListener;

    #[test]
    fn hosts_skip_own() {
        let h = hosts_around(Ipv4Addr::new(192, 168, 1, 87));
        assert_eq!(h.len(), 253);
        assert!(!h.contains(&Ipv4Addr::new(192, 168, 1, 87)));
        assert!(h.contains(&Ipv4Addr::new(192, 168, 1, 1)));
        assert!(h.contains(&Ipv4Addr::new(192, 168, 1, 254)));
    }

    #[test]
    fn model_parsing() {
        assert_eq!(parse_model(&[0x5F, b'T', b'M', b'-', b'm', b'3', b'0', 0]), "TM-m30");
        assert_eq!(parse_model(&[]), "");
    }

    #[tokio::test]
    async fn finds_epson_and_silent_printers() {
        let epson = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let ep = epson.local_addr().unwrap().port();
        tokio::spawn(async move {
            let (mut c, _) = epson.accept().await.unwrap();
            let mut q = [0u8; 3];
            c.read_exact(&mut q).await.unwrap();
            assert_eq!(q, [0x1D, 0x49, 0x43]);
            c.write_all(&[0x5F, b'T', b'M', b'-', b'm', b'3', b'0', 0]).await.unwrap();
            tokio::time::sleep(Duration::from_millis(200)).await;
        });
        let f = probe(Ipv4Addr::LOCALHOST, ep, 500).await.unwrap();
        assert_eq!(f.model, "TM-m30");

        let silent = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let sp = silent.local_addr().unwrap().port();
        tokio::spawn(async move {
            let (_c, _) = silent.accept().await.unwrap();
            tokio::time::sleep(Duration::from_millis(1200)).await;
        });
        let f = probe(Ipv4Addr::LOCALHOST, sp, 500).await.unwrap();
        assert_eq!(f.model, "");

        let closed = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let cp = closed.local_addr().unwrap().port();
        drop(closed);
        assert!(probe(Ipv4Addr::LOCALHOST, cp, 300).await.is_none());
    }
}
