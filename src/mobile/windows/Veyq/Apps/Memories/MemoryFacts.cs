// A Memory's date and place on Windows (ADR 0143 decision 5; Linux's memory_facts.rs, Apple's MemoryFactsLine): the facts
// line under a Memory's or a chapter's head — its date and its place's name, each a HyperlinkButton to its editor where
// whoever looks may edit its meta online (GalleryShown.editsFacts), plain text otherwise — and the two editors as
// ContentDialogs, Fluent's way of asking for a few fields over a page: the date's four modes (RadioButtons) with
// CalendarDatePickers, the `auto` range previewed; the place's map panned under its fixed centre pin (RasterMap), the level
// picker from the reverse lookup, the typed name, "Follow the photos" and "Remove location". Every state is the core's
// (Date.*, Watched.Date; Place.*, Watched.Place); a typed name is set from the state by TypedText's rule. A save answered
// REOPENED stays open on the peer's value the state now shows (uc.memory.concurrent-curation).

namespace Veyq.Apps.Memories;

using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Veyq.Bridge;
using Veyq.Bridge.Wire;
using Veyq.Core;

internal static class MemoryFacts
{
    /// <summary>The facts line of gallery <paramref name="gallery"/> as <paramref name="s"/> shows it; null where there is
    /// nothing to show (no date, no place, and nothing to edit).</summary>
    public static UIElement? Line(Window window, long gallery, GalleryShown s)
    {
        var date = MemoryDates.DateText(s.date);
        // a place armed at birth whose name the photos have yet to give says so (uc.memory.place A1), as the phones' line does
        var place = s.place ?? (s.placePending ? Words.MemoryPlacePending() : null);
        if (date is null && place is null && !s.editsFacts) return null;
        var line = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 16 };
        AutomationProperties.SetAutomationId(line, "memory-facts");
        if (date is not null || s.editsFacts)
        {
            line.Children.Add(Fact("", date ?? Words.MemoryDateNone(), date is null, s.editsFacts, Words.MemoryDateEdit(), "memory-date",
                () => DateEditor.Open(window, gallery)));
        }
        if (place is not null || s.editsFacts)
        {
            line.Children.Add(Fact("", place ?? Words.MemoryPlaceSet(), place is null, s.editsFacts, Words.MemoryPlaceEdit(), "memory-place",
                () => PlaceEditor.Open(window, gallery)));
        }
        return line;
    }

    private static FrameworkElement Fact(string glyph, string text, bool muted, bool edits, string help, string id, Action edit)
    {
        var content = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6 };
        content.Children.Add(new FontIcon { Glyph = glyph, FontSize = 14 });
        var label = new TextBlock { Text = text, VerticalAlignment = VerticalAlignment.Center };
        if (muted) label.Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"];
        content.Children.Add(label);
        if (!edits)
        {
            AutomationProperties.SetAutomationId(content, id);
            AutomationProperties.SetName(content, text);
            return content;
        }
        var button = new HyperlinkButton { Content = content, Padding = new Thickness(4, 2, 4, 2) };
        AutomationProperties.SetAutomationId(button, id);
        AutomationProperties.SetName(button, $"{help}: {text}");
        ToolTipService.SetToolTip(button, help);
        button.Click += (_, _) => edit();
        return button;
    }

    /// <summary>An ISO day as a CalendarDatePicker's date (local midnight); null for none.</summary>
    public static DateTimeOffset? Picked(string day) =>
        DateOnly.TryParseExact(day, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out var d)
            ? new DateTimeOffset(d.ToDateTime(TimeOnly.MinValue)) : null;

    /// <summary>A CalendarDatePicker's date as an ISO day; empty for none.</summary>
    public static string Day(DateTimeOffset? date) => date is { } d ? d.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture) : "";

    /// <summary>A dialog asked to be wider than Fluent's default 548 (the place editor's map).</summary>
    public static void Widen(ContentDialog dialog, double width)
    {
        dialog.Resources["ContentDialogMaxWidth"] = width;
    }
}

