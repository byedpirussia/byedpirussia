using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Controls;
using Microsoft.Win32;
using ByeDpiRussia.Desktop.Models;
using ByeDpiRussia.Desktop.Services;
using MessageBox = System.Windows.MessageBox;
using OpenFileDialog = Microsoft.Win32.OpenFileDialog;
using TextBox = System.Windows.Controls.TextBox;
using Button = System.Windows.Controls.Button;
using Clipboard = System.Windows.Clipboard;

namespace ByeDpiRussia.Desktop
{
    public partial class MainWindow : Window
    {
        private readonly ProcessManager _processManager = new();
        private readonly StrategyBenchmarkService _benchmarkService = new();
        private readonly ObservableCollection<Strategy> _strategies = new();
        private readonly ObservableCollection<VlessProfile> _vlessProfiles = new();

        private CancellationTokenSource? _benchmarkCts;
        private System.Windows.Forms.NotifyIcon? _notifyIcon;
        private bool _isRealExit = false;
        private bool _isSwitching = false;

        private static bool IsAdministrator()
        {
            try
            {
                using var identity = System.Security.Principal.WindowsIdentity.GetCurrent();
                var principal = new System.Security.Principal.WindowsPrincipal(identity);
                return principal.IsInRole(System.Security.Principal.WindowsBuiltInRole.Administrator);
            }
            catch
            {
                return false;
            }
        }

        private string _warpConfigText = @"[Interface]
PrivateKey = Wli/uh1ka24/qHSysIhhvdIqOKz58Gid0cEwlI0Nfhc=
Address = 172.16.0.2, 2606:4700:110:8700:31d8:595c:36c3:8014
DNS = 1.1.1.1, 1.0.0.1
MTU = 1280
[Peer]
PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
AllowedIPs = 0.0.0.0/0, ::/0
Endpoint = 188.114.98.8:854";

        private static string SettingsFilePath =>
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "ByeDpiRussia", "desktop_settings.json");

        public MainWindow()
        {
            InitializeComponent();
            InitTrayIcon();
            InitData();
            LoadSettings();
        }

        private void InitTrayIcon()
        {
            try
            {
                _notifyIcon = new System.Windows.Forms.NotifyIcon
                {
                    Text = "ByeDPI Russia Desktop",
                    Icon = System.Drawing.SystemIcons.Shield,
                    Visible = true
                };

                var contextMenu = new System.Windows.Forms.ContextMenuStrip();
                contextMenu.Items.Add("Развернуть", null, (s, e) => ShowAndRestore());
                contextMenu.Items.Add(new System.Windows.Forms.ToolStripSeparator());
                contextMenu.Items.Add("Отключить всё", null, (s, e) => Dispatcher.Invoke(DisconnectAll));
                contextMenu.Items.Add("Выход", null, (s, e) => Dispatcher.Invoke(ExitApplication));

                _notifyIcon.ContextMenuStrip = contextMenu;
                _notifyIcon.DoubleClick += (s, e) => ShowAndRestore();
            }
            catch { }
        }

        private void ShowAndRestore()
        {
            Show();
            WindowState = WindowState.Normal;
            Activate();
        }

        private void InitData()
        {
            foreach (var strat in Strategy.GetPresetStrategies())
            {
                _strategies.Add(strat);
            }
            CmbByeDpiStrategies.ItemsSource = _strategies;
            CmbByeDpiStrategies.SelectedIndex = 0;

            CmbVlessProfiles.ItemsSource = _vlessProfiles;
        }

        private void LoadSettings()
        {
            try
            {
                if (!File.Exists(SettingsFilePath)) return;

                var json = File.ReadAllText(SettingsFilePath);
                var root = JsonNode.Parse(json);
                if (root == null) return;

                var warp = root["warp_config"]?.ToString();
                if (!string.IsNullOrWhiteSpace(warp)) _warpConfigText = warp;

                var profilesNode = root["vless_profiles"]?.AsArray();
                if (profilesNode != null)
                {
                    _vlessProfiles.Clear();
                    foreach (var item in profilesNode)
                    {
                        var p = item.Deserialize<VlessProfile>();
                        if (p != null) _vlessProfiles.Add(p);
                    }
                }

                if (_vlessProfiles.Count > 0)
                {
                    CmbVlessProfiles.SelectedIndex = 0;
                }
            }
            catch { }
        }

