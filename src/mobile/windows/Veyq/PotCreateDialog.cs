// "New pot" from New (the web's PotCreateDialog.vue; D5 of the Expenses CR), Fluent's way and Linux's pot_create.rs: a
// ContentDialog — the name a TextBox, the space (only where there is more than one) and the base currency ComboBoxes, the
// people a list with a Remove button each, a name typed added through a TextBox and its button, the space's members by
// their row's button; Create its primary button, Cancel its close button. Every rule is the core's PotCreateSheet
// (PotCreate.Open, watched as Watched.PotCreate): its defaults, its members read again for another space, whether Create is
// offered for the name typed (PotCreate.Name, sent as typed, never echoed), where the Pot lands. Create answers what to do
// at once (Answer.Stored): stored closes the dialog — the shell opens the new Pot —, else the core's refusal stands in the
// dialog. Built per open (Dialogs).
using System;
using System.Collections.Generic;
using System.Globalization;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Veyq.Bridge;
using Veyq.Bridge.Wire;

namespace Veyq;

internal sealed class PotCreateDialog
{
    private readonly Window _window;
    private readonly Action<bool, string> _say;
    private readonly long _create;
    private readonly ContentDialog _dialog;
    private readonly TextBox _name = new() { Header = Words.CreatePotName(), MinWidth = 320 };
    private readonly StackPanel _choices = new() { Spacing = 12 };
    private readonly StackPanel _people = new() { Spacing = 4 };
    private readonly TextBox _typed = new() { PlaceholderText = Words.PeopleName(), MinWidth = 220 };
    private readonly StackPanel _members = new() { Spacing = 4 };
    private readonly TextBlock _problem = new() { TextWrapping = TextWrapping.Wrap, Visibility = Visibility.Collapsed };
    private ComboBox? _space;
    private ComboBox? _currency;
    private List<string> _spaceIds = [];
    private List<string> _codes = [];
    private Watch? _watch;
    private bool _drawing;
    private bool _creating;

    /// <summary>Opens New pot over <paramref name="window"/>; <paramref name="say"/> takes what a create that landed
    /// still says.</summary>
    public static async void Open(Window window, Action<bool, string> say)
    {
        try
        {
            // the words' language (a pass's VEYQ_LANGUAGE, else the person's): the core starts the Pot in its currency
            var language = DebugEnv.Language ?? CultureInfo.CurrentUICulture.Name;
            var (answer, _) = await Core.CallAsync(new Call.PotCreateOpen { language = language });
            if (answer is not Answer.PotCreateOpened { create: var create })
            {
                Log.Write($"PotCreate.Open answered {answer.GetType().Name}");
                say(false, Words.CreatePotFailed());
                return;
            }
            Log.Write("new pot open");
            await new PotCreateDialog(window, say, create).Show();
        }
        catch (Exception e)
        {
            // an exception out of an async void would end the app in combase, with nothing in the log
            Log.Write($"New pot failed: {e.GetType().Name}");
        }
    }

