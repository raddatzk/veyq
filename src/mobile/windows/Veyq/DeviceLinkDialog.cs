// One device-linking ceremony on screen (ADR 0059; cr-app-device-linking phase 6), in any of its four roles: the core's
// DeviceLink decides every step (Link.*, Watched.Link: LinkShown), this draws its state in a ContentDialog and hands the
// person's acts back — the camera into the link's own scan (QrCamera.OpenLink), the six digits typed (Link.EnterCode),
// the slide's end (Link.Confirm) and the close (Link.Close). Linux's device_link.rs and Android's DeviceLinkScreen, in
// Fluent.
//
// The code this side shows reaches it only as a picture (Link.Code, a PNG): its text, the channel key and the grant never
// cross the bridge. The code and the scanner's camera are a secret on screen (src/mobile/CLAUDE.md § Secrets on screen):
// the dialog's content is ScreenSecrets.Guard'ed, so Recall's snapshots and every capture through the compositor see the
// desktop behind the window while it shows. A new device's link that is done needs nothing here: the bridge opens the
// account it signed in, the session's gate moves on under the dialog, which then closes.
//
// The slide (ADR 0059 §7, D5) is a Slider that confirms only when a pointer gesture that began at its start (≤ 15 %) ends
// at its end (≥ 98 %), and springs back otherwise — a click on the track's end moves it there in one move and does not
// confirm, nor does a UIA RangeValue set. Keyboard: the arrow keys move it in ten steps, Enter at the end confirms; Home,
// End and the Page keys are taken and move nothing. UIA offers no named action of its own in WinUI, so Narrator reads the
// sentence as the slider's name and confirms by the keys.
using System;
using System.Linq;
using System.Runtime.InteropServices.WindowsRuntime;
using System.Threading.Tasks;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Veyq.Bridge;
using Veyq.Bridge.Wire;
using Windows.Storage.Streams;
using Windows.System;

namespace Veyq;

internal sealed class DeviceLinkDialog
{
    private const double CodeSide = 280;

    private readonly Window _window;
    private readonly Call _opening;
    private readonly ContentDialog _dialog;
    private readonly StackPanel _column = new() { Spacing = 16, Width = 460 };
    private long? _id;
    private Watch? _watch;
    private LinkShown? _drawn;
    private QrCamera? _scan;
    private bool _closed;
    /// <summary>The new device's six digits and the new device's name, kept across redraws so what is typed stays.</summary>
    private readonly TextBox _code = new() { Header = Words.LinkSignInCode(), InputScope = Digits(), MaxLength = 6 };
    private readonly TextBox _label = new() { Header = Words.LinkLabel() };
    private Button? _send;
    private Slide? _slide;

    private DeviceLinkDialog(Window window, Call opening)
    {
        _window = window;
        _opening = opening;
        AutomationProperties.SetAutomationId(_column, "link");
        AutomationProperties.SetAutomationId(_code, "link-code-field");
        AutomationProperties.SetAutomationId(_label, "link-label");
        var scroll = new ScrollViewer { Content = _column, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, MaxHeight = 640 };
        _dialog = new ContentDialog
        {
            Title = opening switch
            {
                Call.LinkScanFromTrusted => Words.LinkJoinTitle(),
                Call.LinkShowForSignIn => Words.SigninLinkShow(),
                _ => Words.AccountSettingsAddDevice(),
            },
            // the code and the camera: out of capture while the dialog shows
            Content = ScreenSecrets.Guard(scroll),
            CloseButtonText = Words.LinkClose(),
        };
        AutomationProperties.SetAutomationId(_dialog, "link-dialog");
        _code.TextChanged += (_, _) =>
        {
            // digits alone, six at most; the button only with six
            var digits = new string(_code.Text.Where(char.IsAsciiDigit).Take(6).ToArray());
            if (digits != _code.Text)
            {
                _code.Text = digits;
                _code.SelectionStart = digits.Length;
            }
            if (_send is not null) _send.IsEnabled = digits.Length == 6;
        };
        _code.KeyDown += (_, e) =>
        {
            if (e.Key != VirtualKey.Enter) return;
            e.Handled = true;
            EnterCode();
        };
        _label.TextChanged += (_, _) => NameSlide();
    }

    private static InputScope Digits()
    {
        var scope = new InputScope();
        scope.Names.Add(new InputScopeName { NameValue = InputScopeNameValue.Number });
        return scope;
    }

