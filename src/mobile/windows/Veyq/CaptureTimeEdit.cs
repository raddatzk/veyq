// A medium's capture time corrected on Windows (ADR 0144 decision 3; Linux's capture-time editor, the web's lightbox
// editor): in the details' dialog, under the "Taken" row, "Edit capture time" opens a CalendarDatePicker and a TimePicker
// in place (one ContentDialog at a time, Dialogs.cs) holding the effective time in the device's zone; Save hands the core
// the picked local date and time as epoch ms, converted by the device's calendar (CurationText.EpochMs; the core holds it
// to the minute and does no zone arithmetic); "Reset to original" removes the correction. A peer's correction meanwhile
// reopens the editor on theirs, said with the web's words. Every rule is the core's (CaptureTime.*, Watched.CaptureTime).
using System;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Veyq.Bridge;
using Veyq.Bridge.Wire;

namespace Veyq;

internal sealed class CaptureTimeEdit : IDisposable
{
    private readonly long _id;
    private readonly Action _saved;
    private readonly StackPanel _root = new() { Spacing = 6 };
    private readonly StackPanel _actions = new() { Orientation = Orientation.Horizontal, Spacing = 12 };
    private readonly HyperlinkButton _edit = new() { Content = Words.MediaEditCapturedAt(), Padding = new Thickness(0) };
    private readonly HyperlinkButton _reset = new() { Content = Words.MediaResetCapturedAt(), Padding = new Thickness(0), Visibility = Visibility.Collapsed };
    private readonly StackPanel _editor = new() { Spacing = 8, Visibility = Visibility.Collapsed };
    private readonly CalendarDatePicker _day = new() { MinWidth = 200 };
    private readonly TimePicker _time = new() { MinuteIncrement = 1 };
    private readonly Button _save = new() { Content = Words.EntrySave(), Style = (Style)Application.Current.Resources["AccentButtonStyle"] };
    private readonly Button _cancel = new() { Content = Words.EntryCancel() };
    private readonly TextBlock _problem = new() { TextWrapping = TextWrapping.Wrap, Visibility = Visibility.Collapsed };
    private CaptureTimeShown? _shown;
    private Watch? _watch;
    private bool _closed;

    public UIElement View => _root;

    /// <summary>Medium <paramref name="mediaId"/>'s editor in lightbox <paramref name="lightbox"/>'s details, or null where
    /// whoever looks may not correct it (CaptureTime.Open refused). <paramref name="saved"/> is told once a save or reset
    /// landed: the details are shown anew.</summary>
    public static async System.Threading.Tasks.Task<CaptureTimeEdit?> Open(long lightbox, string mediaId, Action saved)
    {
        var (answer, _) = await Core.CallAsync(new Call.CaptureTimeOpen { lightbox = lightbox, mediaId = mediaId });
        if (answer is not Answer.CaptureTimeOpened { captureTime: var id })
        {
            Log.Write($"CaptureTime.Open answered {(answer is Answer.Refused r ? $"Refused {r.why}" : answer.GetType().Name)}");
            return null;
        }
        Log.Write($"capture-time editor {id} open");
        return new CaptureTimeEdit(id, saved);
    }

