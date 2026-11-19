// The safety number on a person's page (ADR 0148 decision 3; uc.spaces.enforce-verified-identities; the web's
// MySafetyNumber and PersonSafetyNumber): a section below the name. One's own: Show → a ContentDialog with the digits,
// the chain code and the code another member scans (Trust.Mine, Trust.MineCode: the core's QR as a PNG). Another member's:
// their state as the core reads it (Trust.Person: not verified, verified by …, new, rotated, replaced, can't confirm) and
// Compare → a ContentDialog with their digits and chain code, "Scan their code" where there is a camera (a SAFETY scan,
// taking only a veyqid: code, compared by the core: Trust.CompareScanned) and "They match" / "They don't match"
// (Trust.CompareDigits); a match verifies them. A safety number is no secret: no guard on the window. Linux
// draws the same.
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices.WindowsRuntime;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Veyq.Bridge;
using Veyq.Bridge.Wire;
using Windows.Storage.Streams;

namespace Veyq;

internal sealed class SafetyNumberSection
{
    private readonly Window _window;
    private readonly string _userId;
    private readonly bool _mine;
    private readonly Func<string> _name;
    private readonly StackPanel _panel = new() { Spacing = 8, Margin = new Thickness(0, 12, 0, 0) };
    private readonly StackPanel _state = new() { Spacing = 4 };
    private bool _closed;

    public UIElement View => _panel;

    /// <summary>The section of <paramref name="userId"/>'s page: <paramref name="mine"/> for one's own; <paramref name="name"/>
    /// the person's name as the page says it now.</summary>
    public SafetyNumberSection(Window window, string userId, bool mine, Func<string> name)
    {
        _window = window;
        _userId = userId;
        _mine = mine;
        _name = name;
        var heading = new TextBlock { Text = mine ? Words.SafetyMineTitle() : Words.SafetyTheirsTitle(), Style = (Style)Application.Current.Resources["SubtitleTextBlockStyle"] };
        AutomationProperties.SetHeadingLevel(heading, Microsoft.UI.Xaml.Automation.Peers.AutomationHeadingLevel.Level2);
        AutomationProperties.SetAutomationId(heading, "safety-heading");
        _panel.Children.Add(heading);
        _panel.Children.Add(_state);
        if (mine)
        {
            var show = new Button { Content = Words.SafetyShow() };
            AutomationProperties.SetAutomationId(show, "safety-show");
            show.Click += (_, _) => ShowMine();
            _panel.Children.Add(show);
        }
        else Read();
    }

    public void Close() => _closed = true;

    private static Brush Brush(string key) => (Brush)Application.Current.Resources[key];

    private static TextBlock Line(string text, string id, bool secondary = false)
    {
        var t = new TextBlock { Text = text, TextWrapping = TextWrapping.Wrap };
        if (secondary) t.Foreground = Brush("TextFillColorSecondaryBrush");
        AutomationProperties.SetAutomationId(t, id);
        return t;
    }

