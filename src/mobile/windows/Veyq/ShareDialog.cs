// A node's share sheet (ADR 0127 decision 2; core's ShareSheet, the web's FolderShareDialog), Fluent's way: one
// ContentDialog (WinUI shows one at a time, so a renew's and a delete's question are asked in place) holding the list of
// links — each a card with Copy link, Share (Windows' share sheet, DataTransferManager for the app's window) and its other
// commands in a More flyout —, the new-link or edit form (RadioButtons for the role, a TextBox for the label, a ToggleSwitch
// with a PasswordBox, a ToggleSwitch with a CalendarDatePicker; Create/Save the primary button, Cancel the secondary one),
// or the address just minted, shown once to copy or share. Every state and every rule is the core's (Share.*, watched by
// id); the password typed goes in and never comes back.
using System;
using System.Linq;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Veyq.Bridge;
using Veyq.Bridge.Wire;
using Windows.ApplicationModel.DataTransfer;

namespace Veyq;

internal sealed class ShareDialog
{
    private readonly Window _window;
    private readonly StackPanel _body = new() { Spacing = 12, MinWidth = 460 };
    private ContentDialog? _dialog;
    private long? _id;
    private Watch? _watch;
    private ShareShown? _shown;
    private bool _closed;
    /// <summary>A draw sets the form's controls: their events are not the person's.</summary>
    private bool _drawing;
    /// <summary>Which form the controls hold (the link edited, or "" for a new one): a new one builds them anew.</summary>
    private string? _formOf;
    private Form? _form;
    /// <summary>The address handed to Windows' share sheet, while it asks for it.</summary>
    private string? _sharing;

    private sealed class Form
    {
        public required StackPanel Panel { get; init; }
        public required RadioButtons Role { get; init; }
        public required TextBlock Hint { get; init; }
        public required TextBox Label { get; init; }
        public required ToggleSwitch Lock { get; init; }
        public required PasswordBox Password { get; init; }
        public required TextBlock Short { get; init; }
        public required ToggleSwitch End { get; init; }
        public required CalendarDatePicker Day { get; init; }
        public required TextBlock Problem { get; init; }
    }

    private ShareDialog(Window window) => _window = window;

    /// <summary>Opens the share sheet of <paramref name="node"/> over <paramref name="window"/>.</summary>
    public static void Open(Window window, NodeAt node) => new ShareDialog(window).Show(node);

    private async void Show(NodeAt node)
    {
        if (Dialogs.Open) return;
        var dialog = new ContentDialog
        {
            Title = node.name,
            Content = new ScrollViewer { Content = _body, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Padding = new Thickness(0, 0, 16, 0) },
            CloseButtonText = Words.ShareDone(),
            DefaultButton = ContentDialogButton.Primary,
        };
        AutomationProperties.SetAutomationId(dialog, "share");
        // the form's buttons: sent or cancelled, the dialog stays (the state says what shows next)
        dialog.PrimaryButtonClick += (_, args) =>
        {
            args.Cancel = true;
            if (_id is { } share) Act.Send(new Call.ShareSave { share = share });
        };
        dialog.SecondaryButtonClick += (_, args) =>
        {
            args.Cancel = true;
            if (_id is { } share) Act.Send(new Call.ShareCancelForm { share = share });
        };
        dialog.Closing += (_, args) =>
        {
            if (_shown?.saving == true) args.Cancel = true;
        };
        _dialog = dialog;
        Draw();
        Watch(node);
        await Dialogs.Show(_window, dialog);
        _closed = true;
        _watch?.Dispose();
        _watch = null;
        if (_id is { } id) Act.Send(new Call.ShareClose { share = id });
        _id = null;
    }

    private async void Watch(NodeAt node)
    {
        Answer answer;
        try
        {
            (answer, _) = await Core.CallAsync(new Call.ShareOpen { node = node });
        }
        catch (Exception e)
        {
            Log.Write($"the share sheet did not open in the core ({e.GetType().Name})");
            return;
        }
        if (answer is not Answer.ShareOpened { share: var share })
        {
            Log.Write($"the share sheet did not open in the core ({answer.GetType().Name})");
            return;
        }
        if (_closed)
        {
            Act.Send(new Call.ShareClose { share = share });
            return;
        }
        _id = share;
        _watch = Core.Watch(new Watched.Share { share = share }, update =>
        {
            if (update is not Update.Share u || _closed) return;
            _shown = u.shown;
            if (u.shown is { } s)
            {
                Log.Write($"share sheet: {s.links?.Count ?? 0} links [{string.Join(", ", (s.links ?? []).Select(l => l.shareId[..Math.Min(6, l.shareId.Length)] + (l.canCopy ? "" : " off")))}], " +
                    $"busy {s.busy is not null}, notice {s.notice?.msg}, form {s.form is not null}, editing {s.editing is not null}, saving {s.saving}, " +
                    $"problem {s.problem?.msg}, fresh {s.freshAddress is not null}, renewing {s.renewing is not null}, deleting {s.deleting is not null}");
            }
            Draw();
        }, e => Log.Write($"a share sheet update: {e.Message}"));
    }

