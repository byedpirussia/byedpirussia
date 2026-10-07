using System;
using System.Collections.Generic;
using System.Text.Json.Serialization;
using System.Web;

namespace ByeDpiRussia.Desktop.Models
{
    public class Strategy
    {
        public string Id { get; set; } = string.Empty;
        public string Name { get; set; } = string.Empty;
        public string Description { get; set; } = string.Empty;
        public string Args { get; set; } = string.Empty;
        public string RecommendedFor { get; set; } = "YouTube, Discord, TG";

        public string DisplayTitle => $"{Name}  [{RecommendedFor}]";

        public static List<Strategy> GetPresetStrategies()
        {
            return new List<Strategy>
            {
                new Strategy
                {
                    Id = "cascade_10",
                    Name = "⚡ Каскадный сплит (10 шагов + TLSrec)",
                    Description = "Глубокая фрагментация пакетов на 10 интервалах с разворотом очереди и сплитом TLS записи.",
                    Args = "-d1 -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -r1+s",
                    RecommendedFor = "YouTube, Discord"
                },
                new Strategy
                {
                    Id = "cascade_10_md5",
                    Name = "🛡️ Каскадный сплит 10 + Fake UDP",
                    Description = "10 интервалов каскада с разворотом очереди и фейковыми UDP пакетами.",
                    Args = "-d1 -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -r1+s -a1",
                    RecommendedFor = "ТСПУ, YouTube, Discord"
                },
                new Strategy
                {
                    Id = "cascade_double_auto",
                    Name = "🔄 Двойной каскад с автопереключением (Auto TLS)",
                    Description = "Двухуровневый каскад с автоматическим переходом на резервный профиль при ошибках TLS.",
                    Args = "-d1 -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -r1+s -a1 -A s -d1 -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -a1",
                    RecommendedFor = "YouTube 4K, Ростелеком, Дом.ru"
                },
                new Strategy
                {
                    Id = "multilevel_cascade",
                    Name = "📶 Многоуровневый каскад (Disorder + Split)",
                    Description = "Чередование сплита и перестановки на возрастающих смещениях от начала SNI.",
                    Args = "-d1 -s1+s -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s",
                    RecommendedFor = "YouTube, Discord"
                },
                new Strategy
                {
                    Id = "double_cascade",
                    Name = "🧱 Двойной каскад",
                    Description = "Комбинированный каскадный сплит с малыми шагами для сложных DPI-фильтров.",
                    Args = "-d1 -s1+s -d1+s -s3+s -d6+s -s12+s -d14+s -s20+s -d24+s -s30+s",
                    RecommendedFor = "YouTube 4K, Telegram"
                },
                new Strategy
                {
                    Id = "progressive_split",
                    Name = "📈 Прогрессивный мультисплит",
                    Description = "Последовательное разбиение пакета на 8 сегментов после заголовка SNI.",
                    Args = "-d1 -s1+s -s3+s -s6+s -s9+s -s12+s -s15+s -s20+s -s30+s",
                    RecommendedFor = "YouTube, Браузер"
                },
                new Strategy
                {
                    Id = "fast_tlsrec",
                    Name = "🚀 Быстрый TLSrec (YouTube / Discord)",
                    Description = "Разбиение TLS записи с последующей перестановкой фрагментов SNI. Высокая скорость.",
                    Args = "-s1 -d3+s -r1+s",
                    RecommendedFor = "Discord, Голос, YouTube"
                },
                new Strategy
                {
                    Id = "rhythmic_disoob_tlsrec",
                    Name = "🎵 Ритмичный Disoob + TLSrec + HTTP mod",
                    Description = "Чередование коротких сплитов, TLS записей и модификаций HTTP-заголовков.",
                    Args = "-q2 -s2 -s3+s -r3 -s4 -r4 -s5+s -r5+s -s6 -s7+s -r8 -s9+s -M h,d,r -a1 -A t,r -s2+s -r2 -d2 -s3 -r3 -r4 -s4 -d5+s -r5 -d6 -s7+s -d7 -a1",
                    RecommendedFor = "Discord, Голосовые каналы, Twitch"
                },
                new Strategy
                {
                    Id = "oob_cascade_sack",
                    Name = "🛡️ OOB каскад (Auto TLS/RST)",
                    Description = "Внедрение OOB-байтов и глубокая фрагментация с автоподстройкой на ошибки TLS.",
                    Args = "-o1 -d1 -a1 -A t,r,s -s1 -d1 -s5+s -s10+s -s15+s -s20+s -r1+s -a1 -A s -s1 -d1 -s5+s -s10+s -s15+s -s20+s -a1",
                    RecommendedFor = "МТС, Мегафон, Билайн, Tele2"
                },
                new Strategy
                {
                    Id = "fake_ttl8",
                    Name = "🌐 Fake SNI (Google) + TTL 8",
                    Description = "Отправка фиктивного ClientHello с SNI google.com с последующим сплитом TLS записи.",
                    Args = "-f -1 -n www.google.com -s2+s -r3 -t4",
                    RecommendedFor = "ТСПУ обход (все сервисы)"
                },
                new Strategy
                {
                    Id = "fake_tlsrec_ttl8",
                    Name = "🎯 Фейк + TLSrec (TTL 8)",
                    Description = "Фейковый пакет с TTL 8 и фрагментацией TLS записи.",
                    Args = "-d1 -s1+s -r1+s -f -1 -t8",
                    RecommendedFor = "ТСПУ, YouTube"
                },
                new Strategy
                {
                    Id = "fake_low_ttl",
                    Name = "⏱️ Фейк с низким TTL (TTL 2)",
                    Description = "Фейковый пакет умирает до сервера, сбивая только оборудование ТСПУ.",
                    Args = "-d1 -s1+s -r1+s -f -1 -t2",
                    RecommendedFor = "Мобильные операторы"
                },
                new Strategy
                {
                    Id = "fake_google_clean",
                    Name = "🔎 Fake SNI Google + TLS split",
                    Description = "Фейковый заголовок google.com со сплитом TLS записи на 1-м байте.",
                    Args = "-n www.google.com -f -1 -r1+s",
                    RecommendedFor = "Discord, YouTube"
                },
                new Strategy
                {
                    Id = "sni_google_multisplit",
                    Name = "🔀 Fake SNI Google + Мульти-сплит и OOB",
                    Description = "Фейковый SNI google.com с каскадным разделением и внедрением OOB-байтов.",
                    Args = "-n www.google.com -f -1 -s1+s -d1 -s3+s -s5+s -q7 -a1 -A s -o2 -f -1 -a1 -A s -r5 -M h -s1+s -s3+s -a1",
                    RecommendedFor = "ТСПУ, YouTube, Мессенджеры"
                },
                new Strategy
                {
                    Id = "sni_google_triple_auto",
                    Name = "⚡ Fake SNI Google + Тройной авто-профиль",
                    Description = "Трёхуровневый адаптивный профиль с фейковым SNI и OOB защитой.",
                    Args = "-n www.google.com -f -1 -a1 -A s -s1+s -a1 -A s -s5+s -a1 -A s -d3 -q7 -o2 -f -1 -r5 -M h -a1",
                    RecommendedFor = "YouTube 1080p/4K, Discord Web"
                },
                new Strategy
                {
                    Id = "split50_multi_ttl",
                    Name = "🔢 Мульти-сплит до 60 шагов + Fake TTL 2",
                    Description = "Глубокое дробление пакета с шагом до 60 байт и фейком с ультракоротким TTL.",
                    Args = "-d1+s -s50+s -a1 -A s -f -1 -t2 -r2+s -a1 -A t -d2 -s1+s -s5+s -s10+s -s15+s -s25+s -s35+s -s50+s -s60+s -a1",
                    RecommendedFor = "Мобильный интернет, Сложные блокировки"
                },
                new Strategy
                {
                    Id = "oob_fake_split11",
                    Name = "🪓 OOB + Fake SNI (Split 1-11)",
                    Description = "Адаптивное разделение первых 11 байт с фейковым SNI и проверкой desync.",
                    Args = "-o1 -a1 -A t,r,s -f -1 -a1 -A t,r,s -d1+s -s11+s -a1 -A t,r,s -n www.google.com -f -1 -d1+s -s1+s -a1",
                    RecommendedFor = "YouTube, Discord, ТСПУ"
                },
                new Strategy
                {
                    Id = "disoob_minimal",
                    Name = "💨 Минимальный Disoob + Disorder + Split",
                    Description = "Легковесная комбинация с минимальным оверхедом и высокой пропускной способностью.",
                    Args = "-d1 -s1 -q1 -a1",
                    RecommendedFor = "Высокая скорость, Игры, Звонки"
                },
                new Strategy
                {
                    Id = "fake_cascade_http",
                    Name = "🌍 Фейк + смещение HTTP Host + TLSrec",
                    Description = "Специализированная стратегия для одновременного обхода HTTP и HTTPS цензуры.",
                    Args = "-f -1 -s3+s -a1 -A s -d1 -s4+s -s8+h -d6+h -a1 -A t,r,s -o2 -r5 -M h -r6+h -s2+s -s3+s -a1",
                    RecommendedFor = "Веб-серфинг, Трекеры, Сайты"
                },
                new Strategy
                {
                    Id = "ultra_cascade_fibonacci",
                    Name = "🌀 Ультра-каскад 12 шагов (Фибоначчи)",
                    Description = "Разбиение по возрастающей шкале смещений (1, 2, 3, 5, 8, 12, 17, 23, 30, 40, 50).",
                    Args = "-d1 -s1+s -d2+s -s3+s -d5+s -s8+s -d12+s -s17+s -d23+s -s30+s -d40+s -s50+s -r1+s -a1",
                    RecommendedFor = "YouTube, ТСПУ"
                },
                new Strategy
                {
                    Id = "fake_low_ttl_md5",
                    Name = "⏳ Fake SNI Google + Низкий TTL 3",
                    Description = "Короткоживущий фейковый пакет умирает перед сервером, оставляя ТСПУ в тупике.",
                    Args = "-f -1 -t3 -n www.google.com -d1 -s2+s -r1+s -a1",
                    RecommendedFor = "Ростелеком, МТС, Билайн"
                },
                new Strategy
                {
                    Id = "oob_multisplit_10",
                    Name = "🧩 OOB + Прогрессивный мультисплит (10 шагов)",
                    Description = "Внедрение OOB-байта в сочетании с 10-ступенчатым дроблением SNI.",
                    Args = "-o1 -s1+s -s3+s -s6+s -s10+s -s15+s -s21+s -s28+s -s36+s -s45+s -s55+s -a1",
                    RecommendedFor = "YouTube, Discord, Telegram"
                },
                new Strategy
                {
                    Id = "disorder_reverse_cascade",
                    Name = "🔀 Обратный каскад Disorder",
                    Description = "Разворот пакетов от хвоста к голове SNI для разрушения анализа потока.",
                    Args = "-d35+s -d30+s -d25+s -d20+s -d15+s -d10+s -d5+s -d1+s -s1+s -r1+s -a1",
                    RecommendedFor = "YouTube, ТСПУ"
                },
                new Strategy
                {
                    Id = "classic_fake_split",
                    Name = "🏛️ Классический Fake + Split",
                    Description = "Проверенный временем пресет с фейковым пакетом, TTL 8 и перестановкой.",
                    Args = "-f -1 -t 8 -s 1+s -d 3+s",
                    RecommendedFor = "Универсально"
                },
                new Strategy
                {
                    Id = "combo_oob_disorder",
                    Name = "🎲 Комбо: OOB + Disorder + TLSrec",
                    Description = "Внедрение OOB-байта в сочетании с перестановкой и разделением TLS записи.",
                    Args = "-o1 -d1 -r1+s -s1+s -d3+s",
                    RecommendedFor = "Сложные блокировки"
                },
                new Strategy
                {
                    Id = "mixed_split_disorder",
                    Name = "🧬 Смешанный сплит (Disorder + Split)",
                    Description = "Смешанный сплит до и после SNI с перестановкой пакетов.",
                    Args = "-d1 -s4 -d8 -s1+s -d5+s -s10+s -d20+s",
                    RecommendedFor = "YouTube, Discord"
                },
                new Strategy
                {
                    Id = "disoob_fake_sack",
                    Name = "💥 Disoob + Fake",
                    Description = "Специальная комбинация Disoob с фейком.",
                    Args = "-q1+s -s29+s -o5+s -f -1",
                    RecommendedFor = "ТСПУ Ростелеком/МТС/Билайн"
                },
                new Strategy
                {
                    Id = "heavy_fake_oob",
                    Name = "🥊 Полный фарш: Fake + OOB + TLSrec",
                    Description = "Максимально агрессивный режим: OOB-байт, фейковый пакет и сплит TLS записи.",
                    Args = "-d1 -s1+s -r1+s -e 1 -o1+s -f -1 -t2",
                    RecommendedFor = "Сложные блокировки"
                },
                new Strategy
                {
                    Id = "split_50_tlsrec",
                    Name = "✂️ Сплит 50 + TLSrec",
                    Description = "Сдвинутый сплит на 50 байт со смещением TLS записи.",
                    Args = "-d1+s -s50+s -r2+s",
                    RecommendedFor = "Discord, TG"
                },
                new Strategy
                {
                    Id = "simple_d1_s3",
                    Name = "⚡ Простой сплит (Disorder 1 + Split 3)",
                    Description = "Быстрый режим с минимальной нагрузкой на процессор.",
                    Args = "-d1 -s3+s",
                    RecommendedFor = "Высокая скорость"
                },
                new Strategy
                {
                    Id = "disorder_7_split_2",
                    Name = "🧪 Disorder 7 + Split 2",
                    Description = "Перестановка на 7-м байте со сплитом на 2-м байте.",
                    Args = "-d7 -s2",
                    RecommendedFor = "Telegram, Мессенджеры"
                },
                new Strategy
                {
                    Id = "classic_split_disorder",
                    Name = "🎯 Классический Split 1 + Disorder 1",
                    Description = "Базовая стратегия фрагментации первого байта TCP.",
                    Args = "-s 1 -d 1",
                    RecommendedFor = "Легкие блокировки"
                }
            };
        }
    }