        private void SaveSettings()
        {
            try
            {
                var dir = Path.GetDirectoryName(SettingsFilePath);
                if (!string.IsNullOrEmpty(dir)) Directory.CreateDirectory(dir);

                var root = new JsonObject
                {
                    ["warp_config"] = _warpConfigText,
                    ["vless_profiles"] = JsonSerializer.SerializeToNode(_vlessProfiles)
                };

                File.WriteAllText(SettingsFilePath, root.ToJsonString());
            }
            catch { }
        }

        #region Service Toggles

        private void SwitchByeDpi_Toggled(object sender, RoutedEventArgs e)
        {
            if (_isSwitching) return;

            if (SwitchByeDpi.IsOn)
            {
                try
                {
                    _isSwitching = true;
                    if (SwitchWarp.IsOn) SwitchWarp.IsOn = false;
                    if (SwitchVless.IsOn) SwitchVless.IsOn = false;
                }
                finally
                {
                    _isSwitching = false;
                }

                try
                {
                    var strat = (Strategy)CmbByeDpiStrategies.SelectedItem;
                    var rawArgs = strat?.Args ?? "-d1 -d3+s -s6+s -r1+s";
                    var args = rawArgs.Contains("-i ") ? rawArgs : $"-i 127.0.0.1 -p 1080 {rawArgs}";

                    if (_processManager.StartByeDpi(args, out var err))
                    {
                        if (ChkSystemProxy.IsChecked == true)
                        {
                            SystemProxyManager.SetProxy(true, "127.0.0.1", 1080);
                        }
                        UpdateHeroStatus("🟢 ByeDPI активен • Прямой обход DPI (YouTube в 4K, Discord)", true);
                    }
                    else
                    {
                        _isSwitching = true;
                        SwitchByeDpi.IsOn = false;
                        _isSwitching = false;
                        _processManager.StopByeDpi();
                        SystemProxyManager.SetProxy(false);
                        CheckAnyActive();
                        MessageBox.Show($"Не удалось запустить ByeDPI:\n\n{err}", "Ошибка запуска ByeDPI", MessageBoxButton.OK, MessageBoxImage.Error);
                    }
                }
                catch (Exception ex)
                {
                    _isSwitching = true;
                    SwitchByeDpi.IsOn = false;
                    _isSwitching = false;
                    _processManager.StopByeDpi();
                    SystemProxyManager.SetProxy(false);
                    CheckAnyActive();
                    MessageBox.Show($"Ошибка при запуске ByeDPI:\n\n{ex.Message}", "Ошибка", MessageBoxButton.OK, MessageBoxImage.Error);
                }
            }
            else
            {
                _processManager.StopByeDpi();
                if (ChkSystemProxy.IsChecked == true)
                {
                    SystemProxyManager.SetProxy(false);
                }
                CheckAnyActive();
            }
        }

