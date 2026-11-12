// An open Pot (ADR 0122 decision 4: Expenses' places are the open Pot's Entries, Overview and Settings, as the web's Pot
// has them). The Pot is a model of its own in the core (Pot.Open), watched while it is on screen and closed when it
// leaves; what each row says — its line, its badge, a balance's words and tone, who pays whom — is the core's
// (PotView.Shown). This lays it out and writes money. Linux's pot.rs, in Fluent: a SelectorBar over the places' lists,
// each person with their picture (Avatars), each place's scroll starting with the node's head (NodeHeader.Hero).
//
// Its live poll (Watched.PotLive) runs while the page is loaded and its window is active, as the phones poll while the
// Pot is shown: the watch is disposed when either ends, which stops the poll. Refresh (Pot.Refresh) is the CommandBar's
// (F5 / Ctrl+R, ShellView). A settle-up row is recorded (Pot.Record, dated the core's today) after a ContentDialog asked,
// one row or all; an entry opens the entry editor (EntryEditorPage, a page in place of the places, its history
// inside), a deleted one its history and restore (EntryHistoryDialog); "Add entry" is the shell's CommandBar's New
// (AddEntry). Its download
// (Pot.Download) goes where the system's save picker says, under the name the core gives (Pot.ArchiveName); the core
// writes the file (ADR 0130 decision 3).
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Veyq.Bridge;
using Veyq.Bridge.Wire;
using Windows.Storage.Pickers;

namespace Veyq;

internal sealed class PotPage : IDisposable
{
    private readonly Grid _root = new();
    private long? _id;
    private Watch? _watch;
    private PotModelState? _state;
    private IReadOnlyList<ShownTab> _tabs = [];
    private BarTab? _selected;
    private bool _closed;
    private readonly Window _window;
    private readonly Action<bool, string> _say;
    private Watch? _live;
    private bool _loaded;
    private bool _active = true;
    private UIElement? _hero;
    /// <summary>The column the head was put in last.</summary>
    private Panel? _heroHolder;
    /// <summary>The places' scroll, kept across redraws (an update of the live poll keeps where it stood).</summary>
    private readonly ScrollViewer _scroll = new() { HorizontalScrollMode = ScrollMode.Disabled, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled };
    private string? _place;
    private EntryEditorPage? _editor;
    /// <summary>The Settings place, opened in the core when it is first shown and kept while the page is, and the column
    /// it was put in last (taken out of it before the next draw, as the head).</summary>
    private PotSettingsView? _settings;
    private Panel? _settingsHolder;
    private bool _canWrite;

    public ObjectRef Ref { get; }

    /// <summary>Told whether the Pot takes writes, each time its view is read (its header's facts).</summary>
    public Action<bool>? Read { get; set; }

    /// <summary>Told when what the shell's CommandBar offers for this Pot changed: it was read, an editor opened or closed.</summary>
    public Action? Changed { get; set; }

    /// <summary>Whether "Add entry" is offered: the Pot read, it takes writes, and no editor is open.</summary>
    public bool CanAdd => _canWrite && _editor is null && _id is not null;

    /// <summary>The entry editor of a new entry (the CommandBar's "Add entry").</summary>
    public void AddEntry() => OpenEditor(null);

    /// <summary>The entry editor of <paramref name="entry"/> (null: a new one), in place of the places until it closes.</summary>
    private void OpenEditor(string? entry)
    {
        if (_id is not { } pot || _editor is not null) return;
        _editor = new EntryEditorPage(_window, pot, entry, _say, () =>
        {
            _editor = null;
            Draw();
            Changed?.Invoke();
        });
        Draw();
        Changed?.Invoke();
    }

    public UIElement Page => _root;

    /// <summary>Opens Pot <paramref name="r"/> on a page of <paramref name="window"/>; <paramref name="say"/> says what
    /// its actions did.</summary>
    public PotPage(ObjectRef r, Window window, Action<bool, string> say)
    {
        Ref = r;
        _window = window;
        _say = say;
        _root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        _root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        // the live poll's lifetime: the page loaded and its window active
        _root.Loaded += (_, _) => { _loaded = true; Live(); };
        _root.Unloaded += (_, _) => { _loaded = false; Live(); };
        _window.Activated += Activated;
        Draw();
        Open();
    }

