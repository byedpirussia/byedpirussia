using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using ByeDpiRussia.Desktop.Models;

namespace ByeDpiRussia.Desktop.Services
{
    public class StrategyBenchmarkService
    {
        private const int BenchmarkPort = 11080;
        private const int ConnectTimeoutMs = 2500;
        private const int HandshakeTimeoutMs = 3000;

        private static readonly (string Host, string Label)[] Targets = new[]
        {
            ("www.youtube.com", "YouTube"),
            ("discord.com", "Discord")
        };

        private static readonly Dictionary<string, string> FallbackIps = new()
        {
            { "www.youtube.com", "142.251.152.4" },
            { "discord.com", "162.159.137.232" }
        };

        private static string CoreDirectory => Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "core");

        public async Task<StrategyResult?> RunBenchmarkAsync(
            Action<int, int, Strategy, string> onProgress,
            Action<StrategyResult> onStrategyTested,
            CancellationToken cancellationToken)
        {
            var strategies = Strategy.GetPresetStrategies();
            var results = new List<StrategyResult>();

            for (int i = 0; i < strategies.Count; i++)
            {
                if (cancellationToken.IsCancellationRequested) break;

                var strat = strategies[i];
                onProgress(i + 1, strategies.Count, strat, $"{strat.Name}...");

                Process? testProcess = null;
                try
                {
                    // 1. Launch temporary ciadpi instance on port 11080
                    testProcess = StartTestProxy(strat.Args);
                    await Task.Delay(150, cancellationToken); // Wait for socket bind

                    var targetResults = new Dictionary<string, long?>();
                    int successes = 0;
                    long totalLatency = 0;

                    for (int targetIdx = 0; targetIdx < Targets.Length; targetIdx++)
                    {
                        if (cancellationToken.IsCancellationRequested) break;

                        var (host, label) = Targets[targetIdx];
                        onProgress(i + 1, strategies.Count, strat, $"{strat.Name} — проверка {label} ({targetIdx + 1}/{Targets.Length})...");

                        var latency = await TestTlsThroughSocks5Async(host, 443, BenchmarkPort, cancellationToken);
                        targetResults[label] = latency;

                        if (latency.HasValue)
                        {
                            successes++;
                            totalLatency += latency.Value;
                        }
                    }

                    var avgLatency = successes > 0 ? totalLatency / successes : 9999;
                    var res = new StrategyResult
                    {
                        Strategy = strat,
                        SuccessCount = successes,
                        AverageLatencyMs = avgLatency,
                        Details = targetResults
                    };

                    results.Add(res);
                    onStrategyTested(res);
                }
                catch (OperationCanceledException)
                {
                    break;
                }
                catch
                {
                    // In case of error on current strategy, continue to next
                }
                finally
                {
                    StopTestProxy(testProcess);
                    await Task.Delay(80, CancellationToken.None);
                }
            }

            // Find best strategy: highest success count, lowest average latency
            var best = results
                .Where(r => r.SuccessCount > 0)
                .OrderByDescending(r => r.SuccessCount)
                .ThenBy(r => r.AverageLatencyMs)
                .FirstOrDefault();

            return best;
        }

        private static Process? StartTestProxy(string strategyArgs)
        {
            var exePath = Path.Combine(CoreDirectory, "ciadpi.exe");
            if (!File.Exists(exePath)) return null;

            try
            {
                var psi = new ProcessStartInfo
                {
                    FileName = exePath,
                    Arguments = $"-i 127.0.0.1 -p {BenchmarkPort} {strategyArgs}",
                    WorkingDirectory = CoreDirectory,
                    CreateNoWindow = true,
                    UseShellExecute = false,
                    RedirectStandardOutput = false,
                    RedirectStandardError = false
                };
                return Process.Start(psi);
            }
            catch
            {
                return null;
            }
        }

        private static void StopTestProxy(Process? process)
        {
            if (process == null) return;
            try
            {
                if (!process.HasExited)
                {
                    process.Kill(true);
                    process.WaitForExit(300);
                }
            }
            catch { }
            finally
            {
                process.Dispose();
            }
        }

        private static async Task<long?> TestTlsThroughSocks5Async(
            string host, int port, int proxyPort, CancellationToken parentToken)
        {
            using var timeoutCts = new CancellationTokenSource(ConnectTimeoutMs + HandshakeTimeoutMs);
            using var linkedCts = CancellationTokenSource.CreateLinkedTokenSource(parentToken, timeoutCts.Token);

            TcpClient? tcp = null;
            SslStream? ssl = null;
            var sw = Stopwatch.StartNew();

            try
            {
                tcp = new TcpClient();
                await tcp.ConnectAsync("127.0.0.1", proxyPort, linkedCts.Token);
                var stream = tcp.GetStream();

                // 1. SOCKS5 Greeting: VER=5, NMETHODS=1, METHOD=0 (No Auth)
                await stream.WriteAsync(new byte[] { 0x05, 0x01, 0x00 }, linkedCts.Token);
                var authResp = new byte[2];
                await stream.ReadExactlyAsync(authResp, 0, 2, linkedCts.Token);
                if (authResp[0] != 0x05 || authResp[1] != 0x00) return null;

                // 2. SOCKS5 CONNECT request
                var hostBytes = Encoding.ASCII.GetBytes(host);
                var req = new byte[7 + hostBytes.Length];
                req[0] = 0x05; // VER
                req[1] = 0x01; // CMD: CONNECT
                req[2] = 0x00; // RSV
                req[3] = 0x03; // ATYP: DOMAINNAME
                req[4] = (byte)hostBytes.Length;
                Buffer.BlockCopy(hostBytes, 0, req, 5, hostBytes.Length);
                req[5 + hostBytes.Length] = (byte)(port >> 8);
                req[6 + hostBytes.Length] = (byte)(port & 0xFF);

                await stream.WriteAsync(req, linkedCts.Token);

                // Read server response header (4 bytes: VER, REP, RSV, ATYP)
                var connHeader = new byte[4];
                await stream.ReadExactlyAsync(connHeader, 0, 4, linkedCts.Token);
                if (connHeader[1] != 0x00) return null; // 0x00 = success

                // Drain remaining BND.ADDR and BND.PORT
                int remainingBytes = connHeader[3] switch
                {
                    0x01 => 6, // IPv4: 4 bytes addr + 2 port
                    0x04 => 18, // IPv6: 16 bytes addr + 2 port
                    0x03 => await ReadDomainLengthAndPortAsync(stream, linkedCts.Token),
                    _ => 6
                };

                var dummy = new byte[remainingBytes];
                await stream.ReadExactlyAsync(dummy, 0, remainingBytes, linkedCts.Token);

                // 3. Perform TLS Handshake with SNI
                ssl = new SslStream(stream, false, (s, cert, chain, errs) => true);
                var sslOptions = new SslClientAuthenticationOptions
                {
                    TargetHost = host,
                    EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13
                };

                await ssl.AuthenticateAsClientAsync(sslOptions, linkedCts.Token);
                sw.Stop();

                return sw.ElapsedMilliseconds;
            }
            catch
            {
                return null;
            }
            finally
            {
                ssl?.Dispose();
                tcp?.Dispose();
            }
        }

        private static async Task<int> ReadDomainLengthAndPortAsync(NetworkStream stream, CancellationToken token)
        {
            var lenBuf = new byte[1];
            await stream.ReadExactlyAsync(lenBuf, 0, 1, token);
            return lenBuf[0] + 2; // domain length + 2 bytes port
        }
    }
}
