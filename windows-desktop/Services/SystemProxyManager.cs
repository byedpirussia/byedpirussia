using System;
using System.Runtime.InteropServices;
using Microsoft.Win32;

namespace ByeDpiRussia.Desktop.Services
{
    public static class SystemProxyManager
    {
        private const int INTERNET_OPTION_SETTINGS_CHANGED = 39;
        private const int INTERNET_OPTION_REFRESH = 37;

        [DllImport("wininet.dll", SetLastError = true)]
        private static extern bool InternetSetOption(IntPtr hInternet, int dwOption, IntPtr lpBuffer, int dwBufferLength);

        private const string REG_KEY = @"Software\Microsoft\Windows\CurrentVersion\Internet Settings";

        public static void SetProxy(bool enable, string host = "127.0.0.1", int port = 1080)
        {
            try
            {
                using var key = Registry.CurrentUser.OpenSubKey(REG_KEY, true);
                if (key != null)
                {
                    if (enable)
                    {
                        key.SetValue("ProxyEnable", 1, RegistryValueKind.DWord);
                        key.SetValue("ProxyServer", $"{host}:{port}", RegistryValueKind.String);
                        key.SetValue("ProxyOverride", "localhost;127.*;10.*;172.16.*;192.168.*;<local>", RegistryValueKind.String);
                    }
                    else
                    {
                        key.SetValue("ProxyEnable", 0, RegistryValueKind.DWord);
                    }
                }

                // Notify Windows subsystems and browsers (Edge, Chrome, etc.)
                InternetSetOption(IntPtr.Zero, INTERNET_OPTION_SETTINGS_CHANGED, IntPtr.Zero, 0);
                InternetSetOption(IntPtr.Zero, INTERNET_OPTION_REFRESH, IntPtr.Zero, 0);
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"Failed to set system proxy: {ex.Message}");
            }
        }

        public static bool IsProxyEnabled()
        {
            try
            {
                using var key = Registry.CurrentUser.OpenSubKey(REG_KEY, false);
                if (key != null)
                {
                    var val = key.GetValue("ProxyEnable");
                    return val is int i && i == 1;
                }
            }
            catch { }
            return false;
        }
    }
}
