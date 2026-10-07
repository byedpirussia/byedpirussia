using System;
using System.Collections.Generic;
using System.IO;
using System.Net.Http;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;

namespace ByeDpiRussia.Desktop.Services
{
    public class WarpGeneratorResult
    {
        public bool Success { get; set; }
        public string ConfigText { get; set; } = string.Empty;
        public string ErrorMessage { get; set; } = string.Empty;
        public string PrivateKey { get; set; } = string.Empty;
        public string PublicKey { get; set; } = string.Empty;
        public string Endpoint { get; set; } = string.Empty;
    }

    public static class WarpGeneratorService
    {
        private static readonly string[] Endpoints = new[]
        {
            "https://warp.sub-aggregator.workers.dev/",
            "https://warp-gen.netlify.app/",
            "https://warp-vercel-chi.vercel.app/api/warp-data",
            "https://warp-vercel-murex.vercel.app/api/warp-data",
            "https://www.warp-generator.workers.dev/"
        };

        private static readonly int[] Ports = new[]
        {
            500, 854, 859, 864, 878, 880, 890, 891, 894, 903, 908, 928, 934, 939,
            942, 943, 945, 946, 955, 968, 987, 988, 1002, 1010, 1014, 1018, 1070,
            1074, 1180, 1387, 1701, 1843, 2371, 2408, 2506, 3138, 3476, 3581, 3854,
            4177, 4198, 4233, 4500, 5279, 5956, 7103, 7152, 7156, 7281, 7559, 8319,
            8742, 8854, 8886
        };

        private static readonly string[] Prefixes = new[]
        {
            "162.159.192.",
            "162.159.195.",
            "engage.cloudflareclient.com",
            "8.6.112.",
            "8.34.70.",
            "8.34.146.",
            "8.35.211.",
            "8.39.125.",
            "8.39.204.",
            "8.39.214.",
            "8.47.69.",
            "188.114.96.",
            "188.114.97.",
            "188.114.98."
        };

        public static string GenerateRandomEndpoint()
        {
            var rand = Random.Shared;
            var port = Ports[rand.Next(Ports.Length)];
            var prefix = Prefixes[rand.Next(Prefixes.Length)];

            if (prefix == "engage.cloudflareclient.com")
            {
                return $"{prefix}:{port}";
            }

            var num = rand.Next(1, 11);
            return $"{prefix}{num}:{port}";
        }

        public static async Task<WarpGeneratorResult> GenerateAsync(CancellationToken cancellationToken = default)
        {
            using var httpClient = new HttpClient { Timeout = TimeSpan.FromSeconds(8) };
            httpClient.DefaultRequestHeaders.Add("User-Agent", "WARP-Generator/2.0");
            httpClient.DefaultRequestHeaders.Add("X-Client", "WARP");

            string lastError = string.Empty;

            foreach (var url in Endpoints)
            {
                if (cancellationToken.IsCancellationRequested) break;

                try
                {
                    var response = await httpClient.GetStringAsync(url, cancellationToken);
                    if (string.IsNullOrWhiteSpace(response)) continue;

                    var json = JsonNode.Parse(response);
                    if (json == null) continue;

                    var privKey = json["privKey"]?.ToString();
                    var peerPub = json["peer_pub"]?.ToString() ?? "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=";
                    var ipv4 = json["client_ipv4"]?.ToString() ?? "172.16.0.2";
                    var ipv6 = json["client_ipv6"]?.ToString() ?? "2606:4700:110:8700:31d8:595c:36c3:8014";

                    if (!string.IsNullOrWhiteSpace(privKey))
                    {
                        var endpoint = GenerateRandomEndpoint();
                        var confText = $@"[Interface]
PrivateKey = {privKey}
Address = {ipv4}, {ipv6}
DNS = 1.1.1.1, 1.0.0.1, 2606:4700:4700::1111, 2606:4700:4700::1001
MTU = 1280

[Peer]
PublicKey = {peerPub}
AllowedIPs = 0.0.0.0/0, ::/0
Endpoint = {endpoint}";

                        return new WarpGeneratorResult
                        {
                            Success = true,
                            ConfigText = confText,
                            PrivateKey = privKey,
                            PublicKey = peerPub,
                            Endpoint = endpoint
                        };
                    }
                }
                catch (Exception ex)
                {
                    lastError = ex.Message;
                }
            }

            return new WarpGeneratorResult
            {
                Success = false,
                ErrorMessage = !string.IsNullOrEmpty(lastError)
                    ? $"Ошибка связи с сервисами генерации WARP: {lastError}"
                    : "Не удалось получить ключи от серверов генерации WARP."
            };
        }
    }
}
