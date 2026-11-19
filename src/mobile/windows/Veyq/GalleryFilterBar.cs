// A Memory's filter above its photos (ADR 0149 decision 5; the web's MemoryDetail filter; Linux's gtk::SearchBar), Fluent's
// way: an AutoSuggestBox whose suggestions are the chips the core offers for the text typed (a day, a year, a place, a kind:
// FilterShown.suggestions), the chips chosen as a row of buttons under it — each pressed to take it back —, the marks'
// mode as a DropDownButton of radio items (All, Everyone's, Only mine; where FilterShown.modeOffered: a member's, in a shared
// space), and Clear filter while anything narrows. The grid and the lightbox then page the rows the filter shows
// (GalleryShown.rows). Every state is the core's (FilterShown), every action one call (Filter.*); stored nowhere — leaving
// the Memory clears it.
using System;
using System.Collections.Generic;
using System.Linq;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Veyq.Bridge.Wire;
using Windows.System;

namespace Veyq;

internal sealed class GalleryFilterBar
{
    private readonly long _gallery;
    private readonly StackPanel _view = new() { Spacing = 6 };
    private readonly AutoSuggestBox _box = new() { PlaceholderText = Words.MediaFilterPlaceholder(), QueryIcon = new SymbolIcon(Symbol.Filter), MinWidth = 220 };
    private readonly DropDownButton _mode = new();
    private readonly MenuFlyout _modes = new() { Placement = Microsoft.UI.Xaml.Controls.Primitives.FlyoutPlacementMode.BottomEdgeAlignedLeft };
    private readonly Button _clear = new() { Content = Words.MediaFilterClear() };
    private readonly StackPanel _chips = new() { Orientation = Orientation.Horizontal, Spacing = 6 };
    private readonly ScrollViewer _chipRow;
    private readonly TypedText _typed = new();
    private FilterShown? _shown;
    private bool _focused;

    public UIElement View => _view;

