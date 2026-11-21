using System;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Cryptography.X509Certificates;
using System.Threading;
using System.Threading.Tasks;

namespace ByeDpiRussia.Desktop.Services
{
    public static class PingService
    {
        private const int DefaultTimeoutMs = 3000;

        public static async Task<long> MeasurePingByMethodAsync(string method, string address, int port, string? sni = null, int timeoutMs = DefaultTimeoutMs)
        {
            method = method?.ToLowerInvariant() ?? "proxy_get";
            return method switch
            {
                "tcp" => await MeasureTcpPingAsync(address, port, timeoutMs),
                "icmp" => await MeasureIcmpPingAsync(address, timeoutMs),
                _ => await MeasureProxyGetRealPingAsync(address, port, sni, timeoutMs)
            };
        }

        public static async Task<long> MeasureTcpPingAsync(string address, int port, int timeoutMs = DefaultTimeoutMs)
        {
            var sw = Stopwatch.StartNew();
            try
            {
                using var client = new TcpClient();
                using var cts = new CancellationTokenSource(timeoutMs);
                await client.ConnectAsync(address, port, cts.Token);
                sw.Stop();
                return sw.ElapsedMilliseconds > 0 ? sw.ElapsedMilliseconds : 1;
            }
            catch
            {
                return -1;
            }
        }

        public static async Task<long> MeasureIcmpPingAsync(string address, int timeoutMs = DefaultTimeoutMs)
        {
            var sw = Stopwatch.StartNew();
            try
            {
                using var ping = new System.Net.NetworkInformation.Ping();
                var reply = await ping.SendPingAsync(address, timeoutMs);
                sw.Stop();
                if (reply.Status == System.Net.NetworkInformation.IPStatus.Success)
                {
                    return reply.RoundtripTime > 0 ? reply.RoundtripTime : 1;
                }
                return await MeasureTcpPingAsync(address, 443, timeoutMs);
            }
            catch
            {
                return await MeasureTcpPingAsync(address, 443, timeoutMs);
            }
        }

        public static async Task<long> MeasureProxyGetRealPingAsync(string address, int port, string? sni = null, int timeoutMs = DefaultTimeoutMs)
        {
            var sw = Stopwatch.StartNew();
            try
            {
                using var client = new TcpClient();
                using var cts = new CancellationTokenSource(timeoutMs);
                await client.ConnectAsync(address, port, cts.Token);

                var targetSni = !string.IsNullOrWhiteSpace(sni) ? sni : address;
                using var sslStream = new SslStream(client.GetStream(), false, (s, cert, chain, errors) => true);
                
                var sslOptions = new SslClientAuthenticationOptions
                {
                    TargetHost = targetSni,
                    EnabledSslProtocols = System.Security.Authentication.SslProtocols.Tls12 | System.Security.Authentication.SslProtocols.Tls13
                };

                await sslStream.AuthenticateAsClientAsync(sslOptions, cts.Token);
                sw.Stop();
                return sw.ElapsedMilliseconds > 0 ? sw.ElapsedMilliseconds : 1;
            }
            catch
            {
                return await MeasureTcpPingAsync(address, port, timeoutMs);
            }
        }

        public static async Task<long> PingDnsServerAsync(string hostOrIp, int timeoutMs = 2500)
        {
            var sw = Stopwatch.StartNew();
            try
            {
                IPAddress? ip = null;
                if (!IPAddress.TryParse(hostOrIp.Trim(), out ip))
                {
                    var addrs = await Dns.GetHostAddressesAsync(hostOrIp.Trim());
                    if (addrs.Length > 0) ip = addrs[0];
                }

                if (ip == null) return -1;

                using var udp = new UdpClient();
                udp.Client.SendTimeout = timeoutMs;
                udp.Client.ReceiveTimeout = timeoutMs;

                // Стандартный DNS запрос для A-записи google.com (ID 0x1234, Flags 0x0100, QDCOUNT 1)
                byte[] query = new byte[]
                {
                    0x12, 0x34, // Transaction ID
                    0x01, 0x00, // Flags: Standard query
                    0x00, 0x01, // Questions: 1
                    0x00, 0x00, // Answer RRs: 0
                    0x00, 0x00, // Authority RRs: 0
                    0x00, 0x00, // Additional RRs: 0
                    // 6 'g' 'o' 'o' 'g' 'l' 'e'
                    0x06, 0x67, 0x6f, 0x6f, 0x67, 0x6c, 0x65,
                    // 3 'c' 'o' 'm'
                    0x03, 0x63, 0x6f, 0x6d,
                    0x00,       // End of name
                    0x00, 0x01, // Type: A (IPv4)
                    0x00, 0x01  // Class: IN
                };

                var ep = new IPEndPoint(ip, 53);
                await udp.SendAsync(query, query.Length, ep);

                using var cts = new CancellationTokenSource(timeoutMs);
                var recvTask = udp.ReceiveAsync(cts.Token).AsTask();
                var res = await recvTask;

                sw.Stop();
                if (res.Buffer != null && res.Buffer.Length > 12)
                {
                    return sw.ElapsedMilliseconds > 0 ? sw.ElapsedMilliseconds : 1;
                }
                return -1;
            }
            catch
            {
                // Fallback TCP к порту 53
                return await MeasureTcpPingAsync(hostOrIp, 53, timeoutMs);
            }
        }
    }
}
