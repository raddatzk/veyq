// *Start a list over* (core's TrustReset, ADR 0028 decision 7; the web's TrustResetPanel and TrustResetDialog): one
// ContentDialog over Settings, Fluent's way of a confirmation that stays in its window — the rows of each list this device
// refuses (its name, why, *Start over…*), and in its place, once one is chosen, the question: what it costs, that the
// server can wait, the acknowledgement (unticked at every open) and the primary *Start over*, enabled only once
// acknowledged; *Cancel* goes back to the rows. WinUI shows one ContentDialog at a time, so the question is the same
// dialog's content, not a second one. Every row, word and outcome is the core's (TrustReset.*, Watched.TrustReset); no
// password or key is asked. Closing it closes the flow (TrustReset.Close). Android's TrustResetScreen.kt, Apple's
// TrustResetView.swift, Linux's trust_reset.rs.

namespace Veyq.Core;

using System;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Veyq.Bridge;
using Veyq.Bridge.Wire;

internal static class TrustResetDialog
{
    /// <summary>Opens the page over <paramref name="window"/> for the account <paramref name="connection"/> names.</summary>
    public static async void Open(Window window, string connection)
    {
        long? flow = null;
        Watch? watch = null;
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.TrustResetOpen { connection = connection });
            if (answer is not Answer.FlowOpened { flow: var id })
            {
                Log.Write($"TrustReset.Open answered {answer?.GetType().Name}");
                return;
            }
            flow = id;
            var body = new StackPanel { Spacing = 12 };
            var dialog = new ContentDialog
            {
                Title = Words.TrustResetTitle(),
                Content = new ScrollViewer { Content = body, MaxHeight = 560 },
                CloseButtonText = Words.ShareDone(),
                DefaultButton = ContentDialogButton.Close,
            };
            AutomationProperties.SetAutomationId(dialog, "trust-reset");
            TrustResetState? state = null;
            var drawing = false;
            void Draw(TrustResetState s)
            {
                state = s;
                Log.Write($"trust reset: loading {s.loading}, rows {s.rows.Count}, said {s.said ?? "none"}, asking {s.asking is not null}");
                body.Children.Clear();
                if (s.asking is { } ask)
                {
                    dialog.Title = Said.Key(ask.title);
                    body.Children.Add(TermsText.Line(Said.Key(ask.cost), false));
                    body.Children.Add(TermsText.Line(Words.TrustResetServerCanWait(), true));
                    var check = new CheckBox { Content = Words.TrustResetAcknowledge(), IsEnabled = !ask.working };
                    AutomationProperties.SetAutomationId(check, "trust-reset-acknowledge");
                    drawing = true;
                    check.IsChecked = ask.acknowledged;
                    drawing = false;
                    check.Checked += (_, _) => { if (!drawing) Act.Send(new Call.TrustResetAcknowledge { flow = id, on = true }); };
                    check.Unchecked += (_, _) => { if (!drawing) Act.Send(new Call.TrustResetAcknowledge { flow = id, on = false }); };
                    body.Children.Add(check);
                    if (ask.failed is { } failed)
                    {
                        var line = TermsText.Line(Said.Key(failed), false);
                        line.Foreground = (Brush)Application.Current.Resources["SystemFillColorCriticalBrush"];
                        body.Children.Add(line);
                    }
                    if (ask.working) body.Children.Add(new ProgressBar { IsIndeterminate = true });
                    dialog.PrimaryButtonText = Words.TrustResetConfirm();
                    dialog.IsPrimaryButtonEnabled = ask.acknowledged && !ask.working;
                    dialog.CloseButtonText = Words.SigninCancel();
                    dialog.IsSecondaryButtonEnabled = false;
                    return;
                }
                dialog.Title = Words.TrustResetTitle();
                dialog.PrimaryButtonText = "";
                dialog.CloseButtonText = Words.ShareDone();
                body.Children.Add(TermsText.Line(Words.TrustResetSub(), true));
                if (s.said is { } said) body.Children.Add(TermsText.Line(Said.Key(said), false));
                if (s.loading)
                {
                    body.Children.Add(new ProgressRing { IsActive = true, Width = 20, Height = 20, HorizontalAlignment = HorizontalAlignment.Left });
                    return;
                }
                if (s.loadFailed is { } why)
                {
                    body.Children.Add(TermsText.Line(Said.Key(why), false));
                    return;
                }
                if (s.none)
                {
                    body.Children.Add(TermsText.Line(Words.TrustResetNone(), false));
                    return;
                }
                foreach (var row in s.rows)
                {
                    var card = new StackPanel { Spacing = 4, Padding = new Thickness(0, 6, 0, 6) };
                    var title = TermsText.Line(Said.Key(row.title), false);
                    title.Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"];
                    card.Children.Add(title);
                    card.Children.Add(TermsText.Line(Said.Key(row.why), true));
                    var start = new Button { Content = Words.TrustResetAction() };
                    AutomationProperties.SetAutomationId(start, $"trust-reset-ask:{row.document}");
                    var document = row.document;
                    start.Click += (_, _) => Act.Send(new Call.TrustResetAsk { flow = id, document = document });
                    card.Children.Add(start);
                    body.Children.Add(card);
                }
            }
            dialog.PrimaryButtonClick += (_, args) =>
            {
                // the dialog stays: the outcome, or the failure, is drawn in it
                args.Cancel = true;
                Act.Send(new Call.TrustResetConfirm { flow = id });
            };
            dialog.CloseButtonClick += (_, args) =>
            {
                if (state?.asking is null) return;
                // the question's Cancel goes back to the rows, never closes the page; refused while it runs
                args.Cancel = true;
                if (state.asking.working) return;
                Act.Send(new Call.TrustResetCancel { flow = id });
            };
            watch = Core.Watch(new Watched.TrustReset { flow = id }, update =>
            {
                if (update is not Update.TrustReset { shown: var s }) return;
                if (s is null)
                {
                    dialog.Hide();
                    return;
                }
                Draw(s);
            }, e => Log.Write($"a trust reset update: {e.Message}"));
            await Dialogs.Show(window, dialog);
        }
        catch (Exception e)
        {
            Log.Write($"the trust reset did not open ({e.GetType().Name})");
        }
        finally
        {
            watch?.Dispose();
            if (flow is { } f) Act.Send(new Call.TrustResetClose { flow = f });
        }
    }
}