/// <summary>A Memory's date editor (core's DateEditor, the web's MemoryDateEditor.vue).</summary>
internal sealed class DateEditor
{
    private readonly Window _window;
    private readonly long _date;
    private readonly RadioButtons _modes = new() { MaxColumns = 2 };
    private readonly CalendarDatePicker _from = new() { MinWidth = 200 };
    private readonly CalendarDatePicker _to = new() { MinWidth = 200 };
    private readonly TextBlock _auto = new() { TextWrapping = TextWrapping.Wrap };
    private readonly TextBlock _hint = new() { TextWrapping = TextWrapping.Wrap, Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"] };
    private readonly TextBlock _problem = new() { TextWrapping = TextWrapping.Wrap, Visibility = Visibility.Collapsed };
    private readonly ContentDialog _dialog;
    private bool _drawing;
    /// <summary>The mode the state shows: RadioButtons raises SelectionChanged after the draw, and that one is no choice.</summary>
    private DateMode? _mode;
    private string _fromDay = "", _toDay = "";
    private Watch? _watch;

    private static readonly DateMode[] Modes = [DateMode.Auto, DateMode.Single, DateMode.Range, DateMode.Ongoing];

    /// <summary>Gallery <paramref name="gallery"/>'s date editor: opened in the core (Date.Open) and shown.</summary>
    public static async void Open(Window window, long gallery)
    {
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.DateOpen { gallery = gallery });
            if (answer is not Answer.DateOpened { date: var date })
            {
                Log.Write($"Date.Open answered {(answer is Answer.Refused r ? $"Refused {r.why}" : answer.GetType().Name)}");
                return;
            }
            Log.Write($"date editor {date} open");
            await new DateEditor(window, date).Show();
        }
        catch (Exception e)
        {
            Log.Write($"the date editor failed ({e.GetType().Name})");
        }
    }

    private DateEditor(Window window, long date)
    {
        _window = window;
        _date = date;
        _modes.Header = Words.MemoryDateTitle();
        foreach (var m in new[] { Words.MemoryDateModeAuto(), Words.MemoryDateModeSingle(), Words.MemoryDateModeRange(), Words.MemoryDateModeOngoing() }) _modes.Items.Add(m);
        AutomationProperties.SetAutomationId(_modes, "date-modes");
        AutomationProperties.SetAutomationId(_from, "date-from");
        AutomationProperties.SetAutomationId(_to, "date-to");
        AutomationProperties.SetAutomationId(_auto, "date-auto");
        AutomationProperties.SetAutomationId(_problem, "date-problem");
        _problem.Foreground = (Brush)Application.Current.Resources["SystemFillColorCriticalBrush"];
        _modes.SelectionChanged += (_, _) =>
        {
            if (_drawing || _modes.SelectedIndex < 0 || Modes[_modes.SelectedIndex] == _mode) return;
            Log.Write($"date editor: mode {Modes[_modes.SelectedIndex]}");
            Act.Send(new Call.DateMode { date = _date, mode = Modes[_modes.SelectedIndex] });
        };
        _from.DateChanged += (_, e) =>
        {
            if (_drawing || MemoryFacts.Day(e.NewDate) == _fromDay) return;
            Act.Send(new Call.DateFrom { date = _date, day = MemoryFacts.Day(e.NewDate) });
        };
        _to.DateChanged += (_, e) =>
        {
            if (_drawing || MemoryFacts.Day(e.NewDate) == _toDay) return;
            Act.Send(new Call.DateTo { date = _date, day = MemoryFacts.Day(e.NewDate) });
        };
        var content = new StackPanel { Spacing = 12, MinWidth = 360 };
        content.Children.Add(_modes);
        content.Children.Add(_from);
        content.Children.Add(_to);
        content.Children.Add(_auto);
        content.Children.Add(_hint);
        content.Children.Add(_problem);
        _dialog = new ContentDialog
        {
            Title = Words.MemoryDateTitle(),
            Content = content,
            PrimaryButtonText = Words.EntrySave(),
            CloseButtonText = Words.EntryCancel(),
            DefaultButton = ContentDialogButton.Primary,
            IsPrimaryButtonEnabled = false,
        };
        AutomationProperties.SetAutomationId(_dialog, "date-dialog");
        _dialog.PrimaryButtonClick += async (_, args) =>
        {
            // the dialog stays until the core answered: a peer's value re-opens it, a failure is said in it
            args.Cancel = true;
            var deferral = args.GetDeferral();
            try
            {
                var (answer, _) = await Core.CallAsync(new Call.DateSave { date = _date });
                var outcome = answer is Answer.FactsSaved f ? f.outcome.ToString() : answer.GetType().Name;
                Log.Write($"Date.Save: {outcome}");
                _problem.Text = Words.NodeSaveFailed();
                _problem.Visibility = answer is Answer.FactsSaved { outcome: FactsSave.Saved or FactsSave.Reopened } ? Visibility.Collapsed : Visibility.Visible;
                if (answer is Answer.FactsSaved { outcome: FactsSave.Saved }) _dialog.Hide();
            }
            catch (Exception e)
            {
                Log.Write($"Date.Save failed ({e.GetType().Name})");
            }
            finally
            {
                deferral.Complete();
            }
        };
        _watch = Core.Watch(new Watched.Date { date = date }, Updated, e => Log.Write($"a date editor update: {e.Message}"));
    }

    private async System.Threading.Tasks.Task Show()
    {
        await Dialogs.Show(_window, _dialog);
        _watch?.Dispose();
        Act.Send(new Call.DateClose { date = _date });
        Log.Write($"date editor {_date} closed");
    }

    private void Updated(Update update)
    {
        if (update is not Update.Date { shown: var s }) return;
        if (s is null)
        {
            _dialog.Hide();
            return;
        }
        Log.Write($"date shown: {s.mode} {s.from}..{s.to}, invalid {s.rangeInvalid}, save {s.canSave}, auto {s.auto.from}..{s.auto.to}");
        _drawing = true;
        try
        {
            _mode = s.mode;
            (_fromDay, _toDay) = (s.from, s.to);
            _modes.SelectedIndex = Array.IndexOf(Modes, s.mode);
            _from.Header = s.mode switch { DateMode.Range => Words.MemoryDateFrom(), DateMode.Ongoing => Words.MemoryDateStart(), _ => Words.MemoryDateDay() };
            _to.Header = Words.MemoryDateTo();
            if (MemoryFacts.Day(_from.Date) != s.from) _from.Date = MemoryFacts.Picked(s.from);
            if (MemoryFacts.Day(_to.Date) != s.to) _to.Date = MemoryFacts.Picked(s.to);
            _from.Visibility = s.mode == DateMode.Auto ? Visibility.Collapsed : Visibility.Visible;
            _to.Visibility = s.mode == DateMode.Range ? Visibility.Visible : Visibility.Collapsed;
            var auto = MemoryDates.DateText(s.auto);
            _auto.Text = auto ?? "";
            _auto.Visibility = s.mode == DateMode.Auto && auto is not null ? Visibility.Visible : Visibility.Collapsed;
            _hint.Text = auto is null ? Words.MemoryDateAutoEmpty() : Words.MemoryDateAutoHint();
            _hint.Visibility = s.mode == DateMode.Auto ? Visibility.Visible : Visibility.Collapsed;
            if (s.rangeInvalid)
            {
                _problem.Text = Words.MemoryDateInvalidRange();
                _problem.Visibility = Visibility.Visible;
            }
            else if (_problem.Text == Words.MemoryDateInvalidRange()) _problem.Visibility = Visibility.Collapsed;
            _dialog.IsPrimaryButtonEnabled = s.canSave;
        }
        finally
        {
            _drawing = false;
        }
    }
}

