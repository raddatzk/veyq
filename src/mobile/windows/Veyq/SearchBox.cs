// The search over every space of the open tenant (ADR 0146 decisions 7, 8, 9, 12 and 13; the web's SearchField.vue),
// Fluent's way: the NavigationView's AutoSuggestBox is the field, and its suggestion list holds what the core says
// (SearchPlan: the field's status lines, a *Filter by* heading with the chips offered, a separator, a *Results* heading
// with the rows — the name with its matched part bold, the second line dim — and *Show more*). The chips set are a row of
// buttons at the top of the content frame while one is set ([Chips], placed by the shell), each its icon, its value and a
// close glyph, pressed to remove it. A calm focus builds the index and shows no list.
//
// The keys: Ctrl+E and Ctrl+F focus the box (the shell's), Enter takes the highlighted entry or else the first — the first
// chip offered, else the first result —, Esc empties the field and its chips (Search.Close), Backspace in an empty box
// removes the last chip; Up and Down move through the list as the box's own. A result opens where it lives (the core's
// SearchModel.open: the shell's state moves, a photo of another Memory opens its lightbox through the gallery's request);
// a settings section is handed to [Settings]. What a screen reader hears is the core's announcement, raised as UI
// Automation's notification event on the box.
//
// Every state is the core's (Search.Open, Watched.Search: SearchShown), every action one call, the text sent in the
// order it was typed; nothing is decided here. Linux's search.rs draws the same.
using System;
using System.Collections.Generic;
using System.Linq;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Automation.Peers;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Documents;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Veyq.Bridge;
using Veyq.Bridge.Wire;
using Windows.System;

namespace Veyq;

internal sealed class SearchBox
{
    private readonly AutoSuggestBox _box;
    private readonly StackPanel _chips = new() { Orientation = Orientation.Horizontal, Spacing = 6 };
    /// <summary>The search's id in the core once Search.Open answered; none before and after its tenant closed.</summary>
    private long? _id;
    private bool _opening;
    private Watch? _watch;
    private SearchShown? _shown;
    private IReadOnlyList<SearchPlan.Entry> _entries = [];
    /// <summary>The text last told the core.</summary>
    private string _sent = "";
    /// <summary>A chip was chosen while this many were set: the next state with more takes the text the core kept.</summary>
    private int? _adopt;
    /// <summary>The announcement said last, so a state that repeats it says nothing.</summary>
    private string _announced = "";
    /// <summary>How many settings results the last state counted (SearchShown.openSettingsCount): each new one opens the
    /// page, the same section chosen twice included.</summary>
    private int _settingsOpened;
    /// <summary>A result was opened: the list stays shut until the person types, chooses or focuses again.</summary>
    private bool _held;
    private bool _focused;

    /// <summary>The chips set, a row the shell places at the top of its content frame; collapsed while none is set.</summary>
    public UIElement Chips { get; }

    /// <summary>A result was opened (the shell closes an overlaid pane): true where it opens in the shell's content (a node,
    /// a person), false for a settings section (SearchModel's SETTINGS_ICON), which [Settings] opens.</summary>
    public Action<bool>? Opened { get; init; }

    /// <summary>A settings result chose this section (SettingsSection.id): the account's Settings opens there.</summary>
    public Action<string>? Settings { get; init; }