    /// <summary>Their state read anew (after a compare too).</summary>
    private async void Read()
    {
        _state.Children.Clear();
        _state.Children.Add(new ProgressRing { IsActive = true, Width = 20, Height = 20, HorizontalAlignment = HorizontalAlignment.Left });
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.TrustPerson { userId = _userId });
            if (_closed) return;
            _state.Children.Clear();
            if (answer is not Answer.PersonTrust { shown: { } shown })
            {
                Log.Write($"Trust.Person answered {(answer is Answer.Refused r ? $"Refused {r.why}" : answer.GetType().Name)}");
                _state.Children.Add(Line(Words.SafetyLoadError(), "safety-load-error", secondary: true));
                return;
            }
            Log.Write($"safety: anomaly {shown.anomaly}, number {shown.number is not null}, new {shown.isNew}, by me {shown.verifiedByMe}, by {shown.verifiedBy.Count}, rotated {shown.rotatedAt is not null}, replaced {shown.replaced}");
            Status(shown);
            if (shown.number is not null)
            {
                var compare = new Button { Content = Words.SafetyOpen() };
                AutomationProperties.SetAutomationId(compare, "safety-compare");
                compare.Click += (_, _) => Compare();
                _state.Children.Add(compare);
            }
        }
        catch (Exception e)
        {
            Log.Write($"the safety number was not read: {e.GetType().Name}");
        }
    }

    /// <summary>The state's lines (the web's PersonSafetyNumber).</summary>
    private void Status(Shown s)
    {
        if (s.anomaly)
        {
            var anomaly = Line(Words.TrustAnomaly(), "safety-state");
            anomaly.Foreground = Brush("SystemFillColorCriticalBrush");
            _state.Children.Add(anomaly);
            _state.Children.Add(Line(Words.TrustAnomalyNote(), "safety-anomaly-note", secondary: true));
            return;
        }
        var verified = s.verifiedByMe || s.verifiedBy.Count > 0;
        var state = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        state.Children.Add(new FontIcon { Glyph = verified ? "" : "", FontSize = 14 });
        state.Children.Add(Line(verified ? Words.SafetyStateVerified() : s.isNew ? Words.SafetyStateNew() : Words.SafetyStateUnverified(), "safety-state"));
        _state.Children.Add(state);
        if (verified)
        {
            var names = new List<string>();
            if (s.verifiedByMe) names.Add(Words.SafetyVerifiedByYou());
            names.AddRange(s.verifiedBy);
            _state.Children.Add(Line(Words.SafetyVerifiedBy(string.Join(", ", names)), "safety-verified-by", secondary: true));
        }
        if (s.rotatedAt is { } at) _state.Children.Add(Line(Words.TrustRotated(Said.Moment(at)), "safety-rotated", secondary: true));
        if (s.replaced) _state.Children.Add(Line(Words.SafetyReplaced(), "safety-replaced", secondary: true));
    }

    /// <summary>Digits as the web shows them: monospaced, selectable.</summary>
    private static TextBlock Digits(string text, string id)
    {
        var t = new TextBlock
        {
            Text = text,
            FontFamily = new FontFamily("Consolas"),
            FontSize = 20,
            IsTextSelectionEnabled = true,
            TextWrapping = TextWrapping.Wrap,
            CharacterSpacing = 40,
        };
        AutomationProperties.SetAutomationId(t, id);
        return t;
    }

    private static TextBlock Strong(string text) => new() { Text = text, FontWeight = FontWeights.SemiBold, TextWrapping = TextWrapping.Wrap };

    /// <summary>One's own: the digits, the chain code and the code (the core's QR, a PNG decoded from memory).</summary>
    private async void ShowMine()
    {
        try
        {
            var body = new StackPanel { Spacing = 10, MinWidth = 360 };
            var (answer, _) = await Core.CallAsync(new Call.TrustMine());
            if (answer is Answer.SafetyNumber mine)
            {
                body.Children.Add(Digits(mine.number, "safety-mine-number"));
                body.Children.Add(Line(Words.SafetyMineIntro(), "safety-mine-intro", secondary: true));
                body.Children.Add(Strong(Words.SafetyChainCode()));
                body.Children.Add(Digits(mine.chainCode, "safety-mine-chain"));
                body.Children.Add(Line(Words.SafetyMineChainCodeHint(), "safety-mine-chain-hint", secondary: true));
                var (code, png) = await Core.CallAsync(new Call.TrustMineCode { px = 560 });
                if (code is Answer.Bytes && png is not null)
                {
                    using var stream = new InMemoryRandomAccessStream();
                    await stream.WriteAsync(png.AsBuffer());
                    stream.Seek(0);
                    var bitmap = new BitmapImage();
                    await bitmap.SetSourceAsync(stream);
                    var image = new Image { Source = bitmap, Width = 280, Height = 280, HorizontalAlignment = HorizontalAlignment.Center };
                    AutomationProperties.SetName(image, Words.SafetyQrAlt());
                    AutomationProperties.SetAutomationId(image, "safety-mine-code");
                    body.Children.Add(image);
                }
                else Log.Write($"Trust.MineCode answered {code.GetType().Name}");
            }
            else
            {
                Log.Write($"Trust.Mine answered {(answer is Answer.Refused r ? $"Refused {r.why}" : answer.GetType().Name)}");
                body.Children.Add(Line(Words.SafetyMineUnavailable(), "safety-mine-unavailable"));
            }
            var dialog = new ContentDialog
            {
                Title = Words.SafetyMineTitle(),
                Content = new ScrollViewer { Content = body, VerticalScrollBarVisibility = ScrollBarVisibility.Auto },
                CloseButtonText = Words.ShareDone(),
                DefaultButton = ContentDialogButton.Close,
            };
            AutomationProperties.SetAutomationId(dialog, "safety-mine");
            await Dialogs.Show(_window, dialog);
        }
        catch (Exception e)
        {
            Log.Write($"one's own safety number was not shown: {e.GetType().Name}");
        }
    }

    /// <summary>Another member's: their digits and chain code, the scan and the digits' compare; their state read anew after.</summary>
    private async void Compare()
    {
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.TrustPerson { userId = _userId });
            var name = _name();
            var body = new StackPanel { Spacing = 10, MinWidth = 400 };
            QrCamera? camera = null;
            if (answer is not Answer.PersonTrust { shown: { number: { } number } shown })
            {
                body.Children.Add(Line(Words.SafetyUnreadable(), "safety-unreadable"));
            }
            else
            {
                body.Children.Add(Digits(number, "safety-theirs-number"));
                body.Children.Add(Line(Words.SafetyTheirsIntro(name), "safety-theirs-intro", secondary: true));
                if (shown.chainCode is { } chain)
                {
                    body.Children.Add(Strong(Words.SafetyChainCode()));
                    body.Children.Add(Digits(chain, "safety-theirs-chain"));
                    body.Children.Add(Line(Words.SafetyChainCodeHint(name), "safety-chain-hint", secondary: true));
                }
                if (!shown.anomaly)
                {
                    var outcome = new ContentControl { HorizontalContentAlignment = HorizontalAlignment.Stretch };
                    var actions = new StackPanel { Spacing = 8 };
                    var scanHolder = new StackPanel { Spacing = 6 };
                    actions.Children.Add(Strong(Words.SafetyCompare()));
                    actions.Children.Add(Line(Words.SafetyCompareHint(name), "safety-compare-hint", secondary: true));
                    var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
                    void Tell(Outcome? o)
                    {
                        if (o is not { } got) return;
                        var verified = got == Outcome.Verified;
                        var line = Line(OutcomeWords(got, name), "safety-outcome");
                        if (!verified) line.Foreground = Brush("SystemFillColorCriticalBrush");
                        AutomationProperties.SetLiveSetting(line, Microsoft.UI.Xaml.Automation.Peers.AutomationLiveSetting.Assertive);
                        outcome.Content = line;
                        if (verified) actions.Visibility = Visibility.Collapsed;
                    }
                    async void CompareDigits(bool match)
                    {
                        try
                        {
                            var (a, _) = await Core.CallAsync(new Call.TrustCompareDigits { userId = _userId, match = match });
                            Log.Write($"Trust.CompareDigits answered {(a is Answer.TrustOutcome t ? t.outcome.ToString() : a.GetType().Name)}");
                            Tell((a as Answer.TrustOutcome)?.outcome ?? Outcome.Failed);
                        }
                        catch (Exception e)
                        {
                            Log.Write($"the digits' compare failed: {e.GetType().Name}");
                        }
                    }
                    if (await QrCamera.PresentAsync())
                    {
                        var scan = new Button { Content = Words.SafetyScan() };
                        AutomationProperties.SetAutomationId(scan, "safety-scan");
                        scan.Click += (_, _) =>
                        {
                            if (camera is not null) return;
                            scan.IsEnabled = false;
                            camera = QrCamera.Open(ScanKind.Safety, s => s.state is ScanState.NoCamera or ScanState.Denied
                                ? new TextBlock { Text = Words.SafetyScanError(), TextWrapping = TextWrapping.Wrap }
                                : null, async s =>
                                {
                                    Log.Write($"safety scan {s.state}");
                                    if (s.state != ScanState.Found || camera?.Scan is not { } id) return;
                                    try
                                    {
                                        var (a, _) = await Core.CallAsync(new Call.TrustCompareScanned { userId = _userId, scan = id });
                                        Log.Write($"Trust.CompareScanned answered {(a is Answer.TrustOutcome t ? t.outcome.ToString() : a.GetType().Name)}");
                                        Tell((a as Answer.TrustOutcome)?.outcome ?? Outcome.Failed);
                                    }
                                    catch (Exception e)
                                    {
                                        Log.Write($"the scan's compare failed: {e.GetType().Name}");
                                    }
                                    finally
                                    {
                                        camera?.Dispose();
                                        camera = null;
                                        scanHolder.Children.Clear();
                                        scan.IsEnabled = true;
                                    }
                                });
                            camera.View.Height = 300;
                            scanHolder.Children.Add(camera.View);
                            scanHolder.Children.Add(Line(Words.SafetyScanHint(name), "safety-scan-hint", secondary: true));
                        };
                        row.Children.Add(scan);
                    }
                    var match = new Button { Content = Words.SafetyTheyMatch() };
                    AutomationProperties.SetAutomationId(match, "safety-match");
                    match.Click += (_, _) => CompareDigits(true);
                    row.Children.Add(match);
                    var differ = new Button { Content = Words.SafetyTheyDiffer() };
                    AutomationProperties.SetAutomationId(differ, "safety-differ");
                    differ.Click += (_, _) => CompareDigits(false);
                    row.Children.Add(differ);
                    actions.Children.Add(row);
                    actions.Children.Add(scanHolder);
                    body.Children.Add(actions);
                    body.Children.Add(outcome);
                }
            }
            var dialog = new ContentDialog
            {
                Title = Words.SafetyTheirsDialogTitle(name),
                Content = new ScrollViewer { Content = body, VerticalScrollBarVisibility = ScrollBarVisibility.Auto },
                CloseButtonText = Words.ShareDone(),
                DefaultButton = ContentDialogButton.Close,
            };
            AutomationProperties.SetAutomationId(dialog, "safety-theirs");
            await Dialogs.Show(_window, dialog);
            camera?.Dispose();
            if (!_closed) Read();
        }
        catch (Exception e)
        {
            Log.Write($"the compare was not shown: {e.GetType().Name}");
        }
    }

    /// <summary>What a compare came to, in the web's words.</summary>
    private static string OutcomeWords(Outcome o, string name) => o switch
    {
        Outcome.Verified => Words.SafetyVerified(name),
        Outcome.Mismatch => Words.SafetyMismatch(name),
        Outcome.Foreign => Words.SafetyForeign(),
        Outcome.Outdated => Words.SafetyOutdated(name),
        Outcome.Moved => Words.SafetyMoved(name),
        Outcome.Unanchored => Words.SafetyUnanchored(name),
        _ => Words.SafetyFailed(),
    };
}
