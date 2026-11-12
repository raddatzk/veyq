// An entry's history (the web's EntryHistory.vue; Linux's entry_history.rs), Fluent's way: a ContentDialog headed by
// the entry's title and amount, listing every revision the Pot holds, newest first — what it said, who wrote it and when
// (Pot.History, each line the core's historyLine). A deleted entry says so, until when it can be restored (its
// receipts' erasure, read on the core's clock) and offers Restore as the dialog's primary button (Pot.Restore); the
// dialog closes once the restore was stored, and a refusal stays in it as an InfoBar. A deleted entry's row opens this
// dialog; a live entry's opens the entry editor (EntryEditorPage), whose History expander lists the same.
using System;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Veyq.Bridge;
using Veyq.Bridge.Wire;

namespace Veyq;

internal static class EntryHistoryDialog
{
    /// <summary>Opens entry <paramref name="entry"/>'s history in Pot <paramref name="pot"/> over
    /// <paramref name="window"/>, headed by <paramref name="title"/> and <paramref name="amount"/>.</summary>
    public static async void Open(Window window, long pot, string entry, string title, string? amount, Action<bool, string> say)
    {
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.PotHistory { pot = pot, entry = entry });
            if (answer is not Answer.EntryHistory h)
            {
                Log.Write($"Pot.History answered {answer.GetType().Name}");
                return;
            }
            Log.Write($"entry history: {h.rows.Count} revision(s), deleted {h.deleted}, restorable {h.restorable}");
            // built per open: a control's content is never reused under a new dialog
            var column = new StackPanel { Spacing = 8 };
            if (amount is not null) column.Children.Add(Text(amount, "history-amount", "BodyStrongTextBlockStyle"));
            if (h.deleted)
            {
                column.Children.Add(Text(Words.EntryDeletedHint(), "history-deleted", "BodyTextBlockStyle"));
                if (h.until is { } until)
                {
                    column.Children.Add(Text(h.restorable ? Words.EntryRestorableUntil(Said.Moment(until)) : Words.EntryNotRestorable(), "history-until", "BodyTextBlockStyle"));
                }
            }
            var heading = Text(Words.HistoryShow(), "history-heading", "BodyStrongTextBlockStyle");
            heading.Margin = new Thickness(0, 8, 0, 0);
            AutomationProperties.SetHeadingLevel(heading, Microsoft.UI.Xaml.Automation.Peers.AutomationHeadingLevel.Level2);
            column.Children.Add(heading);
            var i = 0;
            foreach (var r in h.rows)
            {
                var row = new StackPanel { Padding = new Thickness(0, 4, 0, 4) };
                var line = Said.Line(r.line);
                var by = Words.HistoryBy(r.writer ?? Words.HistoryUnknownWriter(), Said.Moment(r.at));
                row.Children.Add(new TextBlock { Text = line, TextWrapping = TextWrapping.Wrap });
                row.Children.Add(new TextBlock
                {
                    Text = by,
                    Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"],
                    Foreground = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["TextFillColorSecondaryBrush"],
                });
                AutomationProperties.SetAutomationId(row, $"history:{i++}");
                AutomationProperties.SetName(row, $"{line}, {by}");
                column.Children.Add(row);
            }
            var refused = new InfoBar { IsOpen = false, IsClosable = false, Severity = InfoBarSeverity.Error };
            AutomationProperties.SetAutomationId(refused, "history-refused");
            column.Children.Add(refused);
            var dialog = new ContentDialog
            {
                Title = title,
                Content = new ScrollViewer { Content = column, HorizontalScrollMode = ScrollMode.Disabled, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled },
                CloseButtonText = Words.ReceiptsClose(),
                DefaultButton = ContentDialogButton.Close,
            };
            AutomationProperties.SetAutomationId(dialog, "entry-history");
            if (h.restorable)
            {
                dialog.PrimaryButtonText = Words.EntryRestore();
                dialog.DefaultButton = ContentDialogButton.Primary;
                dialog.PrimaryButtonClick += async (d, e) =>
                {
                    // the dialog stays open until the restore is stored; a refusal is said in it
                    var deferral = e.GetDeferral();
                    try
                    {
                        var (restored, _) = await Core.CallAsync(new Call.PotRestore { pot = pot, entry = entry });
                        Log.Write($"pot restore: {(restored is Answer.Said { notice: { } n } ? n.msg : restored.GetType().Name)}");
                        if (restored is Answer.Said { notice: null }) return;
                        e.Cancel = true;
                        refused.Message = restored is Answer.Said { notice: { } why } ? Said.Notice(why) : Words.PeopleFailed();
                        refused.IsOpen = true;
                    }
                    catch (Exception ex)
                    {
                        Log.Write($"Pot.Restore failed: {ex.GetType().Name}");
                        e.Cancel = true;
                    }
                    finally
                    {
                        deferral.Complete();
                    }
                };
            }
            await Dialogs.Show(window, dialog);
        }
        catch (Exception e)
        {
            // an exception out of an async void would end the app in combase, with nothing in the log
            Log.Write($"the entry's history failed: {e.GetType().Name}: {e.Message}");
            say(false, Words.PeopleFailed());
        }
    }

    private static TextBlock Text(string text, string id, string style)
    {
        var block = new TextBlock { Text = text, TextWrapping = TextWrapping.Wrap, Style = (Style)Application.Current.Resources[style] };
        AutomationProperties.SetAutomationId(block, id);
        return block;
    }
}