        private void SwitchWarp_Toggled(object sender, RoutedEventArgs e)
        {
            if (_isSwitching) return;

            if (SwitchWarp.IsOn)
            {
                try
                {
                    _isSwitching = true;
                    if (SwitchByeDpi.IsOn) SwitchByeDpi.IsOn = false;
                    if (SwitchVless.IsOn) SwitchVless.IsOn = false;
                }
                finally
                {
                    _isSwitching = false;
                }

                var tunMode = ChkTunMode.IsChecked == true;
                if (tunMode && !IsAdministrator())
                {
                    _isSwitching = true;
                    SwitchWarp.IsOn = false;
                    _isSwitching = false;
                    MessageBox.Show(
                        "Для работы виртуального сетевого адаптера (Wintun TUN) требуются права администратора.\n\nЗапустите приложение от имени администратора или снимите флажок «Виртуальный адаптер Wintun», чтобы использовать системный прокси (работает без прав админа).",
                        "Требуются права администратора",
                        MessageBoxButton.OK,
                        MessageBoxImage.Warning);
                    return;
                }

                try
                {
                    var configPath = SingBoxConfigGenerator.GenerateWarpConfig(_warpConfigText, tunMode, 10808);

                    if (_processManager.StartSingBox(configPath, out var err))
                    {
                        if (!tunMode && ChkSystemProxy.IsChecked == true)
                        {
                            SystemProxyManager.SetProxy(true, "127.0.0.1", 10808);
                        }
                        var modeDesc = tunMode ? "Виртуальный адаптер Wintun" : "Системный прокси 127.0.0.1:10808";
                        UpdateHeroStatus($"🟢 Cloudflare WARP подключен • {modeDesc}", true);
                    }
                    else
                    {
                        _isSwitching = true;
                        SwitchWarp.IsOn = false;
                        _isSwitching = false;
                        _processManager.StopSingBox();
                        SystemProxyManager.SetProxy(false);
                        CheckAnyActive();
                        MessageBox.Show($"Не удалось запустить Cloudflare WARP:\n\n{err}", "Ошибка запуска WARP", MessageBoxButton.OK, MessageBoxImage.Error);
                    }
                }
                catch (Exception ex)
                {
                    _isSwitching = true;
                    SwitchWarp.IsOn = false;
                    _isSwitching = false;
                    _processManager.StopSingBox();
                    SystemProxyManager.SetProxy(false);
                    CheckAnyActive();
                    MessageBox.Show($"Ошибка при настройке Cloudflare WARP:\n\n{ex.Message}", "Ошибка", MessageBoxButton.OK, MessageBoxImage.Error);
                }
            }
            else
            {
                _processManager.StopSingBox();
                if (ChkSystemProxy.IsChecked == true)
                {
                    SystemProxyManager.SetProxy(false);
                }
                CheckAnyActive();
            }
        }

        private void SwitchVless_Toggled(object sender, RoutedEventArgs e)
        {
            if (_isSwitching) return;

            if (SwitchVless.IsOn)
            {
                if (_vlessProfiles.Count == 0)
                {
                    _isSwitching = true;
                    SwitchVless.IsOn = false;
                    _isSwitching = false;
                    MessageBox.Show("Сначала добавьте хотя бы один сервер VLESS / Hysteria 2 с помощью кнопки «+ Добавить ключ / ссылку».", "Нет серверов", MessageBoxButton.OK, MessageBoxImage.Warning);
                    return;
                }

                try
                {
                    _isSwitching = true;
                    if (SwitchByeDpi.IsOn) SwitchByeDpi.IsOn = false;
                    if (SwitchWarp.IsOn) SwitchWarp.IsOn = false;
                }
                finally
                {
                    _isSwitching = false;
                }

                var tunMode = ChkTunMode.IsChecked == true;
                if (tunMode && !IsAdministrator())
                {
                    _isSwitching = true;
                    SwitchVless.IsOn = false;
                    _isSwitching = false;
                    MessageBox.Show(
                        "Для работы виртуального сетевого адаптера (Wintun TUN) требуются права администратора.\n\nЗапустите приложение от имени администратора или снимите флажок «Виртуальный адаптер Wintun», чтобы использовать системный прокси (работает без прав админа).",
                        "Требуются права администратора",
                        MessageBoxButton.OK,
                        MessageBoxImage.Warning);
                    return;
                }

                try
                {
                    var profile = (VlessProfile)CmbVlessProfiles.SelectedItem ?? _vlessProfiles.First();
                    var configPath = SingBoxConfigGenerator.GenerateVlessConfig(profile, tunMode, 10808);

                    if (_processManager.StartSingBox(configPath, out var err))
                    {
                        if (!tunMode && ChkSystemProxy.IsChecked == true)
                        {
                            SystemProxyManager.SetProxy(true, "127.0.0.1", 10808);
                        }
                        var modeDesc = tunMode ? "Wintun TUN" : "Прокси 127.0.0.1:10808";
                        UpdateHeroStatus($"🟢 {profile.Name} подключен • {modeDesc}", true);
                    }
                    else
                    {
                        _isSwitching = true;
                        SwitchVless.IsOn = false;
                        _isSwitching = false;
                        _processManager.StopSingBox();
                        SystemProxyManager.SetProxy(false);
                        CheckAnyActive();
                        MessageBox.Show($"Не удалось запустить VLESS:\n\n{err}", "Ошибка запуска", MessageBoxButton.OK, MessageBoxImage.Error);
                    }
                }
                catch (Exception ex)
                {
                    _isSwitching = true;
                    SwitchVless.IsOn = false;
                    _isSwitching = false;
                    _processManager.StopSingBox();
                    SystemProxyManager.SetProxy(false);
                    CheckAnyActive();
                    MessageBox.Show($"Ошибка при запуске VLESS:\n\n{ex.Message}", "Ошибка", MessageBoxButton.OK, MessageBoxImage.Error);
                }
            }
            else
            {
                _processManager.StopSingBox();
                if (ChkSystemProxy.IsChecked == true)
                {
                    SystemProxyManager.SetProxy(false);
                }
                CheckAnyActive();
            }
        }