    private void Activated(object sender, WindowActivatedEventArgs e)
    {
        _active = e.WindowActivationState != WindowActivationState.Deactivated;
        Live();
    }

    /// <summary>The live poll watched while the page is loaded and its window active, disposed (so stopped) when either
    /// ends.</summary>
    private void Live()
    {
        var wanted = _loaded && _active && !_closed && _id is not null;
        if (wanted == (_live is not null)) return;
        Log.Write($"pot live poll {(wanted ? "on" : "off")}");
        if (wanted)
        {
            _live = Core.Watch(new Watched.PotLive { pot = _id!.Value }, _ => { }, e => Log.Write($"the Pot's poll: {e.Message}"));
        }
        else
        {
            _live?.Dispose();
            _live = null;
        }
    }

    /// <summary>Reads the Pot again now (Refresh, F5 / Ctrl+R); what changed arrives on its watch.</summary>
    public void Refresh()
    {
        if (_id is not { } pot) return;
        Log.Write("pot refresh");
        Act.Send(new Call.PotRefresh { pot = pot });
    }

    /// <summary>The node's head (NodeHeader.Hero), placed first in each place's scroll; another header's replaces it.</summary>
    public void Hero(UIElement? hero)
    {
        if (ReferenceEquals(hero, _hero)) return;
        if (_hero is not null) _heroHolder?.Children.Remove(_hero);
        _heroHolder = null;
        _hero = hero;
        Draw();
    }

    private async void Open()
    {
        Answer answer;
        try
        {
            (answer, _) = await Core.CallAsync(new Call.PotOpen { Ref = Ref });
        }
        catch (Exception e)
        {
            Log.Write($"the Pot did not open in the core ({e.GetType().Name})");
            return;
        }
        if (answer is not Answer.PotOpened { pot: var pot })
        {
            Log.Write($"the Pot did not open in the core ({answer.GetType().Name})");
            return;
        }
        if (_closed)
        {
            Act.Send(new Call.PotClose { pot = pot });
            return;
        }
        _id = pot;
        Live();
        _watch = Core.Watch(new Watched.Pot { pot = pot }, update =>
        {
            if (_closed || update is not Update.Pot p) return;
            _state = p.state;
            if (p.state?.view is { } view)
            {
                Log.Write($"pot update: {view.entries.Count} entries, {view.deleted.Count} deleted, {view.settleUp.Count} to settle");
                Read?.Invoke(view.canWrite);
                if (view.canWrite != _canWrite)
                {
                    _canWrite = view.canWrite;
                    Changed?.Invoke();
                }
            }
            Draw();
        }, e => Log.Write($"a Pot update: {e.Message}"));
    }

    public void Dispose()
    {
        _closed = true;
        _editor?.Dispose();
        _editor = null;
        _settings?.Dispose();
        _settings = null;
        _window.Activated -= Activated;
        _live?.Dispose();
        _live = null;
        _watch?.Dispose();
        _watch = null;
        if (_id is { } pot) Act.Send(new Call.PotClose { pot = pot });
        _id = null;
    }

    /// <summary>
    /// Downloads this Pot as the web's ZIP: the system's save picker (owned by <paramref name="window"/>, the handle an
    /// unpackaged app gives it) suggests the core's name in Downloads, the core writes the file the picker chose, and
    /// <paramref name="said"/> says how it went — true when done. A dismissed picker says nothing.
    /// </summary>
    public async void Download(nint window, Action<bool, string> said)
    {
        if (_id is not { } pot) return;
        var name = Ref.name;
        try
        {
            var (named, _) = await Core.CallAsync(new Call.PotArchiveName { pot = pot });
            if (named is not Answer.Name { name: var suggested })
            {
                Log.Write($"Pot.ArchiveName answered {named.GetType().Name}");
                said(false, Words.DownloadFailed(name));
                return;
            }
            var picker = new FileSavePicker { SuggestedStartLocation = PickerLocationId.Downloads, SuggestedFileName = suggested };
            picker.FileTypeChoices.Add(Words.DownloadTypeZip(), [Path.GetExtension(suggested)]);
            WinRT.Interop.InitializeWithWindow.Initialize(picker, window);
            var file = await picker.PickSaveFileAsync();
            if (file is null) return;
            var (answer, _) = await Core.CallAsync(new Call.PotDownload { pot = pot, path = file.Path, deletedName = Words.PotDeletedPerson() });
            if (answer is Answer.Done)
            {
                said(true, Words.DownloadDone(name));
                return;
            }
            Log.Write($"Pot.Download answered {(answer is Answer.Failed f ? $"Failed ({f.error})" : answer.GetType().Name)}");
            said(false, Words.DownloadFailed(name));
        }
        catch (Exception e)
        {
            // an exception out of an async void would end the app in combase, with nothing in the log
            Log.Write($"the download failed: {e.GetType().Name}: {e.Message}");
            said(false, Words.DownloadFailed(name));
        }
    }

