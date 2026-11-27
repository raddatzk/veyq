// A selection's capture-time shift on Windows (uc.memory.buckets § Correct a selection's capture time; core's
// SelectionShift, Linux's open_shift): one ContentDialog over the Contributions view — the anchor's day and minute in a
// CalendarDatePicker and a TimePicker in the device's zone (CurationText.EpochMs, as the details' editor), Continue, then
// "All N, shifted by …" or "Only this one" with how many stay undated, the progress, what it came to and its Undo. Every
// state is the core's (Watched.CaptureTimeShift), every action one call (CaptureTime.Shift*); nothing is decided here.

namespace Veyq.Apps.Memories;

using System;
using System.Linq;
using System.Threading.Tasks;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Veyq.Bridge;
using Veyq.Bridge.Wire;
using Veyq.Core;

internal sealed class CaptureTimeShiftDialog
{
    private readonly long _id;
    private readonly ContentDialog _dialog = new() { Title = Words.MemoryShiftAction() };
    private readonly TextBlock _intro = new() { TextWrapping = TextWrapping.Wrap };
    private readonly CalendarDatePicker _day = new() { MinWidth = 200 };
    private readonly TimePicker _time = new() { MinuteIncrement = 1 };
    private readonly StackPanel _edit = new() { Spacing = 12 };
    private readonly Button _all = new() { Style = (Style)Application.Current.Resources["AccentButtonStyle"], HorizontalAlignment = HorizontalAlignment.Stretch };
    private readonly Button _only = new() { Content = Words.MemoryShiftOnlyThis(), HorizontalAlignment = HorizontalAlignment.Stretch };
    private readonly TextBlock _undated = new() { TextWrapping = TextWrapping.Wrap };
    private readonly StackPanel _choose = new() { Spacing = 8 };
    private readonly TextBlock _progress = new();
    private readonly StackPanel _run = new() { Orientation = Orientation.Horizontal, Spacing = 12 };
    private readonly TextBlock _done = new() { TextWrapping = TextWrapping.Wrap };
    private readonly TextBlock _refused = new() { TextWrapping = TextWrapping.Wrap };
    private readonly StackPanel _finished = new() { Spacing = 8 };
    private CaptureTimeShiftShown? _shown;
    private bool _filled;

    private CaptureTimeShiftDialog(long id)
    {
        _id = id;
        AutomationProperties.SetAutomationId(_dialog, "shift-dialog");
        AutomationProperties.SetAutomationId(_day, "shift-day");
        AutomationProperties.SetName(_day, Words.MemoryFieldCaptured());
        AutomationProperties.SetAutomationId(_time, "shift-time");
        AutomationProperties.SetName(_time, Words.MemoryFieldCaptured());
        AutomationProperties.SetAutomationId(_all, "shift-all");
        AutomationProperties.SetAutomationId(_only, "shift-only");
        AutomationProperties.SetAutomationId(_done, "shift-done");
        AutomationProperties.SetAutomationId(_refused, "shift-refused");
        _undated.Foreground = _progress.Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"];
        _refused.Foreground = (Brush)Application.Current.Resources["SystemFillColorCriticalBrush"];
        var pickers = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        pickers.Children.Add(_day);
        pickers.Children.Add(_time);
        _edit.Children.Add(_intro);
        _edit.Children.Add(pickers);
        _choose.Children.Add(_all);
        _choose.Children.Add(_only);
        _choose.Children.Add(_undated);
        _run.Children.Add(new ProgressRing { IsActive = true, Width = 20, Height = 20 });
        _run.Children.Add(_progress);
        _finished.Children.Add(_done);
        _finished.Children.Add(_refused);
        var content = new StackPanel { MinWidth = 380 };
        foreach (var panel in new UIElement[] { _edit, _choose, _run, _finished }) content.Children.Add(panel);
        _dialog.Content = content;
        _all.Click += (_, _) => Send(new Call.CaptureTimeShiftAll { shift = _id });
        _only.Click += (_, _) => Send(new Call.CaptureTimeShiftOnly { shift = _id });
        // Primary: Continue while the time is picked, Close once done; Secondary: the Undo; Close: Cancel (back from the choice)
        _dialog.PrimaryButtonClick += (_, e) =>
        {
            if (_shown?.step != ShiftStep.Edit) return;
            e.Cancel = true;
            if (_day.Date is not { } day) return;
            Send(new Call.CaptureTimeShiftPropose { shift = _id, epochMs = CurationText.EpochMs(day, _time.Time, TimeZoneInfo.Local) });
        };
        _dialog.SecondaryButtonClick += (_, e) =>
        {
            e.Cancel = true;
            Send(new Call.CaptureTimeShiftUndo { shift = _id });
        };
        _dialog.CloseButtonClick += (_, e) =>
        {
            if (_shown?.step != ShiftStep.Choose) return;
            e.Cancel = true;
            Send(new Call.CaptureTimeShiftBack { shift = _id });
        };
        _dialog.Closing += (_, e) => { if (_shown?.step == ShiftStep.Run) e.Cancel = true; };
    }