        #endregion

        #region UI Handlers

        private void CmbByeDpiStrategies_SelectionChanged(object sender, SelectionChangedEventArgs e)
        {
            if (CmbByeDpiStrategies.SelectedItem is Strategy s)
            {
                TxtStrategyDesc.Text = s.Description;
                if (SwitchByeDpi.IsOn)
                {
                    // Restart ByeDPI with new strategy
                    SwitchByeDpi.IsOn = false;
                    SwitchByeDpi.IsOn = true;
                }
            }
        }

        private void BtnDisconnectAll_Click(object sender, RoutedEventArgs e)
        {
            DisconnectAll();
        }

        private void DisconnectAll()
        {
            SwitchByeDpi.IsOn = false;
            SwitchWarp.IsOn = false;
            SwitchVless.IsOn = false;
            _processManager.StopAll();
            SystemProxyManager.SetProxy(false);
            UpdateHeroStatus("Все службы отключены • Выберите службу для обхода блокировок", false);
        }

        private void UpdateHeroStatus(string text, bool hasActive)
        {
            TxtHeroStatus.Text = text;
            BtnDisconnectAll.Visibility = hasActive ? Visibility.Visible : Visibility.Collapsed;
        }

        private void CheckAnyActive()
        {
            if (!SwitchByeDpi.IsOn && !SwitchWarp.IsOn && !SwitchVless.IsOn)
            {
                UpdateHeroStatus("Все службы отключены • Выберите службу для обхода блокировок", false);
            }
        }

        private void ChkSystemProxy_Click(object sender, RoutedEventArgs e)
        {
            if (ChkSystemProxy.IsChecked == true)
            {
                if (SwitchByeDpi.IsOn) SystemProxyManager.SetProxy(true, "127.0.0.1", 1080);
                else if (SwitchWarp.IsOn || SwitchVless.IsOn) SystemProxyManager.SetProxy(true, "127.0.0.1", 10808);
            }
            else
            {
                SystemProxyManager.SetProxy(false);
            }
        }

        private void ChkTunMode_Click(object sender, RoutedEventArgs e)
        {
            if (ChkTunMode.IsChecked == true)
            {
                MessageBox.Show("Режим Wintun TUN активирует виртуальный сетевой адаптер в системе. Это туннелирует все приложения (включая голосовые каналы Discord и игры) без настройки прокси.", "Виртуальный адаптер", MessageBoxButton.OK, MessageBoxImage.Information);
            }
        }

        private void BtnEditWarpConfig_Click(object sender, RoutedEventArgs e)
        {
            var dialog = new Window
            {
                Title = "Конфигурация Cloudflare WARP (.conf)",
                Width = 550,
                Height = 450,
                WindowStartupLocation = WindowStartupLocation.CenterOwner,
                Owner = this
            };

            var tb = new TextBox
            {
                Text = _warpConfigText,
                AcceptsReturn = true,
                VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
                FontFamily = new System.Windows.Media.FontFamily("Consolas"),
                Margin = new Thickness(16)
            };

            var btnSave = new Button { Content = "Сохранить", Margin = new Thickness(16, 0, 16, 16), Height = 36 };
            btnSave.Click += (s, args) =>
            {
                _warpConfigText = tb.Text.Trim();
                SaveSettings();
                dialog.Close();
                MessageBox.Show("Конфигурация WARP успешно сохранена!", "Успешно", MessageBoxButton.OK, MessageBoxImage.Information);
            };

            var panel = new DockPanel();
            DockPanel.SetDock(btnSave, Dock.Bottom);
            panel.Children.Add(btnSave);
            panel.Children.Add(tb);

            dialog.Content = panel;
            dialog.ShowDialog();
        }