    /// <summary>The places the shell offers for this Pot and the one selected (Shell.barTabs, selectedTab).</summary>
    public void Places(IReadOnlyList<ShownTab> tabs, BarTab? selected)
    {
        if (Same(tabs, _tabs) && selected?.key == _selected?.key) return;
        _tabs = tabs;
        _selected = selected;
        Draw();
    }

    private static bool Same(IReadOnlyList<ShownTab> a, IReadOnlyList<ShownTab> b)
    {
        if (a.Count != b.Count) return false;
        for (var i = 0; i < a.Count; i++)
        {
            if (a[i].tab.key != b[i].tab.key || a[i].word != b[i].word) return false;
        }
        return true;
    }

    /// <summary>A person's picture on a row, as Linux draws it (32 px).</summary>
    private const double PersonSize = 32;

    private static Brush Brush(string key) => (Brush)Application.Current.Resources[key];

    private static Style TextStyle(string key) => (Style)Application.Current.Resources[key];

    /// <summary>The page drawn anew; what it threw is said in the log (out of a watch's callback it would end the app in
    /// combase, with nothing in the log).</summary>
    private void Draw()
    {
        try
        {
            DrawNow();
        }
        catch (Exception e)
        {
            Log.Write($"the Pot did not draw: {e.GetType().Name} 0x{e.HResult:X8}: {e.Message} | {string.Join(" | ", (e.StackTrace ?? "").Split('\n').Select(l => l.Trim()).Take(6))}");
        }
    }