    public class StrategyResult
    {
        public Strategy Strategy { get; set; } = new();
        public int SuccessCount { get; set; }
        public long AverageLatencyMs { get; set; }
        public Dictionary<string, long?> Details { get; set; } = new();

        public string SummaryText =>
            SuccessCount > 0
                ? $"✅ Доступно {SuccessCount}/2 (пинг {AverageLatencyMs} мс)"
                : "❌ Недоступно";
    }

    public class VlessProfile
    {
        public string Id { get; set; } = Guid.NewGuid().ToString();
        public string Name { get; set; } = "VLESS Профиль";
        public string Protocol { get; set; } = "vless"; // vless, hysteria2, shadowsocks, trojan
        public string Address { get; set; } = string.Empty;
        public int Port { get; set; } = 443;
        public string Uuid { get; set; } = string.Empty;
        public string Flow { get; set; } = string.Empty;
        public string Encryption { get; set; } = "none";
        public string Transport { get; set; } = "tcp";
        public string Security { get; set; } = "reality";
        public string Sni { get; set; } = string.Empty;
        public string Pbk { get; set; } = string.Empty;
        public string Sid { get; set; } = string.Empty;
        public string Fp { get; set; } = "chrome";
        public string Path { get; set; } = string.Empty;
        public string Host { get; set; } = string.Empty;
        public string RawUri { get; set; } = string.Empty;
    }