    /// <summary>The shift of Contributions view <paramref name="buckets"/>'s selection, shown until it is closed; nothing where
    /// the core offers none (Buckets.Shift refused).</summary>
    public static async Task Show(Window window, long buckets)
    {
        var (answer, _) = await Core.CallAsync(new Call.BucketsShift { buckets = buckets });
        if (answer is not Answer.CaptureTimeShiftOpened { shift: var id })
        {
            Log.Write($"Buckets.Shift answered {(answer is Answer.Refused r ? $"Refused {r.why}" : answer.GetType().Name)}");
            return;
        }
        Log.Write($"capture-time shift {id} open");
        var shift = new CaptureTimeShiftDialog(id);
        using var watch = Core.Watch(new Watched.CaptureTimeShift { shift = id }, shift.Updated, e => Log.Write($"a shift update: {e.Message}"));
        try
        {
            await Dialogs.Show(window, shift._dialog);
        }
        finally
        {
            Act.Send(new Call.CaptureTimeShiftClose { shift = id });
        }
    }

    private async void Send(Call call)
    {
        try
        {
            var (answer, _) = await Core.CallAsync(call);
            Log.Write($"{call.GetType().Name}: {(answer is Answer.Refused r ? $"Refused {r.why}" : answer.GetType().Name)}");
        }
        catch (Exception e)
        {
            Log.Write($"{call.GetType().Name} failed ({e.GetType().Name})");
        }
    }

    private void Updated(Update update)
    {
        if (update is not Update.CaptureTimeShift { shown: var s }) return;
        _shown = s;
        if (s is null || s.closed)
        {
            _dialog.Hide();
            return;
        }
        Log.Write($"shift shown: {s.step}, undoable {s.undoable}, refused {s.refused.Count}");
        if (!_filled)
        {
            _filled = true;
            var (day, time) = CurationText.Local(s.anchorAtMs, TimeZoneInfo.Local);
            _day.Date = day;
            _time.Time = time;
        }
        _intro.Text = Said.Notice(s.intro);
        _all.Content = s.all is { } all ? Said.Notice(all) : "";
        Line(_undated, s.undated);
        Line(_progress, s.progress);
        Line(_done, s.done);
        _refused.Text = string.Join("\n", s.refused.Select(Said.Notice));
        _refused.Visibility = s.refused.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
        _edit.Visibility = s.step == ShiftStep.Edit ? Visibility.Visible : Visibility.Collapsed;
        _choose.Visibility = s.step == ShiftStep.Choose ? Visibility.Visible : Visibility.Collapsed;
        _run.Visibility = s.step == ShiftStep.Run ? Visibility.Visible : Visibility.Collapsed;
        _finished.Visibility = s.step == ShiftStep.Done ? Visibility.Visible : Visibility.Collapsed;
        _dialog.PrimaryButtonText = s.step switch
        {
            ShiftStep.Edit => Words.MemoryShiftNext(),
            ShiftStep.Done => Words.MemoryClose(),
            _ => "",
        };
        _dialog.SecondaryButtonText = s.step == ShiftStep.Done && s.undoable ? Words.TrashUndo() : "";
        _dialog.CloseButtonText = s.step is ShiftStep.Edit or ShiftStep.Choose ? Words.EntryCancel() : "";
        _dialog.DefaultButton = s.step == ShiftStep.Edit ? ContentDialogButton.Primary : ContentDialogButton.None;
    }

    private static void Line(TextBlock line, Notice? n)
    {
        line.Text = n is null ? "" : Said.Notice(n);
        line.Visibility = n is null ? Visibility.Collapsed : Visibility.Visible;
    }
}