    private void DrawNow()
    {
        // the head moves to the new place's scroll, taken from the column it was put in (its Parent read null while that
        // column still loaded, two draws in a row after the editor closed, and the add threw 0x800F1000)
        if (_hero is not null) _heroHolder?.Children.Remove(_hero);
        _heroHolder = null;
        if (_settings is not null) _settingsHolder?.Children.Remove(_settings.Panel);
        _settingsHolder = null;
        // the scroll stays in the tree across redraws: taken out and put back while it still loads (an update right after
        // the editor closed) WinUI threw "no installed components were detected" and the page stayed empty
        foreach (var child in _root.Children.Where(c => !ReferenceEquals(c, _scroll)).ToList()) _root.Children.Remove(child);
        _scroll.Visibility = Visibility.Collapsed;
        if (_editor is { } editor)
        {
            Grid.SetRowSpan((FrameworkElement)editor.Page, 2);
            _root.Children.Add(editor.Page);
            return;
        }
        var view = _state?.view;
        if (view is null)
        {
            var status = new StackPanel { Spacing = 12, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
            if (_state is { failed: true })
            {
                status.Children.Add(Text(Words.PotOpenFailed(), "pot-failed", "SubtitleTextBlockStyle"));
            }
            else
            {
                status.Children.Add(new ProgressRing { IsActive = true });
                status.Children.Add(Text(Words.PotLoading(), "pot-loading", "BodyTextBlockStyle"));
            }
            Grid.SetRow(status, 1);
            _root.Children.Add(status);
            return;
        }
        if (_tabs.Count > 1) _root.Children.Add(PlacesBar());
        var content = new StackPanel { Spacing = 4, Padding = new Thickness(12, 8, 12, 24) };
        if (_hero is not null)
        {
            content.Children.Add(_hero);
            _heroHolder = content;
        }
        if (view.missing.Count > 0) Missing(content, view);
        if (_selected?.key == "expenses.settings")
        {
            // the settings choose a base currency the Pot does not have yet
            Settings(content);
        }
        else if (!view.ready)
        {
            content.Children.Add(Note(Words.SettingsMissingReadOnly(), "pot-not-ready"));
        }
        else if (_selected?.key == "expenses.overview")
        {
            Overview(content, view, _id);
        }
        else
        {
            Entries(content, view, _id);
        }
        _scroll.Content = content;
        Grid.SetRow(_scroll, 1);
        if (!_root.Children.Contains(_scroll)) _root.Children.Add(_scroll);
        _scroll.Visibility = Visibility.Visible;
        // another place starts at its top; an update of the same one stays where it stood
        if (_selected?.key != _place)
        {
            _place = _selected?.key;
            _scroll.ChangeView(null, 0, null, disableAnimation: true);
        }
    }

    /// <summary>The Pot's places as a SelectorBar above its body; a choice is the shell's.</summary>
    private SelectorBar PlacesBar()
    {
        var bar = new SelectorBar { Margin = new Thickness(12, 0, 12, 0) };
        AutomationProperties.SetAutomationId(bar, "pot-places");
        SelectorBarItem? chosen = null;
        foreach (var t in _tabs)
        {
            var item = new SelectorBarItem { Text = Said.Key(t.word), Tag = t.tab.key };
            AutomationProperties.SetAutomationId(item, $"place:{t.tab.key}");
            bar.Items.Add(item);
            if (t.tab.key == _selected?.key) chosen = item;
        }
        bar.SelectedItem = chosen;
        var current = _selected?.key;
        bar.SelectionChanged += (b, _) =>
        {
            if (b.SelectedItem?.Tag is string key && key != current) Act.Send(new Call.ShellSelect { tab = new BarTab { key = key } });
        };
        return bar;
    }

    private static TextBlock Text(string text, string? id, string style)
    {
        var block = new TextBlock { Text = text, Style = TextStyle(style), TextWrapping = TextWrapping.Wrap };
        if (id is not null) AutomationProperties.SetAutomationId(block, id);
        return block;
    }

    internal static TextBlock Note(string text, string id)
    {
        var note = Text(text, id, "BodyTextBlockStyle");
        note.Foreground = Brush("TextFillColorSecondaryBrush");
        note.Margin = new Thickness(12, 12, 12, 0);
        return note;
    }

    internal static void Heading(StackPanel content, string text)
    {
        var title = Text(text, null, "BodyStrongTextBlockStyle");
        title.Margin = new Thickness(12, 16, 0, 4);
        AutomationProperties.SetHeadingLevel(title, Microsoft.UI.Xaml.Automation.Peers.AutomationHeadingLevel.Level2);
        content.Children.Add(title);
    }

    /// <summary>A row of a place: a title, a line under it, and what stands at its end (a badge, an amount).</summary>
    internal static Grid Row(string title, string? line, string automationId, params UIElement[] trailing) =>
        LedRow(null, title, line, automationId, trailing);

    /// <summary>A row led by <paramref name="leading"/> (a person's picture) where there is one.</summary>
    internal static Grid LedRow(UIElement? leading, string title, string? line, string automationId, UIElement[] trailing)
    {
        var row = new Grid { ColumnSpacing = 12, Padding = new Thickness(12, 8, 12, 8), MinHeight = 44 };
        var column = 0;
        if (leading is FrameworkElement lead)
        {
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            lead.VerticalAlignment = VerticalAlignment.Center;
            row.Children.Add(lead);
            column = 1;
        }
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        var words = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        Grid.SetColumn(words, column++);
        words.Children.Add(new TextBlock { Text = title, TextTrimming = TextTrimming.CharacterEllipsis });
        if (!string.IsNullOrEmpty(line))
        {
            words.Children.Add(new TextBlock
            {
                Text = line,
                TextWrapping = TextWrapping.Wrap,
                Style = TextStyle("CaptionTextBlockStyle"),
                Foreground = Brush("TextFillColorSecondaryBrush"),
            });
        }
        row.Children.Add(words);
        foreach (var t in trailing)
        {
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            Grid.SetColumn((FrameworkElement)t, column++);
            row.Children.Add(t);
        }
        AutomationProperties.SetAutomationId(row, automationId);
        AutomationProperties.SetName(row, string.Join(", ", RowWords(title, line, trailing)));
        return row;
    }

    /// <summary>What a screen reader says of a row: its words in order.</summary>
    private static IEnumerable<string> RowWords(string title, string? line, UIElement[] trailing)
    {
        yield return title;
        if (!string.IsNullOrEmpty(line)) yield return line;
        foreach (var t in trailing)
        {
            if (t is TextBlock { Text: { Length: > 0 } text }) yield return text;
        }
    }

    /// <summary>A text at a row's end: an amount, a balance, a badge.</summary>
    private static TextBlock Trailing(string text, string? brush = null, bool strong = false)
    {
        var block = new TextBlock { Text = text, VerticalAlignment = VerticalAlignment.Center };
        if (strong) block.Style = TextStyle("BodyStrongTextBlockStyle");
        if (brush is not null) block.Foreground = Brush(brush);
        return block;
    }

    private static string Money(PotView view, long amount) => Said.Money(amount, view.currency, view.minorUnits);

    /// <summary>The ledger check's notice (ADR 0108 decision 5): what the read found missing, so the balances may be wrong.</summary>
    private static void Missing(StackPanel content, PotView view)
    {
        var bar = new InfoBar { IsOpen = true, IsClosable = false, Severity = InfoBarSeverity.Error, Message = Words.PotLedgerMissing() };
        AutomationProperties.SetAutomationId(bar, "pot-missing");
        content.Children.Add(bar);
        var i = 0;
        foreach (var m in view.missing) content.Children.Add(Row(Said.Piece(m), null, $"missing:{i++}"));
    }

    private void Entries(StackPanel content, PotView view, long? pot)
    {
        if (view.entries.Count == 0) content.Children.Add(Note(Words.PotNoEntriesReadonly(), "pot-no-entries"));
        else content.Children.Add(EntryList(view, view.entries, pot, deleted: false));
        if (view.deleted.Count > 0)
        {
            Heading(content, Words.EntriesDeletedTitle());
            content.Children.Add(EntryList(view, view.deleted, pot, deleted: true));
        }
    }

    /// <summary>Entries as a list whose rows open the editor (a deleted one's its history and restore).</summary>
    private ListView EntryList(PotView view, IEnumerable<ShownEntry> entries, long? pot, bool deleted)
    {
        var list = new ListView { SelectionMode = ListViewSelectionMode.None, IsItemClickEnabled = true };
        foreach (var e in entries)
        {
            var trailing = new List<UIElement>();
            if (deleted) trailing.Add(Trailing(Words.EntriesDeletedBadge(), "TextFillColorSecondaryBrush"));
            else if (e.badge is { } b) trailing.Add(Trailing(Said.Notice(b.notice), b.danger ? "SystemFillColorCriticalBrush" : "TextFillColorSecondaryBrush"));
            string? amount = e.row.amount is { } a ? Money(view, a) : null;
            if (amount is not null) trailing.Add(Trailing(amount));
            var title = e.row.title ?? Words.PotTransfer();
            var line = Row(title, Said.Line(e.line), $"{(deleted ? "deleted" : "entry")}-line:{e.row.id}", [.. trailing]);
            line.Padding = new Thickness(0, 8, 0, 8);
            var id = e.row.id;
            var item = new ListViewItem { Content = line, MinHeight = 44, HorizontalContentAlignment = HorizontalAlignment.Stretch };
            item.Tag = deleted
                ? (Action)(() => { if (pot is { } p) EntryHistoryDialog.Open(_window, p, id, title, amount, _say); })
                : () => OpenEditor(id);
            AutomationProperties.SetAutomationId(item, $"{(deleted ? "deleted" : "entry")}:{id}");
            AutomationProperties.SetName(item, AutomationProperties.GetName(line));
            list.Items.Add(item);
        }
        list.ItemClick += (_, e) =>
        {
            var row = e.ClickedItem as ListViewItem ?? (e.ClickedItem as FrameworkElement)?.Parent as ListViewItem;
            if (row?.Tag is Action open) open();
            else Log.Write($"an entry's click reached no row ({e.ClickedItem?.GetType().Name})");
        };
        return list;
    }

    private void Overview(StackPanel content, PotView view, long? pot)
    {
        Heading(content, Words.PotBalances());
        foreach (var b in view.balances)
        {
            var tone = b.balance.tone switch
            {
                Tone.Owes => "SystemFillColorCriticalBrush",
                Tone.Owed => "SystemFillColorSuccessBrush",
                _ => "TextFillColorSecondaryBrush",
            };
            var name = Said.Piece(b.name);
            content.Children.Add(LedRow(Avatars.Person(name, b.row.userId, PersonSize), name, Said.Line(b.facts), $"balance:{b.row.id}",
                [Trailing(Said.Notice(b.balance.notice), tone)]));
        }
        if (view.settleUp.Count > 0)
        {
            var head = new Grid { Margin = new Thickness(0, 0, 12, 0) };
            head.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            head.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var heading = new StackPanel();
            Heading(heading, Words.PotSettleUp());
            head.Children.Add(heading);
            if (view.canRecordAll && pot is { } all)
            {
                var rows = view.settleUp.Select(t => t.row).ToList();
                var recordAll = new HyperlinkButton { Content = Words.BalancesRecordAll(), VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 12, 0, 0) };
                AutomationProperties.SetAutomationId(recordAll, "settle-all");
                recordAll.Click += (_, _) => Record(all, rows, Words.BalancesRecordAllQuestion(), Words.BalancesRecordAllHint(rows.Count));
                Grid.SetColumn(recordAll, 1);
                head.Children.Add(recordAll);
            }
            content.Children.Add(head);
            foreach (var t in view.settleUp)
            {
                var pays = Said.Notice(t.pays);
                var amount = Money(view, t.row.amount);
                var trailing = new List<UIElement> { Trailing(amount) };
                if (view.canWrite && pot is { } one)
                {
                    var record = new Button { Content = Words.BalancesRecord(), VerticalAlignment = VerticalAlignment.Center };
                    AutomationProperties.SetAutomationId(record, $"record:{t.row.fromId}:{t.row.toId}");
                    var row = t.row;
                    record.Click += (_, _) => Record(one, [row], Words.BalancesRecordQuestion(), $"{pays} · {amount}\n\n{Words.BalancesRecordHint()}");
                    trailing.Add(record);
                }
                content.Children.Add(Row(pays, null, $"settle:{t.row.fromId}:{t.row.toId}", [.. trailing]));
            }
        }
        if (view.byCategory.Count > 0)
        {
            Heading(content, Words.PotByCategory());
            foreach (var c in view.byCategory) content.Children.Add(Row(Said.Piece(c.label), null, $"category:{c.row.id}", Trailing(Money(view, c.row.amount))));
            content.Children.Add(Row(Words.PotTotal(), null, "total", Trailing(Money(view, view.total), strong: true)));
        }
    }