        private void BtnImportWarpFile_Click(object sender, RoutedEventArgs e)
        {
            var ofd = new OpenFileDialog
            {
                Filter = "Конфигурации WireGuard (*.conf)|*.conf|Все файлы (*.*)|*.*",
                Title = "Выберите файл конфигурации WARP (.conf)"
            };

            if (ofd.ShowDialog() == true)
            {
                try
                {
                    _warpConfigText = File.ReadAllText(ofd.FileName);
                    SaveSettings();
                    MessageBox.Show("Конфигурация WARP успешно загружена из файла!", "Успешно", MessageBoxButton.OK, MessageBoxImage.Information);
                }
                catch (Exception ex)
                {
                    MessageBox.Show($"Ошибка чтения файла: {ex.Message}", "Ошибка", MessageBoxButton.OK, MessageBoxImage.Error);
                }
            }
        }

        private async void BtnGenerateWarp_Click(object sender, RoutedEventArgs e)
        {
            BtnGenerateWarp.IsEnabled = false;
            TxtWarpStatus.Text = "⏳ Обращение к серверам Cloudflare и генерация ключей... Пожалуйста, подождите.";

            try
            {
                var res = await WarpGeneratorService.GenerateAsync();
                if (res.Success)
                {
                    _warpConfigText = res.ConfigText;
                    SaveSettings();

                    TxtWarpStatus.Text = $"✅ Новый профиль Cloudflare WARP успешно сгенерирован!\nEndpoint: {res.Endpoint} • MTU: 1280";

                    _notifyIcon?.ShowBalloonTip(3000, "Cloudflare WARP", "Сгенерирован и настроен новый рабочий ключ WARP!", System.Windows.Forms.ToolTipIcon.Info);

                    if (SwitchWarp.IsOn)
                    {
                        SwitchWarp.IsOn = false;
                        SwitchWarp.IsOn = true;
                    }

                    var keyPreview = res.PrivateKey.Length > 8 ? res.PrivateKey[..8] + "..." : res.PrivateKey;
                    MessageBox.Show($"✨ Ключ Cloudflare WARP успешно сгенерирован!\n\n• Endpoint: {res.Endpoint}\n• Private Key: {keyPreview}\n\nКонфигурация WireGuard сохранена и готова к подключению.", "WARP Generator", MessageBoxButton.OK, MessageBoxImage.Information);
                }
                else
                {
                    TxtWarpStatus.Text = $"❌ {res.ErrorMessage}";
                    MessageBox.Show($"Не удалось сгенерировать конфигурацию WARP:\n{res.ErrorMessage}", "Ошибка генерации", MessageBoxButton.OK, MessageBoxImage.Error);
                }
            }
            catch (Exception ex)
            {
                TxtWarpStatus.Text = $"❌ Ошибка: {ex.Message}";
                MessageBox.Show($"Ошибка: {ex.Message}", "Ошибка", MessageBoxButton.OK, MessageBoxImage.Error);
            }
            finally
            {
                BtnGenerateWarp.IsEnabled = true;
            }
        }

