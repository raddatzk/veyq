// The music bar (ADR 0142 decision 5; the web's AudioBar, Linux's music_bar.rs), Fluent's way: along the window's bottom
// edge under the shell's content, as the Media Player app keeps its transport — the track's title and, as a link, the
// Memory or chapter whose music it is (Player.OpenNode opens it); Previous, Play or Pause, Next; a slider over the track's
// span that seeks where it is let go (Player.Seek) with the time elapsed and the span's length; Close (Player.Stop). It
// shows while the account's player holds a queue (Watched.Player's nowPlaying) and says for 5 s why a queue ended by
// itself (none of its tracks played on this device). Every state is the core's MusicPlayer; every press one call.
using System;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Veyq.Bridge;
using Veyq.Bridge.Wire;

namespace Veyq;

internal sealed class MusicBar : IDisposable
{
    private readonly Grid _bar = new() { Padding = new Thickness(12, 6, 8, 6), ColumnSpacing = 8, Visibility = Visibility.Collapsed };
    private readonly TextBlock _title = new() { Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"], TextTrimming = TextTrimming.CharacterEllipsis };
    private readonly HyperlinkButton _album = new() { Padding = new Thickness(0) };
    private readonly TextBlock _albumText = new() { Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"], TextTrimming = TextTrimming.CharacterEllipsis };
    private readonly Button _toggle;
    private readonly Slider _progress = new() { Minimum = 0, IsThumbToolTipEnabled = false, VerticalAlignment = VerticalAlignment.Center, MinWidth = 120 };
    private readonly TextBlock _elapsed = new() { Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"], VerticalAlignment = VerticalAlignment.Center };
    private readonly TextBlock _total = new() { Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"], VerticalAlignment = VerticalAlignment.Center };
    private readonly Action<string> _notice;
    private readonly Watch? _watch;
    private NowPlayingShown? _now;
    private string? _noticeShown;
    private bool _dragging;
    private bool _setting;
    private string? _logged;

    public FrameworkElement View => _bar;

    /// <summary>The bar over the account's player; <paramref name="notice"/> says why a queue ended by itself.</summary>
    public MusicBar(Action<string> notice)
    {
        _notice = notice;
        _bar.Background = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["LayerFillColorDefaultBrush"];
        _bar.BorderBrush = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["CardStrokeColorDefaultBrush"];
        _bar.BorderThickness = new Thickness(0, 1, 0, 0);
        AutomationProperties.SetAutomationId(_bar, "music-bar");
        foreach (var w in new[] { 2.0, 0, 3.0, 0 })
        {
            _bar.ColumnDefinitions.Add(new ColumnDefinition { Width = w > 0 ? new GridLength(w, GridUnitType.Star) : GridLength.Auto });
        }

        var heading = new StackPanel { VerticalAlignment = VerticalAlignment.Center, MinWidth = 120 };
        AutomationProperties.SetAutomationId(_title, "music-bar-title");
        heading.Children.Add(_title);
        _album.Content = _albumText;
        AutomationProperties.SetAutomationId(_album, "music-bar-album");
        _album.Click += (_, _) => Act.Send(new Call.PlayerOpenNode());
        heading.Children.Add(_album);
        _bar.Children.Add(heading);

        Button Transport(string glyph, string label, string id, Func<Call> call)
        {
            var b = new Button { Content = new FontIcon { Glyph = glyph, FontSize = 16 }, Padding = new Thickness(10, 6, 10, 6) };
            AutomationProperties.SetAutomationId(b, id);
            AutomationProperties.SetName(b, label);
            ToolTipService.SetToolTip(b, label);
            b.Click += (_, _) => Act.Send(call());
            return b;
        }
        var transport = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 4, VerticalAlignment = VerticalAlignment.Center };
        transport.Children.Add(Transport("", Words.MusicPrevious(), "music-bar-previous", () => new Call.PlayerPrevious()));
        _toggle = Transport("", Words.MusicPause(), "music-bar-toggle", () => _now?.playing == true ? new Call.PlayerPause() : new Call.PlayerResume());
        _toggle.Style = (Style)Application.Current.Resources["AccentButtonStyle"];
        transport.Children.Add(_toggle);
        transport.Children.Add(Transport("", Words.MusicNext(), "music-bar-next", () => new Call.PlayerNext()));
        Grid.SetColumn(transport, 1);
        _bar.Children.Add(transport);

        var progress = new Grid { ColumnSpacing = 8, VerticalAlignment = VerticalAlignment.Center };
        progress.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        progress.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        progress.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        AutomationProperties.SetAutomationId(_elapsed, "music-bar-elapsed");
        progress.Children.Add(_elapsed);
        AutomationProperties.SetAutomationId(_progress, "music-bar-progress");
        Grid.SetColumn(_progress, 1);
        progress.Children.Add(_progress);
        Grid.SetColumn(_total, 2);
        progress.Children.Add(_total);
        Grid.SetColumn(progress, 2);
        _bar.Children.Add(progress);
        // the web's range: the position follows the player until it is held, and seeks where it is let go; a keyboard's or
        // UI Automation's move (no pointer) seeks at once
        _progress.AddHandler(UIElement.PointerPressedEvent, new PointerEventHandler((_, _) => _dragging = true), true);
        void LetGo(object sender, PointerRoutedEventArgs e)
        {
            if (!_dragging) return;
            _dragging = false;
            Seek();
        }
        _progress.AddHandler(UIElement.PointerReleasedEvent, new PointerEventHandler(LetGo), true);
        _progress.AddHandler(UIElement.PointerCaptureLostEvent, new PointerEventHandler(LetGo), true);
        _progress.ValueChanged += (_, e) =>
        {
            _elapsed.Text = MusicTime.Said((long)e.NewValue);
            if (!_setting && !_dragging) Seek();
        };

        var close = Transport("", Words.MusicClose(), "music-bar-close", () => new Call.PlayerStop());
        close.VerticalAlignment = VerticalAlignment.Center;
        Grid.SetColumn(close, 3);
        _bar.Children.Add(close);

        _watch = Core.Watch(new Watched.Player(), Updated, e => Log.Write($"a player update: {e.Message}"));
    }

    private void Seek()
    {
        if (_now is null) return;
        var to = (long)_progress.Value;
        Log.Write($"music bar: seek to {to} ms");
        Act.Send(new Call.PlayerSeek { positionMs = to });
    }

    private void Updated(Update update)
    {
        if (update is not Update.Player p) return;
        var shown = p.shown;
        _now = shown?.nowPlaying;
        if (shown?.notice is { } notice)
        {
            var said = Said.Notice(notice);
            if (said != _noticeShown)
            {
                _noticeShown = said;
                Log.Write($"music bar: said {notice.msg}");
                _notice(said);
            }
        }
        else _noticeShown = null;
        _bar.Visibility = _now is null ? Visibility.Collapsed : Visibility.Visible;
        if (_now is not { } now)
        {
            if (_logged is not null) Log.Write("music bar: hidden");
            _logged = null;
            return;
        }
        // the log at each change and every fifth second, for a pass to read the play from
        var line = $"music bar: {(now.playing ? "playing" : "paused")} {now.trackId} at {now.positionMs / 5000 * 5} s of {now.durationMs / 1000} s";
        if (line != _logged)
        {
            _logged = line;
            Log.Write(line);
        }
        _title.Text = now.title;
        _albumText.Text = now.album ?? "";
        _album.Visibility = now.album is null ? Visibility.Collapsed : Visibility.Visible;
        _album.IsEnabled = now.route is not null;
        if (now.album is { } album) AutomationProperties.SetName(_album, Words.MusicOpenNode(album));
        var label = now.playing ? Words.MusicPause() : Words.MusicResume();
        _toggle.Content = new FontIcon { Glyph = now.playing ? "" : "", FontSize = 16 };
        AutomationProperties.SetName(_toggle, label);
        ToolTipService.SetToolTip(_toggle, label);
        AutomationProperties.SetName(_progress, Words.MusicProgress(now.title));
        _total.Text = now.durationMs > 0 ? MusicTime.Said(now.durationMs) : "";
        if (!_dragging)
        {
            _setting = true;
            _progress.Maximum = Math.Max(1, now.durationMs);
            _progress.Value = Math.Clamp(now.positionMs, 0, Math.Max(1, now.durationMs));
            _progress.IsEnabled = now.durationMs > 0;
            _setting = false;
        }
        _elapsed.Text = MusicTime.Said(now.positionMs);
    }

    public void Dispose() => _watch?.Dispose();
}