    public SearchBox(AutoSuggestBox box)
    {
        _box = box;
        _box.UpdateTextOnSelect = false;
        AutomationProperties.SetAutomationId(_chips, "search-chips");
        Chips = new ScrollViewer
        {
            Content = _chips,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Auto,
            HorizontalScrollMode = ScrollMode.Auto,
            VerticalScrollBarVisibility = ScrollBarVisibility.Disabled,
            VerticalScrollMode = ScrollMode.Disabled,
            Padding = new Thickness(16, 8, 16, 0),
            Visibility = Visibility.Collapsed,
        };
        _box.GotFocus += (_, _) =>
        {
            if (_focused) return;
            _focused = true;
            _held = false;
            Focus();
        };
        _box.LostFocus += (_, _) => _focused = false;
        _box.TextChanged += (_, e) =>
        {
            // only what the person typed: the text the state set (a chip's words gone) is no edit
            if (e.Reason != AutoSuggestionBoxTextChangeReason.UserInput) return;
            var text = _box.Text;
            if (text == _sent) return;
            _sent = text;
            _held = false;
            if (_id is { } search) Act.Send(new Call.SearchText { search = search, text = text });
        };
        _box.QuerySubmitted += (_, e) =>
        {
            // the box hands over the highlighted item or, chosen by a click, its content: both carry the entry; Enter with
            // nothing highlighted takes the first entry
            var chosen = e.ChosenSuggestion is FrameworkElement { Tag: SearchPlan.Entry entry } ? entry : null;
            if (e.ChosenSuggestion is not null && chosen is null) Log.Write($"a chosen suggestion carried no entry ({e.ChosenSuggestion.GetType().Name})");
            var taken = e.ChosenSuggestion is null ? SearchPlan.First(_entries) : chosen;
            if (taken is null || !taken.Acts) return;
            if (e.ChosenSuggestion is null) Log.Write($"search Enter takes {taken}");
            Do(taken);
        };
        // Backspace in an empty box removes the last chip, Esc empties the field (tunnelled: the inner TextBox would take them)
        _box.PreviewKeyDown += (_, e) =>
        {
            if (e.Key == VirtualKey.Back && _box.Text.Length == 0 && _shown is { chips.Count: > 0 } && _id is { } search)
            {
                e.Handled = true;
                Log.Write("search Backspace removes the last chip");
                Act.Send(new Call.SearchRemoveLastChip { search = search });
            }
            else if (e.Key == VirtualKey.Escape && (_box.Text.Length > 0 || _shown is { chips.Count: > 0 } || _box.IsSuggestionListOpen))
            {
                e.Handled = true;
                Log.Write("search Esc closes");
                Close();
            }
        };
    }