        private async void BtnBenchmark_Click(object sender, RoutedEventArgs e)
        {
            if (_benchmarkCts != null) return;

            BtnBenchmark.IsEnabled = false;
            PnlBenchmark.Visibility = Visibility.Visible;
            PrgBenchmark.Value = 0;
            PrgBenchmark.Maximum = _strategies.Count;
            TxtBenchmarkStatus.Text = "Запуск автоподбора лучших стратегий...";

            _benchmarkCts = new CancellationTokenSource();

            try
            {
                var best = await _benchmarkService.RunBenchmarkAsync(
                    onProgress: (current, total, strategy, statusText) =>
                    {
                        Dispatcher.Invoke(() =>
                        {
                            PrgBenchmark.Value = current;
                            TxtBenchmarkStatus.Text = $"[{current}/{total}] {statusText}";
                        });
                    },
                    onStrategyTested: (res) =>
                    {
                    },
                    _benchmarkCts.Token);

                if (best != null && best.SuccessCount > 0)
                {
                    var matched = _strategies.FirstOrDefault(s => s.Id == best.Strategy.Id);
                    if (matched != null)
                    {
                        CmbByeDpiStrategies.SelectedItem = matched;
                    }

                    TxtStrategyDesc.Text = $"{best.Strategy.Description}\n\n🏆 Рекомендовано автоподбором: доступно {best.SuccessCount}/2 сервисов (средний пинг {best.AverageLatencyMs} мс)";
                    TxtBenchmarkStatus.Text = $"Готово! Лучшая стратегия: {best.Strategy.Name}";

                    _notifyIcon?.ShowBalloonTip(3000, "Автоподбор завершен", $"Выбрана стратегия: {best.Strategy.Name} ({best.AverageLatencyMs} мс)", System.Windows.Forms.ToolTipIcon.Info);

                    MessageBox.Show($"🏆 Автоподбор успешно завершен!\n\nВыбрана стратегия:\n«{best.Strategy.Name}»\n\n• Доступно сервисов: {best.SuccessCount}/2\n• Средняя задержка: {best.AverageLatencyMs} мс\n\nСтратегия уже выбрана в списке и готова к работе.", "Автоподбор стратегий", MessageBoxButton.OK, MessageBoxImage.Information);
                }
                else if (!_benchmarkCts.IsCancellationRequested)
                {
                    TxtBenchmarkStatus.Text = "Ни одна стратегия не смогла установить соединение.";
                    MessageBox.Show("Не удалось найти работающую стратегию для текущей сети.\nПопробуйте повторить тест позже или используйте Cloudflare WARP / VLESS.", "Результаты теста", MessageBoxButton.OK, MessageBoxImage.Warning);
                }
            }
            catch (OperationCanceledException)
            {
                TxtBenchmarkStatus.Text = "Автоподбор отменен пользователем.";
            }
            catch (Exception ex)
            {
                TxtBenchmarkStatus.Text = $"Ошибка: {ex.Message}";
            }
            finally
            {
                _benchmarkCts?.Dispose();
                _benchmarkCts = null;
                BtnBenchmark.IsEnabled = true;
                await Task.Delay(2500);
                if (_benchmarkCts == null)
                {
                    PnlBenchmark.Visibility = Visibility.Collapsed;
                }
            }
        }

        private void BtnCancelBenchmark_Click(object sender, RoutedEventArgs e)
        {
            _benchmarkCts?.Cancel();
            TxtBenchmarkStatus.Text = "Отмена тестирования...";
        }

        private void BtnAddVlessKey_Click(object sender, RoutedEventArgs e)
        {
            var text = Clipboard.GetText();
            var input = Microsoft.VisualBasic.Interaction.InputBox("Вставьте ссылку на сервер (vless://, hysteria2://, hy2://):", "Добавить сервер", text);
            if (!string.IsNullOrWhiteSpace(input))
            {
                var profile = VlessParser.Parse(input);
                if (profile != null)
                {
                    _vlessProfiles.Add(profile);
                    CmbVlessProfiles.SelectedItem = profile;
                    SaveSettings();
                    MessageBox.Show($"Сервер «{profile.Name}» успешно добавлен!", "Добавлено", MessageBoxButton.OK, MessageBoxImage.Information);
                }
                else
                {
                    MessageBox.Show("Не удалось распознать формат ключа. Поддерживаются протоколы vless://, hysteria2://, hy2://", "Неверный формат", MessageBoxButton.OK, MessageBoxImage.Warning);
                }
            }
        }

        private void BtnDeleteVlessKey_Click(object sender, RoutedEventArgs e)
        {
            if (CmbVlessProfiles.SelectedItem is VlessProfile p)
            {
                if (MessageBox.Show($"Удалить сервер «{p.Name}»?", "Подтверждение", MessageBoxButton.YesNo, MessageBoxImage.Question) == MessageBoxResult.Yes)
                {
                    _vlessProfiles.Remove(p);
                    if (_vlessProfiles.Count > 0) CmbVlessProfiles.SelectedIndex = 0;
                    SaveSettings();
                }
            }
        }

