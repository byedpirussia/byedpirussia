using System;
using System.Windows;
using ByeDpiRussia.Desktop.Models;

namespace ByeDpiRussia.Desktop
{
    public partial class AddDnsWindow : Window
    {
        public WarpDnsPreset? CreatedPreset { get; private set; }

        public AddDnsWindow()
        {
            InitializeComponent();
        }

        private void BtnCancel_Click(object sender, RoutedEventArgs e)
        {
            DialogResult = false;
            Close();
        }

        private void BtnAdd_Click(object sender, RoutedEventArgs e)
        {
            var name = TxtDnsName.Text.Trim();
            var host = TxtDnsHost.Text.Trim();

            if (string.IsNullOrWhiteSpace(host))
            {
                MessageBox.Show("Пожалуйста, укажите адрес DNS сервера.", "Ошибка", MessageBoxButton.OK, MessageBoxImage.Warning);
                return;
            }

            if (string.IsNullOrWhiteSpace(name))
            {
                name = $"Пользовательский ({host})";
            }

            CreatedPreset = new WarpDnsPreset
            {
                Key = "custom_" + Guid.NewGuid().ToString("N")[..8],
                Title = name,
                HostOrIps = host,
                StaticIps = new() { host },
                IsCustom = true
            };

            DialogResult = true;
            Close();
        }
    }
}