    /// <summary>Asks before settle-up <paramref name="rows"/> are recorded (one, or all), then Pot.Record; a refusal is
    /// said at once from the answer, the settled rows arrive on the Pot's watch.</summary>
    private async void Record(long pot, List<SettleRow> rows, string title, string question)
    {
        try
        {
            if (!await Dialogs.Ask(_window, "record-ask", title, question, Words.BalancesRecord())) return;
            var (answer, _) = await Core.CallAsync(new Call.PotRecord { pot = pot, rows = rows });
            Log.Write($"pot record {rows.Count} row(s): {(answer is Answer.Said { notice: { } n } ? n.msg : answer.GetType().Name)}");
            if (answer is Answer.Said { notice: { } said }) _say(false, Said.Notice(said));
            else if (answer is not Answer.Said) _say(false, Words.PeopleFailed());
        }
        catch (Exception e)
        {
            // an exception out of an async void would end the app in combase, with nothing in the log
            Log.Write($"Pot.Record failed: {e.GetType().Name}");
        }
    }

    /// <summary>The Settings place (PotSettingsView): opened in the core the first time it shows, kept across redraws.</summary>
    private void Settings(StackPanel content)
    {
        if (_id is not { } pot) return;
        if (_settings is null || _settings.Pot != pot)
        {
            _settings?.Dispose();
            _settings = new PotSettingsView(pot, _window, _say);
        }
        content.Children.Add(_settings.Panel);
        _settingsHolder = content;
    }
}