    /// <summary>Opens the ceremony <paramref name="opening"/> starts (one of the four Link.* roles' calls) over
    /// <paramref name="window"/>; returns once its dialog closed, the link ended with it.</summary>
    public static async Task Show(Window window, Call opening)
    {
        var ceremony = new DeviceLinkDialog(window, opening);
        ceremony.Start();
        try
        {
            await Dialogs.Show(window, ceremony._dialog);
        }
        finally
        {
            ceremony.End();
        }
    }

    private static bool Trusted(LinkRole role) => role is LinkRole.ShowForNewDevice or LinkRole.ScanSignInCode;

    /// <summary>Opens a link of this ceremony's role and follows its state; a link already open (Start over) is closed first.</summary>
    private async void Start()
    {
        try
        {
            CloseLink();
            _code.Text = "";
            _label.Text = "";
            _drawn = null;
            Clear();
            _column.Children.Add(Waiting(null));
            var (answer, _) = await Core.CallAsync(_opening);
            if (answer is not Answer.LinkOpened { link: var link })
            {
                Log.Write($"veyq-link: opening answered {(answer is Answer.Refused r ? $"Refused {r.why}" : answer.GetType().Name)}");
                _dialog.Hide();
                return;
            }
            if (_closed)
            {
                Act.Send(new Call.LinkClose { link = link });
                return;
            }
            _id = link;
            _watch = Core.Watch(new Watched.Link { link = link }, update =>
            {
                if (_closed || _id != link || update is not Update.Link { shown: var shown }) return;
                try
                {
                    if (shown is null)
                    {
                        // closed in the core (its tenant closed): the dialog goes with it
                        Log.Write("veyq-link: closed in the core");
                        _id = null;
                        _dialog.Hide();
                    }
                    else Show(link, shown);
                }
                catch (Exception e)
                {
                    // out of a watch's callback it would end the app in combase, with nothing in the log
                    Log.Write($"veyq-link: the step did not draw: {e.GetType().Name} 0x{e.HResult:X8}: {e.Message}");
                }
            }, e => Log.Write($"veyq-link: an update: {e.Message}"));
        }
        catch (Exception e)
        {
            Log.Write($"veyq-link: the opening failed: {e.GetType().Name}");
        }
    }

    /// <summary>Ends the link this dialog holds (its polls stop, its scan closes).</summary>
    private void CloseLink()
    {
        _watch?.Dispose();
        _watch = null;
        _scan?.Dispose();
        _scan = null;
        if (_id is { } link) Act.Send(new Call.LinkClose { link = link });
        _id = null;
    }

    private void End()
    {
        _closed = true;
        CloseLink();
        Clear();
    }

    private void Clear()
    {
        // the kept boxes and the camera's view are moved into each redraw: off their old panel first
        foreach (var kept in new FrameworkElement?[] { _code, _label, _scan?.View }) (kept?.Parent as Panel)?.Children.Remove(kept);
        _column.Children.Clear();
        _send = null;
        _slide = null;
    }

