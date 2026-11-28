// The app's ContentDialogs, Fluent's way and WinUI's rule: one ContentDialog at a time
// (a second ShowAsync while one is open throws), so every dialog goes through Show, which refuses a second while the
// first stands; a confirmation inside a dialog is asked in place (the space's trash's "Delete forever?", as Manage
// servers asks its password). Each dialog's content is built per open: a control reused under a new parent after its
// dialog closed crashed the second open (ADR 0123's Consequences, the Windows app).

namespace Veyq.Core;

using System;
using System.Threading.Tasks;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Veyq.Bridge.Wire;

internal static class Dialogs
{
    private static ContentDialog? _open;

    /// <summary>Whether a dialog is open now.</summary>
    public static bool Open => _open is not null;

    /// <summary>Shows <paramref name="dialog"/> over <paramref name="window"/>; None while another is open (said in the
    /// log), else the button the person pressed.</summary>
    public static async Task<ContentDialogResult> Show(Window window, ContentDialog dialog)
    {
        if (_open is not null)
        {
            Log.Write("a dialog was asked for while another was open: not shown");
            return ContentDialogResult.None;
        }
        dialog.XamlRoot = window.Content.XamlRoot;
        dialog.RequestedTheme = Appearance.Theme;
        _open = dialog;
        try
        {
            return await dialog.ShowAsync();
        }
        catch (Exception e)
        {
            Log.Write($"a dialog did not open ({e.GetType().Name})");
            return ContentDialogResult.None;
        }
        finally
        {
            _open = null;
        }
    }

    /// <summary>Closes the dialog open now, where there is one.</summary>
    public static void Close() => _open?.Hide();

    /// <summary>A name asked for (a rename, a new folder): <paramref name="title"/>, a box titled <paramref name="field"/>
    /// holding <paramref name="name"/>, <paramref name="problem"/> under it where the core refused the last one; the name
    /// typed, or null when it was cancelled.</summary>
    public static async Task<string?> AskName(Window window, string automationId, string title, string field, string name, string? problem, string confirm, UIElement? extra = null)
    {
        var box = new TextBox { Header = field, Text = name, MinWidth = 320 };
        AutomationProperties.SetAutomationId(box, "dialog-name");
        box.SelectAll();
        var content = new StackPanel { Spacing = 12 };
        content.Children.Add(box);
        // a choice beside the name (New Memory's "I was there")
        if (extra is not null) content.Children.Add(extra);
        if (problem is not null)
        {
            var said = new TextBlock
            {
                Text = problem,
                TextWrapping = TextWrapping.Wrap,
                Foreground = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["SystemFillColorCriticalBrush"],
            };
            AutomationProperties.SetAutomationId(said, "dialog-problem");
            content.Children.Add(said);
        }
        var dialog = new ContentDialog
        {
            Title = title,
            Content = content,
            PrimaryButtonText = confirm,
            CloseButtonText = Words.EntryCancel(),
            DefaultButton = ContentDialogButton.Primary,
            IsPrimaryButtonEnabled = name.Trim().Length > 0,
        };
        AutomationProperties.SetAutomationId(dialog, automationId);
        box.TextChanged += (_, _) => dialog.IsPrimaryButtonEnabled = box.Text.Trim().Length > 0;
        box.Loaded += (_, _) => box.Focus(FocusState.Programmatic);
        return await Show(window, dialog) == ContentDialogResult.Primary ? box.Text : null;
    }

