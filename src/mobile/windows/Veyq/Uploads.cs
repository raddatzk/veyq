// The uploads (ADR 0123's Consequences, the Windows app's Depot whole), Fluent's way: files and a folder picked through Windows' own pickers
// (FileOpenPicker, FolderPicker, owned by the window as the save picker is) and handed to the core by their paths — the
// core opens and reads each at offsets a frame at a time (Upload.Files, Upload.Folder; ADR 0131 decision 3), so nothing of
// a file crosses and none is held whole —, a file's Replace chosen before its picker opens (File.ChooseReplace,
// Upload.Replace), and the queue as an AppBarButton in the content's CommandBar while it lists anything: its label the
// core's one line, its Flyout each batch with its items, their progress, Cancel and Retry, Cancel all and a finished
// batch's Close. What the uploads say once — a folder's outcome, a replace offered — is an InfoBar under the bar, its
// action button the offer's (Upload.TakeOffer), closed otherwise (Upload.Seen).
//
// Every state is the core's DepotUploads (Watched.Uploads); every action one call.
using System;
using System.Collections.Generic;
using System.Linq;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Veyq.Bridge;
using Veyq.Bridge.Wire;
using Windows.Storage.Pickers;

namespace Veyq;

internal sealed class Uploads : IDisposable
{
    private readonly Window _window;
    private readonly InfoBar _offer;
    private readonly StackPanel _list = new() { Spacing = 12, MinWidth = 360, MaxWidth = 480 };
    private readonly Watch? _watch;
    private long? _saidShown;
    private bool _taking;
    private readonly Dictionary<string, (UploadStatus, int)> _logged = [];

    /// <summary>The queue's command for the CommandBar; null while the queue lists nothing.</summary>
    public AppBarButton? Button { get; private set; }

    private readonly AppBarButton _button;

    /// <summary>Told when the command shows or goes (the CommandBar is drawn anew then).</summary>
    public Action? Changed { get; set; }