    private void Show(long link, LinkShown shown)
    {
        // the step, the refusal and the hint, never a code, a number or a name
        Log.Write($"veyq-link: {shown.role} {shown.step} refusal {shown.refusal?.ToString() ?? "none"} hint {shown.hint?.ToString() ?? "none"}");
        if (_drawn == shown) return;
        var sameStep = _drawn?.step == shown.step;
        _drawn = shown;
        // a new device's link is done: the account it signed in is open, the gate under this dialog moved on
        if (shown.step == LinkStep.Done && !Trusted(shown.role))
        {
            _id = null;
            _watch?.Dispose();
            _watch = null;
            _dialog.Hide();
            return;
        }
        if (shown.step != LinkStep.Scanning)
        {
            _scan?.Dispose();
            _scan = null;
        }
        if (sameStep && shown.step == LinkStep.Scanning && _scan is not null)
        {
            // the other direction's code seen: the camera stays, the line under it is drawn anew
            if (_column.Children.Count > 0 && _column.Children[^1] is FrameworkElement { Name: "link-other-direction" } last) _column.Children.Remove(last);
            OtherDirection(shown);
            return;
        }
        Clear();
        var c = _column.Children;
        switch (shown.step)
        {
            case LinkStep.Opening:
                c.Add(Waiting(shown.role is LinkRole.ShowForSignIn or LinkRole.ShowForNewDevice ? Words.LinkSignInOpening() : null));
                break;
            case LinkStep.Showing when shown.role == LinkRole.ShowForNewDevice:
                c.Add(Copy(Words.LinkScanHint()));
                c.Add(CodePicture(link, Words.LinkCodeAlt()));
                c.Add(Waiting(Words.LinkWaiting()));
                SameServer(shown);
                break;
            case LinkStep.Showing:
                c.Add(Copy(Words.LinkSignInIntro()));
                c.Add(CodePicture(link, Words.LinkCodeAltSignIn()));
                c.Add(Copy(Words.LinkSignInScanHint()));
                SixDigits();
                break;
            case LinkStep.Scanning:
                Scanning(link, shown);
                break;
            case LinkStep.Code:
                c.Add(Copy(Words.LinkCodePrompt()));
                c.Add(DigitsShown(shown.code ?? ""));
                c.Add(Consequence());
                c.Add(Waiting(Words.LinkCodeWaiting()));
                SameServer(shown);
                break;
            case LinkStep.Waiting when shown.role == LinkRole.ScanFromTrusted:
                c.Add(Copy(Words.LinkJoinIntro()));
                c.Add(DigitsShown(shown.number ?? ""));
                c.Add(Copy(Words.LinkJoinCompare()));
                c.Add(Waiting(Words.LinkJoinWaiting()));
                SameServer(shown);
                break;
            case LinkStep.Waiting:
                c.Add(Waiting(Words.LinkSignInWaiting()));
                SameServer(shown);
                break;
            case LinkStep.Confirming:
                Confirming(link, shown);
                break;
            case LinkStep.Sealing:
                c.Add(Waiting(Words.LinkSealing()));
                break;
            case LinkStep.Finishing:
                c.Add(Waiting(Words.LinkFinishing()));
                break;
            case LinkStep.Done:
                var sealedLine = new TextBlock { Text = Words.LinkSealed(), Style = Style("SubtitleTextBlockStyle"), TextWrapping = TextWrapping.Wrap, HorizontalAlignment = HorizontalAlignment.Center, TextAlignment = TextAlignment.Center };
                AutomationProperties.SetAutomationId(sealedLine, "link-sealed");
                c.Add(sealedLine);
                c.Add(Copy(Words.LinkSealedHint()));
                break;
            case LinkStep.Ended:
                Ended(shown);
                break;
        }
    }

    /// <summary>This side's code as the core drew it, dark on light whatever the theme (a scanner reads that), at the
    /// window's scale so a module is whole device pixels; <paramref name="alt"/> for a screen reader.</summary>
    private Image CodePicture(long link, string alt)
    {
        var image = new Image { Width = CodeSide, Height = CodeSide, Stretch = Stretch.Uniform, HorizontalAlignment = HorizontalAlignment.Center };
        AutomationProperties.SetName(image, alt);
        AutomationProperties.SetAutomationId(image, "link-qr");
        _ = Draw(image, link);
        return image;
    }

    private async Task Draw(Image image, long link)
    {
        try
        {
            var scale = _window.Content?.XamlRoot?.RasterizationScale ?? 1.0;
            var (answer, png) = await Core.CallAsync(new Call.LinkCode { link = link, px = (int)Math.Round(CodeSide * Math.Max(1.0, scale)) });
            if (answer is not Answer.Bytes || png is null)
            {
                Log.Write($"veyq-link: Link.Code answered {answer.GetType().Name}");
                return;
            }
            using var stream = new InMemoryRandomAccessStream();
            await stream.WriteAsync(png.AsBuffer());
            stream.Seek(0);
            var bitmap = new BitmapImage();
            await bitmap.SetSourceAsync(stream);
            image.Source = bitmap;
        }
        catch (Exception e)
        {
            Log.Write($"veyq-link: the code's picture failed: {e.GetType().Name}");
        }
    }

    /// <summary>The new device's six digits (§6): editable until sent, sent once; the button only with six digits.</summary>
    private void SixDigits()
    {
        _column.Children.Add(_code);
        _column.Children.Add(Caption(Words.LinkSignInCodeHint()));
        var send = new Button
        {
            Content = Words.LinkSignInSend(),
            HorizontalAlignment = HorizontalAlignment.Stretch,
            Style = Style("AccentButtonStyle"),
            IsEnabled = _code.Text.Length == 6,
        };
        AutomationProperties.SetAutomationId(send, "link-send");
        send.Click += (_, _) => EnterCode();
        _send = send;
        _column.Children.Add(send);
    }

