// The entry editor (the web's EntryEditor.vue and EntrySplit.vue; Linux's entry_editor.rs; D5 of the Expenses CR), Fluent's
// way: a page in the content frame, not a ContentDialog — WinUI shows one ContentDialog at a time, and the editor asks its
// delete over itself. Every rule and the editor's state are the core's EntryEditor (Editor.Open, watched as
// Watched.Editor): this draws EditorShown and sends each edit (Editor.Change), which the core applies in the order sent.
//
// The form is a ScrollViewer of ComboBoxes, TextBoxes (amounts are text; the core parses), a CalendarDatePicker and an
// AutoSuggestBox for the currency; each split inline — its mode as RadioButtons, one CheckBox and value per person, its
// hint, "Split equally among all"; the preview; the history in an Expander. Save, Delete and Cancel are its CommandBar's
// (Ctrl+S, Escape). A refusal is the page's InfoBar and its field's description; the rate's note is the rate box's
// description. The form is built anew only when its shape changes (a payer, an item, the kind…), never while typing; a
// box is set from the state only by TypedText's rule, so what is typed and the caret stay.
using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Veyq.Bridge;
using Veyq.Bridge.Wire;
using Windows.System;

namespace Veyq;

internal sealed class EntryEditorPage : IDisposable
{
    private readonly Window _window;
    private readonly long _pot;
    private readonly string? _entry;
    private readonly Action<bool, string> _say;
    private readonly Action _closed;
    private readonly Grid _root = new();
    private readonly TextBlock _heading;
    private readonly AppBarButton _save;
    private readonly AppBarButton _cancel;
    private readonly AppBarButton _delete;
    private readonly InfoBar _refused;
    private readonly ScrollViewer _scroll = new() { HorizontalScrollMode = ScrollMode.Disabled, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled };
    private long? _editor;
    private Watch? _watch;
    private bool _gone;
    private bool _drawing;
    private string? _shape;
    private Form? _form;
    private EditorShown? _last;

    public UIElement Page => _root;

