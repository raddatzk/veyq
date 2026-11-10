// An open Pot, read only (ADR 0122 decision 4: Expenses' places are the open Pot's Entries, Overview and Settings, as
// the web's Pot has them). The Pot is a model of its own in the core (Pot.Open), watched while it is on screen and closed
// when it leaves; what each row says — its line, its badge, a balance's words and tone, who pays whom — is the core's
// (PotView.Shown). This lays it out and writes money. Linux's pot.rs, in Fluent: a SelectorBar over the places' lists.
using System;
using System.Collections.Generic;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Veyq.Bridge;
using Veyq.Bridge.Wire;

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

    public ObjectRef Ref { get; }

    public UIElement Page => _root;

    public PotPage(ObjectRef r)
    {
        Ref = r;
        _root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        _root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        Draw();
        Open();
    }

    private async void Open()
    {
        Answer answer;
        try
        {
            answer = await Core.CallAsync(new Call.PotOpen { Ref = Ref });
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
        _watch = Core.Watch(new Watched.Pot { pot = pot }, update =>
        {
            if (_closed || update is not Update.Pot p) return;
            _state = p.state;
            Draw();
        }, e => Log.Write($"a Pot update: {e.Message}"));
    }

    public void Dispose()
    {
        _closed = true;
        _watch?.Dispose();
        _watch = null;
        if (_id is { } pot) Act.Send(new Call.PotClose { pot = pot });
        _id = null;
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

    private static Brush Brush(string key) => (Brush)Application.Current.Resources[key];

    private static Style TextStyle(string key) => (Style)Application.Current.Resources[key];

    private void Draw()
    {
        _root.Children.Clear();
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
        if (view.missing.Count > 0) Missing(content, view);
        if (!view.ready)
        {
            content.Children.Add(Note(Words.SettingsMissingReadOnly(), "pot-not-ready"));
        }
        else
        {
            switch (_selected?.key)
            {
                case "expenses.overview":
                    Overview(content, view);
                    break;
                case "expenses.settings":
                    Settings(content, view);
                    break;
                default:
                    Entries(content, view);
                    break;
            }
        }
        var scroll = new ScrollViewer { Content = content, HorizontalScrollMode = ScrollMode.Disabled, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled };
        Grid.SetRow(scroll, 1);
        _root.Children.Add(scroll);
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

    private static TextBlock Note(string text, string id)
    {
        var note = Text(text, id, "BodyTextBlockStyle");
        note.Foreground = Brush("TextFillColorSecondaryBrush");
        note.Margin = new Thickness(12, 12, 12, 0);
        return note;
    }

    private static void Heading(StackPanel content, string text)
    {
        var title = Text(text, null, "BodyStrongTextBlockStyle");
        title.Margin = new Thickness(12, 16, 0, 4);
        AutomationProperties.SetHeadingLevel(title, Microsoft.UI.Xaml.Automation.Peers.AutomationHeadingLevel.Level2);
        content.Children.Add(title);
    }

    /// <summary>A row of a place: a title, a line under it, and what stands at its end (a badge, an amount).</summary>
    private static Grid Row(string title, string? line, string automationId, params UIElement[] trailing)
    {
        var row = new Grid { ColumnSpacing = 12, Padding = new Thickness(12, 8, 12, 8), MinHeight = 44 };
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        var words = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
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
        var column = 1;
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

    private static void Entries(StackPanel content, PotView view)
    {
        if (view.entries.Count == 0)
        {
            content.Children.Add(Note(Words.PotNoEntriesReadonly(), "pot-no-entries"));
            return;
        }
        foreach (var e in view.entries)
        {
            var trailing = new List<UIElement>();
            if (e.badge is { } b) trailing.Add(Trailing(Said.Notice(b.notice), b.danger ? "SystemFillColorCriticalBrush" : "TextFillColorSecondaryBrush"));
            if (e.row.amount is { } amount) trailing.Add(Trailing(Money(view, amount)));
            content.Children.Add(Row(e.row.title ?? Words.PotTransfer(), Said.Line(e.line), $"entry:{e.row.id}", [.. trailing]));
        }
    }

    private static void Overview(StackPanel content, PotView view)
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
            content.Children.Add(Row(Said.Piece(b.name), Said.Line(b.facts), $"balance:{b.row.id}", Trailing(Said.Notice(b.balance.notice), tone)));
        }
        if (view.settleUp.Count > 0)
        {
            Heading(content, Words.PotSettleUp());
            foreach (var t in view.settleUp)
            {
                content.Children.Add(Row(Said.Notice(t.pays), null, $"settle:{t.row.fromId}:{t.row.toId}", Trailing(Money(view, t.row.amount))));
            }
        }
        if (view.byCategory.Count > 0)
        {
            Heading(content, Words.PotByCategory());
            foreach (var c in view.byCategory) content.Children.Add(Row(Said.Piece(c.label), null, $"category:{c.row.id}", Trailing(Money(view, c.row.amount))));
            content.Children.Add(Row(Words.PotTotal(), null, "total", Trailing(Money(view, view.total), strong: true)));
        }
    }

    private static void Settings(StackPanel content, PotView view)
    {
        Heading(content, Words.PotBaseCurrency());
        content.Children.Add(Row(view.currency ?? "—", null, "currency"));
        Heading(content, Words.PotPeople());
        foreach (var p in view.people) content.Children.Add(Row(p.entry.name, Said.Line(p.facts), $"person:{p.entry.id}"));
        if (view.deletedPeople > 0) content.Children.Add(Note(Words.PeopleDeletedSummary(view.deletedPeople), "people-deleted"));
    }
}