    private void Draw()
    {
        if (_dialog is not { } dialog) return;
        if (_shown is not { } s || _id is not { } share)
        {
            _body.Children.Clear();
            _body.Children.Add(new ProgressRing { IsActive = true });
            return;
        }
        dialog.Title = Said.Key(s.title);
        if (s.freshAddress is { } fresh)
        {
            _formOf = null;
            Plain(dialog, fresh: true);
            DrawFresh(fresh, share);
        }
        else if (s.form is { } form)
        {
            dialog.CloseButtonText = Words.ShareDone();
            DrawForm(dialog, s, form, share);
        }
        else
        {
            _formOf = null;
            Plain(dialog);
            DrawList(s, share);
        }
    }

    /// <summary>No form: only Done closes the dialog — on the address shown once its own Done leads back to the list, so
    /// the dialog's is not shown beside it.</summary>
    private static void Plain(ContentDialog dialog, bool fresh = false)
    {
        dialog.PrimaryButtonText = "";
        dialog.SecondaryButtonText = "";
        dialog.CloseButtonText = fresh ? "" : Words.ShareDone();
    }

    private void DrawList(ShareShown s, long share)
    {
        _body.Children.Clear();
        _body.Children.Add(Text(Said.Key(s.intro), "share-intro", "TextFillColorSecondaryBrush"));
        if (s.canMint)
        {
            var add = new Button { Content = Words.ShareNew(), Style = (Style)Application.Current.Resources["AccentButtonStyle"], IsEnabled = s.busy is null };
            AutomationProperties.SetAutomationId(add, "share-new");
            add.Click += (_, _) => Act.Send(new Call.ShareAdd { share = share });
            _body.Children.Add(add);
        }
        if (s.notice is { } n) _body.Children.Add(Text(Said.Notice(n), "share-notice", "TextFillColorSecondaryBrush"));
        if (s.links is not { } links)
        {
            _body.Children.Add(s.failed ? Text(Words.ShareLoadFailed(), "share-failed", "SystemFillColorCriticalBrush") : new ProgressRing { IsActive = true });
            return;
        }
        foreach (var link in links) _body.Children.Add(Row(link, s.busy == link.shareId, share));
        // a renew or a delete the core asks, in place (WinUI shows one ContentDialog at a time)
        if (s.renewing is not null) _body.Children.Add(Ask(Words.ShareRenew(), Said.Key(s.renewQuestion), new Call.ShareRenew { share = share }, share, "renew"));
        else if (s.deleting is not null) _body.Children.Add(Ask(Words.ShareDelete(), Said.Key(s.deleteQuestion), new Call.ShareDelete { share = share }, share, "delete"));
    }

