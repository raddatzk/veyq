// New → Bookmark (ADR 0150 decision 6; the web's BookmarkCreateDialog.vue, uc.sharing.keep-a-received-link;
// cr-app-received-links phase 10, inbox #1020), Fluent's way — Linux's bookmark_create.rs, in WinUI: a ContentDialog with
// the link — checked by the core as it is typed (Bookmark.Check: why it is no link to keep, the host the name defaults
// to) —, its password, its name (the host where none is typed), and in a group space the line that everyone who opens
// the place opens the link. Keep link sends it whole (Bookmark.Create), the core sealing the meta; a save that did not
// go through says so and keeps the dialog. Nothing of the link is kept here once the dialog closes.

namespace Veyq.Core;

using System;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Veyq.Bridge;
using Veyq.Bridge.Wire;

internal static class BookmarkCreateDialog
{
    /// <summary>The dialog over <paramref name="window"/>, keeping the link in <paramref name="scopeId"/> under
    /// <paramref name="parentId"/> (null: the space's top); <paramref name="made"/> is told the bookmark created.</summary>
    public static async void Open(Window window, string scopeId, string? parentId, Action<NodeAt> made)
    {
        try
        {
            var link = new TextBox { Header = Words.BookmarkLinkLabel(), MinWidth = 380 };
            AutomationProperties.SetAutomationId(link, "bookmark-link");
            var password = new PasswordBox { Header = Words.BookmarkPasswordLabel() };
            AutomationProperties.SetAutomationId(password, "bookmark-password");
            var hint = Caption(Words.BookmarkPasswordHint(), "bookmark-password-hint");
            var name = new TextBox { Header = Words.NodeName() };
            AutomationProperties.SetAutomationId(name, "bookmark-name");
            var problem = Critical("bookmark-problem");
            var groupLine = Caption(Words.BookmarkWhoCanOpen(), "bookmark-who-can-open");
            groupLine.Visibility = Visibility.Collapsed;
            var failed = Critical("bookmark-failed");
            failed.Text = Words.BookmarkCreateFailed();
            var body = new StackPanel { Spacing = 12, MaxWidth = 460 };
            body.Children.Add(new TextBlock { Text = Words.BookmarkCreateIntro(), TextWrapping = TextWrapping.Wrap });
            foreach (var e in new UIElement[] { link, problem, password, hint, name, groupLine, failed }) body.Children.Add(e);
            var dialog = new ContentDialog
            {
                Title = Words.BookmarkCreateTitle(),
                Content = new ScrollViewer { Content = body, VerticalScrollBarVisibility = ScrollBarVisibility.Auto },
                PrimaryButtonText = Words.BookmarkSave(),
                CloseButtonText = Words.EntryCancel(),
                DefaultButton = ContentDialogButton.Primary,
                IsPrimaryButtonEnabled = false,
            };
            AutomationProperties.SetAutomationId(dialog, "bookmark-create");
            // each change checked by the core; only the last one's answer counts
            var asked = 0;
            link.TextChanged += async (_, _) =>
            {
                var mine = ++asked;
                failed.Visibility = Visibility.Collapsed;
                try
                {
                    var (answer, _) = await Core.CallAsync(new Call.BookmarkCheck { scopeId = scopeId, text = link.Text });
                    if (mine != asked || answer is not Answer.BookmarkChecked c) return;
                    problem.Text = c.problem is { } p ? ReceivedText.Said(p) : "";
                    problem.Visibility = c.problem is null ? Visibility.Collapsed : Visibility.Visible;
                    groupLine.Visibility = c.groupSpace ? Visibility.Visible : Visibility.Collapsed;
                    // the name defaults to the host (the core's rule): its placeholder while none is typed
                    name.PlaceholderText = c.host ?? "";
                    dialog.IsPrimaryButtonEnabled = c.problem is null && c.host is not null;
                }
                catch (Exception e)
                {
                    Log.Write($"the bookmark's link check failed ({e.GetType().Name})");
                }
            };
            link.Loaded += (_, _) => link.Focus(FocusState.Programmatic);
            NodeAt? created = null;
            dialog.PrimaryButtonClick += async (_, args) =>
            {
                var deferral = args.GetDeferral();
                try
                {
                    var (answer, _) = await Core.CallAsync(new Call.BookmarkCreate
                    {
                        scopeId = scopeId, parentId = parentId, text = link.Text, password = password.Password, name = name.Text.Trim(),
                    });
                    if (answer is Answer.Created { node: { } node })
                    {
                        Log.Write("new bookmark: made");
                        created = node;
                    }
                    else
                    {
                        args.Cancel = true;
                        failed.Visibility = Visibility.Visible;
                    }
                }
                catch (Exception e)
                {
                    args.Cancel = true;
                    failed.Visibility = Visibility.Visible;
                    Log.Write($"new bookmark failed ({e.GetType().Name})");
                }
                finally
                {
                    deferral.Complete();
                }
            };
            await Dialogs.Show(window, dialog);
            if (created is { } bookmark) made(bookmark);
        }
        catch (Exception e)
        {
            // an exception out of an async void would end the app in combase, with nothing in the log
            Log.Write($"New bookmark failed ({e.GetType().Name})");
        }
    }

    private static TextBlock Caption(string text, string automationId)
    {
        var caption = new TextBlock
        {
            Text = text,
            TextWrapping = TextWrapping.Wrap,
            Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"],
            Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"],
        };
        AutomationProperties.SetAutomationId(caption, automationId);
        return caption;
    }

    private static TextBlock Critical(string automationId)
    {
        var text = new TextBlock { TextWrapping = TextWrapping.Wrap, Visibility = Visibility.Collapsed };
        text.Foreground = (Brush)Application.Current.Resources["SystemFillColorCriticalBrush"];
        AutomationProperties.SetAutomationId(text, automationId);
        return text;
    }
}