    private async void EnterCode()
    {
        try
        {
            if (_id is not { } link || _code.Text.Length != 6) return;
            var (answer, _) = await Core.CallAsync(new Call.LinkEnterCode { link = link, code = _code.Text });
            Log.Write($"veyq-link: the code sent: {(answer is Answer.Refused r ? $"Refused {r.why}" : answer.GetType().Name)}");
        }
        catch (Exception e)
        {
            Log.Write($"veyq-link: the code's send failed: {e.GetType().Name}");
        }
    }

    /// <summary>The camera into the link's own scan; the other direction's code said with the other entry's name, the scan
    /// going on.</summary>
    private void Scanning(long link, LinkShown shown)
    {
        var newDevice = shown.role == LinkRole.ScanFromTrusted;
        if (_scan is null)
        {
            var denied = newDevice ? Words.LinkJoinScanDenied() : Words.LinkScanDenied();
            _scan = QrCamera.OpenLink(link, s => s.state is ScanState.NoCamera or ScanState.Denied
                ? new TextBlock { Text = denied, TextWrapping = TextWrapping.Wrap, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, TextAlignment = TextAlignment.Center }
                : null, s => Log.Write($"veyq-link: scan {s.state}"));
            _scan.View.Height = 340;
        }
        _column.Children.Add(_scan.View);
        _column.Children.Add(Copy(newDevice ? Words.LinkJoinScanHint() : Words.LinkScanSignInHint()));
        OtherDirection(shown);
    }

    private void OtherDirection(LinkShown shown)
    {
        if (shown.hint != LinkHint.OtherDirection) return;
        var line = Copy(shown.role == LinkRole.ScanFromTrusted ? Words.LinkOtherDirectionNew() : Words.LinkOtherDirectionTrusted());
        line.Foreground = Brush("SystemFillColorCriticalBrush");
        line.Name = "link-other-direction";
        AutomationProperties.SetAutomationId(line, "link-other-direction");
        _column.Children.Add(line);
    }

    /// <summary>The trusted device's confirmation (§5, §7): the number compared or the proof held, the name, then the slide.</summary>
    private void Confirming(long link, LinkShown shown)
    {
        var compared = shown.role == LinkRole.ShowForNewDevice;
        var c = _column.Children;
        c.Add(Copy(compared ? Words.LinkComparePrompt() : Words.LinkProven()));
        if (compared) c.Add(DigitsShown(shown.number ?? ""));
        c.Add(_label);
        c.Add(Caption(Words.LinkLabelHint()));
        _slide = new Slide(async () =>
        {
            var label = _label.Text.Trim();
            if (label.Length == 0) return false;
            try
            {
                var (answer, _) = await Core.CallAsync(new Call.LinkConfirm { link = link, label = label });
                Log.Write($"veyq-link: the slide's end: {(answer is Answer.Done ? "moved on" : "refused")}");
                return answer is Answer.Done;
            }
            catch (Exception e)
            {
                Log.Write($"veyq-link: the confirmation failed: {e.GetType().Name}");
                return false;
            }
        });
        NameSlide();
        c.Add(_slide.Panel);
        c.Add(Consequence());
        var stop = new Button { Content = compared ? Words.LinkMismatch() : Words.SigninCancel(), HorizontalAlignment = HorizontalAlignment.Stretch };
        AutomationProperties.SetAutomationId(stop, compared ? "link-mismatch" : "link-cancel");
        stop.Click += (_, _) => _dialog.Hide();
        c.Add(stop);
    }

    private void NameSlide()
    {
        if (_slide is null) return;
        var name = _label.Text.Trim();
        _slide.Name(name.Length > 0 ? name : Words.LinkSlideNoName(), name.Length > 0);
    }

