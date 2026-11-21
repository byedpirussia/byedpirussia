using System;
using System.Windows;

namespace ByeDpiRussia.Desktop
{
    public partial class PingMethodWindow : Window
    {
        public string SelectedMethod { get; private set; } = "proxy_get";

        public PingMethodWindow(string currentMethod)
        {
            InitializeComponent();
            SelectedMethod = currentMethod.ToLowerInvariant();

            if (SelectedMethod == "tcp") RbTcp.IsChecked = true;
            else if (SelectedMethod == "icmp") RbIcmp.IsChecked = true;
            else RbProxyGet.IsChecked = true;
        }

        private void BtnCancel_Click(object sender, RoutedEventArgs e)
        {
            DialogResult = false;
            Close();
        }

        private void BtnSave_Click(object sender, RoutedEventArgs e)
        {
            if (RbTcp.IsChecked == true) SelectedMethod = "tcp";
            else if (RbIcmp.IsChecked == true) SelectedMethod = "icmp";
            else SelectedMethod = "proxy_get";

            DialogResult = true;
            Close();
        }
    }
}
