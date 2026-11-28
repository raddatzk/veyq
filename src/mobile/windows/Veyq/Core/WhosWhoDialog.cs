// Settings' Who's who (core's SettingsSection.WHOS_WHO over PersonTrust.whosWho, the web's PeopleOverviewPanel; inbox
// #967): every other member of the open space with the one state their key has here — its badge, New beside it, who
// verified them ("you" first), a new identity, a replaced key —, in the core's order, in a ContentDialog over the page as
// Settings' Terms is. A row, and its "Compare safety numbers" where this account has not verified them, opens their
// profile in the shell, where the compare lives; the core decides every word and whether the compare is offered.
// Linux's core_ui::whos_who.

namespace Veyq.Core;

using System;
using System.Linq;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Veyq.Bridge;
using Veyq.Bridge.Wire;

internal static class WhosWhoDialog
{
    /// <summary>Opens the list over <paramref name="window"/>; <paramref name="openPerson"/> leaves Settings for a person's
    /// profile once the dialog has closed.</summary>
    public static async void Open(Window window, Action<string> openPerson, Action? trustReset = null)
    {
        try
        {
            var body = new StackPanel { Spacing = 12 };
            var dialog = new ContentDialog
            {
                Title = Words.WhosWhoTitle(),
                Content = new ScrollViewer { Content = body, MaxHeight = 560 },
                CloseButtonText = Words.ShareDone(),
                DefaultButton = ContentDialogButton.Close,
            };
            AutomationProperties.SetAutomationId(dialog, "whos-who");
            string? chosen = null;
            var resetting = false;
            void Choose(string userId)
            {
                chosen = userId;
                dialog.Hide();
            }
            async void Load()
            {
                body.Children.Clear();
                body.Children.Add(TermsText.Line(Words.WhosWhoSub(), true));
                var ring = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
                ring.Children.Add(new ProgressRing { IsActive = true, Width = 20, Height = 20 });
                ring.Children.Add(TermsText.Line(Words.WhosWhoLoading(), true));
                body.Children.Add(ring);
                var (answer, _) = await Core.CallAsync(new Call.TrustWhosWho());
                body.Children.Remove(ring);
                if (answer is not Answer.WhosWho { whosWho: { } read })
                {
                    Log.Write($"Trust.WhosWho answered {answer?.GetType().Name}");
                    Said(body, Words.WhosWhoLoadFailed(), retry: true, Load);
                    return;
                }
                if (read.said is { } said)
                {
                    Said(body, ReceivedText.Said(said), read.retry, Load);
                    // the web's `TrustResetLink`: the one way back from a refused pin store (ADR 0028 decision 7)
                    if (read.trustReset && trustReset is not null)
                    {
                        var start = new HyperlinkButton { Content = Words.TrustResetLink(), Padding = new Thickness(0) };
                        AutomationProperties.SetAutomationId(start, "whos-who-trust-reset");
                        start.Click += (_, _) =>
                        {
                            resetting = true;
                            dialog.Hide();
                        };
                        body.Children.Add(start);
                    }
                    return;
                }
                var list = new StackPanel { Spacing = 4 };
                AutomationProperties.SetAutomationId(list, "whos-who-list");
                foreach (var row in read.people) list.Children.Add(Row(row, Choose));
                body.Children.Add(list);
            }
            Load();
            await Dialogs.Show(window, dialog);
            if (chosen is { } id) openPerson(id);
            else if (resetting) trustReset?.Invoke();
        }
        catch (Exception e)
        {
            Log.Write($"Who's who did not open ({e.GetType().Name})");
        }
    }

    private static void Said(StackPanel body, string text, bool retry, Action again)
    {
        var line = TermsText.Line(text, false);
        AutomationProperties.SetAutomationId(line, "whos-who-said");
        body.Children.Add(line);
        if (!retry) return;
        var button = new Button { Content = Words.PeopleRetry() };
        AutomationProperties.SetAutomationId(button, "whos-who-retry");
        button.Click += (_, _) => again();
        body.Children.Add(button);
    }

    /// <summary>One member: the picture, the name (a link to the profile) and @username, the state's badge and New, then the
    /// lines the core says beside them; "Compare safety numbers" where the core offers it.</summary>
    private static UIElement Row(Other row, Action<string> open)
    {
        var grid = new Grid { ColumnSpacing = 12, Padding = new Thickness(0, 6, 0, 6) };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        var picture = Avatars.Person(row.name, row.userId, 40);
        picture.VerticalAlignment = VerticalAlignment.Top;
        grid.Children.Add(picture);
        var column = new StackPanel { Spacing = 2 };
        Grid.SetColumn(column, 1);
        var name = new HyperlinkButton { Content = row.name, Padding = new Thickness(0) };
        AutomationProperties.SetName(name, Words.WhosWhoOpenProfile(row.name));
        AutomationProperties.SetAutomationId(name, $"whos-who-{row.userId}");
        name.Click += (_, _) => open(row.userId);
        column.Children.Add(name);
        column.Children.Add(TermsText.Line(row.subtitle, true));
        var badges = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        if (row.stateWord is { } word)
        {
            var brush = row.state switch
            {
                WhosWhoState.CantConfirm => "SystemFillColorCriticalBrush",
                WhosWhoState.Verified => "SystemFillColorSuccessBrush",
                WhosWhoState.NotVerified => "TextFillColorSecondaryBrush",
                WhosWhoState.Unchecked => "TextFillColorSecondaryBrush",
            };
            var badge = new TextBlock { Text = ReceivedText.Said(word), Foreground = (Brush)Application.Current.Resources[brush], Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"] };
            AutomationProperties.SetAutomationId(badge, "whos-who-state");
            badges.Children.Add(badge);
        }
        if (row.isNew) badges.Children.Add(new TextBlock { Text = Words.SafetyStateNew(), Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"] });
        if (badges.Children.Count > 0) column.Children.Add(badges);
        if (row.note is { } note) column.Children.Add(Caption(ReceivedText.Said(note)));
        if (row.state == WhosWhoState.Verified)
        {
            var names = (row.verifiedByMe ? new[] { Words.SafetyVerifiedByYou() } : []).Concat(row.verifiedBy);
            column.Children.Add(Caption(Words.SafetyVerifiedBy(string.Join(", ", names))));
        }
        if (row.rotatedText is { } at) column.Children.Add(Caption(Words.TrustRotated(at)));
        if (row.replaced) column.Children.Add(Caption(Words.SafetyReplaced()));
        if (row.compare)
        {
            var compare = new Button { Content = Words.WhosWhoCompare(), Margin = new Thickness(0, 4, 0, 0) };
            AutomationProperties.SetAutomationId(compare, $"whos-who-compare-{row.userId}");
            compare.Click += (_, _) => open(row.userId);
            column.Children.Add(compare);
        }
        grid.Children.Add(column);
        return grid;
    }

    private static TextBlock Caption(string text)
    {
        var t = TermsText.Line(text, true);
        t.Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"];
        return t;
    }
}