    /// <summary>The box's focus: the search opened in the core first (once per tenant), then told.</summary>
    private async void Focus()
    {
        if (_id is { } open)
        {
            Act.Send(new Call.SearchFocus { search = open });
            return;
        }
        if (_opening) return;
        _opening = true;
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.SearchOpen());
            if (answer is not Answer.SearchOpened { search: var search })
            {
                Log.Write($"Search.Open answered {(answer is Answer.Refused r ? $"Refused {r.why}" : answer.GetType().Name)}");
                return;
            }
            var watch = Core.Watch(new Watched.Search { search = search }, update =>
            {
                if (update is not Update.Search { search: var s, shown: var shown } || s != _id) return;
                try
                {
                    if (shown is null) Dropped();
                    else Draw(shown);
                }
                catch (Exception e)
                {
                    // out of a watch's callback it would end the app in combase, with nothing in the log
                    Log.Write($"the search did not draw: {e.GetType().Name} 0x{e.HResult:X8}: {e.Message}");
                }
            }, e => Log.Write($"a search update did not read: {e.Message}"));
            if (watch is null) return;
            _id = search;
            _watch = watch;
            Log.Write("search open");
            Act.Send(new Call.SearchFocus { search = search });
            // what was typed while it opened
            _sent = _box.Text;
            if (_sent.Length > 0) Act.Send(new Call.SearchText { search = search, text = _sent });
        }
        catch (Exception e)
        {
            // an exception out of an async void would end the app in combase, with nothing in the log
            Log.Write($"Search.Open failed ({e.GetType().Name})");
        }
        finally
        {
            _opening = false;
        }
    }

    /// <summary>Esc: the core forgets the text and the chips, the field and the list are emptied.</summary>
    private void Close()
    {
        if (_id is { } search) Act.Send(new Call.SearchClose { search = search });
        Clear();
    }

    /// <summary>Its tenant closed (a switch, a lock, a sign-out): the field empties; the next focus opens one anew.</summary>
    private void Dropped()
    {
        Log.Write("search closed with its tenant");
        _watch?.Dispose();
        _watch = null;
        _id = null;
        // the next search is a new model, counting from nothing
        _settingsOpened = 0;
        Clear();
    }

    private void Clear()
    {
        _sent = "";
        _box.Text = "";
        _adopt = null;
        _announced = "";
        _shown = null;
        _entries = [];
        _chips.Children.Clear();
        Chips.Visibility = Visibility.Collapsed;
        _box.ItemsSource = null;
        _box.IsSuggestionListOpen = false;
    }

    private void Do(SearchPlan.Entry entry)
    {
        if (_id is not { } search) return;
        switch (entry)
        {
            case SearchPlan.Entry.Choose c:
                _adopt = _shown?.chips.Count ?? 0;
                _held = false;
                Act.Send(new Call.SearchChoose { search = search, index = c.Index });
                break;
            case SearchPlan.Entry.Open o:
                Log.Write($"search opens result {o.Index}");
                Act.Send(new Call.SearchOpenResult { search = search, index = o.Index });
                // the list shuts once a result opened, as Explorer's does; the query stays
                _held = true;
                _box.IsSuggestionListOpen = false;
                Opened?.Invoke(_shown?.results.ElementAtOrDefault(o.Index)?.icon != "settings");
                break;
            case SearchPlan.Entry.More:
                Act.Send(new Call.SearchMore { search = search });
                break;
        }
    }

    private void Draw(SearchShown s)
    {
        Log.Write($"search shown: asked {s.asked}, {s.chips.Count} chips, {s.suggestions.Count} offered, {s.results.Count} results of {s.total}, {s.more} more, status [{string.Join(",", s.status.Select(n => n.msg))}]");
        _shown = s;
        // a chip chosen: the text is what the core kept of it (the chip's words gone)
        if (_adopt is { } before && s.chips.Count > before)
        {
            _adopt = null;
            if (_box.Text != s.text)
            {
                _sent = s.text;
                _box.Text = s.text;
            }
        }
        DrawChips(s);
        DrawList(s);
        Announce(s);
        // a settings result: the page opens at its section, once per result chosen (the count moves with each)
        if (_settingsOpened != s.openSettingsCount)
        {
            _settingsOpened = s.openSettingsCount;
            if (s.openSettings is { } section)
            {
                Log.Write($"search opens settings at {section} ({s.openSettingsCount})");
                Settings?.Invoke(section);
            }
        }
    }

    private void DrawChips(SearchShown s)
    {
        _chips.Children.Clear();
        for (var i = 0; i < s.chips.Count; i++)
        {
            var chip = s.chips[i];
            var label = Said.Piece(chip.label);
            var named = $"{Said.Key(chip.word)}: {label}";
            var content = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6 };
            content.Children.Add(new FontIcon { Glyph = SearchPlan.Glyph(chip.icon), FontSize = 12 });
            content.Children.Add(new TextBlock { Text = label, VerticalAlignment = VerticalAlignment.Center });
            content.Children.Add(new FontIcon { Glyph = "", FontSize = 10 });
            var button = new Button { Content = content, Padding = new Thickness(8, 3, 8, 4), CornerRadius = new CornerRadius(12) };
            // the button removes it: its name says so, its tooltip names the chip (the web's ui.removeTag)
            AutomationProperties.SetName(button, Words.SearchRemoveChip(named));
            AutomationProperties.SetAutomationId(button, $"search-chip:{i}");
            ToolTipService.SetToolTip(button, named);
            var index = i;
            button.Click += (_, _) =>
            {
                if (_id is { } search) Act.Send(new Call.SearchRemoveChip { search = search, index = index });
            };
            _chips.Children.Add(button);
        }
        Chips.Visibility = s.chips.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    private void DrawList(SearchShown s)
    {
        _entries = SearchPlan.Entries(s);
        var items = new List<ListViewItem>();
        var status = 0;
        foreach (var entry in _entries)
        {
            items.Add(entry switch
            {
                SearchPlan.Entry.Status n => Still(Line(Said.Notice(n.Said), wrap: true), $"search-status:{status++}"),
                SearchPlan.Entry.Heading h => Still(Heading(Said.Key(h.Key)), $"search-heading:{h.Key}"),
                SearchPlan.Entry.Separator => Still(new Border { Height = 1, Margin = new Thickness(0, 4, 0, 4), Background = Brush("DividerStrokeColorDefaultBrush") }, "search-separator"),
                SearchPlan.Entry.Choose c => Suggestion(entry, s.suggestions[c.Index], c.Index),
                SearchPlan.Entry.Open o => Result(entry, s.results[o.Index], o.Index),
                SearchPlan.Entry.More m => More(entry, m.Count),
                _ => throw new InvalidOperationException(entry.ToString()),
            });
        }
        _box.ItemsSource = items.Count > 0 ? items : null;
        // a state anew (a revalidation, a space loaded) opens no list the person is not in, nor one a result shut
        _box.IsSuggestionListOpen = items.Count > 0 && !_held && _box.FocusState != FocusState.Unfocused;
    }

    private static ListViewItem Item(SearchPlan.Entry entry, UIElement content, string id, string name)
    {
        // the content carries the entry too: a click hands the box the content, the keyboard the item
        if (content is FrameworkElement f) f.Tag = entry;
        var item = new ListViewItem { Content = content, Tag = entry };
        AutomationProperties.SetAutomationId(item, id);
        AutomationProperties.SetName(item, name);
        return item;
    }

    private static ListViewItem Still(UIElement content, string id)
    {
        var item = new ListViewItem { Content = content, IsEnabled = false, IsTabStop = false, MinHeight = 0 };
        AutomationProperties.SetAutomationId(item, id);
        if (content is TextBlock t) AutomationProperties.SetName(item, t.Text);
        return item;
    }

    private static ListViewItem Suggestion(SearchPlan.Entry entry, SuggestionShown o, int index)
    {
        var word = Said.Key(o.word);
        var label = Said.Piece(o.label);
        var where = Said.Line(o.where);
        var row = Row(o.icon, Line(label), where.Length > 0 ? Line(where, dim: true) : null, word);
        return Item(entry, row, $"search-suggestion:{index}", $"{word}: {label}");
    }

    private static ListViewItem Result(SearchPlan.Entry entry, ResultShown r, int index)
    {
        var name = new TextBlock { TextTrimming = TextTrimming.CharacterEllipsis };
        Parts(name, r.name);
        var line = Said.Line(r.line);
        TextBlock? second = null;
        if (line.Length > 0 || r.reason is { Count: > 0 })
        {
            second = Line(line, dim: true);
            if (r.reason is { Count: > 0 } reason)
            {
                if (line.Length > 0) second.Inlines.Add(new Run { Text = " · " });
                Parts(second, reason);
            }
        }
        return Item(entry, Row(r.icon, name, second, null), $"search-result:{index}", SearchPlan.Spoken(r, line));
    }

    private static ListViewItem More(SearchPlan.Entry entry, int count)
    {
        var said = Words.SearchShowMore(count);
        var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 12 };
        row.Children.Add(new FontIcon { Glyph = "", FontSize = 16 });
        row.Children.Add(new TextBlock { Text = said, VerticalAlignment = VerticalAlignment.Center });
        return Item(entry, row, "search-more", said);
    }

    /// <summary>A row: the kind's glyph, the title with a dim second line under it where it has one, a dim word at its end.</summary>
    private static Grid Row(string icon, TextBlock title, TextBlock? second, string? suffix)
    {
        var row = new Grid { ColumnSpacing = 12, Padding = new Thickness(0, 2, 0, 2) };
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var glyph = new FontIcon { Glyph = SearchPlan.Glyph(icon), FontSize = 16, VerticalAlignment = VerticalAlignment.Center };
        row.Children.Add(glyph);
        var lines = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        lines.Children.Add(title);
        if (second is not null) lines.Children.Add(second);
        Grid.SetColumn(lines, 1);
        row.Children.Add(lines);
        if (suffix is not null)
        {
            var word = Line(suffix, dim: true);
            word.VerticalAlignment = VerticalAlignment.Center;
            Grid.SetColumn(word, 2);
            row.Children.Add(word);
        }
        return row;
    }

    /// <summary>A name's parts as runs, the matched ones bold.</summary>
    private static void Parts(TextBlock into, IEnumerable<PartShown> parts)
    {
        foreach (var p in parts) into.Inlines.Add(new Run { Text = p.text, FontWeight = p.strong ? FontWeights.Bold : FontWeights.Normal });
    }

    private static TextBlock Line(string text, bool dim = false, bool wrap = false)
    {
        var t = new TextBlock { Text = text, TextTrimming = wrap ? TextTrimming.None : TextTrimming.CharacterEllipsis, TextWrapping = wrap ? TextWrapping.Wrap : TextWrapping.NoWrap };
        if (dim || wrap)
        {
            t.Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"];
            t.Foreground = Brush("TextFillColorSecondaryBrush");
        }
        return t;
    }

    private static TextBlock Heading(string text)
    {
        var t = new TextBlock { Text = text, Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"], Margin = new Thickness(0, 4, 0, 0) };
        AutomationProperties.SetHeadingLevel(t, AutomationHeadingLevel.Level2);
        return t;
    }

    private static Brush Brush(string key) => (Brush)Application.Current.Resources[key];

    /// <summary>The core's announcement for a screen reader, raised once it changed: UI Automation's notification event on
    /// the box, polite (the most recent replaces one not yet read).</summary>
    private void Announce(SearchShown s)
    {
        var text = string.Join(" ", s.announcement.Select(Said.Notice));
        if (text.Length == 0 || text == _announced) return;
        _announced = text;
        var peer = FrameworkElementAutomationPeer.FromElement(_box) ?? FrameworkElementAutomationPeer.CreatePeerForElement(_box);
        Log.Write($"search announces [{string.Join(",", s.announcement.Select(n => n.msg))}]{(peer is null ? ": no peer" : "")}");
        peer?.RaiseNotificationEvent(AutomationNotificationKind.ActionCompleted, AutomationNotificationProcessing.MostRecent, text, "veyq-search");
    }
}