        private void BtnImportBackup_Click(object sender, RoutedEventArgs e)
        {
            var ofd = new OpenFileDialog
            {
                Filter = "Резервная копия ByeDPI (*.json)|*.json|Все файлы (*.*)|*.*",
                Title = "Выберите файл бэкапа из Android-приложения"
            };

            if (ofd.ShowDialog() == true)
            {
                try
                {
                    var json = File.ReadAllText(ofd.FileName);
                    var root = JsonNode.Parse(json);
                    if (root == null) return;

                    int vlessAdded = 0;
                    var vlessObj = root["vless"];
                    if (vlessObj != null && vlessObj["configs"] is JsonArray cfgs)
                    {
                        foreach (var item in cfgs)
                        {
                            var uri = item?["rawUri"]?.ToString() ?? "";
                            var parsed = VlessParser.Parse(uri);
                            if (parsed != null && !_vlessProfiles.Any(x => x.RawUri == parsed.RawUri))
                            {
                                _vlessProfiles.Add(parsed);
                                vlessAdded++;
                            }
                        }
                    }

                    bool warpRestored = false;
                    var warpObj = root["warp"];
                    if (warpObj != null && warpObj["config"] != null)
                    {
                        var conf = warpObj["config"]!.ToString();
                        if (conf.Contains("[Interface]") && conf.Contains("[Peer]"))
                        {
                            _warpConfigText = conf;
                            warpRestored = true;
                        }
                    }

                    SaveSettings();
                    if (_vlessProfiles.Count > 0 && CmbVlessProfiles.SelectedItem == null)
                    {
                        CmbVlessProfiles.SelectedIndex = 0;
                    }

                    MessageBox.Show($"✅ Резервная копия Android успешно импортирована!\n\n• Добавлено серверов VLESS: {vlessAdded}\n• Конфигурация WARP: {(warpRestored ? "Обновлена" : "Без изменений")}", "Импорт завершен", MessageBoxButton.OK, MessageBoxImage.Information);
                }
                catch (Exception ex)
                {
                    MessageBox.Show($"Ошибка при импорте бэкапа: {ex.Message}", "Ошибка", MessageBoxButton.OK, MessageBoxImage.Error);
                }
            }
        }

        private void BtnAutoStart_Click(object sender, RoutedEventArgs e)
        {
            try
            {
                using var key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run", true);
                if (key != null)
                {
                    var appPath = System.Diagnostics.Process.GetCurrentProcess().MainModule?.FileName;
                    var existing = key.GetValue("ByeDpiRussiaDesktop");

                    if (existing != null)
                    {
                        key.DeleteValue("ByeDpiRussiaDesktop", false);
                        MessageBox.Show("Автозапуск с Windows отключен.", "Автозапуск", MessageBoxButton.OK, MessageBoxImage.Information);
                    }
                    else if (!string.IsNullOrEmpty(appPath))
                    {
                        key.SetValue("ByeDpiRussiaDesktop", $"\"{appPath}\"");
                        MessageBox.Show("Автозапуск с Windows успешно включен!", "Автозапуск", MessageBoxButton.OK, MessageBoxImage.Information);
                    }
                }
            }
            catch (Exception ex)
            {
                MessageBox.Show($"Не удалось изменить параметры автозапуска: {ex.Message}", "Ошибка", MessageBoxButton.OK, MessageBoxImage.Error);
            }
        }

        #endregion

        #region Window Lifecycle

        protected override void OnClosing(CancelEventArgs e)
        {
            if (!_isRealExit)
            {
                e.Cancel = true;
                Hide();
                _notifyIcon?.ShowBalloonTip(2000, "ByeDPI Russia", "Приложение свернуто в системный трей возле часов.", System.Windows.Forms.ToolTipIcon.Info);
            }
            else
            {
                _notifyIcon?.Dispose();
                _processManager.StopAll();
                SystemProxyManager.SetProxy(false);
                base.OnClosing(e);
            }
        }

        private void ExitApplication()
        {
            _isRealExit = true;
            Close();
            System.Windows.Application.Current.Shutdown();
        }

        #endregion
    }
}