    private CaptureTimeEdit(long id, Action saved)
    {
        _id = id;
        _saved = saved;
        AutomationProperties.SetAutomationId(_edit, "capture-edit");
        AutomationProperties.SetAutomationId(_reset, "capture-reset");
        AutomationProperties.SetAutomationId(_day, "capture-day");
        AutomationProperties.SetName(_day, Words.MemoryFieldCaptured());
        AutomationProperties.SetAutomationId(_time, "capture-time");
        AutomationProperties.SetName(_time, Words.MemoryFieldCaptured());
        AutomationProperties.SetAutomationId(_save, "capture-save");
        AutomationProperties.SetAutomationId(_cancel, "capture-cancel");
        AutomationProperties.SetAutomationId(_problem, "capture-problem");
        _problem.Foreground = (Brush)Application.Current.Resources["SystemFillColorCriticalBrush"];
        _actions.Children.Add(_edit);
        _actions.Children.Add(_reset);
        _root.Children.Add(_actions);
        var pickers = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        pickers.Children.Add(_day);
        pickers.Children.Add(_time);
        _editor.Children.Add(pickers);
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        buttons.Children.Add(_save);
        buttons.Children.Add(_cancel);
        _editor.Children.Add(buttons);
        _root.Children.Add(_editor);
        _root.Children.Add(_problem);
        _edit.Click += (_, _) =>
        {
            Log.Write("capture-time editor: edit");
            Fill();
            _editor.Visibility = Visibility.Visible;
            _actions.Visibility = Visibility.Collapsed;
        };
        _cancel.Click += (_, _) =>
        {
            _editor.Visibility = Visibility.Collapsed;
            _actions.Visibility = Visibility.Visible;
        };
        _save.Click += (_, _) =>
        {
            if (_day.Date is not { } day) return;
            var at = CurationText.EpochMs(day, _time.Time, TimeZoneInfo.Local);
            Log.Write($"capture-time editor: set {DateTimeOffset.FromUnixTimeMilliseconds(at):yyyy-MM-dd HH:mm}Z");
            Save(new Call.CaptureTimeSet { captureTime = _id, epochMs = at }, "CaptureTime.Set");
        };
        _reset.Click += (_, _) => Save(new Call.CaptureTimeReset { captureTime = _id }, "CaptureTime.Reset");
        _watch = Core.Watch(new Watched.CaptureTime { captureTime = id }, Updated, e => Log.Write($"a capture-time editor update: {e.Message}"));
    }

    /// <summary>The pickers set to the effective time in the device's zone.</summary>
    private void Fill()
    {
        if (_shown?.atMs is not { } at) return;
        var (day, time) = CurationText.Local(at, TimeZoneInfo.Local);
        _day.Date = day;
        _time.Time = time;
    }

    private async void Save(Call call, string what)
    {
        _save.IsEnabled = _reset.IsEnabled = false;
        try
        {
            var (answer, _) = await Core.CallAsync(call);
            Log.Write($"{what}: {(answer is Answer.FactsSaved f ? f.outcome.ToString() : answer is Answer.Refused r ? $"Refused {r.why}" : answer.GetType().Name)}");
            if (_closed) return;
            if (answer is Answer.FactsSaved { outcome: FactsSave.Saved }) _saved();
            // reopened on a peer's correction: the pickers show theirs (the state says so)
            else if (answer is Answer.FactsSaved { outcome: FactsSave.Reopened }) Fill();
        }
        catch (Exception e)
        {
            Log.Write($"{what} failed ({e.GetType().Name})");
        }
        finally
        {
            _save.IsEnabled = _reset.IsEnabled = true;
        }
    }

    private void Updated(Update update)
    {
        if (update is not Update.CaptureTime { shown: var s } || _closed) return;
        _shown = s;
        if (s is null)
        {
            _root.Visibility = Visibility.Collapsed;
            return;
        }
        Log.Write($"capture time shown: at {(s.atMs is { } a ? DateTimeOffset.FromUnixTimeMilliseconds(a).ToString("yyyy-MM-dd HH:mm") + "Z" : "-")}, corrected {s.corrected}, saving {s.saving}, said {s.said?.ToString() ?? "-"}, saved {s.saved}");
        _reset.Visibility = s.corrected ? Visibility.Visible : Visibility.Collapsed;
        var said = CurationText.CaptureSaid(s.said);
        _problem.Text = said ?? "";
        _problem.Visibility = said is null ? Visibility.Collapsed : Visibility.Visible;
        if (s.said == CurationSaid.Conflict) Fill();
    }

    public void Dispose()
    {
        if (_closed) return;
        _closed = true;
        _watch?.Dispose();
        Act.Send(new Call.CaptureTimeClose { captureTime = _id });
    }
}
