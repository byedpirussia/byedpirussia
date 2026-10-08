using System;
using System.Diagnostics;
using System.IO;

namespace ByeDpiRussia.Desktop.Services
{
    public class ProcessManager
    {
        private Process? _byedpiProcess;
        private Process? _singboxProcess;

        public bool IsByeDpiRunning => _byedpiProcess != null && !_byedpiProcess.HasExited;
        public bool IsSingBoxRunning => _singboxProcess != null && !_singboxProcess.HasExited;

        private static string CoreDirectory => Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "core");

        public bool StartByeDpi(string arguments, out string error)
        {
            StopByeDpi();
            error = string.Empty;

            var exePath = Path.Combine(CoreDirectory, "ciadpi.exe");
            if (!File.Exists(exePath))
            {
                error = $"Файл ciadpi.exe не найден по пути: {exePath}";
                return false;
            }

            try
            {
                var psi = new ProcessStartInfo
                {
                    FileName = exePath,
                    Arguments = arguments,
                    WorkingDirectory = CoreDirectory,
                    CreateNoWindow = true,
                    UseShellExecute = false,
                    RedirectStandardOutput = false,
                    RedirectStandardError = false
                };

                _byedpiProcess = Process.Start(psi);
                return _byedpiProcess != null && !_byedpiProcess.HasExited;
            }
            catch (Exception ex)
            {
                error = ex.Message;
                return false;
            }
        }

        public void StopByeDpi()
        {
            try
            {
                if (_byedpiProcess != null && !_byedpiProcess.HasExited)
                {
                    _byedpiProcess.Kill(true);
                    _byedpiProcess.WaitForExit(1000);
                }
            }
            catch { }
            finally
            {
                _byedpiProcess = null;
            }

            // Also kill any orphaned ciadpi processes
            KillOrphanedProcesses("ciadpi");
        }

        public bool StartSingBox(string configJsonPath, out string error)
        {
            StopSingBox();
            error = string.Empty;

            var exePath = Path.Combine(CoreDirectory, "sing-box.exe");
            if (!File.Exists(exePath))
            {
                error = $"Файл sing-box.exe не найден по пути: {exePath}";
                return false;
            }

            try
            {
                // Pre-flight validation
                var checkPsi = new ProcessStartInfo
                {
                    FileName = exePath,
                    Arguments = $"check -c \"{configJsonPath}\"",
                    WorkingDirectory = CoreDirectory,
                    CreateNoWindow = true,
                    UseShellExecute = false,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true
                };

                using (var checkProc = Process.Start(checkPsi))
                {
                    if (checkProc != null)
                    {
                        var checkErr = checkProc.StandardError.ReadToEnd();
                        var checkOut = checkProc.StandardOutput.ReadToEnd();
                        checkProc.WaitForExit(3000);
                        if (checkProc.ExitCode != 0)
                        {
                            error = !string.IsNullOrWhiteSpace(checkErr) ? checkErr : checkOut;
                            return false;
                        }
                    }
                }

                // Run sing-box with stdout/stderr capture
                var psi = new ProcessStartInfo
                {
                    FileName = exePath,
                    Arguments = $"run -c \"{configJsonPath}\"",
                    WorkingDirectory = CoreDirectory,
                    CreateNoWindow = true,
                    UseShellExecute = false,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true
                };

                var errBuilder = new System.Text.StringBuilder();
                _singboxProcess = new Process { StartInfo = psi, EnableRaisingEvents = true };
                _singboxProcess.ErrorDataReceived += (s, e) =>
                {
                    if (e.Data != null) lock (errBuilder) errBuilder.AppendLine(e.Data);
                };
                _singboxProcess.OutputDataReceived += (s, e) =>
                {
                    if (e.Data != null) lock (errBuilder) errBuilder.AppendLine(e.Data);
                };

                _singboxProcess.Start();
                _singboxProcess.BeginErrorReadLine();
                _singboxProcess.BeginOutputReadLine();

                System.Threading.Thread.Sleep(400);

                if (_singboxProcess.HasExited)
                {
                    lock (errBuilder) error = errBuilder.ToString().Trim();
                    if (string.IsNullOrWhiteSpace(error))
                    {
                        error = $"Процесс sing-box завершился с кодом {_singboxProcess.ExitCode}.";
                    }
                    return false;
                }

                return true;
            }
            catch (Exception ex)
            {
                error = ex.Message;
                return false;
            }
        }

        public void StopSingBox()
        {
            try
            {
                if (_singboxProcess != null && !_singboxProcess.HasExited)
                {
                    _singboxProcess.Kill(true);
                    _singboxProcess.WaitForExit(1000);
                }
            }
            catch { }
            finally
            {
                _singboxProcess?.Dispose();
                _singboxProcess = null;
            }

            KillOrphanedProcesses("sing-box");
        }

        public void StopAll()
        {
            StopByeDpi();
            StopSingBox();
        }

        private static void KillOrphanedProcesses(string processName)
        {
            try
            {
                foreach (var p in Process.GetProcessesByName(processName))
                {
                    try { p.Kill(true); } catch { }
                }
            }
            catch { }
        }
    }
}
