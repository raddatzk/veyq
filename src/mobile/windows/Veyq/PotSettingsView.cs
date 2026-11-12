// A Pot's Settings place (the web's SettingsPanel.vue and PeoplePanel.vue; D5 of the Expenses CR), Fluent's way and
// editable in place, Linux's pot_settings.rs: every rule is the core's PotSettings (PotSettings.Open, watched as
// Watched.PotSettings); what may be changed and done to whom arrives in PotSettingsShown, each change is a call answered
// once its write ended (Answer.Stored: stored — the box is emptied —, or why not, said under the CommandBar).
//
// The base currency a ComboBox while it may be chosen, else its code and, once money fixes it, why; the own categories
// renamed through Dialogs.AskName, one added through a TextBox and its button; the people with their facts, each renamed,
// (de)activated and erased (asked by Dialogs.Confirm: the core's asking), a person added through a TextBox, a member of
// the space by their row's button. The place is kept across the Pot page's redraws, and its two boxes are never moved:
// the lists around them are drawn anew, so what is typed stays.
using System;
using System.Linq;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Veyq.Bridge;
using Veyq.Bridge.Wire;

namespace Veyq;

internal sealed class PotSettingsView : IDisposable
{
    private readonly long _pot;
    private readonly Window _window;
    private readonly Action<bool, string> _say;
    private long? _id;
    private Watch? _watch;
    private bool _closed;
    private bool _drawing;
    private bool _asking;
    private readonly StackPanel _note = new();
    private readonly StackPanel _currency = new() { Spacing = 4 };
    private readonly StackPanel _categories = new() { Spacing = 4 };
    private readonly StackPanel _addCategory;
    private readonly TextBox _categoryName = new() { MinWidth = 240 };
    private readonly StackPanel _people = new() { Spacing = 4 };
    private readonly StackPanel _addPerson;
    private readonly TextBox _personName = new() { MinWidth = 240 };
    private readonly StackPanel _members = new() { Spacing = 4 };
    private ComboBox? _currencyBox;

    /// <summary>The place, moved into each redraw of the Pot page.</summary>
    public StackPanel Panel { get; } = new() { Spacing = 4 };

    public long Pot => _pot;

    public PotSettingsView(long pot, Window window, Action<bool, string> say)
    {
        _pot = pot;
        _window = window;
        _say = say;
        _categoryName.PlaceholderText = Words.SettingsCategoryName();
        AutomationProperties.SetName(_categoryName, Words.SettingsCategoryName());
        AutomationProperties.SetAutomationId(_categoryName, "settings-category-name");
        _addCategory = AddRow(_categoryName, Words.SettingsAddCategory(), "settings-category-add", () =>
            Change(_categoryName, id => new Call.PotSettingsAddCategory { settings = id, name = _categoryName.Text }));
        _personName.PlaceholderText = Words.PeopleName();
        AutomationProperties.SetName(_personName, Words.PeopleName());
        AutomationProperties.SetAutomationId(_personName, "settings-person-name");
        _addPerson = AddRow(_personName, Words.PeopleAdd(), "settings-person-add", () =>
            Change(_personName, id => new Call.PotSettingsAddPerson { settings = id, name = _personName.Text }));
        foreach (var part in new UIElement[] { _note, _currency, _categories, _addCategory, _people, _addPerson, _members }) Panel.Children.Add(part);
        foreach (var part in new UIElement[] { _currency, _categories, _addCategory, _people, _addPerson, _members }) part.Visibility = Visibility.Collapsed;
        Open();
    }

