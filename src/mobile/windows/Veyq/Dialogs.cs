// The app's ContentDialogs (the header CR's Phase 2), Fluent's way and WinUI's rule: one ContentDialog at a time
// (a second ShowAsync while one is open throws), so every dialog goes through Show, which refuses a second while the
// first stands; a confirmation inside a dialog is asked in place (the space's trash's "Delete forever?", as Manage
// servers asks its password). Each dialog's content is built per open: a control reused under a new parent after its
// dialog closed crashed the second open (ADR 0123's Consequences, the Windows app).
using System;
using System.Threading.Tasks;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;

namespace Veyq;

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
        dialog.RequestedTheme = DebugEnv.Theme;
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
    public static async Task<string?> AskName(Window window, string automationId, string title, string field, string name, string? problem, string confirm)
    {
        var box = new TextBox { Header = field, Text = name, MinWidth = 320 };
        AutomationProperties.SetAutomationId(box, "dialog-name");
        box.SelectAll();
        var content = new StackPanel { Spacing = 12 };
        content.Children.Add(box);
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
