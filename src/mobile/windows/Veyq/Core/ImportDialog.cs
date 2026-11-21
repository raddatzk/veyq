// A veyq download offered as an import (ADR 0152 decisions 1, 5 and 6; the web's ImportSubtreeDialog.vue): one
// ContentDialog through every step, as Fluent's dialogs hold a short flow — reading, the summary with what is left out and
// whether it fits, running with its progress — and its buttons the web's: Import (Import the rest where something is left
// out; it keeps the dialog open and runs), Upload as a file (a ZIP from an upload: queued as the file it is, the dialog
// closes), and Cancel, which stops a run and rolls back what it made (Import.Cancel). Escape is Cancel. A done import
// closes with the core's imported line in the shell's InfoBar. Every state is the core's ImportDialog
// (Watched.Import → ImportShown), what lands where ImportText's; every button one call. Nothing of the download is here:
// no path, no byte, no key — counts, the manifest's names and words.

namespace Veyq.Core;

using System;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Veyq.Bridge;
using Veyq.Bridge.Wire;

internal sealed class ImportDialog
{
    private readonly long _import;
    private readonly Action<bool, string> _say;
    private readonly ContentDialog _dialog;
    private readonly StackPanel _body = new() { Spacing = 12, MinWidth = 360, MaxWidth = 480 };
    private ImportStep? _logged;
    private bool _answered;
    private bool _done;

    private ImportDialog(long import, Action<bool, string> say)
    {
        _import = import;
        _say = say;
        _dialog = new ContentDialog
        {
            Title = Words.ImportTitle(),
            Content = new ScrollViewer { Content = _body, VerticalScrollBarVisibility = ScrollBarVisibility.Auto },
            CloseButtonText = Words.EntryCancel(),
            DefaultButton = ContentDialogButton.Primary,
        };
        AutomationProperties.SetAutomationId(_dialog, "import-dialog");
        _dialog.PrimaryButtonClick += (_, e) =>
        {
            // the run is drawn in this dialog: it stays open
            e.Cancel = true;
            Log.Write($"import {_import}: import pressed");
            Act.Send(new Call.ImportRun { import = _import });
        };
        _dialog.SecondaryButtonClick += (_, _) =>
        {
            _answered = true;
            Log.Write($"import {_import}: upload as a file pressed");
            Act.Send(new Call.ImportAsFile { import = _import });
        };
    }

    /// <summary>Opens the dialog of the import <paramref name="import"/> over <paramref name="window"/>, saying through
    /// <paramref name="say"/> what a done import says.</summary>
    public static async void Open(Window window, long import, Action<bool, string> say)
    {
        try
        {
            await new ImportDialog(import, say).Show(window);
        }
        catch (Exception e)
        {
            // an exception out of an async void would end the app in combase, with nothing in the log
            Log.Write($"the import's dialog failed: {e.GetType().Name}: {e.Message}");
        }
    }

    private async System.Threading.Tasks.Task Show(Window window)
    {
        using var watch = Core.Watch(new Watched.Import { import = _import }, update =>
        {
            if (update is Update.Import u) Draw(u.shown);
        }, e => Log.Write($"an import update: {e.Message}"));
        await Dialogs.Show(window, _dialog);
        // closed by Cancel, Escape, or a dialog that could not open: the core stops a run and rolls back; after a done
        // import or the core's own close it only lets the import go
        if (!_answered)
        {
            _answered = true;
            Log.Write($"import {_import}: {(_done ? "closed after its end" : "cancelled")}");
            Act.Send(new Call.ImportCancel { import = _import });
        }
    }

    private void Draw(ImportShown? s)
    {
        if (s is null || s.closed)
        {
            // Upload as a file taken, or the import gone: nothing more to answer
            _answered = true;
            _dialog.Hide();
            return;
        }
        if (_logged != s.step)
        {
            _logged = s.step;
            Log.Write($"import {_import}: {s.step}, {s.done} of {s.total}, said {string.Join(", ", System.Linq.Enumerable.Select(s.said, n => n.msg))}");
        }
        if (s.step == ImportStep.Done)
        {
            if (_done) return;
            _done = true;
            _say(true, ImportText.Done(s));
            _dialog.Hide();
            return;
        }
        var face = ImportText.Of(s);
        _body.Children.Clear();
        if (face.Reading)
        {
            var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
            row.Children.Add(new ProgressRing { IsActive = true, Width = 16, Height = 16 });
            row.Children.Add(Text(Words.ImportReading(), "import-reading"));
            _body.Children.Add(row);
        }
        if (face.Root is not null || face.Summary is not null || face.Nothing is not null)
        {
            var summary = new StackPanel { Spacing = 2 };
            if (face.Root is { } root)
            {
                var name = Text(root, "import-root");
                name.Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"];
                summary.Children.Add(name);
            }
            if ((face.Summary ?? face.Nothing) is { } line) summary.Children.Add(Text(line, "import-summary"));
            _body.Children.Add(summary);
        }
        if (face.LeftOut.Count > 0)
        {
            var left = new StackPanel { Spacing = 4 };
            AutomationProperties.SetAutomationId(left, "import-skipped");
            left.Children.Add(Text(Words.ImportSkipped(), "import-skipped-heading"));
            for (var i = 0; i < face.LeftOut.Count; i++) left.Children.Add(Text($"• {face.LeftOut[i]}", $"import-skipped:{i}"));
            _body.Children.Add(left);
        }
        if (face.NoRoom is { } noRoom) _body.Children.Add(Bar(noRoom, InfoBarSeverity.Warning, "import-no-room"));
        if (face.Notice)
        {
            var notice = Text(Words.ImportNotice(), "import-notice");
            notice.Foreground = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["TextFillColorSecondaryBrush"];
            _body.Children.Add(notice);
        }
        if (face.Progress is { } progress)
        {
            var run = new StackPanel { Spacing = 4 };
            run.Children.Add(Text(progress, "import-progress"));
            var bar = new ProgressBar { Minimum = 0, Maximum = 1, Value = face.Fraction };
            AutomationProperties.SetAutomationId(bar, "import-progress-bar");
            run.Children.Add(bar);
            _body.Children.Add(run);
        }
        if (face.Problem is { } problem) _body.Children.Add(Bar(problem, InfoBarSeverity.Error, "import-error"));

        _dialog.PrimaryButtonText = face.Primary ?? "";
        _dialog.IsPrimaryButtonEnabled = face.PrimaryEnabled;
        _dialog.SecondaryButtonText = face.AsFile ? Words.ImportUploadAsFile() : "";
        _dialog.IsSecondaryButtonEnabled = face.AsFileEnabled;
        _dialog.DefaultButton = face.PrimaryEnabled ? ContentDialogButton.Primary : ContentDialogButton.Close;
    }

    private static TextBlock Text(string text, string id)
    {
        var t = new TextBlock { Text = text, TextWrapping = TextWrapping.Wrap, IsTextSelectionEnabled = true };
        AutomationProperties.SetAutomationId(t, id);
        return t;
    }

    private static InfoBar Bar(string message, InfoBarSeverity severity, string id)
    {
        var bar = new InfoBar { Message = message, Severity = severity, IsOpen = true, IsClosable = false };
        AutomationProperties.SetAutomationId(bar, id);
        return bar;
    }
}
