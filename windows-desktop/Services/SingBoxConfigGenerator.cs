using System;
using System.IO;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using ByeDpiRussia.Desktop.Models;

namespace ByeDpiRussia.Desktop.Services
{
    public static class SingBoxConfigGenerator
    {
        public static string GenerateWarpConfig(string warpConfText, bool tunMode, int localPort = 10808)
        {
            var privKey = ExtractRegex(warpConfText, @"PrivateKey\s*=\s*(.+)") ?? "Wli/uh1ka24/qHSysIhhvdIqOKz58Gid0cEwlI0Nfhc=";
            var pubKey = ExtractRegex(warpConfText, @"PublicKey\s*=\s*(.+)") ?? "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=";
            var endpoint = ExtractRegex(warpConfText, @"Endpoint\s*=\s*(.+)") ?? "188.114.98.8:854";

            var endpointHost = endpoint;
            var endpointPort = 854;
            if (endpoint.Contains(':'))
            {
                var parts = endpoint.Split(':');
                endpointHost = parts[0];
                if (int.TryParse(parts[1], out var p)) endpointPort = p;
            }

            var addressLine = ExtractRegex(warpConfText, @"Address\s*=\s*(.+)");
            var localAddresses = new JsonArray();
            if (!string.IsNullOrWhiteSpace(addressLine))
            {
                var addrs = addressLine.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
                foreach (var a in addrs)
                {
                    if (a.Contains('/')) localAddresses.Add(a);
                    else if (a.Contains(':')) localAddresses.Add($"{a}/128");
                    else localAddresses.Add($"{a}/32");
                }
            }
            if (localAddresses.Count == 0)
            {
                localAddresses.Add("172.16.0.2/32");
                localAddresses.Add("2606:4700:110:8700:31d8:595c:36c3:8014/128");
            }

            var root = new JsonObject
            {
                ["log"] = new JsonObject { ["level"] = "warn" },
                ["dns"] = CreateDnsConfig("wg-ep"),
                ["endpoints"] = new JsonArray
                {
                    new JsonObject
                    {
                        ["type"] = "wireguard",
                        ["tag"] = "wg-ep",
                        ["address"] = localAddresses,
                        ["private_key"] = privKey,
                        ["mtu"] = 1280,
                        ["peers"] = new JsonArray
                        {
                            new JsonObject
                            {
                                ["address"] = endpointHost,
                                ["port"] = endpointPort,
                                ["public_key"] = pubKey,
                                ["allowed_ips"] = new JsonArray { "0.0.0.0/0", "::/0" }
                            }
                        }
                    }
                },
                ["inbounds"] = CreateInbounds(tunMode, localPort),
                ["outbounds"] = new JsonArray
                {
                    new JsonObject { ["type"] = "direct", ["tag"] = "direct" }
                },
                ["route"] = CreateRouteConfig("wg-ep", tunMode)
            };

            var tmpPath = Path.Combine(Path.GetTempPath(), "byedpi_warp_singbox.json");
            File.WriteAllText(tmpPath, root.ToJsonString());
            return tmpPath;
        }

        public static string GenerateVlessConfig(VlessProfile profile, bool tunMode, int localPort = 10808)
        {
            var root = new JsonObject
            {
                ["log"] = new JsonObject { ["level"] = "warn" },
                ["dns"] = CreateDnsConfig("proxy"),
                ["inbounds"] = CreateInbounds(tunMode, localPort),
                ["outbounds"] = new JsonArray
                {
                    CreateVlessOutbound(profile),
                    new JsonObject { ["type"] = "direct", ["tag"] = "direct" }
                },
                ["route"] = CreateRouteConfig("proxy", tunMode)
            };

            var tmpPath = Path.Combine(Path.GetTempPath(), "byedpi_vless_singbox.json");
            File.WriteAllText(tmpPath, root.ToJsonString());
            return tmpPath;
        }

        private static JsonObject CreateDnsConfig(string targetDetour)
        {
            return new JsonObject
            {
                ["servers"] = new JsonArray
                {
                    new JsonObject
                    {
                        ["type"] = "udp",
                        ["tag"] = "dns-remote",
                        ["server"] = "1.1.1.1",
                        ["detour"] = targetDetour
                    }
                },
                ["strategy"] = "prefer_ipv4"
            };
        }

        private static JsonObject CreateRouteConfig(string finalTarget, bool tunMode)
        {
            var route = new JsonObject
            {
                ["auto_detect_interface"] = true,
                ["final"] = finalTarget
            };

            if (tunMode)
            {
                route["rules"] = new JsonArray
                {
                    new JsonObject
                    {
                        ["protocol"] = "dns",
                        ["action"] = "hijack-dns"
                    }
                };
            }

            return route;
        }

        private static JsonArray CreateInbounds(bool tunMode, int localPort)
        {
            var inbounds = new JsonArray
            {
                new JsonObject
                {
                    ["type"] = "mixed",
                    ["tag"] = "mixed-in",
                    ["listen"] = "127.0.0.1",
                    ["listen_port"] = localPort
                }
            };

            if (tunMode)
            {
                inbounds.Add(new JsonObject
                {
                    ["type"] = "tun",
                    ["tag"] = "tun-in",
                    ["interface_name"] = "byedpi-tun",
                    ["address"] = new JsonArray { "172.19.0.1/30", "fdfe:dcba:9876::1/126" },
                    ["auto_route"] = true,
                    ["strict_route"] = true,
                    ["stack"] = "mixed"
                });
            }

            return inbounds;
        }

        private static JsonObject CreateVlessOutbound(VlessProfile p)
        {
            var outbound = new JsonObject
            {
                ["type"] = p.Protocol.ToLowerInvariant() == "hysteria2" ? "hysteria2" : "vless",
                ["tag"] = "proxy",
                ["server"] = p.Address,
                ["server_port"] = p.Port
            };

            if (p.Protocol.ToLowerInvariant() == "hysteria2")
            {
                outbound["password"] = p.Uuid;
                if (!string.IsNullOrEmpty(p.Sni))
                {
                    outbound["tls"] = new JsonObject
                    {
                        ["enabled"] = true,
                        ["server_name"] = p.Sni
                    };
                }
            }
            else
            {
                outbound["uuid"] = p.Uuid;
                if (!string.IsNullOrEmpty(p.Flow)) outbound["flow"] = p.Flow;

                if (p.Security.Equals("reality", StringComparison.OrdinalIgnoreCase))
                {
                    outbound["tls"] = new JsonObject
                    {
                        ["enabled"] = true,
                        ["server_name"] = p.Sni,
                        ["reality"] = new JsonObject
                        {
                            ["enabled"] = true,
                            ["public_key"] = p.Pbk,
                            ["short_id"] = p.Sid
                        },
                        ["utls"] = new JsonObject
                        {
                            ["enabled"] = true,
                            ["fingerprint"] = string.IsNullOrEmpty(p.Fp) ? "chrome" : p.Fp
                        }
                    };
                }
                else if (p.Security.Equals("tls", StringComparison.OrdinalIgnoreCase))
                {
                    outbound["tls"] = new JsonObject
                    {
                        ["enabled"] = true,
                        ["server_name"] = p.Sni
                    };
                }
            }

            return outbound;
        }

        public static string? ExtractRegex(string input, string pattern)
        {
            var match = Regex.Match(input, pattern, RegexOptions.Multiline);
            return match.Success ? match.Groups[1].Value.Trim() : null;
        }
    }
}