    /// <summary>A box and its button; Enter in the box presses it.</summary>
    private static StackPanel AddRow(TextBox box, string label, string id, Action add)
    {
        // the box's words are its placeholder: beside a box with a header the button would sit at the box's foot
        var button = new Button { Content = label };
        AutomationProperties.SetAutomationId(button, id);
        button.Click += (_, _) => add();
        box.KeyDown += (_, e) =>
        {
            if (e.Key != Windows.System.VirtualKey.Enter) return;
            e.Handled = true;
            add();
        };
        var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, Padding = new Thickness(12, 4, 12, 8) };
        row.Children.Add(box);
        row.Children.Add(button);
        return row;
    }

    private async void Open()
    {
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.PotSettingsOpen { pot = _pot });
            if (answer is not Answer.PotSettingsOpened { settings: var id })
            {
                Log.Write($"PotSettings.Open answered {answer.GetType().Name}");
                return;
            }
            if (_closed)
            {
                Act.Send(new Call.PotSettingsClose { settings = id });
                return;
            }
            _id = id;
            Log.Write("pot settings open");
            _watch = Core.Watch(new Watched.PotSettings { settings = id }, update =>
            {
                if (_closed || update is not Update.PotSettings { shown: { } s }) return;
                try
                {
                    Draw(s);
                }
                catch (Exception e)
                {
                    // out of a watch's callback it would end the app in combase, with nothing in the log
                    Log.Write($"the settings did not draw: {e.GetType().Name} 0x{e.HResult:X8}: {e.Message}");
                }
            }, e => Log.Write($"a settings update: {e.Message}"));
        }
        catch (Exception e)
        {
            Log.Write($"PotSettings.Open failed: {e.GetType().Name}");
        }
    }

    public void Dispose()
    {
        _closed = true;
        _watch?.Dispose();
        _watch = null;
        if (_id is { } id) Act.Send(new Call.PotSettingsClose { settings = id });
        _id = null;
    }

    private static Button IconButton(Symbol symbol, string words, string id)
    {
        var b = new Button { Content = new SymbolIcon(symbol), VerticalAlignment = VerticalAlignment.Center };
        ToolTipService.SetToolTip(b, words);
        AutomationProperties.SetName(b, words);
        AutomationProperties.SetAutomationId(b, id);
        return b;
    }

    private static Button TextButton(string words, string id)
    {
        var b = new Button { Content = words, VerticalAlignment = VerticalAlignment.Center };
        AutomationProperties.SetAutomationId(b, id);
        return b;
    }

    private void Draw(PotSettingsShown s)
    {
        _drawing = true;
        try
        {
            foreach (var part in new FrameworkElement[] { _currency, _categories, _addCategory, _people, _addPerson, _members }) part.IsHitTestVisible = !s.saving;
            _note.Children.Clear();
            if (!s.ready) _note.Children.Add(PotPage.Note(s.canChangeSettings ? Words.SettingsMissing() : Words.SettingsMissingReadOnly(), "settings-missing"));

            // the base currency: chosen while no money fixes it
            if (_currencyBox is not null) _currencyBox.IsDropDownOpen = false;
            _currency.Children.Clear();
            _currencyBox = null;
            PotPage.Heading(_currency, Words.PotBaseCurrency());
            if (s.currencyEditable)
            {
                var box = new ComboBox { Header = Words.PotBaseCurrency(), MinWidth = 200, Margin = new Thickness(12, 0, 12, 0), Description = Words.SettingsCurrencyHint() };
                AutomationProperties.SetAutomationId(box, "settings-currency");
                foreach (var code in s.currencies)
                {
                    var item = new ComboBoxItem { Content = code, Tag = code };
                    AutomationProperties.SetAutomationId(item, $"settings-currency:{code}");
                    box.Items.Add(item);
                }
                box.SelectedIndex = s.currency is null ? -1 : s.currencies.ToList().IndexOf(s.currency);
                box.SelectionChanged += (_, _) =>
                {
                    if (_drawing || box.SelectedItem is not ComboBoxItem { Tag: string code } || code == s.currency) return;
                    Change(null, id => new Call.PotSettingsCurrency { settings = id, code = code });
                };
                _currencyBox = box;
                _currency.Children.Add(box);
            }
            else
            {
                _currency.Children.Add(PotPage.Row(s.currency ?? "—", s.currencyFixed ? Words.SettingsCurrencyFixed() : null, "currency"));
            }
            _currency.Visibility = Visibility.Visible;

            // the own categories
            _categories.Children.Clear();
            PotPage.Heading(_categories, Words.PotOwnCategories());
            if (s.canChangeSettings) _categories.Children.Add(PotPage.Note(Words.SettingsCategoriesHint(), "settings-categories-hint"));
            foreach (var c in s.categories)
            {
                var label = Said.Piece(c.label);
                var trailing = Array.Empty<UIElement>();
                if (s.canChangeSettings)
                {
                    var rename = IconButton(Symbol.Edit, Words.SettingsRenameCategory(label), $"category-rename:{c.id}");
                    var id = c.id;
                    rename.Click += (_, _) => Rename(Words.SettingsRenameCategory(label), Words.SettingsCategoryName(), label,
                        (settings, name) => new Call.PotSettingsRenameCategory { settings = settings, category = id, name = name });
                    trailing = [rename];
                }
                _categories.Children.Add(PotPage.Row(label, null, $"category:{c.id}", trailing));
            }
            _categories.Visibility = s.categoriesShown ? Visibility.Visible : Visibility.Collapsed;
            _addCategory.Visibility = s.categoriesShown && s.canAddCategory ? Visibility.Visible : Visibility.Collapsed;

            // the people
            _people.Children.Clear();
            PotPage.Heading(_people, Words.PotPeople());
            foreach (var p in s.people)
            {
                var e = p.entry;
                var trailing = new System.Collections.Generic.List<UIElement>();
                if (e.renamable)
                {
                    var rename = IconButton(Symbol.Edit, Words.PeopleRename(e.name), $"person-rename:{e.id}");
                    rename.Click += (_, _) => Rename(Words.PeopleRename(e.name), Words.PeopleName(), e.name,
                        (settings, name) => new Call.PotSettingsRename { settings = settings, person = e.id, name = name });
                    trailing.Add(rename);
                }
                if (e.canToggle)
                {
                    var toggle = TextButton(e.active ? Words.PeopleDeactivate() : Words.PeopleActivate(), $"person-toggle:{e.id}");
                    toggle.Click += (_, _) => Change(null, id => new Call.PotSettingsToggle { settings = id, person = e.id });
                    trailing.Add(toggle);
                }
                if (e.canErase)
                {
                    var erase = IconButton(Symbol.Delete, e.named ? Words.PeopleDelete(e.name) : Words.PeopleRemove(e.name), $"person-erase:{e.id}");
                    erase.Click += (_, _) =>
                    {
                        if (_id is { } id) Act.Send(new Call.PotSettingsAskErase { settings = id, person = e.id });
                    };
                    trailing.Add(erase);
                }
                _people.Children.Add(PotPage.LedRow(Avatars.Person(e.name, e.userId, 32), e.name, Said.Line(p.facts), $"person:{e.id}", trailing.ToArray()));
            }
            if (s.deletedPeople > 0) _people.Children.Add(PotPage.Note(Words.PeopleDeletedSummary(s.deletedPeople), "people-deleted"));
            if (s.settleFirst) _people.Children.Add(PotPage.Note(Words.PeopleSettleFirst(), "people-settle-first"));
            _people.Visibility = Visibility.Visible;
            _addPerson.Visibility = s.canWrite ? Visibility.Visible : Visibility.Collapsed;

            // the space's members still to add
            _members.Children.Clear();
            if (s.canWrite && s.members.Count > 0)
            {
                PotPage.Heading(_members, Words.PeopleAddMember());
                _members.Children.Add(PotPage.Note(Words.PeopleChooseMember(), "members-hint"));
                foreach (var m in s.members)
                {
                    var add = TextButton(Words.PeopleAddMember(), $"member-add:{m.userId}");
                    var userId = m.userId;
                    add.Click += (_, _) => Change(null, id => new Call.PotSettingsAddMember { settings = id, userId = userId });
                    _members.Children.Add(PotPage.LedRow(Avatars.Person(m.name, m.userId, 32), m.name, null, $"member:{m.userId}", [add]));
                }
            }
            _members.Visibility = _members.Children.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
        }
        finally
        {
            _drawing = false;
        }
        if (s.asking is { } asked) AskErase(asked);
    }

    /// <summary>The erase the core asks about: its answer erases, anything else cancels.</summary>
    private async void AskErase(ErasingPerson asked)
    {
        if (_asking || _id is not { } id) return;
        _asking = true;
        try
        {
            var (title, question, accept) = asked.named
                ? (Words.PeopleDelete(asked.name), Words.PeopleDeleteConfirm(), Words.PeopleDeleteAccept())
                : (Words.PeopleRemove(asked.name), Words.PeopleRemoveConfirm(), Words.PeopleRemoveAccept());
            if (await Dialogs.Confirm(_window, "erase-ask", title, question, accept)) Change(null, settings => new Call.PotSettingsErase { settings = settings });
            else Act.Send(new Call.PotSettingsCancelAsk { settings = id });
        }
        catch (Exception e)
        {
            Log.Write($"the erase's question failed: {e.GetType().Name}");
        }
        finally
        {
            _asking = false;
        }
    }

    /// <summary>One change sent, answered once its write ended: stored empties <paramref name="box"/>; a refusal is said.</summary>
    private async void Change(TextBox? box, Func<long, Call> call)
    {
        if (_id is not { } id) return;
        var c = call(id);
        try
        {
            var (answer, _) = await Core.CallAsync(c);
            if (answer is Answer.Stored { stored: var stored, notice: var notice })
            {
                Log.Write($"{c.GetType().Name}: stored {stored}{(notice is null ? "" : ", " + notice.msg)}");
                if (stored && box is not null) box.Text = "";
                if (notice is not null) _say(false, Said.Notice(notice));
            }
            else Log.Write($"{c.GetType().Name} answered {answer.GetType().Name}");
        }
        catch (Exception e)
        {
            Log.Write($"{c.GetType().Name} failed: {e.GetType().Name}");
        }
    }

    /// <summary>A name asked for, again with the core's refusal under it until it is stored or cancelled.</summary>
    private async void Rename(string title, string field, string name, Func<long, string, Call> call)
    {
        try
        {
            string? problem = null;
            while (await Dialogs.AskName(_window, "rename", title, field, name, problem, Words.EntrySave()) is { } typed)
            {
                if (_id is not { } id) return;
                var (answer, _) = await Core.CallAsync(call(id, typed));
                Log.Write($"rename: {(answer is Answer.Stored st ? $"stored {st.stored}" : answer.GetType().Name)}");
                if (answer is not Answer.Stored { stored: false, notice: { } n }) return;
                name = typed;
                problem = Said.Notice(n);
            }
        }
        catch (Exception e)
        {
            Log.Write($"a rename failed: {e.GetType().Name}");
        }
    }
}