    /// <summary>How the ceremony ended, in this role's words, and the way to start over.</summary>
    private void Ended(LinkShown shown)
    {
        var role = shown.role;
        var newDevice = !Trusted(role);
        (string? said, string? hint) = shown.refusal switch
        {
            LinkRefusal.Off => (Words.LinkOff(), null),
            LinkRefusal.Gone => (Words.LinkGone(), role switch
            {
                LinkRole.ScanFromTrusted => Words.LinkJoinGoneHint(),
                LinkRole.ShowForSignIn => Words.LinkSignInGoneHint(),
                _ => null,
            }),
            LinkRefusal.Refused => role switch
            {
                LinkRole.ShowForNewDevice => (Words.LinkAnswerRefused(), Words.LinkAnswerRefusedHint()),
                LinkRole.ScanSignInCode => (Words.LinkScanRefused(), Words.LinkScanRefusedHint()),
                _ => (Words.LinkJoinRefused(), (string?)null),
            },
            LinkRefusal.Failed => (newDevice ? Words.LinkJoinFailed() : Words.LinkGrantFailed(), null),
            _ => (null, null),
        };
        var c = _column.Children;
        if (said is not null)
        {
            var line = new TextBlock { Text = said, Style = Style("BodyStrongTextBlockStyle"), TextWrapping = TextWrapping.Wrap, TextAlignment = TextAlignment.Center, Foreground = Brush("SystemFillColorCriticalBrush") };
            AutomationProperties.SetAutomationId(line, "link-ended");
            AutomationProperties.SetLiveSetting(line, Microsoft.UI.Xaml.Automation.Peers.AutomationLiveSetting.Assertive);
            c.Add(line);
        }
        if (hint is not null) c.Add(Copy(hint));
        SameServer(shown);
        if (shown.refusal != LinkRefusal.Off)
        {
            var again = new Button { Content = Words.LinkAgain(), HorizontalAlignment = HorizontalAlignment.Stretch, Style = Style("AccentButtonStyle") };
            AutomationProperties.SetAutomationId(again, "link-again");
            again.Click += (_, _) => Start();
            c.Add(again);
        }
    }

    private void SameServer(LinkShown shown)
    {
        if (shown.hint == LinkHint.SameServer) _column.Children.Add(Caption(Words.LinkSameServer()));
    }

    private static Style Style(string key) => (Style)Application.Current.Resources[key];

    private static Brush Brush(string key) => (Brush)Application.Current.Resources[key];

    /// <summary>A line of the ceremony's words.</summary>
    private static TextBlock Copy(string text) => new() { Text = text, TextWrapping = TextWrapping.Wrap, TextAlignment = TextAlignment.Center, HorizontalAlignment = HorizontalAlignment.Stretch };

    private static TextBlock Caption(string text) => new()
    {
        Text = text,
        Style = Style("CaptionTextBlockStyle"),
        Foreground = Brush("TextFillColorSecondaryBrush"),
        TextWrapping = TextWrapping.Wrap,
    };