    public Uploads(Window window, InfoBar offer)
    {
        _window = window;
        _offer = offer;
        var scroll = new ScrollViewer { Content = _list, MaxHeight = 480, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
        _button = new AppBarButton { Icon = new SymbolIcon(Symbol.Upload), Label = Words.UploadTitle(), Flyout = new Flyout { Content = scroll } };
        AutomationProperties.SetAutomationId(_button, "uploads");
        _offer.CloseButtonClick += (_, _) =>
        {
            if (_saidShown is { } id && !_taking) Act.Send(new Call.UploadSeen { said = id });
        };
        _watch = Core.Watch(new Watched.Uploads(), update =>
        {
            if (update is Update.Uploads u) Draw(u.shown);
        }, e => Log.Write($"an uploads update: {e.Message}"));
    }

    private void Draw(UploadsShown? s)
    {
        _list.Children.Clear();
        var visible = s is not null && (s.summary is not null || s.preparing is not null);
        var was = Button is not null;
        Button = visible ? _button : null;
        if (s is null)
        {
            if (was) Changed?.Invoke();
            return;
        }
        LogItems(s);
        var summary = s.summary is { } n ? Said.Notice(n) : null;
        _button.Label = summary ?? (s.preparing is { } p ? Said.Notice(p) : Words.UploadTitle());
        AutomationProperties.SetName(_button, _button.Label);

        // the head: the title, the one line, Cancel all while anything may still go up
        var head = new Grid { ColumnSpacing = 12 };
        head.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        head.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var titles = new StackPanel();
        titles.Children.Add(new TextBlock { Text = Words.UploadTitle(), Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"] });
        if (summary is not null) titles.Children.Add(new TextBlock { Text = summary, Foreground = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["TextFillColorSecondaryBrush"] });
        head.Children.Add(titles);
        if (s.active > 0)
        {
            var all = new Button { Content = Words.UploadCancelAll(), VerticalAlignment = VerticalAlignment.Center };
            AutomationProperties.SetAutomationId(all, "uploads-cancel-all");
            all.Click += (_, _) =>
            {
                Log.Write("uploads: cancel all pressed");
                Act.Send(new Call.UploadCancelAll());
            };
            Grid.SetColumn(all, 1);
            head.Children.Add(all);
        }
        _list.Children.Add(head);
        if (s.preparing is { } preparing)
        {
            var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
            row.Children.Add(new ProgressRing { IsActive = true, Width = 16, Height = 16 });
            row.Children.Add(new TextBlock { Text = Said.Notice(preparing), TextWrapping = TextWrapping.Wrap });
            _list.Children.Add(row);
        }
        foreach (var batch in s.batches)
        {
            var top = new Grid();
            top.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            top.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            top.Children.Add(new TextBlock { Text = batch.label, Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"], TextTrimming = TextTrimming.CharacterEllipsis, VerticalAlignment = VerticalAlignment.Center });
            if (batch.finished)
            {
                var close = new HyperlinkButton { Content = Words.UploadClose() };
                AutomationProperties.SetAutomationId(close, $"upload-close:{batch.label}");
                var id = batch.id;
                close.Click += (_, _) => Act.Send(new Call.UploadAcknowledge { batch = id });
                Grid.SetColumn(close, 1);
                top.Children.Add(close);
            }
            _list.Children.Add(top);
            foreach (var item in batch.items) _list.Children.Add(Row(item));
        }
        Say(s);
        if (was != visible) Changed?.Invoke();
    }

    private static UIElement Row(UploadItem item)
    {
        var row = new Grid { ColumnSpacing = 8 };
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var text = new StackPanel { Spacing = 2 };
        text.Children.Add(new TextBlock { Text = item.name, TextTrimming = TextTrimming.CharacterEllipsis });
        if (item.status == UploadStatus.Uploading || item.status == UploadStatus.Preparing)
        {
            var bar = new ProgressBar { Minimum = 0, Maximum = 1, Value = item.progress, IsIndeterminate = item.status == UploadStatus.Preparing };
            AutomationProperties.SetAutomationId(bar, $"upload-progress:{item.name}");
            text.Children.Add(bar);
        }
        var line = new TextBlock { Text = Said.Notice(item.line), TextWrapping = TextWrapping.Wrap, Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"] };
        line.Foreground = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources[item.failed ? "SystemFillColorCriticalBrush" : "TextFillColorSecondaryBrush"];
        AutomationProperties.SetAutomationId(line, $"upload-line:{item.name}");
        text.Children.Add(line);
        row.Children.Add(text);
        Button? act = null;
        if (item.canCancel)
        {
            act = new Button { Content = new SymbolIcon(Symbol.Cancel) };
            AutomationProperties.SetName(act, Words.UploadCancel());
            ToolTipService.SetToolTip(act, Words.UploadCancel());
            AutomationProperties.SetAutomationId(act, $"upload-cancel:{item.name}");
            var (id, name) = (item.id, item.name);
            act.Click += (_, _) =>
            {
                Log.Write($"upload {name}: cancel pressed");
                Act.Send(new Call.UploadCancel { item = id });
            };
        }
        else if (item.canRetry)
        {
            act = new Button { Content = new SymbolIcon(Symbol.Refresh) };
            AutomationProperties.SetName(act, Words.UploadRetry());
            ToolTipService.SetToolTip(act, Words.UploadRetry());
            AutomationProperties.SetAutomationId(act, $"upload-retry:{item.name}");
            var (id, name) = (item.id, item.name);
            act.Click += (_, _) =>
            {
                Log.Write($"upload {name}: retry pressed");
                Act.Send(new Call.UploadRetry { item = id });
            };
        }
        if (act is not null)
        {
            act.VerticalAlignment = VerticalAlignment.Center;
            Grid.SetColumn(act, 1);
            row.Children.Add(act);
        }
        return row;
    }

    /// <summary>What the uploads say now, in the InfoBar once: its action button the offer's, closed otherwise.</summary>
    private void Say(UploadsShown s)
    {
        if (s.said is not { } told)
        {
            if (_saidShown is not null && !_taking) _offer.IsOpen = false;
            _saidShown = null;
            return;
        }
        if (_saidShown == told.id) return;
        _saidShown = told.id;
        _taking = false;
        Log.Write($"uploads say {told.id}: {string.Join(", ", told.notices.Select(n => n.msg))}");
        _offer.Severity = told.action is null ? InfoBarSeverity.Warning : InfoBarSeverity.Informational;
        _offer.Message = string.Join("\n", told.notices.Select(Said.Notice));
        if (told.action is { } action)
        {
            var take = new Button { Content = Said.Key(action) };
            AutomationProperties.SetAutomationId(take, "uploads-take");
            var id = told.id;
            take.Click += (_, _) =>
            {
                _taking = true;
                Log.Write($"uploads said {id}: its action taken");
                Act.Send(new Call.UploadTakeOffer { said = id });
                _offer.IsOpen = false;
            };
            _offer.ActionButton = take;
        }
        else _offer.ActionButton = null;
        _offer.IsOpen = true;
    }

    /// <summary>Each item's status once, and its progress at each tenth, in the log (a pass reads them).</summary>
    private void LogItems(UploadsShown s)
    {
        foreach (var item in s.batches.SelectMany(b => b.items))
        {
            var now = (item.status, (int)Math.Floor(item.progress * 10));
            if (_logged.TryGetValue(item.id, out var was) && was == now) continue;
            _logged[item.id] = now;
            Log.Write($"upload {item.name}: {item.status} {now.Item2 * 10}% of {item.sizeBytes} B");
        }
    }

    /// <summary>New's "Upload files": the open picker picks several, their paths go to the core (Upload.Files).</summary>
    public async void PickFiles(UploadLevel level, Action<bool, string> say)
    {
        try
        {
            var picker = new FileOpenPicker { SuggestedStartLocation = PickerLocationId.DocumentsLibrary };
            picker.FileTypeFilter.Add("*");
            WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(_window));
            var picked = await picker.PickMultipleFilesAsync();
            if (picked is null || picked.Count == 0) return;
            var paths = picked.Select(f => f.Path).Where(p => !string.IsNullOrEmpty(p)).ToList();
            Log.Write($"upload files: {paths.Count} picked");
            var (answer, _) = await Core.CallAsync(new Call.UploadFiles { scopeId = level.ScopeId, parentId = level.ParentId, label = level.Label, paths = paths });
            Queued(answer, "Upload.Files", say);
        }
        catch (Exception e)
        {
            // an exception out of an async void would end the app in combase, with nothing in the log
            Log.Write($"upload files failed: {e.GetType().Name}: {e.Message}");
        }
    }

    /// <summary>New's "Upload folder…": the folder picker picks one directory, which the core walks and uploads
    /// (Upload.Folder).</summary>
    public async void PickFolder(UploadLevel level, Action<bool, string> say)
    {
        try
        {
            var picker = new FolderPicker { SuggestedStartLocation = PickerLocationId.DocumentsLibrary };
            picker.FileTypeFilter.Add("*");
            WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(_window));
            var folder = await picker.PickSingleFolderAsync();
            if (folder is null || string.IsNullOrEmpty(folder.Path)) return;
            Log.Write("upload folder: picked");
            var (answer, _) = await Core.CallAsync(new Call.UploadFolder { scopeId = level.ScopeId, parentId = level.ParentId, path = folder.Path });
            Queued(answer, "Upload.Folder", say);
        }
        catch (Exception e)
        {
            Log.Write($"upload folder failed: {e.GetType().Name}: {e.Message}");
        }
    }

    /// <summary>A file's Replace (the header's, on its page <paramref name="file"/>): chosen in the core first — the replace
    /// expects the content shown now —, then the open picker picks the one file whose path goes to the core
    /// (Upload.Replace).</summary>
    public async void Replace(long file, Action<bool, string> say)
    {
        try
        {
            var (chosen, _) = await Core.CallAsync(new Call.FileChooseReplace { file = file });
            if (chosen is not Answer.Done)
            {
                Log.Write($"File.ChooseReplace answered {chosen.GetType().Name}");
                return;
            }
            var picker = new FileOpenPicker { SuggestedStartLocation = PickerLocationId.DocumentsLibrary };
            picker.FileTypeFilter.Add("*");
            WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(_window));
            var picked = await picker.PickSingleFileAsync();
            if (picked is null || string.IsNullOrEmpty(picked.Path)) return;
            var (answer, _) = await Core.CallAsync(new Call.UploadReplace { file = file, path = picked.Path });
            Queued(answer, "Upload.Replace", say);
        }
        catch (Exception e)
        {
            Log.Write($"replace failed: {e.GetType().Name}: {e.Message}");
        }
    }

    internal static void Queued(Answer answer, string what, Action<bool, string> say)
    {
        switch (answer)
        {
            case Answer.Queued { count: var count }:
                Log.Write($"{what}: {count} queued");
                break;
            case Answer.Said { notice: { } n }:
                say(false, Said.Notice(n));
                break;
            default:
                Log.Write($"{what} answered {answer.GetType().Name}");
                break;
        }
    }

    public void Dispose() => _watch?.Dispose();
}