    /// <summary>The editor of entry <paramref name="entry"/> of Pot <paramref name="pot"/> (null: a new entry);
    /// <paramref name="say"/> takes what a done save says, <paramref name="closed"/> runs once the editor is gone.</summary>
    public EntryEditorPage(Window window, long pot, string? entry, Action<bool, string> say, Action closed)
    {
        _window = window;
        _pot = pot;
        _entry = entry;
        _say = say;
        _closed = closed;
        _root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        _root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        _root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        _heading = new TextBlock { Text = entry is null ? Words.EdNewTitle() : Words.EdEditTitle(), Style = (Style)Application.Current.Resources["SubtitleTextBlockStyle"], VerticalAlignment = VerticalAlignment.Center };
        AutomationProperties.SetAutomationId(_heading, "ed-heading");
        AutomationProperties.SetHeadingLevel(_heading, Microsoft.UI.Xaml.Automation.Peers.AutomationHeadingLevel.Level2);
        _save = new AppBarButton { Icon = new SymbolIcon(Symbol.Save), Label = Words.EntrySave() };
        AutomationProperties.SetAutomationId(_save, "ed-save");
        _save.Click += (_, _) => Save();
        _save.KeyboardAccelerators.Add(new KeyboardAccelerator { Key = VirtualKey.S, Modifiers = VirtualKeyModifiers.Control });
        _delete = new AppBarButton { Icon = new SymbolIcon(Symbol.Delete), Label = Words.EntryDelete(), Visibility = Visibility.Collapsed };
        AutomationProperties.SetAutomationId(_delete, "ed-delete");
        _delete.Click += (_, _) => AskDelete();
        _cancel = new AppBarButton { Icon = new SymbolIcon(Symbol.Cancel), Label = Words.EntryCancel() };
        AutomationProperties.SetAutomationId(_cancel, "ed-cancel");
        _cancel.Click += (_, _) => Close();
        _cancel.KeyboardAccelerators.Add(new KeyboardAccelerator { Key = VirtualKey.Escape });
        var bar = new CommandBar { DefaultLabelPosition = CommandBarDefaultLabelPosition.Right, VerticalAlignment = VerticalAlignment.Center };
        bar.PrimaryCommands.Add(_save);
        bar.PrimaryCommands.Add(_delete);
        bar.PrimaryCommands.Add(_cancel);
        AutomationProperties.SetAutomationId(bar, "ed-commands");
        var head = new Grid { Padding = new Thickness(12, 0, 12, 0), ColumnSpacing = 8 };
        head.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        head.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        head.Children.Add(_heading);
        Grid.SetColumn(bar, 1);
        head.Children.Add(bar);
        _root.Children.Add(head);
        _refused = new InfoBar { IsOpen = false, IsClosable = false, Severity = InfoBarSeverity.Error, Margin = new Thickness(12, 0, 12, 4) };
        AutomationProperties.SetAutomationId(_refused, "ed-refused");
        Grid.SetRow(_refused, 1);
        _root.Children.Add(_refused);
        var loading = new ProgressRing { IsActive = true, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
        Grid.SetRow(loading, 2);
        _root.Children.Add(loading);
        Grid.SetRow(_scroll, 2);
        Open();
    }

    private async void Open()
    {
        try
        {
            var separator = CultureInfo.CurrentCulture.NumberFormat.NumberDecimalSeparator;
            var (answer, _) = await Core.CallAsync(new Call.EditorOpen { pot = _pot, entry = _entry, separator = separator.Length == 1 ? separator : "." });
            if (answer is not Answer.EditorOpened { editor: var editor })
            {
                Log.Write($"Editor.Open answered {answer.GetType().Name}");
                _say(false, Words.EdSaveFailed());
                Close();
                return;
            }
            if (_gone)
            {
                Act.Send(new Call.EditorClose { editor = editor });
                return;
            }
            _editor = editor;
            Log.Write($"entry editor open ({(_entry is null ? "new" : "edit")})");
            _watch = Core.Watch(new Watched.Editor { editor = editor }, update =>
            {
                if (_gone || update is not Update.Editor u) return;
                try
                {
                    if (u.shown is { } s) Draw(s);
                    else Close();
                }
                catch (Exception e)
                {
                    // an exception out of a watch's callback ends the app in combase, with nothing in the log
                    Log.Write($"the editor did not draw: {e.GetType().Name}: {e.Message}");
                }
            }, e => Log.Write($"an editor update: {e.Message}"));
        }
        catch (Exception e)
        {
            // an exception out of an async void would end the app in combase, with nothing in the log
            Log.Write($"the editor did not open: {e.GetType().Name}");
            Close();
        }
    }

    /// <summary>The editor gone: the core's closed, the watch disposed, the Pot's places shown again.</summary>
    public void Close()
    {
        if (_gone) return;
        Dispose();
        Log.Write("entry editor closed");
        _closed();
    }

    public void Dispose()
    {
        if (_gone) return;
        _gone = true;
        foreach (var combo in _form?.Combos ?? []) combo.IsDropDownOpen = false;
        _watch?.Dispose();
        _watch = null;
        if (_editor is { } e) Act.Send(new Call.EditorClose { editor = e });
    }

    /// <summary>One edit sent; the core applies it in the order sent.</summary>
    private void Edit(EditorChange change)
    {
        if (_drawing || _editor is not { } e) return;
        Act.Send(new Call.EditorChange { editor = e, change = change });
    }

    private async void Save()
    {
        if (_editor is not { } e) return;
        _save.IsEnabled = false;
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.EditorSave { editor = e });
            if (answer is Answer.EditorSaved saved)
            {
                Log.Write($"editor save: done {saved.done}, {saved.notice?.msg ?? "no notice"}");
                if (saved.done)
                {
                    if (saved.notice is { } n) _say(false, Said.Notice(n));
                    Close();
                    return;
                }
                if (saved.notice is { } why) Refuse(Said.Notice(why));
            }
            else
            {
                Log.Write($"Editor.Save answered {answer.GetType().Name}");
                Refuse(Words.EdSaveFailed());
            }
        }
        catch (Exception ex)
        {
            Log.Write($"Editor.Save failed: {ex.GetType().Name}");
        }
        if (!_gone) _save.IsEnabled = true;
    }

    /// <summary>The delete asked over the page (Editor.AskDelete, Dialogs.Confirm), then done or cancelled.</summary>
    private async void AskDelete()
    {
        if (_editor is not { } e) return;
        try
        {
            Act.Send(new Call.EditorAskDelete { editor = e });
            if (!await Dialogs.Confirm(_window, "ed-delete-ask", Words.EntryDelete(), Words.EntryDeleteConfirm(), Words.EntryDelete()))
            {
                Act.Send(new Call.EditorCancelAsk { editor = e });
                return;
            }
            var (answer, _) = await Core.CallAsync(new Call.EditorDelete { editor = e });
            Log.Write($"editor delete: {(answer is Answer.EditorSaved s ? $"done {s.done}" : answer.GetType().Name)}");
            if (answer is Answer.EditorSaved { done: true }) Close();
            else Refuse(answer is Answer.EditorSaved { notice: { } n } ? Said.Notice(n) : Words.EdSaveFailed());
        }
        catch (Exception ex)
        {
            Log.Write($"Editor.Delete failed: {ex.GetType().Name}");
        }
    }

    private void Refuse(string message)
    {
        _refused.Message = message;
        _refused.IsOpen = true;
    }

    // ---- the form ----

    /// <summary>A text box and what it sent (TypedText).</summary>
    private sealed class Box(TextBox box)
    {
        public readonly TextBox Control = box;
        public readonly TypedText Typed = new();

        /// <summary>The text the state set last: TextBox raises TextChanged later, after the draw, and that one is no
        /// edit of the person's.</summary>
        private string? _set;

        public void Set(string state)
        {
            var text = Typed.Take(Control.FocusState != FocusState.Unfocused, Control.Text, state);
            if (text is null) return;
            _set = text;
            Control.Text = text;
        }

        /// <summary>Whether the box's change is the person's (to send), not the state's own set arriving.</summary>
        public bool Typing()
        {
            if (_set is { } set && set == Control.Text)
            {
                _set = null;
                return false;
            }
            _set = null;
            return true;
        }
    }

    private sealed class SplitRows
    {
        public required EditorSplit Shown;
        public TextBlock? Folded;
        public RadioButtons? Modes;
        public readonly List<(string Person, CheckBox On, Box Value, TextBlock Percent)> People = [];
        public required TextBlock Hint;
    }

    private sealed class Form
    {
        public required ComboBox Kind;
        public required Box Title;
        public required CalendarDatePicker Date;
        public ComboBox? Category;
        public List<string> CategoryIds = [];
        public required AutoSuggestBox Currency;
        public Box? Rate;
        public Box? Amount;
        public ComboBox? From;
        public ComboBox? To;
        public readonly List<(ComboBox Person, Box Paid, Button Remove)> Payers = [];
        public HyperlinkButton? AddPayer;
        public TextBlock? Total;
        public readonly List<(Box Label, Box Amount, SplitRows Split, TextBlock Refusal)> Items = [];
        public SplitRows? Rest;
        public TextBlock? RestRefusal;
        public TextBlock? PayersRefusal;
        public TextBlock? ItemsRefusal;
        public TextBlock? TransferRefusal;
        public required StackPanel Preview;
        public required TextBlock Invalid;
        public List<string> People = [];
        /// <summary>Every ComboBox of the form: its drop-down closed before the form is replaced or the page goes (UI
        /// Automation's select leaves it open, and its control would leave the tree under it).</summary>
        public readonly List<ComboBox> Combos = [];
    }

    private static Brush Brush(string key) => (Brush)Application.Current.Resources[key];

    private static TextBlock Heading(string text)
    {
        var t = new TextBlock { Text = text, Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"], Margin = new Thickness(0, 12, 0, 0) };
        AutomationProperties.SetHeadingLevel(t, Microsoft.UI.Xaml.Automation.Peers.AutomationHeadingLevel.Level3);
        return t;
    }

    private static TextBlock Problem(string id)
    {
        var t = new TextBlock { TextWrapping = TextWrapping.Wrap, Foreground = Brush("SystemFillColorCriticalBrush"), Visibility = Visibility.Collapsed };
        AutomationProperties.SetAutomationId(t, id);
        return t;
    }

    private Box TextField(string header, string id, Func<string, EditorChange> change, double width = double.NaN)
    {
        var control = new TextBox { Header = header, Width = width };
        AutomationProperties.SetAutomationId(control, id);
        var box = new Box(control);
        control.TextChanged += (_, _) =>
        {
            if (_drawing || !box.Typing()) return;
            box.Typed.Sent(control.Text);
            Edit(change(control.Text));
        };
        return box;
    }

    private ComboBox Choice(string header, string id, IEnumerable<(string Id, string Label)> choices, Func<string, EditorChange> change)
    {
        var combo = new ComboBox { Header = header, MinWidth = 200 };
        AutomationProperties.SetAutomationId(combo, id);
        var ids = new List<string>();
        foreach (var (choiceId, label) in choices)
        {
            var item = new ComboBoxItem { Content = label, Tag = choiceId };
            AutomationProperties.SetAutomationId(item, $"{id}:{choiceId}");
            combo.Items.Add(item);
            ids.Add(choiceId);
        }
        combo.Tag = ids;
        combo.SelectionChanged += (_, _) =>
        {
            if (combo.SelectedItem is ComboBoxItem { Tag: string chosen }) Edit(change(chosen));
        };
        return combo;
    }

    private static void Select(ComboBox combo, string id)
    {
        var at = ((List<string>)combo.Tag).IndexOf(id);
        if (combo.SelectedIndex != at) combo.SelectedIndex = at;
    }

    private void Draw(EditorShown s)
    {
        _last = s;
        _heading.Text = s.isNew ? Words.EdNewTitle() : s.editable ? Words.EdEditTitle() : Words.EdViewTitle();
        _save.Visibility = s.editable ? Visibility.Visible : Visibility.Collapsed;
        _save.IsEnabled = !s.saving;
        _delete.Visibility = !s.isNew && s.editable ? Visibility.Visible : Visibility.Collapsed;
        _delete.IsEnabled = !s.saving;
        _cancel.Label = s.editable ? Words.EntryCancel() : Words.ReceiptsClose();
        var shape = Shape(s);
        _drawing = true;
        try
        {
            if (_form is null || shape != _shape)
            {
                _shape = shape;
                foreach (var combo in _form?.Combos ?? []) combo.IsDropDownOpen = false;
                _form = Build(s);
            }
            Update(_form, s);
        }
        finally
        {
            _drawing = false;
        }
        if (!_root.Children.Contains(_scroll))
        {
            foreach (var ring in _root.Children.OfType<ProgressRing>().ToList()) _root.Children.Remove(ring);
            _root.Children.Add(_scroll);
        }
    }

    /// <summary>What makes the form's controls: a change here builds it anew (never one a keystroke makes).</summary>
    private static string Shape(EditorShown s) => string.Join("|",
        s.kind, s.editable, s.isNew, string.Join(",", s.people.Select(p => p.id + "=" + p.name)), string.Join(",", s.categories.Select(c => c.id)),
        s.currencies.Count, s.currency != s.Base ? s.currency : "", s.payers.Count,
        string.Join(",", s.items.Select(i => i.split.expanded)), s.rest is { } r ? $"rest {r.expanded} {s.restIsWhole}" : "no rest");

    private Form Build(EditorShown s)
    {
        var column = new StackPanel { Spacing = 12, MaxWidth = 640, HorizontalAlignment = HorizontalAlignment.Stretch, Padding = new Thickness(12, 8, 12, 24) };
        var people = s.people.Select(p => (p.id, p.name)).ToList();
        var invalid = new TextBlock { Text = Words.EdInvalid(), TextWrapping = TextWrapping.Wrap, Foreground = Brush("SystemFillColorCriticalBrush"), Visibility = Visibility.Collapsed };
        AutomationProperties.SetAutomationId(invalid, "ed-invalid");
        column.Children.Add(invalid);
        var transfer = s.kind == EntryKind.Transfer;
        var kind = Choice(Words.EntryKind(), "ed-kind", [("EXPENSE", Words.EdExpense()), ("REFUND", Words.EdRefund()), ("TRANSFER", Words.PotTransfer())],
            k => new EditorChange.EditKind { kind = k switch { "REFUND" => EntryKind.Refund, "TRANSFER" => EntryKind.Transfer, _ => EntryKind.Expense } });
        column.Children.Add(kind);
        var title = TextField(transfer ? Words.EdNote() : Words.EntryTitle(), "ed-title", t => new EditorChange.EditTitle { text = t });
        column.Children.Add(title.Control);
        var date = new CalendarDatePicker { Header = Words.EdDate(), MinWidth = 200 };
        AutomationProperties.SetAutomationId(date, "ed-date");
        date.DateChanged += (_, e) =>
        {
            if (e.NewDate is { } d) Edit(new EditorChange.EditDay { iso = d.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture) });
        };
        column.Children.Add(date);
        var form = new Form
        {
            Kind = kind,
            Title = title,
            Date = date,
            Currency = new AutoSuggestBox(),
            Preview = new StackPanel { Spacing = 4 },
            Invalid = invalid,
            People = s.people.Select(p => p.id).ToList(),
        };
        if (!transfer)
        {
            form.CategoryIds = s.categories.Select(c => c.id).ToList();
            form.Category = Choice(Words.EntryCategory(), "ed-category", s.categories.Select(c => (c.id, Said.Piece(c.label))), id => new EditorChange.EditCategory { id = id });
            column.Children.Add(form.Category);
        }
        // the currency: typed with suggestions from the core's list, taken once a code of it is chosen or submitted
        var currency = new AutoSuggestBox { Header = Words.EdCurrency(), MinWidth = 200, HorizontalAlignment = HorizontalAlignment.Left };
        AutomationProperties.SetAutomationId(currency, "ed-currency");
        var codes = s.currencies.ToList();
        currency.TextChanged += (box, e) =>
        {
            if (e.Reason != AutoSuggestionBoxTextChangeReason.UserInput) return;
            var typed = box.Text.Trim().ToUpperInvariant();
            box.ItemsSource = codes.Where(c => c.StartsWith(typed, StringComparison.Ordinal)).Take(12).ToList();
        };
        currency.SuggestionChosen += (box, e) => { if (e.SelectedItem is string code) Edit(new EditorChange.EditCurrency { code = code }); };
        currency.QuerySubmitted += (box, e) =>
        {
            var code = (e.ChosenSuggestion as string ?? e.QueryText).Trim().ToUpperInvariant();
            if (codes.Contains(code)) Edit(new EditorChange.EditCurrency { code = code });
            else box.Text = _last?.currency ?? "";
        };
        form.Currency = currency;
        column.Children.Add(currency);
        if (s.currency != s.Base)
        {
            form.Rate = TextField(Words.EdRate(s.currency, s.Base), "ed-rate", t => new EditorChange.EditRateTyped { text = t }, 280);
            form.Rate.Control.HorizontalAlignment = HorizontalAlignment.Left;
            column.Children.Add(form.Rate.Control);
        }
        if (transfer)
        {
            form.Amount = TextField(Words.EntryAmount(), "ed-amount", t => new EditorChange.EditAmount { text = t }, 200);
            form.Amount.Control.HorizontalAlignment = HorizontalAlignment.Left;
            column.Children.Add(form.Amount.Control);
            form.From = Choice(Words.EdFrom(), "ed-from", people, p => new EditorChange.EditFrom { person = p });
            form.To = Choice(Words.EdTo(), "ed-to", people, p => new EditorChange.EditTo { person = p });
            column.Children.Add(form.From);
            column.Children.Add(form.To);
            form.TransferRefusal = Problem("ed-transfer-problem");
            column.Children.Add(form.TransferRefusal);
        }
        else
        {
            column.Children.Add(Heading(s.kind == EntryKind.Refund ? Words.EdReceivedBy() : Words.EntryPaidBy()));
            for (var i = 0; i < s.payers.Count; i++)
            {
                var index = i;
                var row = new Grid { ColumnSpacing = 8 };
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                var person = Choice(Words.EdPerson(), $"ed-payer:{i}", people, p => new EditorChange.EditPayer { index = index, person = p });
                person.HorizontalAlignment = HorizontalAlignment.Stretch;
                var paid = TextField(Words.EntryAmount(), $"ed-paid:{i}", t => new EditorChange.EditPaid { index = index, text = t }, 140);
                Grid.SetColumn(paid.Control, 1);
                var remove = new Button { Content = new SymbolIcon(Symbol.Remove), VerticalAlignment = VerticalAlignment.Top, Margin = new Thickness(0, 28, 0, 0) };
                var name = s.people.FirstOrDefault(p => p.id == s.payers[i].person)?.name ?? "";
                AutomationProperties.SetName(remove, Words.EdRemovePayer(name));
                ToolTipService.SetToolTip(remove, Words.EdRemovePayer(name));
                AutomationProperties.SetAutomationId(remove, $"ed-remove-payer:{i}");
                remove.Click += (_, _) => Edit(new EditorChange.EditRemovePayer { index = index });
                Grid.SetColumn(remove, 2);
                row.Children.Add(person);
                row.Children.Add(paid.Control);
                row.Children.Add(remove);
                column.Children.Add(row);
                form.Payers.Add((person, paid, remove));
            }
            form.PayersRefusal = Problem("ed-payers-problem");
            column.Children.Add(form.PayersRefusal);
            form.AddPayer = new HyperlinkButton { Content = Words.EdAddPayer() };
            AutomationProperties.SetAutomationId(form.AddPayer, "ed-add-payer");
            form.AddPayer.Click += (_, _) => Edit(new EditorChange.EditAddPayer());
            column.Children.Add(form.AddPayer);
            form.Total = new TextBlock { Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"] };
            AutomationProperties.SetAutomationId(form.Total, "ed-total");
            column.Children.Add(form.Total);

            for (var i = 0; i < s.items.Count; i++)
            {
                var index = i;
                var card = new StackPanel { Spacing = 8, Padding = new Thickness(12), CornerRadius = new CornerRadius(8), Background = Brush("CardBackgroundFillColorDefaultBrush"), BorderBrush = Brush("CardStrokeColorDefaultBrush"), BorderThickness = new Thickness(1) };
                var line = new Grid { ColumnSpacing = 8 };
                line.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                line.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                line.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                var label = TextField(Words.EdItem(), $"ed-item-label:{i}", t => new EditorChange.EditItemLabel { index = index, text = t });
                var amount = TextField(Words.EntryAmount(), $"ed-item-amount:{i}", t => new EditorChange.EditItemAmount { index = index, text = t }, 140);
                Grid.SetColumn(amount.Control, 1);
                var remove = new Button { Content = new SymbolIcon(Symbol.Delete), VerticalAlignment = VerticalAlignment.Top, Margin = new Thickness(0, 28, 0, 0) };
                AutomationProperties.SetName(remove, Words.EdRemoveItem());
                ToolTipService.SetToolTip(remove, Words.EdRemoveItem());
                AutomationProperties.SetAutomationId(remove, $"ed-remove-item:{i}");
                remove.Click += (_, _) => Edit(new EditorChange.EditRemoveItem { index = index });
                Grid.SetColumn(remove, 2);
                line.Children.Add(label.Control);
                line.Children.Add(amount.Control);
                line.Children.Add(remove);
                card.Children.Add(line);
                var split = SplitSection(card, s.items[i].split, index, $"item{i}", s.editable);
                var refusal = Problem($"ed-item-problem:{i}");
                card.Children.Add(refusal);
                column.Children.Add(card);
                form.Items.Add((label, amount, split, refusal));
            }
            if (s.editable)
            {
                var add = new HyperlinkButton { Content = Words.EdAddItem() };
                AutomationProperties.SetAutomationId(add, "ed-add-item");
                add.Click += (_, _) => Edit(new EditorChange.EditAddItem());
                column.Children.Add(add);
            }
            form.ItemsRefusal = Problem("ed-items-problem");
            column.Children.Add(form.ItemsRefusal);
            if (s.rest is { } rest)
            {
                column.Children.Add(Heading(s.restIsWhole ? Words.EdWhole() : Words.EdRest()));
                var panel = new StackPanel { Spacing = 8 };
                form.Rest = SplitSection(panel, rest, null, "rest", s.editable);
                column.Children.Add(panel);
                form.RestRefusal = Problem("ed-rest-problem");
                column.Children.Add(form.RestRefusal);
            }
            column.Children.Add(form.Preview);
        }
        if (!s.isNew && _entry is { } entry) column.Children.Add(History(entry));
        form.Combos.AddRange(new[] { form.Kind, form.Category, form.From, form.To }.OfType<ComboBox>().Concat(form.Payers.Select(p => p.Person)));
        foreach (var c in column.Children.OfType<Control>()) c.IsEnabled = s.editable || c is Expander;
        var holder = new Grid();
        holder.Children.Add(column);
        _scroll.Content = holder;
        return form;
    }

    /// <summary>A split inline (an item's, or the rest's for null): folded, its one line and Adjust; else its mode, a
    /// CheckBox and a value per person, its hint and "Split equally among all".</summary>
    private SplitRows SplitSection(StackPanel into, EditorSplit split, int? item, string key, bool editable)
    {
        var hint = new TextBlock { TextWrapping = TextWrapping.Wrap, Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"], Foreground = Brush("TextFillColorSecondaryBrush"), Visibility = Visibility.Collapsed };
        AutomationProperties.SetAutomationId(hint, $"ed-split-hint:{key}");
        var rows = new SplitRows { Shown = split, Hint = hint };
        if (!split.expanded)
        {
            var line = new Grid { ColumnSpacing = 8 };
            line.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            line.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            rows.Folded = new TextBlock { TextWrapping = TextWrapping.Wrap, VerticalAlignment = VerticalAlignment.Center };
            AutomationProperties.SetAutomationId(rows.Folded, $"ed-split-folded:{key}");
            line.Children.Add(rows.Folded);
            if (editable)
            {
                var adjust = new HyperlinkButton { Content = Words.EdAdjust() };
                AutomationProperties.SetAutomationId(adjust, $"ed-split-adjust:{key}");
                adjust.Click += (_, _) => Edit(new EditorChange.EditExpand { item = item });
                Grid.SetColumn(adjust, 1);
                line.Children.Add(adjust);
            }
            into.Children.Add(line);
            return rows;
        }
        var modes = new RadioButtons { MaxColumns = 3, IsEnabled = editable };
        AutomationProperties.SetAutomationId(modes, $"ed-split-mode:{key}");
        foreach (var (mode, label) in new[] { ("SHARES", Words.EdByShares()), ("PERCENT", Words.EdByPercent()), ("AMOUNTS", Words.EdByAmount()) })
        {
            var radio = new RadioButton { Content = label, Tag = mode };
            AutomationProperties.SetAutomationId(radio, $"ed-split-mode:{key}:{mode}");
            modes.Items.Add(radio);
        }
        modes.SelectionChanged += (_, _) =>
        {
            if (modes.SelectedItem is RadioButton { Tag: string m })
            {
                Edit(new EditorChange.EditSplitMode { item = item, mode = m switch { "PERCENT" => SplitMode.Percent, "AMOUNTS" => SplitMode.Amounts, _ => SplitMode.Shares } });
            }
        };
        rows.Modes = modes;
        into.Children.Add(modes);
        foreach (var p in split.people)
        {
            var person = p.person;
            var row = new Grid { ColumnSpacing = 8 };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var on = new CheckBox { Content = p.name, IsEnabled = editable, VerticalAlignment = VerticalAlignment.Center };
            AutomationProperties.SetAutomationId(on, $"ed-split-on:{key}:{person}");
            on.Click += (_, _) => Edit(new EditorChange.EditToggle { item = item, person = person, on = on.IsChecked == true });
            var control = new TextBox { Width = 120, IsEnabled = editable, VerticalAlignment = VerticalAlignment.Center };
            AutomationProperties.SetAutomationId(control, $"ed-split-value:{key}:{person}");
            AutomationProperties.SetName(control, p.name);
            var value = new Box(control);
            control.TextChanged += (_, _) =>
            {
                if (_drawing || !value.Typing()) return;
                value.Typed.Sent(control.Text);
                Edit(new EditorChange.EditShare { item = item, person = person, text = control.Text });
            };
            Grid.SetColumn(control, 1);
            var percent = new TextBlock { Text = "%", VerticalAlignment = VerticalAlignment.Center };
            Grid.SetColumn(percent, 2);
            row.Children.Add(on);
            row.Children.Add(control);
            row.Children.Add(percent);
            into.Children.Add(row);
            rows.People.Add((person, on, value, percent));
        }
        into.Children.Add(hint);
        if (editable)
        {
            var equally = new HyperlinkButton { Content = Words.EdEveryoneEqually() };
            AutomationProperties.SetAutomationId(equally, $"ed-split-equally:{key}");
            equally.Click += (_, _) => Edit(new EditorChange.EditEqually { item = item });
            into.Children.Add(equally);
        }
        return rows;
    }

    /// <summary>The entry's history (Pot.History), read when the Expander first opens: the editor's history page.</summary>
    private Expander History(string entry)
    {
        var list = new StackPanel { Spacing = 4 };
        var expander = new Expander { Header = Words.HistoryShow(), Content = list, HorizontalAlignment = HorizontalAlignment.Stretch, HorizontalContentAlignment = HorizontalAlignment.Stretch };
        AutomationProperties.SetAutomationId(expander, "ed-history");
        var read = false;
        expander.Expanding += async (_, _) =>
        {
            if (read) return;
            read = true;
            try
            {
                var (answer, _) = await Core.CallAsync(new Call.PotHistory { pot = _pot, entry = entry });
                if (answer is not Answer.EntryHistory h)
                {
                    Log.Write($"Pot.History answered {answer.GetType().Name}");
                    return;
                }
                Log.Write($"entry history: {h.rows.Count} revision(s)");
                var i = 0;
                foreach (var r in h.rows)
                {
                    var row = new StackPanel { Padding = new Thickness(0, 4, 0, 4) };
                    var line = Said.Line(r.line);
                    var by = Words.HistoryBy(r.writer ?? Words.HistoryUnknownWriter(), Said.Moment(r.at));
                    row.Children.Add(new TextBlock { Text = line, TextWrapping = TextWrapping.Wrap });
                    row.Children.Add(new TextBlock { Text = by, Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"], Foreground = Brush("TextFillColorSecondaryBrush") });
                    AutomationProperties.SetAutomationId(row, $"ed-history:{i++}");
                    AutomationProperties.SetName(row, $"{line}, {by}");
                    list.Children.Add(row);
                }
            }
            catch (Exception e)
            {
                Log.Write($"the entry's history failed: {e.GetType().Name}");
            }
        };
        return expander;
    }

    /// <summary>Everything <paramref name="s"/> says, set on the form's controls (the drawing flag held).</summary>
    private void Update(Form f, EditorShown s)
    {
        Select(f.Kind, s.kind switch { EntryKind.Refund => "REFUND", EntryKind.Transfer => "TRANSFER", _ => "EXPENSE" });
        var at = s.refusal?.at;
        var refusal = s.refusal is { } r ? Said.Notice(r.words) : null;
        f.Title.Set(s.title);
        Describe(f.Title.Control, at == "title" ? refusal : null, null);
        if (DateTime.TryParseExact(s.date, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out var day))
        {
            var date = new DateTimeOffset(day);
            if (f.Date.Date?.Date != date.Date) f.Date.Date = date;
        }
        f.Date.Description = at == "date" ? Critical(refusal!) : null;
        if (f.Category is not null) Select(f.Category, s.category);
        if (f.Currency.FocusState == FocusState.Unfocused && f.Currency.Text != s.currency) f.Currency.Text = s.currency;
        if (f.Rate is { } rate)
        {
            rate.Set(s.rate);
            var note = s.rateFill?.state switch
            {
                RateState.Filling => Words.EdRateLoading(),
                RateState.Filled => Words.EdRateFilled(Said.Day(s.rateFill.day ?? "")),
                RateState.Missing => Words.EdRateMissing(s.currency),
                _ => Words.EdRateHint(),
            };
            Describe(rate.Control, at == "rate" ? refusal : null, note);
        }
        if (f.Amount is { } amount)
        {
            amount.Set(s.amount);
            Describe(amount.Control, at == "amount" ? refusal : null, null);
        }
        if (f.From is not null) Select(f.From, s.from);
        if (f.To is not null) Select(f.To, s.to);
        Show(f.TransferRefusal, at == "transfer" ? refusal : null);
        for (var i = 0; i < Math.Min(f.Payers.Count, s.payers.Count); i++)
        {
            var (person, paid, remove) = f.Payers[i];
            Select(person, s.payers[i].person);
            paid.Set(s.payers[i].amount);
            remove.Visibility = s.canRemovePayer && s.editable ? Visibility.Visible : Visibility.Collapsed;
        }
        if (f.AddPayer is not null) f.AddPayer.Visibility = s.canAddPayer && s.editable ? Visibility.Visible : Visibility.Collapsed;
        if (f.Total is not null) f.Total.Text = Words.EntryAmountTotal(Said.Piece(s.total));
        Show(f.PayersRefusal, at == "payers" ? refusal : null);
        for (var i = 0; i < Math.Min(f.Items.Count, s.items.Count); i++)
        {
            var (label, itemAmount, split, problem) = f.Items[i];
            label.Set(s.items[i].label);
            itemAmount.Set(s.items[i].amount);
            UpdateSplit(split, s.items[i].split);
            Show(problem, at == "item" && s.refusal?.item == i ? refusal : null);
        }
        Show(f.ItemsRefusal, at == "items" ? refusal : null);
        if (f.Rest is { } rest && s.rest is { } shownRest) UpdateSplit(rest, shownRest);
        Show(f.RestRefusal, at == "rest" ? refusal : null);
        f.Preview.Children.Clear();
        if (s.preview.Count > 0)
        {
            f.Preview.Children.Add(Heading(Words.EdSharesIn(s.Base)));
            var given = s.kind == EntryKind.Refund ? Words.EdReceivedColumn() : Words.EdPaidColumn();
            var i = 0;
            foreach (var p in s.preview)
            {
                var row = new Grid { ColumnSpacing = 12 };
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                var gave = p.given is { } g ? $"{given} {Said.Piece(g)}" : "";
                var share = Said.Piece(p.share);
                row.Children.Add(new TextBlock { Text = p.name });
                var gaveText = new TextBlock { Text = gave, Foreground = Brush("TextFillColorSecondaryBrush") };
                Grid.SetColumn(gaveText, 1);
                row.Children.Add(gaveText);
                var shareText = new TextBlock { Text = share, Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"] };
                Grid.SetColumn(shareText, 2);
                row.Children.Add(shareText);
                AutomationProperties.SetAutomationId(row, $"ed-preview:{i++}");
                AutomationProperties.SetName(row, string.Join(", ", new[] { p.name, gave, share }.Where(t => t.Length > 0)));
                f.Preview.Children.Add(row);
            }
        }
        f.Invalid.Visibility = s.invalid ? Visibility.Visible : Visibility.Collapsed;
        // the page's InfoBar says the refusal too, once a save was tried
        if (refusal is not null) Refuse(refusal);
        else if (_refused.IsOpen && s.notice is null) _refused.IsOpen = false;
    }

    private static void UpdateSplit(SplitRows rows, EditorSplit split)
    {
        rows.Shown = split;
        if (rows.Folded is not null) rows.Folded.Text = Said.Notice(split.folded);
        if (rows.Modes is { } modes)
        {
            var at = split.mode switch { SplitMode.Percent => 1, SplitMode.Amounts => 2, _ => 0 };
            if (modes.SelectedIndex != at) modes.SelectedIndex = at;
        }
        for (var i = 0; i < Math.Min(rows.People.Count, split.people.Count); i++)
        {
            var (_, on, value, percent) = rows.People[i];
            var shown = split.people[i];
            if ((on.IsChecked == true) != shown.on) on.IsChecked = shown.on;
            value.Set(shown.value);
            value.Control.Visibility = shown.on ? Visibility.Visible : Visibility.Collapsed;
            percent.Visibility = shown.on && split.mode == SplitMode.Percent ? Visibility.Visible : Visibility.Collapsed;
        }
        rows.Hint.Visibility = split.hint is null ? Visibility.Collapsed : Visibility.Visible;
        if (split.hint is { } h) rows.Hint.Text = Said.Notice(h);
    }

    private static TextBlock Critical(string text) => new() { Text = text, TextWrapping = TextWrapping.Wrap, Foreground = Brush("SystemFillColorCriticalBrush") };

    /// <summary>A field's description: the refusal at it where there is one, else its note.</summary>
    private static void Describe(TextBox box, string? refusal, string? note) =>
        box.Description = refusal is not null ? Critical(refusal) : note is not null ? new TextBlock { Text = note, TextWrapping = TextWrapping.Wrap } : null;

    private static void Show(TextBlock? block, string? text)
    {
        if (block is null) return;
        block.Visibility = text is null ? Visibility.Collapsed : Visibility.Visible;
        if (text is not null) block.Text = text;
    }
}