    public GalleryFilterBar(long gallery)
    {
        _gallery = gallery;
        _box.UpdateTextOnSelect = false;
        AutomationProperties.SetAutomationId(_box, "memory-filter");
        AutomationProperties.SetName(_box, Words.MediaFilterLabel());
        _box.GotFocus += (_, _) => _focused = true;
        _box.LostFocus += (_, _) => _focused = false;
        _box.TextChanged += (_, e) =>
        {
            // only what the person typed: a text the state set (a chip's rest) is no edit
            if (e.Reason != AutoSuggestionBoxTextChangeReason.UserInput) return;
            _typed.Sent(_box.Text);
            Act.Send(new Call.FilterText { gallery = _gallery, text = _box.Text });
        };
        _box.QuerySubmitted += (_, e) =>
        {
            // a suggestion chosen (clicked, or highlighted and Enter), else Enter takes the first one offered
            var chip = e.ChosenSuggestion is FrameworkElement { Tag: string key } ? key : _shown?.suggestions.FirstOrDefault()?.chip;
            if (chip is null) return;
            Log.Write($"filter chip chosen: {chip.Split(':')[0]}");
            Act.Send(new Call.FilterChip { gallery = _gallery, chip = chip });
        };
        // Backspace in an empty box takes the last chip back, as the shell's search does
        _box.PreviewKeyDown += (_, e) =>
        {
            if (e.Key == VirtualKey.Back && _box.Text.Length == 0 && _shown is { chips.Count: > 0 } s)
            {
                e.Handled = true;
                Act.Send(new Call.FilterRemove { gallery = _gallery, chip = s.chips[^1].chip });
            }
        };
        foreach (var (mode, word) in new[] { (MarkMode.All, Words.MediaMarksFilterAll()), (MarkMode.Everyone, Words.MediaMarksFilterEveryone()), (MarkMode.Mine, Words.MediaMarksFilterMine()) })
        {
            var item = new RadioMenuFlyoutItem { Text = word, GroupName = "marks-mode", Tag = mode };
            AutomationProperties.SetAutomationId(item, $"memory-filter-mode:{mode}");
            var chosen = mode;
            item.Click += (_, _) =>
            {
                if (_shown?.mode == chosen) return;
                Log.Write($"filter marks mode: {chosen}");
                Act.Send(new Call.FilterMode { gallery = _gallery, mode = chosen });
            };
            _modes.Items.Add(item);
        }
        _mode.Flyout = _modes;
        AutomationProperties.SetAutomationId(_mode, "memory-filter-mode");
        ToolTipService.SetToolTip(_mode, Words.MediaMarksFilterLabel());
        AutomationProperties.SetAutomationId(_clear, "memory-filter-clear");
        _clear.Click += (_, _) =>
        {
            Log.Write("filter cleared");
            Act.Send(new Call.FilterClear { gallery = _gallery });
        };
        var row = new Grid { ColumnSpacing = 8 };
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star), MaxWidth = 480 });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        row.Children.Add(_box);
        Grid.SetColumn(_mode, 1);
        row.Children.Add(_mode);
        Grid.SetColumn(_clear, 2);
        row.Children.Add(_clear);
        _view.Children.Add(row);
        AutomationProperties.SetAutomationId(_chips, "memory-filter-chips");
        _chipRow = new ScrollViewer
        {
            Content = _chips,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Auto,
            HorizontalScrollMode = ScrollMode.Auto,
            VerticalScrollBarVisibility = ScrollBarVisibility.Disabled,
            VerticalScrollMode = ScrollMode.Disabled,
            Visibility = Visibility.Collapsed,
        };
        _view.Children.Add(_chipRow);
    }

    /// <summary>The filter as the core holds it: the text (TypedText's rule), the chips, the suggestions, the mode.</summary>
    public void Show(FilterShown f)
    {
        var before = _shown;
        _shown = f;
        if (_typed.Take(_focused, _box.Text, f.text) is { } text) _box.Text = text;
        if (before is null || !before.chips.Select(c => c.chip).SequenceEqual(f.chips.Select(c => c.chip))) DrawChips(f.chips);
        if (before is null || !before.suggestions.Select(c => c.chip).SequenceEqual(f.suggestions.Select(c => c.chip))) DrawSuggestions(f.suggestions);
        _mode.Visibility = f.modeOffered ? Visibility.Visible : Visibility.Collapsed;
        _mode.Content = ModeWord(f.mode);
        AutomationProperties.SetName(_mode, $"{Words.MediaMarksFilterLabel()}: {ModeWord(f.mode)}");
        foreach (var item in _modes.Items.OfType<RadioMenuFlyoutItem>()) item.IsChecked = (MarkMode)item.Tag == f.mode;
        _clear.Visibility = GalleryText.Narrows(f) ? Visibility.Visible : Visibility.Collapsed;
    }

    private void DrawChips(IReadOnlyList<ChipShown> chips)
    {
        _chips.Children.Clear();
        foreach (var chip in chips)
        {
            var content = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6 };
            content.Children.Add(new TextBlock { Text = GalleryText.ChipLabel(chip), VerticalAlignment = VerticalAlignment.Center });
            content.Children.Add(new FontIcon { Glyph = "\uE711", FontSize = 10 });
            var button = new Button { Content = content, Padding = new Thickness(8, 3, 8, 4), CornerRadius = new CornerRadius(12) };
            // the button takes the chip back: its name says so (the shell search's words, the web's ui.removeTag)
            AutomationProperties.SetName(button, Words.SearchRemoveChip(GalleryText.ChipLabel(chip)));
            AutomationProperties.SetAutomationId(button, $"memory-filter-chip:{chip.chip}");
            ToolTipService.SetToolTip(button, GalleryText.ChipLabel(chip));
            var key = chip.chip;
            button.Click += (_, _) => Act.Send(new Call.FilterRemove { gallery = _gallery, chip = key });
            _chips.Children.Add(button);
        }
        _chipRow.Visibility = chips.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    private void DrawSuggestions(IReadOnlyList<ChipShown> suggestions)
    {
        var items = new List<ListViewItem>();
        foreach (var s in suggestions)
        {
            // the content carries the chip too: a click hands the box the content, the keyboard the item
            var text = new TextBlock { Text = GalleryText.ChipLabel(s), Tag = s.chip };
            var item = new ListViewItem { Content = text, Tag = s.chip };
            AutomationProperties.SetAutomationId(item, $"memory-filter-suggestion:{s.chip}");
            AutomationProperties.SetName(item, GalleryText.ChipLabel(s));
            items.Add(item);
        }
        _box.ItemsSource = items.Count > 0 ? items : null;
        _box.IsSuggestionListOpen = items.Count > 0 && _box.FocusState != FocusState.Unfocused;
    }

    private static string ModeWord(MarkMode mode) => mode switch
    {
        MarkMode.Everyone => Words.MediaMarksFilterEveryone(),
        MarkMode.Mine => Words.MediaMarksFilterMine(),
        _ => Words.MediaMarksFilterAll(),
    };
}
