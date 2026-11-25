# ByeDPI Russia Android v1.6.9 — Нативный MASQUE (RFC 9484) на базе Hiddify Core

## 🇷🇺 Что нового в 1.6.9:

### ⚡ Нативный протокол Cloudflare WARP MASQUE (Hiddify Core)
* **Полноценная поддержка RFC 9484 (`CONNECT-IP` over HTTP/3 QUIC и HTTP/2 TLS)**: интегрировано официальное ядро **Hiddify Core (`hiddify-core.aar`)** с нативным транспортом `outbound/masque` на порту `443` и ECDSA P-256 аутентификацией.
* **Обход сигнатурных блокировок ТСПУ / РКН**: трафик WARP маскируется под стандартные зашифрованные HTTPS/HTTP3-сессии к Anycast-серверам Cloudflare (`162.159.198.2:443`).
* **Надёжная защита маршрутизации и TLS**: автоматическая защита исходящих сокетов ядра через `VpnService.protect` и проверка сертификатов через системный `AndroidCAStore`.
* **Гибкое переключение режимов**: выбор между проверенным **AmneziaWG 3.1** и **MASQUE HTTP/3** прямо на карточке WARP.

---

## 🇬🇧 What's new in 1.6.9:

### ⚡ Native Cloudflare WARP MASQUE (Hiddify Core)
* **True RFC 9484 (`CONNECT-IP` over HTTP/3 QUIC & HTTP/2 TLS)**: powered by **Hiddify Core (`hiddify-core.aar`)** with native `outbound/masque` on port `443` and ECDSA P-256 keys.
* **DPI & Censorship Bypass**: encapsulates WARP traffic inside standard HTTPS/HTTP3 sessions to Cloudflare Anycast edge (`162.159.198.2:443`).
* **Full Android VPN Integration**: socket loop protection via `VpnService.protect` and system CA verification (`AndroidCAStore`).

---

## 🙏 Благодарности
Особая благодарность проекту **[Hiddify (`hiddify-core`)](https://github.com/hiddify/hiddify-core)** за открытое ядро с нативной поддержкой протокола MASQUE.