    public static class VlessParser
    {
        public static VlessProfile? Parse(string rawUri)
        {
            var uri = rawUri.Trim();
            if (uri.StartsWith("vless://", StringComparison.OrdinalIgnoreCase))
            {
                return ParseVless(uri);
            }
            if (uri.StartsWith("hysteria2://", StringComparison.OrdinalIgnoreCase) || uri.StartsWith("hy2://", StringComparison.OrdinalIgnoreCase))
            {
                return ParseHysteria2(uri);
            }
            return null;
        }

        private static VlessProfile? ParseVless(string uriStr)
        {
            try
            {
                var uri = new Uri(uriStr);
                var query = HttpUtility.ParseQueryString(uri.Query);
                var name = !string.IsNullOrEmpty(uri.Fragment) ? Uri.UnescapeDataString(uri.Fragment.TrimStart('#')) : $"{uri.Host}:{uri.Port}";

                return new VlessProfile
                {
                    Name = name,
                    Protocol = "vless",
                    Address = uri.Host,
                    Port = uri.Port > 0 ? uri.Port : 443,
                    Uuid = uri.UserInfo,
                    Flow = query["flow"] ?? "",
                    Encryption = query["encryption"] ?? "none",
                    Transport = query["type"] ?? "tcp",
                    Security = query["security"] ?? "none",
                    Sni = query["sni"] ?? "",
                    Pbk = query["pbk"] ?? "",
                    Sid = query["sid"] ?? "",
                    Fp = query["fp"] ?? "chrome",
                    Path = query["path"] ?? "",
                    Host = query["host"] ?? "",
                    RawUri = uriStr
                };
            }
            catch
            {
                return null;
            }
        }

        private static VlessProfile? ParseHysteria2(string uriStr)
        {
            try
            {
                var uri = new Uri(uriStr.Replace("hy2://", "hysteria2://"));
                var query = HttpUtility.ParseQueryString(uri.Query);
                var name = !string.IsNullOrEmpty(uri.Fragment) ? Uri.UnescapeDataString(uri.Fragment.TrimStart('#')) : $"Hy2 - {uri.Host}:{uri.Port}";

                return new VlessProfile
                {
                    Name = name,
                    Protocol = "hysteria2",
                    Address = uri.Host,
                    Port = uri.Port > 0 ? uri.Port : 443,
                    Uuid = uri.UserInfo,
                    Sni = query["sni"] ?? "",
                    RawUri = uriStr
                };
            }
            catch
            {
                return null;
            }
        }
    }
}