    /// <summary>A name dialog whose save is the core's (ADR 0154 decision 1: New Folder, a rename, a guest's naming):
    /// <paramref name="save"/> runs on the dialog's primary button while the dialog stands; an answer with a
    /// <c>problem</c> keeps it open with that word under the field, any other closes it and is handed back (its
    /// <c>notice</c> the caller's to say; <c>queued</c> reads as made). A save that threw keeps the dialog open — nothing
    /// turns a failure into "made". Null when the dialog was cancelled. <paramref name="body"/> stands above the field
    /// (a guest's hint and the record's notice), <paramref name="placeholder"/> in the empty field.</summary>
    public static async Task<Answer.Named?> AskNamed(Window window, string automationId, string title, string field, string name,
        string confirm, Func<string, Task<Answer.Named?>> save, UIElement? body = null, string? placeholder = null)
    {
        var box = new TextBox { Header = field, Text = name, MinWidth = 320, PlaceholderText = placeholder ?? "" };
        AutomationProperties.SetAutomationId(box, "dialog-name");
        box.SelectAll();
        var content = new StackPanel { Spacing = 12, MaxWidth = 440 };
        if (body is not null) content.Children.Add(body);
        content.Children.Add(box);
        var said = new TextBlock
        {
            TextWrapping = TextWrapping.Wrap,
            Foreground = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["SystemFillColorCriticalBrush"],
            Visibility = Visibility.Collapsed,
        };
        AutomationProperties.SetAutomationId(said, "dialog-problem");
        // the problem is announced as it appears, as a field's error is
        AutomationProperties.SetLiveSetting(said, Microsoft.UI.Xaml.Automation.Peers.AutomationLiveSetting.Assertive);
        content.Children.Add(said);
        var dialog = new ContentDialog
        {
            Title = title,
            Content = content,
            PrimaryButtonText = confirm,
            CloseButtonText = Words.EntryCancel(),
            DefaultButton = ContentDialogButton.Primary,
            IsPrimaryButtonEnabled = name.Trim().Length > 0,
        };
        AutomationProperties.SetAutomationId(dialog, automationId);
        box.TextChanged += (_, _) => dialog.IsPrimaryButtonEnabled = box.Text.Trim().Length > 0;
        box.Loaded += (_, _) => box.Focus(FocusState.Programmatic);
        Answer.Named? outcome = null;
        dialog.PrimaryButtonClick += async (_, args) =>
        {
            var deferral = args.GetDeferral();
            try
            {
                dialog.IsPrimaryButtonEnabled = false;
                var answer = await save(box.Text);
                if (answer is { problem: { } problem })
                {
                    args.Cancel = true;
                    said.Text = Said.Notice(problem);
                    said.Visibility = Visibility.Visible;
                    Log.Write($"{automationId}: kept open, {problem.msg}");
                }
                else if (answer is null)
                {
                    // the call did not answer a name's outcome: nothing was made, the dialog stays for another try or Cancel
                    args.Cancel = true;
                    Log.Write($"{automationId}: no outcome, kept open");
                }
                else outcome = answer;
            }
            catch (Exception e)
            {
                args.Cancel = true;
                Log.Write($"{automationId}: the save failed ({e.GetType().Name}), kept open");
            }
            finally
            {
                dialog.IsPrimaryButtonEnabled = box.Text.Trim().Length > 0;
                deferral.Complete();
            }
        };
        return await Show(window, dialog) == ContentDialogResult.Primary ? outcome : null;
    }

    /// <summary>The ask before <paramref name="loss"/> deletes writes made offline that wait (Queued.Ask, ADR 0153
    /// decision 2): null where none waits (the action goes on as it would), else whether the person confirmed the core's
    /// question — its own confirm word ("Sign out anyway"), Cancel the other — titled <paramref name="title"/>.</summary>
    public static async Task<bool?> AskLoss(Window window, QueuedLoss loss, string title)
    {
        var (answer, _) = await Veyq.Bridge.Core.CallAsync(new Call.QueuedAsk { loss = loss });
        if (answer is not Answer.QueuedAsked { ask: { } ask }) return null;
        Log.Write($"queued ask before {loss}");
        return await Confirm(window, "queued-loss-ask", title, Said.Notice(ask.question), Said.Key(ask.confirm));
    }

    /// <summary>A name dialog's save as a call answered <c>Answer.Named</c>: that answer, or null (said in the log) where
    /// the call answered anything else.</summary>
    public static async Task<Answer.Named?> Named(Call call, string what)
    {
        var (answer, _) = await Veyq.Bridge.Core.CallAsync(call);
        if (answer is Answer.Named named)
        {
            Log.Write($"{what}: {(named.problem?.msg ?? named.notice?.msg ?? (named.queued ? "queued" : "made"))}");
            return named;
        }
        Log.Write($"{what} answered {answer.GetType().Name}");
        return null;
    }

    /// <summary>A question whose answer is the action asked for (a settle-up recorded): <paramref name="title"/>,
    /// <paramref name="question"/>, and <paramref name="confirm"/> the default, as Fluent's dialogs put the expected
    /// answer first; whether it was confirmed.</summary>
    public static async Task<bool> Ask(Window window, string automationId, string title, string question, string confirm)
    {
        var dialog = new ContentDialog
        {
            Title = title,
            Content = new TextBlock { Text = question, TextWrapping = TextWrapping.Wrap },
            PrimaryButtonText = confirm,
            CloseButtonText = Words.EntryCancel(),
            DefaultButton = ContentDialogButton.Primary,
        };
        AutomationProperties.SetAutomationId(dialog, automationId);
        return await Show(window, dialog) == ContentDialogResult.Primary;
    }

    /// <summary>A question with a destructive answer: <paramref name="title"/>, <paramref name="question"/>, and
    /// <paramref name="confirm"/> (Cancel the default); whether it was confirmed.</summary>
    public static async Task<bool> Confirm(Window window, string automationId, string title, string question, string confirm)
    {
        var dialog = new ContentDialog
        {
            Title = title,
            Content = new TextBlock { Text = question, TextWrapping = TextWrapping.Wrap },
            PrimaryButtonText = confirm,
            CloseButtonText = Words.EntryCancel(),
            DefaultButton = ContentDialogButton.Close,
        };
        AutomationProperties.SetAutomationId(dialog, automationId);
        return await Show(window, dialog) == ContentDialogResult.Primary;
    }
}