/// <summary>A Memory's place editor (core's PlaceEditor, the web's MemoryLocationEditor.vue): the map panned under a fixed
/// centre pin — no drag of the pin, no search —, the level picker, the typed name, Follow the photos, Remove location.</summary>
internal sealed class PlaceEditor
{
    private readonly Window _window;
    private readonly long _place;
    private readonly RasterMap? _map;
    private readonly TextBlock _mode = new() { Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"] };
    private readonly TextBlock _moveHint = new() { TextWrapping = TextWrapping.Wrap, Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"] };
    private readonly HyperlinkButton _follow = new() { Content = Words.MemoryPlaceFollowAgain() };
    private readonly TextBox _name = new() { Header = Words.MemoryPlaceName(), PlaceholderText = Words.MemoryPlaceNamePlaceholder(), MaxLength = 200 };
    private readonly ComboBox _levels = new() { MinWidth = 240 };
    private readonly HyperlinkButton _toggle = new();
    private readonly Button _remove = new() { Content = Words.MemoryPlaceRemove() };
    private readonly TextBlock _problem = new() { TextWrapping = TextWrapping.Wrap, Visibility = Visibility.Collapsed };
    private readonly TypedText _typed = new();
    /// <summary>The text the state set last: TextBox raises TextChanged later, after the draw, and that one is no edit of
    /// the person's (EntryEditorPage's rule).</summary>
    private string? _set;
    private string? _chosen;
    private readonly ContentDialog _dialog;
    private bool _drawing;
    private Watch? _watch;

    public static async void Open(Window window, long gallery)
    {
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.PlaceOpen { gallery = gallery });
            if (answer is not Answer.PlaceOpened { place: var place, map: var map })
            {
                Log.Write($"Place.Open answered {(answer is Answer.Refused r ? $"Refused {r.why}" : answer.GetType().Name)}");
                return;
            }
            Log.Write($"place editor {place} open, map {map?.ToString() ?? "none"}");
            await new PlaceEditor(window, place, map).Show();
        }
        catch (Exception e)
        {
            Log.Write($"the place editor failed ({e.GetType().Name})");
        }
    }