    /// <summary>One link as a card: its title and line, Copy link and Share where it may be copied, the rest under More.</summary>
    private UIElement Row(ShownLink link, bool busy, long share)
    {
        var grid = new Grid { ColumnSpacing = 8, Padding = new Thickness(12, 8, 8, 8), CornerRadius = new CornerRadius(4) };
        grid.Background = (Brush)Application.Current.Resources["CardBackgroundFillColorDefaultBrush"];
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var title = Said.Piece(link.title);
        var text = new StackPanel { Spacing = 2 };
        text.Children.Add(new TextBlock { Text = title, TextTrimming = TextTrimming.CharacterEllipsis });
        var dates = Words.ShareCreated(Said.InstantDay(link.createdAt));
        if (link.expiresAt is { } end) dates += " · " + Words.ShareExpires(Said.InstantDay(end));
        text.Children.Add(new TextBlock { Text = dates, Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"] });
        if (link.badges.Count > 0)
        {
            text.Children.Add(new TextBlock
            {
                Text = string.Join(" · ", link.badges.Select(Said.Notice)),
                Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"],
                Foreground = (Brush)Application.Current.Resources["AccentTextFillColorPrimaryBrush"],
            });
        }
        grid.Children.Add(text);
        var commands = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 4, VerticalAlignment = VerticalAlignment.Center };
        Grid.SetColumn(commands, 1);
        grid.Children.Add(commands);
        if (busy)
        {
            commands.Children.Add(new ProgressRing { IsActive = true, Width = 20, Height = 20 });
            return grid;
        }
        if (link is { canCopy: true, address: { } address })
        {
            commands.Children.Add(Icon(Symbol.Copy, Words.ShareCopy(), $"share-copy:{title}", () => Copy(address)));
            commands.Children.Add(Icon(Symbol.Share, Words.ShareLinkShare(), $"share-send:{title}", () => SendTo(address)));
        }
        var menu = new MenuFlyout();
        void Item(bool offered, string word, string id, Action act)
        {
            if (!offered) return;
            var item = new MenuFlyoutItem { Text = word };
            AutomationProperties.SetAutomationId(item, $"share-{id}:{title}");
            item.Click += (_, _) => act();
            menu.Items.Add(item);
        }
        var l = link.shareId;
        Item(link.canEdit, Words.ShareEdit(), "edit", () => Act.Send(new Call.ShareEdit { share = share, link = l }));
        Item(link.canReactivate, Words.ShareReactivate(), "reactivate", () => Tell(new Call.ShareReactivate { share = share, link = l }, "Share.Reactivate"));
        Item(link.canRenew, Words.ShareRenew(), "renew", () => Act.Send(new Call.ShareAskRenew { share = share, link = l }));
        if (menu.Items.Count > 0 && (link.canSwitchOff || link.canDelete)) menu.Items.Add(new MenuFlyoutSeparator());
        Item(link.canSwitchOff, Words.ShareSwitchOff(), "switch-off", () => Tell(new Call.ShareSwitchOff { share = share, link = l }, "Share.SwitchOff"));
        Item(link.canDelete, Words.ShareDelete(), "delete", () => Act.Send(new Call.ShareAskDelete { share = share, link = l }));
        if (menu.Items.Count > 0)
        {
            var more = new Button { Content = new SymbolIcon(Symbol.More), Flyout = menu };
            AutomationProperties.SetName(more, Words.NodeMore());
            AutomationProperties.SetAutomationId(more, $"share-more:{title}");
            ToolTipService.SetToolTip(more, Words.NodeMore());
            commands.Children.Add(more);
        }
        return grid;
    }

    /// <summary>A renew's or a delete's question, in place: its answer sent, or the question cancelled.</summary>
    private static UIElement Ask(string title, string question, Call confirm, long share, string id)
    {
        var panel = new StackPanel { Spacing = 8 };
        panel.Children.Add(new TextBlock { Text = title, Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"] });
        panel.Children.Add(new TextBlock { Text = question, TextWrapping = TextWrapping.Wrap });
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        var yes = new Button { Content = title, Style = (Style)Application.Current.Resources["AccentButtonStyle"] };
        AutomationProperties.SetAutomationId(yes, $"share-{id}-confirm");
        yes.Click += (_, _) => Tell(confirm, $"Share.{id}");
        var no = new Button { Content = Words.EntryCancel() };
        AutomationProperties.SetAutomationId(no, $"share-{id}-cancel");
        no.Click += (_, _) => Act.Send(new Call.ShareCancelAsk { share = share });
        buttons.Children.Add(yes);
        buttons.Children.Add(no);
        panel.Children.Add(buttons);
        return panel;
    }

    /// <summary>The address just minted or renewed, shown once: Copy link, Share, and Done back to the list.</summary>
    private void DrawFresh(string address, long share)
    {
        _body.Children.Clear();
        _body.Children.Add(new TextBlock { Text = Words.ShareReady(), TextWrapping = TextWrapping.Wrap });
        var box = new TextBox { Text = address, IsReadOnly = true };
        AutomationProperties.SetAutomationId(box, "share-address");
        _body.Children.Add(box);
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        var copy = new Button { Content = Words.ShareCopy(), Style = (Style)Application.Current.Resources["AccentButtonStyle"] };
        AutomationProperties.SetAutomationId(copy, "share-fresh-copy");
        copy.Click += (_, _) => Copy(address);
        var send = new Button { Content = Words.ShareSend() };
        AutomationProperties.SetAutomationId(send, "share-fresh-send");
        send.Click += (_, _) => SendTo(address);
        var done = new Button { Content = Words.ShareDone() };
        AutomationProperties.SetAutomationId(done, "share-fresh-done");
        done.Click += (_, _) => Act.Send(new Call.ShareFreshSeen { share = share });
        buttons.Children.Add(copy);
        buttons.Children.Add(send);
        buttons.Children.Add(done);
        _body.Children.Add(buttons);
    }

    private void DrawForm(ContentDialog dialog, ShareShown s, ShownForm f, long share)
    {
        _drawing = true;
        var which = s.editing ?? "";
        if (_form is null || _formOf != which)
        {
            // a form just opened: its controls built and filled from the core's form once, then only what the person types
            _formOf = which;
            _form = BuildForm(share);
            _form.Label.Text = f.label;
            _form.Password.Password = "";
            _body.Children.Clear();
            _body.Children.Add(_form.Panel);
        }
        else if (!_body.Children.Contains(_form.Panel))
        {
            _body.Children.Clear();
            _body.Children.Add(_form.Panel);
        }
        var form = _form;
        var roles = f.roles.Select(r => r.role).ToList();
        if (!form.Role.Items.Cast<object>().Select(i => (i as RadioButton)?.Tag as string).SequenceEqual(roles))
        {
            form.Role.Items.Clear();
            foreach (var r in f.roles)
            {
                var b = new RadioButton { Content = Said.Key(r.word), Tag = r.role };
                AutomationProperties.SetAutomationId(b, $"share-role:{r.role}");
                form.Role.Items.Add(b);
            }
        }
        form.Role.Visibility = roles.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
        form.Role.SelectedIndex = roles.IndexOf(f.role);
        form.Hint.Text = Said.Key(f.hint);
        form.Label.Visibility = f.namesIt ? Visibility.Visible : Visibility.Collapsed;
        form.Label.Header = Said.Key(f.nameLabel);
        form.Lock.IsOn = f.Lock.on;
        form.Lock.IsEnabled = f.Lock.optional && !s.saving;
        ToolTipService.SetToolTip(form.Lock, Said.Notice(f.Lock.hint));
        form.Lock.Header = Words.SharePasswordSection() + " — " + Said.Notice(f.Lock.hint);
        form.Password.Visibility = f.Lock.on ? Visibility.Visible : Visibility.Collapsed;
        form.Password.PlaceholderText = f.Lock.kept ? Words.SharePasswordKeep() : "";
        form.Short.Visibility = f.Lock.on ? Visibility.Visible : Visibility.Collapsed;
        form.Short.Foreground = (Brush)Application.Current.Resources[f.Lock.tooShort ? "SystemFillColorCriticalBrush" : "TextFillColorSecondaryBrush"];
        form.End.IsOn = f.end.on;
        form.End.IsEnabled = f.end.optional && !s.saving;
        form.End.Header = Words.ShareExpirySection() + " — " + Said.Notice(f.end.hint);
        form.Day.Visibility = f.end.on ? Visibility.Visible : Visibility.Collapsed;
        form.Day.MinDate = ShareDays.ToDate(f.end.today);
        form.Day.MaxDate = f.end.lastDay is { } last ? ShareDays.ToDate(last) : ShareDays.ToDate(f.end.today + 365 * 50);
        form.Day.Date = ShareDays.ToDate(f.end.day);
        form.Problem.Visibility = s.problem is null ? Visibility.Collapsed : Visibility.Visible;
        form.Problem.Text = s.problem is { } p ? Said.Notice(p) : "";
        form.Panel.IsHitTestVisible = !s.saving;
        dialog.PrimaryButtonText = Said.Key(s.submit);
        dialog.IsPrimaryButtonEnabled = f.canSave && !s.saving;
        dialog.SecondaryButtonText = Words.EntryCancel();
        dialog.IsSecondaryButtonEnabled = !s.saving;
        _drawing = false;
    }

    private Form BuildForm(long share)
    {
        var role = new RadioButtons { Header = Words.ShareRole() };
        AutomationProperties.SetAutomationId(role, "share-role");
        role.SelectionChanged += (_, _) =>
        {
            if (_drawing || role.SelectedItem is not RadioButton { Tag: string r }) return;
            Act.Send(new Call.ShareRole { share = share, role = r });
        };
        var hint = new TextBlock { TextWrapping = TextWrapping.Wrap, Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"] };
        var label = new TextBox();
        AutomationProperties.SetAutomationId(label, "share-label");
        label.TextChanged += (_, _) => { if (!_drawing) Act.Send(new Call.ShareLabel { share = share, label = label.Text }); };
        // the switches carry no words of their own (WinUI's On/Off speak the system's language): the header says what
        // each turns on
        var lockOn = new ToggleSwitch { OnContent = "", OffContent = "" };
        AutomationProperties.SetAutomationId(lockOn, "share-lock");
        lockOn.Toggled += (_, _) => { if (!_drawing) Act.Send(new Call.ShareLockOn { share = share, on = lockOn.IsOn }); };
        var password = new PasswordBox { Header = Words.SharePasswordSection() };
        AutomationProperties.SetAutomationId(password, "share-password");
        password.PasswordChanged += (_, _) => { if (!_drawing) Act.Send(new Call.SharePassword { share = share, password = password.Password }); };
        var tooShort = new TextBlock { Text = Words.SharePasswordShort(10), Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"] };
        var end = new ToggleSwitch { OnContent = "", OffContent = "" };
        AutomationProperties.SetAutomationId(end, "share-end");
        end.Toggled += (_, _) => { if (!_drawing) Act.Send(new Call.ShareEndOn { share = share, on = end.IsOn }); };
        var day = new CalendarDatePicker { Header = Words.ShareExpiresOn() };
        AutomationProperties.SetAutomationId(day, "share-day");
        day.DateChanged += (_, args) =>
        {
            if (_drawing || args.NewDate is not { } date) return;
            Act.Send(new Call.ShareEndDay { share = share, day = ShareDays.ToDay(date) });
        };
        var problem = new TextBlock { TextWrapping = TextWrapping.Wrap, Foreground = (Brush)Application.Current.Resources["SystemFillColorCriticalBrush"] };
        AutomationProperties.SetAutomationId(problem, "share-problem");
        var panel = new StackPanel { Spacing = 12 };
        foreach (var c in new UIElement[] { role, hint, label, lockOn, password, tooShort, end, day, problem }) panel.Children.Add(c);
        return new Form { Panel = panel, Role = role, Hint = hint, Label = label, Lock = lockOn, Password = password, Short = tooShort, End = end, Day = day, Problem = problem };
    }

    /// <summary><paramref name="address"/> to the clipboard (a DataPackage), said in the log.</summary>
    private static void Copy(string address)
    {
        var package = new DataPackage();
        package.SetText(address);
        Clipboard.SetContent(package);
        Log.Write("share: a link's address copied");
    }

    /// <summary>Windows' share sheet for the app's window, handing it <paramref name="address"/> (DataTransferManager through
    /// its interop: an unpackaged app has no CoreWindow).</summary>
    private void SendTo(string address)
    {
        try
        {
            var hwnd = WinRT.Interop.WindowNative.GetWindowHandle(_window);
            var manager = DataTransferManagerInterop.GetForWindow(hwnd);
            _sharing = address;
            manager.DataRequested -= Requested;
            manager.DataRequested += Requested;
            DataTransferManagerInterop.ShowShareUIForWindow(hwnd);
            Log.Write("share: Windows' share sheet asked");
        }
        catch (Exception e)
        {
            Log.Write($"share: Windows' share sheet did not open ({e.GetType().Name}: {e.Message})");
        }
    }

    private void Requested(DataTransferManager sender, DataRequestedEventArgs args)
    {
        if (_sharing is not { } address) return;
        args.Request.Data.Properties.Title = _shown is { } s ? Said.Key(s.title) : address;
        args.Request.Data.SetText(address);
        if (Uri.TryCreate(address, UriKind.Absolute, out var uri)) args.Request.Data.SetWebLink(uri);
        Log.Write("share: Windows' share sheet took the link");
    }

    /// <summary>Sends <paramref name="call"/>; what it says, where it says something, goes under the dialog's list as the
    /// state's notice (the answer only to the log).</summary>
    private static async void Tell(Call call, string what)
    {
        try
        {
            var (answer, _) = await Core.CallAsync(call);
            Log.Write(answer is Answer.Said { notice: { } n } ? $"{what}: {n.msg}" : $"{what}: {answer.GetType().Name}");
        }
        catch (Exception e)
        {
            Log.Write($"{what} failed: {e.GetType().Name}");
        }
    }

    private static Button Icon(Symbol symbol, string label, string id, Action act)
    {
        var b = new Button { Content = new SymbolIcon(symbol) };
        AutomationProperties.SetName(b, label);
        AutomationProperties.SetAutomationId(b, id);
        ToolTipService.SetToolTip(b, label);
        b.Click += (_, _) => act();
        return b;
    }

    private static TextBlock Text(string text, string id, string brush)
    {
        var t = new TextBlock { Text = text, TextWrapping = TextWrapping.Wrap, Foreground = (Brush)Application.Current.Resources[brush] };
        AutomationProperties.SetAutomationId(t, id);
        return t;
    }
}