    private PotCreateDialog(Window window, Action<bool, string> say, long create)
    {
        _window = window;
        _say = say;
        _create = create;
        AutomationProperties.SetAutomationId(_name, "create-name");
        AutomationProperties.SetAutomationId(_typed, "create-person-name");
        AutomationProperties.SetAutomationId(_problem, "create-problem");
        _problem.Foreground = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["SystemFillColorCriticalBrush"];
        // the name as typed: the core judges Create on it, nothing comes back into the box
        _name.TextChanged += (_, _) => Act.Send(new Call.PotCreateName { create = _create, name = _name.Text });
        AutomationProperties.SetName(_typed, Words.PeopleName());
        var add = new Button { Content = Words.PeopleAdd() };
        AutomationProperties.SetAutomationId(add, "create-person-add");
        add.Click += (_, _) => AddTyped();
        _typed.KeyDown += (_, e) =>
        {
            if (e.Key != Windows.System.VirtualKey.Enter) return;
            e.Handled = true;
            AddTyped();
        };
        var typedRow = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        typedRow.Children.Add(_typed);
        typedRow.Children.Add(add);
        var body = new StackPanel { Spacing = 12, MinWidth = 400 };
        body.Children.Add(_name);
        body.Children.Add(_choices);
        var people = new TextBlock { Text = Words.PotPeople(), Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"] };
        body.Children.Add(people);
        body.Children.Add(_people);
        body.Children.Add(typedRow);
        body.Children.Add(_members);
        body.Children.Add(_problem);
        _dialog = new ContentDialog
        {
            Title = Words.CreatePot(),
            Content = new ScrollViewer { Content = body, MaxHeight = 560 },
            PrimaryButtonText = Words.CreatePotCreate(),
            CloseButtonText = Words.EntryCancel(),
            DefaultButton = ContentDialogButton.Primary,
            IsPrimaryButtonEnabled = false,
        };
        AutomationProperties.SetAutomationId(_dialog, "new-pot");
        _dialog.PrimaryButtonClick += Created;
        _name.Loaded += (_, _) => _name.Focus(FocusState.Programmatic);
    }

    private async System.Threading.Tasks.Task Show()
    {
        _watch = Core.Watch(new Watched.PotCreate { create = _create }, update =>
        {
            if (update is not Update.PotCreate { shown: { } s }) return;
            try
            {
                Draw(s);
            }
            catch (Exception e)
            {
                Log.Write($"New pot did not draw: {e.GetType().Name} 0x{e.HResult:X8}: {e.Message}");
            }
        }, e => Log.Write($"a New pot update: {e.Message}"));
        try
        {
            await Dialogs.Show(_window, _dialog);
        }
        finally
        {
            _watch?.Dispose();
            _watch = null;
            Act.Send(new Call.PotCreateClose { create = _create });
            Log.Write("new pot closed");
        }
    }

    private async void AddTyped()
    {
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.PotCreateAddTyped { create = _create, name = _typed.Text });
            if (answer is Answer.Stored { stored: true }) _typed.Text = "";
        }
        catch (Exception e)
        {
            Log.Write($"PotCreate.AddTyped failed: {e.GetType().Name}");
        }
    }

    /// <summary>Create: the dialog stays open until the core answered; stored closes it, a refusal stands in it.</summary>
    private async void Created(ContentDialog sender, ContentDialogButtonClickEventArgs args)
    {
        args.Cancel = true;
        if (_creating) return;
        _creating = true;
        _dialog.IsPrimaryButtonEnabled = false;
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.PotCreateCreate { create = _create, typed = _typed.Text });
            switch (answer)
            {
                case Answer.Stored { stored: true, notice: var n }:
                    Log.Write($"new pot created{(n is null ? "" : ", " + n.msg)}");
                    if (n is not null) _say(false, Said.Notice(n));
                    _dialog.Hide();
                    break;
                case Answer.Stored { notice: var n }:
                    Log.Write($"new pot refused: {n?.msg}");
                    if (n is not null)
                    {
                        _problem.Text = Said.Notice(n);
                        _problem.Visibility = Visibility.Visible;
                    }
                    break;
                default:
                    Log.Write($"PotCreate.Create answered {answer.GetType().Name}");
                    _problem.Text = Words.CreatePotFailed();
                    _problem.Visibility = Visibility.Visible;
                    break;
            }
        }
        catch (Exception e)
        {
            Log.Write($"PotCreate.Create failed: {e.GetType().Name}");
        }
        finally
        {
            _creating = false;
        }
    }

    private ComboBox Choice(string header, string id, IReadOnlyList<(string Id, string Label)> choices, string? at, Func<string, Call> call)
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
        combo.SelectedIndex = at is null ? -1 : ids.IndexOf(at);
        combo.SelectionChanged += (_, _) =>
        {
            if (!_drawing && combo.SelectedItem is ComboBoxItem { Tag: string chosen }) Act.Send(call(chosen));
        };
        return combo;
    }

    private void Draw(PotCreateShown s)
    {
        _drawing = true;
        try
        {
            // the space (only where there is a choice) and the currency: built once, chosen from the state after
            if (_currency is null)
            {
                if (s.spaces.Count > 1)
                {
                    var spaces = new List<(string, string)>();
                    foreach (var sp in s.spaces) spaces.Add((sp.space.scopeId, Said.Piece(sp.name)));
                    _spaceIds = spaces.ConvertAll(p => p.Item1);
                    _space = Choice(Words.CreatePotSpace(), "create-space", spaces, s.space, id => new Call.PotCreateSpace { create = _create, scopeId = id });
                    _choices.Children.Add(_space);
                }
                _codes = [.. s.currencies];
                _currency = Choice(Words.PotBaseCurrency(), "create-currency", _codes.ConvertAll(c => (c, c)), s.currency, code => new Call.PotCreateCurrency { create = _create, code = code });
                _currency.Description = Words.SettingsCurrencyHint();
                _choices.Children.Add(_currency);
            }
            if (_space is not null && s.space is not null && _space.SelectedIndex != _spaceIds.IndexOf(s.space)) _space.SelectedIndex = _spaceIds.IndexOf(s.space);
            if (_currency.SelectedIndex != _codes.IndexOf(s.currency)) _currency.SelectedIndex = _codes.IndexOf(s.currency);

            // the people it starts with, each taken out by its button
            _people.Children.Clear();
            for (var i = 0; i < s.people.Count; i++)
            {
                var p = s.people[i];
                var index = i;
                var remove = new Button { Content = new SymbolIcon(Symbol.Remove), VerticalAlignment = VerticalAlignment.Center };
                ToolTipService.SetToolTip(remove, Words.PeopleRemove(p.name));
                AutomationProperties.SetName(remove, Words.PeopleRemove(p.name));
                AutomationProperties.SetAutomationId(remove, $"create-remove:{i}");
                remove.Click += (_, _) => Act.Send(new Call.PotCreateRemove { create = _create, index = index });
                _people.Children.Add(PotPage.LedRow(Avatars.Person(p.name, null, 32), p.name, null, $"create-person:{i}", [remove]));
            }

            // the space's members still to add
            _members.Children.Clear();
            if (s.offered.Count > 0) _members.Children.Add(new TextBlock { Text = Words.PeopleChooseMember(), TextWrapping = TextWrapping.Wrap });
            foreach (var m in s.offered)
            {
                var add = new Button { Content = Words.PeopleAddMember(), VerticalAlignment = VerticalAlignment.Center };
                AutomationProperties.SetAutomationId(add, $"create-member:{m.userId}");
                var userId = m.userId;
                add.Click += (_, _) => Act.Send(new Call.PotCreateAddMember { create = _create, userId = userId });
                _members.Children.Add(PotPage.LedRow(Avatars.Person(m.name, m.userId, 32), m.name, null, $"create-offered:{m.userId}", [add]));
            }
            _dialog.IsPrimaryButtonEnabled = s.canCreate && !s.done && !_creating;
        }
        finally
        {
            _drawing = false;
        }
    }
}