    private PlaceEditor(Window window, long place, long? map)
    {
        _window = window;
        _place = place;
        var content = new StackPanel { Spacing = 10, MinWidth = 680 };
        if (map is { } m)
        {
            _map = new RasterMap(window, m, closesItself: false);
            _map.View.Height = 340;
            content.Children.Add(_map.View);
        }
        var modeLine = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 12 };
        modeLine.Children.Add(_mode);
        AutomationProperties.SetAutomationId(_follow, "place-follow");
        _follow.Padding = new Thickness(0);
        _follow.Click += (_, _) =>
        {
            Log.Write("place editor: follow the photos");
            Act.Send(new Call.PlaceFollow { place = _place });
        };
        modeLine.Children.Add(_follow);
        content.Children.Add(modeLine);
        content.Children.Add(_moveHint);
        AutomationProperties.SetAutomationId(_levels, "place-levels");
        AutomationProperties.SetName(_levels, Words.MemoryPlaceName());
        _levels.SelectionChanged += (_, _) =>
        {
            if (_drawing || _levels.SelectedItem is not ComboBoxItem { Tag: string level } || level == _chosen) return;
            Log.Write($"place editor: level {level}");
            Act.Send(new Call.PlaceLevel { place = _place, level = level });
        };
        content.Children.Add(_levels);
        AutomationProperties.SetAutomationId(_name, "place-name");
        _name.TextChanged += (_, _) =>
        {
            if (_drawing) return;
            if (_set is { } set && set == _name.Text)
            {
                _set = null;
                return;
            }
            _set = null;
            _typed.Sent(_name.Text);
            Act.Send(new Call.PlaceName { place = _place, name = _name.Text });
        };
        content.Children.Add(_name);
        AutomationProperties.SetAutomationId(_toggle, "place-toggle-name");
        _toggle.Padding = new Thickness(0);
        _toggle.Click += (_, _) => Act.Send(new Call.PlaceToggleName { place = _place });
        content.Children.Add(_toggle);
        AutomationProperties.SetAutomationId(_remove, "place-remove");
        _remove.Click += async (_, _) =>
        {
            try
            {
                var (answer, _) = await Core.CallAsync(new Call.PlaceRemove { place = _place });
                Log.Write($"Place.Remove: {(answer is Answer.FactsSaved f ? f.outcome.ToString() : answer.GetType().Name)}");
                if (answer is Answer.FactsSaved { outcome: FactsSave.Saved }) Hide();
            }
            catch (Exception e)
            {
                Log.Write($"Place.Remove failed ({e.GetType().Name})");
            }
        };
        content.Children.Add(_remove);
        _problem.Foreground = (Brush)Application.Current.Resources["SystemFillColorCriticalBrush"];
        content.Children.Add(_problem);
        _dialog = new ContentDialog
        {
            Title = Words.MemoryPlaceTitle(),
            Content = new ScrollViewer { Content = content, VerticalScrollBarVisibility = ScrollBarVisibility.Auto },
            PrimaryButtonText = Words.EntrySave(),
            CloseButtonText = Words.EntryCancel(),
            DefaultButton = ContentDialogButton.Primary,
            IsPrimaryButtonEnabled = false,
        };
        MemoryFacts.Widen(_dialog, 760);
        AutomationProperties.SetAutomationId(_dialog, "place-dialog");
        _dialog.PrimaryButtonClick += async (_, args) =>
        {
            args.Cancel = true;
            var deferral = args.GetDeferral();
            try
            {
                var (answer, _) = await Core.CallAsync(new Call.PlaceSave { place = _place });
                Log.Write($"Place.Save: {(answer is Answer.FactsSaved f ? f.outcome.ToString() : answer.GetType().Name)}");
                _problem.Text = Words.NodeSaveFailed();
                _problem.Visibility = answer is Answer.FactsSaved { outcome: FactsSave.Saved or FactsSave.Reopened } ? Visibility.Collapsed : Visibility.Visible;
                if (answer is Answer.FactsSaved { outcome: FactsSave.Saved }) _dialog.Hide();
            }
            catch (Exception e)
            {
                Log.Write($"Place.Save failed ({e.GetType().Name})");
            }
            finally
            {
                deferral.Complete();
            }
        };
        _watch = Core.Watch(new Watched.Place { place = place }, Updated, e => Log.Write($"a place editor update: {e.Message}"));
    }

    private void Hide() => _dialog.Hide();

    private async System.Threading.Tasks.Task Show()
    {
        await Dialogs.Show(_window, _dialog);
        _watch?.Dispose();
        _map?.Dispose();
        Act.Send(new Call.PlaceClose { place = _place });
        Log.Write($"place editor {_place} closed");
    }

    private void Updated(Update update)
    {
        if (update is not Update.Place { shown: var s }) return;
        if (s is null)
        {
            _dialog.Hide();
            return;
        }
        Log.Write($"place shown: map {s.withMap}, {s.mode}, name \"{s.name}\" ({s.naming}), {s.levels.Count} levels, chosen {s.chosenLevel ?? "-"}, picker {s.levelPicker}, save {s.canSave}, follow {s.canFollow}, remove {s.canRemove}");
        _drawing = true;
        try
        {
            _mode.Text = s.mode == PlaceMode.Auto ? Words.MemoryPlaceFollowsPhotos() : Words.MemoryPlaceCustom();
            _moveHint.Text = Words.MemoryPlaceMoveToSet();
            _moveHint.Visibility = s.withMap ? Visibility.Visible : Visibility.Collapsed;
            _follow.Visibility = s.canFollow && s.mode == PlaceMode.Fixed ? Visibility.Visible : Visibility.Collapsed;
            if (_typed.Take(_name.FocusState != FocusState.Unfocused, _name.Text, s.name) is { } text)
            {
                _set = text;
                _name.Text = text;
            }
            var levels = s.levels.Select(l => l.level).ToList();
            var drawn = _levels.Items.OfType<ComboBoxItem>().Select(i => (i.Tag as string, i.Content as string)).ToList();
            if (!drawn.SequenceEqual(s.levels.Select(l => ((string?)l.level, (string?)l.name))))
            {
                _levels.Items.Clear();
                foreach (var l in s.levels)
                {
                    var item = new ComboBoxItem { Content = l.name, Tag = l.level };
                    AutomationProperties.SetAutomationId(item, $"place-level:{l.level}");
                    _levels.Items.Add(item);
                }
            }
            _chosen = s.chosenLevel;
            _levels.SelectedIndex = s.chosenLevel is { } chosen ? levels.IndexOf(chosen) : -1;
            _levels.Visibility = s.levelPicker && s.naming == PlaceNaming.Geocoded && s.levels.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
            _toggle.Content = s.naming == PlaceNaming.Geocoded ? Words.MemoryPlaceNameOwn() : Words.MemoryPlaceNameFromMap();
            _toggle.Visibility = s.levels.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
            _remove.Visibility = s.canRemove ? Visibility.Visible : Visibility.Collapsed;
            _dialog.IsPrimaryButtonEnabled = s.canSave;
        }
        finally
        {
            _drawing = false;
        }
    }
}
