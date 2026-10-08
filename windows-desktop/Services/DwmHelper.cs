using System;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;

namespace ByeDpiRussia.Desktop.Services
{
    public static class DwmHelper
    {
        private const int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;
        private const int DWMWA_WINDOW_CORNER_PREFERENCE = 33;
        private const int DWMWA_SYSTEMBACKDROP_TYPE = 38;
        private const int DWMWA_MICA_EFFECT = 1029;

        public enum BackdropType
        {
            Auto = 0,
            None = 1,
            MainWindow = 2, // Mica
            Transient = 3,  // Acrylic
            Tabbed = 4      // Mica Alt
        }

        public enum WindowCornerPreference
        {
            Default = 0,
            DoNotRound = 1,
            Round = 2,
            RoundSmall = 3
        }

        [DllImport("dwmapi.dll", PreserveSig = true)]
        private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int attrValue, int attrSize);

        public static void ApplyWindows11Backdrop(Window window, BackdropType backdropType = BackdropType.Tabbed, bool darkMode = true)
        {
            try
            {
                var hwnd = new WindowInteropHelper(window).EnsureHandle();
                if (hwnd == IntPtr.Zero) return;

                // 1. Immersive dark mode for titlebar and borders
                int dark = darkMode ? 1 : 0;
                DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, ref dark, sizeof(int));

                // 2. Windows 11 rounded window corners
                int round = (int)WindowCornerPreference.Round;
                DwmSetWindowAttribute(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, ref round, sizeof(int));

                // 3. System Backdrop (Build 22621+ uses DWMWA_SYSTEMBACKDROP_TYPE = 38)
                int backdrop = (int)backdropType;
                int hr = DwmSetWindowAttribute(hwnd, DWMWA_SYSTEMBACKDROP_TYPE, ref backdrop, sizeof(int));

                // Fallback for Windows 11 Build 22000 (DWMWA_MICA_EFFECT = 1029)
                if (hr != 0)
                {
                    int mica = 1;
                    DwmSetWindowAttribute(hwnd, DWMWA_MICA_EFFECT, ref mica, sizeof(int));
                }
            }
            catch { }
        }
    }
}
