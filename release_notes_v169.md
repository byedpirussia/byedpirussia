# ByeDPI Russia Android v1.6.9 — Переход на MASQUE HTTP/3 (Основной протокол)

## 🇷🇺 Что нового в 1.6.9:

### ⚡ Протокол MASQUE (HTTP/3) — Новый Основной Режим Cloudflare WARP
* **Полная интеграция RFC 9298 (CONNECT-UDP over HTTP/3 / QUIC)**: весь трафик Cloudflare WARP теперь по умолчанию инкапсулируется в зашифрованные HTTPS-сессии через Anycast-шлюзы Cloudflare на порту 443.
* **100% устойчивость к сигнатурным блокировкам ТСПУ/РКН**: MASQUE неотличим от обычного веб-трафика HTTPS/HTTP3, что полностью нивелирует блокировки и дропы WireGuard/AmneziaWG пакетов.
* **Быстрое переключение протоколов на главном экране**: добавлен удобный селектор прямо в карточке WARP — вы можете мгновенно переключаться между **MASQUE HTTP/3 [Основной]** и **AmneziaWG 3.1 [Резервный]**.
* **Интеллектуальный Watchdog для MASQUE**: адаптивный мониторинг качества соединения с автоматическим переподключением сессии при сетевых сбоях.

---

## 🇬🇧 What's new in 1.6.9:

### ⚡ MASQUE (HTTP/3) Protocol — New Primary Mode for Cloudflare WARP
* **Full RFC 9298 implementation (CONNECT-UDP over HTTP/3 / QUIC)**: Cloudflare WARP traffic is now routed by default inside encrypted HTTPS sessions to Cloudflare Anycast edges on port 443.
* **Bypass ISP DPI / censorship blocks**: masquerades all VPN traffic as standard HTTPS/HTTP3 web sessions, evading WireGuard signature drops and handshake filtering.
* **Instant Protocol Switcher**: easily switch between **MASQUE HTTP/3 [Primary]** and **AmneziaWG 3.1 [Backup]** directly from the main WARP card.
* **MASQUE Connection Watchdog**: continuous real-time ping and health monitoring with seamless auto-reconnect.

---

## 🇮🇷 تغییرات نسخه 1.6.9:

### ⚡ پروتکل ماسک (MASQUE HTTP/3) — حالت اصلی جدید کلودفلر وارپ
* **پیاده‌سازی کامل استانداردهای RFC 9298 بر بستر HTTP/3**: ترافیک وارپ به‌صورت پیش‌فرض در نشست‌های رمزنگاری‌شده HTTPS و پورت ۴۴۳ کپسوله‌سازی می‌شود.
* **دور زدن پیشرفته فیلترینگ**: پنهان‌سازی کامل ترافیک در قالب وب معمولی و دور زدن مسدودسازی‌های وایرگارد.
* **دکمه انتخاب سریع پروتکل**: امکان جابجایی سریع میان **پروتکل MASQUE (اصلی)** و **AmneziaWG 3.1 (پشتیبان)** مستقیماً در صفحه اصلی.
