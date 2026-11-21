using System;
using System.Collections.Generic;
using System.Windows;
using ByeDpiRussia.Desktop.Models;

namespace ByeDpiRussia.Desktop
{
    public partial class EditServerWindow : Window
    {
        private readonly VlessProfile _profile;
        private readonly List<Strategy> _strategies;

        public EditServerWindow(VlessProfile profile, List<Strategy> strategies)
        {
            InitializeComponent();
            _profile = profile;
            _strategies = strategies;

            TxtName.Text = _profile.Name;
            TxtAddress.Text = _profile.Address;
            TxtPort.Text = _profile.Port.ToString();
            TxtUuid.Text = _profile.Uuid;
            TxtSni.Text = _profile.Sni;
            TxtFp.Text = string.IsNullOrWhiteSpace(_profile.Fp) ? "chrome" : _profile.Fp;
            TxtPbk.Text = _profile.Pbk;
            TxtSid.Text = _profile.Sid;

            var items = new List<Strategy>
            {
                new Strategy { Id = "", Name = "❌ Не использовать ByeDPI (Прямое подключение)", Description = "", Args = "", RecommendedFor = "Стандарт" }
            };
            items.AddRange(_strategies);
            CmbStrategy.ItemsSource = items;

            var matched = items.Find(s => s.Id == _profile.DesyncStrategyId) ?? items[0];
            CmbStrategy.SelectedItem = matched;
        }

        private void BtnCancel_Click(object sender, RoutedEventArgs e)
        {
            DialogResult = false;
            Close();
        }

        private void BtnSave_Click(object sender, RoutedEventArgs e)
        {
            _profile.Name = TxtName.Text.Trim();
            _profile.Address = TxtAddress.Text.Trim();
            if (int.TryParse(TxtPort.Text.Trim(), out var p)) _profile.Port = p;
            _profile.Uuid = TxtUuid.Text.Trim();
            _profile.Sni = TxtSni.Text.Trim();
            _profile.Fp = TxtFp.Text.Trim();
            _profile.Pbk = TxtPbk.Text.Trim();
            _profile.Sid = TxtSid.Text.Trim();

            if (CmbStrategy.SelectedItem is Strategy selectedStrategy)
            {
                _profile.DesyncStrategyId = selectedStrategy.Id;
            }

            DialogResult = true;
            Close();
        }
    }
}
