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
                var psi = new ProcessStartInfo
                {
                    FileName = exePath,
                    Arguments = $"run -c \"{configJsonPath}\"",
                    WorkingDirectory = CoreDirectory,
                    CreateNoWindow = true,
                    UseShellExecute = false,
                    RedirectStandardOutput = false,
                    RedirectStandardError = false
                };

                _singboxProcess = Process.Start(psi);
                return _singboxProcess != null && !_singboxProcess.HasExited;
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