    /// <summary>Work under way, and what it waits for.</summary>
    private static StackPanel Waiting(string? text)
    {
        var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 12, HorizontalAlignment = HorizontalAlignment.Center };
        row.Children.Add(new ProgressRing { IsActive = true, Width = 20, Height = 20 });
        if (text is not null)
        {
            var label = Caption(text);
            label.VerticalAlignment = VerticalAlignment.Center;
            row.Children.Add(label);
        }
        AutomationProperties.SetAutomationId(row, "link-waiting");
        return row;
    }

    /// <summary>The consequence the slide stands against (ADR 0059 §7): signing the device out later does not take the key back.</summary>
    private static TextBlock Consequence()
    {
        var line = Copy(Words.LinkConsequence());
        line.Foreground = Brush("SystemFillColorCriticalBrush");
        return line;
    }

    /// <summary>Five or six digits to compare or to type, large, spaced and monospaced.</summary>
    private static TextBlock DigitsShown(string digits)
    {
        var t = new TextBlock
        {
            Text = digits,
            FontFamily = new FontFamily("Cascadia Mono, Consolas"),
            FontSize = 40,
            CharacterSpacing = 300,
            FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
            HorizontalAlignment = HorizontalAlignment.Center,
            IsTextSelectionEnabled = true,
        };
        AutomationProperties.SetAutomationId(t, "link-digits");
        return t;
    }

    /// <summary>The slide (D5): a slider from 0 to 1 that confirms only by a gesture carried from its start to its end, or
    /// by the arrow keys to the end and Enter. The sentence it confirms is its label, for the eye and for Narrator.</summary>
    private sealed class Slide
    {
        private const double Start = 0.15;
        private const double End = 0.98;
        private const double Steps = 10;

        private readonly Func<Task<bool>> _confirm;
        private readonly TextBlock _sentence = new() { TextWrapping = TextWrapping.Wrap, TextAlignment = TextAlignment.Center, HorizontalAlignment = HorizontalAlignment.Stretch };
        private readonly Slider _slider = new()
        {
            Minimum = 0,
            Maximum = 1,
            StepFrequency = 0.01,
            SmallChange = 0.1,
            LargeChange = 0.1,
            IsThumbToolTipEnabled = false,
            HorizontalAlignment = HorizontalAlignment.Stretch,
        };
        private bool _pressed;
        private double? _from;
        private bool _confirming;
        private DispatcherTimer? _back;

        public StackPanel Panel { get; } = new() { Spacing = 4 };

        public Slide(Func<Task<bool>> confirm)
        {
            _confirm = confirm;
            _sentence.Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"];
            AutomationProperties.SetAutomationId(_slider, "link-slide");
            AutomationProperties.SetHelpText(_slider, Words.LinkSlideKeys());
            Panel.Children.Add(_sentence);
            Panel.Children.Add(_slider);
            var keys = new TextBlock { Text = Words.LinkSlideKeys(), Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"], Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"], HorizontalAlignment = HorizontalAlignment.Center };
            Panel.Children.Add(keys);
            _slider.ValueChanged += (_, e) =>
            {
                if (_pressed && _from is null) _from = e.NewValue;
            };
            // the pointer: seen even where the slider's thumb or track handled it
            _slider.AddHandler(UIElement.PointerPressedEvent, new PointerEventHandler((_, _) =>
            {
                if (_confirming) return;
                StopBack();
                _pressed = true;
                _from = null;
            }), true);
            _slider.AddHandler(UIElement.PointerReleasedEvent, new PointerEventHandler((_, _) => Released()), true);
            _slider.AddHandler(UIElement.PointerCaptureLostEvent, new PointerEventHandler((_, _) => Released()), true);
            _slider.PreviewKeyDown += (_, e) => e.Handled = Key(e.Key);
        }

        /// <summary>The sentence with the device's <paramref name="name"/>; the slide moves only while a name is typed.</summary>
        public void Name(string name, bool enabled)
        {
            var sentence = Words.LinkSlide(name);
            _sentence.Text = sentence;
            AutomationProperties.SetName(_slider, sentence);
            _slider.IsEnabled = enabled;
            if (!enabled) SpringBack();
        }

        /// <summary>A pointer's gesture ended (released, or its capture lost: a release the thumb took): only one carried
        /// from the start to the end confirms.</summary>
        private void Released()
        {
            if (!_pressed) return;
            _pressed = false;
            var carried = _from is { } from && from <= Start;
            _from = null;
            if (carried && _slider.Value >= End) Confirm();
            else SpringBack();
        }

        /// <summary>The slide's own keys; whether it took <paramref name="key"/>.</summary>
        private bool Key(VirtualKey key)
        {
            var taken = key is VirtualKey.Right or VirtualKey.Left or VirtualKey.Up or VirtualKey.Down
                or VirtualKey.Home or VirtualKey.End or VirtualKey.PageUp or VirtualKey.PageDown;
            if (_confirming) return taken;
            if (taken) StopBack();
            switch (key)
            {
                case VirtualKey.Right or VirtualKey.Up:
                    _slider.Value = Math.Min(Steps, Math.Round(_slider.Value * Steps) + 1) / Steps;
                    return true;
                case VirtualKey.Left or VirtualKey.Down:
                    _slider.Value = Math.Max(0, Math.Round(_slider.Value * Steps) - 1) / Steps;
                    return true;
                case VirtualKey.Enter when _slider.Value >= End:
                    Confirm();
                    return true;
                default:
                    // a key that jumps to the end would be the click the slide refuses: taken, nothing moves
                    return taken;
            }
        }

        private async void Confirm()
        {
            if (_confirming) return;
            _confirming = true;
            var moved = false;
            try
            {
                moved = await _confirm();
            }
            finally
            {
                _confirming = false;
                if (!moved) SpringBack();
            }
        }

        private void StopBack()
        {
            _back?.Stop();
            _back = null;
        }

        private void SpringBack()
        {
            StopBack();
            if (_slider.Value <= 0) return;
            var timer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(16) };
            var from = _slider.Value;
            var started = DateTime.UtcNow;
            timer.Tick += (_, _) =>
            {
                var t = Math.Min(1.0, (DateTime.UtcNow - started).TotalMilliseconds / 250);
                _slider.Value = from * Math.Pow(1 - t, 3);
                if (t >= 1)
                {
                    _slider.Value = 0;
                    timer.Stop();
                    if (_back == timer) _back = null;
                }
            };
            _back = timer;
            timer.Start();
        }
    }
}
