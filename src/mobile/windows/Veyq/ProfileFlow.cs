// One's profile on Windows (ADR 0148 decision 2; uc.spaces.edit-profile, .set-profile-picture): Settings → Your profile,
// and the same page from one's own person page (its CommandBar's Edit profile) — the username, the real name, the picture
// and the group-add consent, all the core's ProfileModel over the bridge (Profile.*, Watched.Profile). Without a tenant
// grant the name and the picture are disabled with the web's hint.
//
// The picture: the file open picker for one image, then a ContentDialog with the cover's cropper in a square frame (the
// core's CoverFrame through Cover.Pose, zoom 1–3, a drag pans), "Use this picture" hands the core the file's path and the
// pose (Profile.Picture); the core asks this side to render it 256 × 256 (Picture.Square, ProfilePicture.Square: WIC
// decodes the file upright, scaled as the pose says, the framed square copied out as RGBA over white), and encodes,
// pads and seals it itself. Nothing of the picture is written to disk here.
using System;
using System.IO;
using System.Runtime.InteropServices.WindowsRuntime;
using System.Threading.Tasks;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Veyq.Bridge;
using Veyq.Bridge.Wire;
using Windows.Graphics.Imaging;

namespace Veyq;

internal sealed class ProfilePage : FlowPage
{
    private readonly TextBox _username = new() { Header = Words.ProfileUsername(), MaxWidth = 420, HorizontalAlignment = HorizontalAlignment.Left };
    private readonly TextBox _first = new() { Header = Words.ProfileFirstName(), MaxWidth = 420, HorizontalAlignment = HorizontalAlignment.Left };
    private readonly TextBox _last = new() { Header = Words.ProfileLastName(), MaxWidth = 420, HorizontalAlignment = HorizontalAlignment.Left };
    private readonly ToggleSwitch _consent = new() { Header = Words.ProfileConsentGroupAdd() };
    private readonly PersonPicture _picture = new() { Width = 72, Height = 72 };
    private readonly Button _choose = new();
    private readonly Button _remove = new() { Content = Words.ProfilePictureRemove() };
    private readonly Button _save = Accent(Words.ProfileSave(), "profile-save");
    private long? _id;
    private Watch? _watch;
    private ProfileModelState? _state;
    private bool _drawing;
    private bool _pictureNotImage;
    private string _pictureDrawn = "";
    private int _savesSeen = -1;
    private readonly TypedText _usernameSent = new();
    private readonly TypedText _firstSent = new();
    private readonly TypedText _lastSent = new();

    public ProfilePage(Window window, string connection, Action back) : base(window, connection, back, AccountFlow.Profile)
    {
        AutomationProperties.SetAutomationId(_username, "profile-username");
        AutomationProperties.SetAutomationId(_first, "profile-first-name");
        AutomationProperties.SetAutomationId(_last, "profile-last-name");
        AutomationProperties.SetAutomationId(_consent, "profile-consent");
        AutomationProperties.SetAutomationId(_choose, "profile-picture-choose");
        AutomationProperties.SetAutomationId(_remove, "profile-picture-remove");
        AutomationProperties.SetAutomationId(_picture, "profile-picture");
        _username.TextChanged += (_, _) => Typed(_username, _usernameSent, (id, v) => new Call.ProfileUsername { profile = id, value = v });
        _first.TextChanged += (_, _) => Typed(_first, _firstSent, (id, v) => new Call.ProfileFirstName { profile = id, value = v });
        _last.TextChanged += (_, _) => Typed(_last, _lastSent, (id, v) => new Call.ProfileLastName { profile = id, value = v });
        _consent.Toggled += (_, _) => Send(id => new Call.ProfileConsent { profile = id, on = _consent.IsOn });
        _choose.Click += (_, _) => Choose();
        _remove.Click += (_, _) => Send(id => new Call.ProfileRemovePicture { profile = id });
        _save.Click += (_, _) => Save();
        Draw(null);
        Start();
    }

    private async void Start()
    {
        var id = await OpenFlow("Profile.Open", new Call.ProfileOpen());
        if (id is not { } profile) return;
        if (Closed)
        {
            Act.Send(new Call.ProfileClose { profile = profile });
            return;
        }
        _id = profile;
        _watch = Core.Watch(new Watched.Profile { profile = profile }, update =>
        {
            if (Closed || update is not Update.Profile { shown: var s }) return;
            Guarded("the profile", () => Draw(s));
        }, e => Log.Write($"a profile update: {e.Message}"));
    }

    /// <summary>A field's change to the core, never one a redraw made.</summary>
    private void Send(Func<long, Call> make)
    {
        if (_drawing || _id is not { } id) return;
        Act.Send(make(id));
    }

    /// <summary>A box's edit to the core, remembered as sent (TypedText).</summary>
    private void Typed(TextBox box, TypedText sent, Func<long, string, Call> make)
    {
        if (_drawing || _id is not { } id) return;
        var text = box.Text;
        sent.Sent(text);
        Act.Send(make(id, text));
    }

    /// <summary>A box set from the state by TypedText's rule: never over what is being typed.</summary>
    private static void Set(TextBox box, TypedText sent, string state)
    {
        if (sent.Take(box.FocusState != FocusState.Unfocused, box.Text, state) is { } text) box.Text = text;
    }

    /// <summary>The picture shown: whether one shows, and a round that moves with each change of it (a draft set or cleared,
    /// a save) — asked for anew (Profile.PictureBytes) only when that changed.</summary>
    private static string PictureKey(ProfileModelState s) => $"{s.serverHasPicture}:{s.picture}:{s.saves}";

    private void Draw(ProfileModelState? s)
    {
        _state = s;
        Body.Children.Clear();
        if (s is not { loaded: true })
        {
            var loading = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
            loading.Children.Add(Ring());
            loading.Children.Add(Caption(Words.ProfileLoading()));
            Body.Children.Add(loading);
            return;
        }
        if (s.refusal == "profile_load_failed")
        {
            Body.Children.Add(Refusal(s.refusal)!);
            return;
        }
        Log.Write($"profile: key {s.hasKey}, picture {s.picture} (server {s.serverHasPicture}), working {s.working}, saves {s.saves}, refusal {s.refusal ?? "none"}");
        _drawing = true;
        try
        {
            // a field is set from the state only where it differs and is not being typed in
            Set(_username, _usernameSent, s.username);
            Set(_first, _firstSent, s.firstName);
            Set(_last, _lastSent, s.lastName);
            _consent.IsOn = s.requireGroupAddConsent;
        }
        finally
        {
            _drawing = false;
        }
        var hasPicture = s.picture switch
        {
            PictureDraft.Set => true,
            PictureDraft.Clear => false,
            _ => s.serverHasPicture,
        };

        // the picture
        Body.Children.Add(Heading(Words.ProfilePictureLabel()));
        var pictureRow = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 16 };
        _picture.DisplayName = s.firstName.Length > 0 ? $"{s.firstName} {s.lastName}".Trim() : s.username;
        pictureRow.Children.Add(_picture);
        var pictureButtons = new StackPanel { Spacing = 8, VerticalAlignment = VerticalAlignment.Center };
        _choose.Content = hasPicture ? Words.ProfilePictureChange() : Words.ProfilePictureChoose();
        _choose.IsEnabled = s.hasKey && !s.working;
        pictureButtons.Children.Add(_choose);
        if (hasPicture)
        {
            _remove.IsEnabled = s.hasKey && !s.working;
            pictureButtons.Children.Add(_remove);
        }
        pictureRow.Children.Add(pictureButtons);
        Body.Children.Add(pictureRow);
        Body.Children.Add(Caption(s.hasKey ? Words.ProfilePictureHint() : Words.ProfilePictureNoKeyYet()));
        if (s.picture == PictureDraft.Set) Body.Children.Add(Caption(Words.ProfilePicturePendingSet(), "profile-picture-pending"));
        if (s.picture == PictureDraft.Clear) Body.Children.Add(Caption(Words.ProfilePicturePendingClear(), "profile-picture-pending"));
        if (_pictureNotImage) Body.Children.Add(FlowText.Critical(Words.ProfilePictureNotImage(), "profile-picture-not-image"));
        if (Refusal(s.pictureRefusal, "profile-picture-refusal") is { } pr) Body.Children.Add(pr);
        var key = PictureKey(s);
        if (key != _pictureDrawn)
        {
            _pictureDrawn = key;
            ShowPicture(hasPicture);
        }

        // the username
        Body.Children.Add(_username);
        Body.Children.Add(Caption(Words.ProfileUsernameHint()));
        if (Refusal(s.usernameRefusal, "profile-username-refusal") is { } ur) Body.Children.Add(ur);

        // the real name
        _first.IsEnabled = s.hasKey;
        _last.IsEnabled = s.hasKey;
        Body.Children.Add(_first);
        Body.Children.Add(_last);
        if (!s.hasKey) Body.Children.Add(Caption(Words.ProfileNoNameYet()));

        // the group-add consent (the account's, the same in every space)
        Body.Children.Add(_consent);
        Body.Children.Add(Caption(Words.ProfileConsentGroupAddHint()));

        var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 12 };
        _save.IsEnabled = !s.working && s.username.Trim().Length > 0;
        row.Children.Add(_save);
        if (s.working) row.Children.Add(Ring());
        Body.Children.Add(row);
        if (s.saves > 0 && s.refusal is null && s.pictureRefusal is null) Body.Children.Add(Text(Words.ProfileSaved(), "profile-saved"));
        if (s.refusal is not null) Body.Children.Add(Refusal(s.refusal)!);
        // a save that came through: the shell's people read anew, so every name and picture follows (the core's readPeople)
        if (_savesSeen >= 0 && s.saves > _savesSeen) Act.Send(new Call.ShellRefresh());
        _savesSeen = s.saves;
    }

    /// <summary>The picture the profile holds now (the draft's, else the server's), decoded from memory; the initials where none.</summary>
    private async void ShowPicture(bool has)
    {
        if (!has || _id is not { } id)
        {
            _picture.ProfilePicture = null;
            return;
        }
        try
        {
            var (answer, bytes) = await Core.CallAsync(new Call.ProfilePictureBytes { profile = id });
            if (answer is not Answer.Bytes || bytes is null)
            {
                Log.Write($"Profile.PictureBytes answered {answer.GetType().Name}");
                _picture.ProfilePicture = null;
                return;
            }
            _picture.ProfilePicture = await Avatars.Decode(bytes);
            Array.Clear(bytes);
        }
        catch (Exception e)
        {
            Log.Write($"the profile's picture did not decode: {e.GetType().Name}");
        }
    }

    /// <summary>"Choose a picture": the open picker for one image, then the square cropper; the path and the pose to the core.</summary>
    private async void Choose()
    {
        if (_id is not { } id) return;
        try
        {
            var picker = new Windows.Storage.Pickers.FileOpenPicker { SuggestedStartLocation = Windows.Storage.Pickers.PickerLocationId.PicturesLibrary, ViewMode = Windows.Storage.Pickers.PickerViewMode.Thumbnail };
            foreach (var ext in new[] { ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".tif", ".tiff", ".webp", ".heic", ".heif" }) picker.FileTypeFilter.Add(ext);
            WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(Window));
            var file = await picker.PickSingleFileAsync();
            if (file is null || Closed) return;
            var pose = await SquareCropDialog.Ask(Window, file.Path);
            _pictureNotImage = pose is SquareCropDialog.NotAPicture;
            if (pose is not SquareCropDialog.Posed posed || Closed)
            {
                if (_state is { } s) Draw(s);
                return;
            }
            var (answer, _) = await Core.CallAsync(new Call.ProfilePicture { profile = id, path = file.Path, pose = posed.Pose });
            Log.Write($"Profile.Picture answered {(answer is Answer.Refused r ? $"Refused {r.why}" : answer.GetType().Name)}");
            _pictureNotImage = answer is Answer.Refused;
            if (_state is { } now) Draw(now);
        }
        catch (Exception e)
        {
            Log.Write($"a profile picture was not chosen: {e.GetType().Name}");
        }
    }

    private void Save()
    {
        if (_id is not { } id) return;
        _save.IsEnabled = false;
        Act.Send(new Call.ProfileSave { profile = id });
    }

    protected override void Close()
    {
        _watch?.Dispose();
        if (_id is { } id) Act.Send(new Call.ProfileClose { profile = id });
    }
}

/// <summary>The cover's cropper with a square frame of the picture's own edge (ProfileModel.EDGE, so the pose the core is
/// handed is the frame's it renders): a drag pans, the Slider zooms 1–3; where the picture sits is the core's math
/// (Cover.Pose), the answer of the latest ask drawn.</summary>
internal sealed class SquareCropDialog
{
    /// <summary>The picture's edge in pixels (ProfileModel.EDGE), the frame's in DIPs.</summary>
    public const int Edge = 256;

    public abstract record Outcome;
    public sealed record Posed(Pose Pose) : Outcome;
    public sealed record NotAPicture : Outcome;
    public sealed record Cancelled : Outcome;

    private readonly Canvas _canvas = new() { Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent) };
    private readonly Image _image = new() { Stretch = Stretch.Fill };
    private readonly Slider _zoom = new() { Minimum = 1, Maximum = 3, StepFrequency = 0.01, IsEnabled = false };
    private ContentDialog? _dialog;
    private (uint Width, uint Height) _size;
    private Pose? _pose;
    private Pose? _dragFrom;
    private long _asked;
    private bool _drawing;

    /// <summary>The picture at <paramref name="path"/> framed square over <paramref name="window"/>: the pose chosen, or why none.</summary>
    public static async Task<Outcome> Ask(Window window, string path)
    {
        SoftwareBitmap bitmap;
        try
        {
            bitmap = await ProfilePicture.Shown(path);
        }
        catch (Exception e)
        {
            Log.Write($"a profile picture did not decode ({e.GetType().Name})");
            return new NotAPicture();
        }
        return await new SquareCropDialog().Show(window, bitmap);
    }

    private async Task<Outcome> Show(Window window, SoftwareBitmap bitmap)
    {
        _size = ((uint)bitmap.PixelWidth, (uint)bitmap.PixelHeight);
        var body = new StackPanel { Spacing = 12 };
        body.Children.Add(new TextBlock { Text = Words.ProfilePictureDialogIntro(), TextWrapping = TextWrapping.Wrap, Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"] });
        var frame = new Grid
        {
            Width = Edge,
            Height = Edge,
            HorizontalAlignment = HorizontalAlignment.Center,
            CornerRadius = new CornerRadius(Edge / 2),
            Background = (Brush)Application.Current.Resources["CardBackgroundFillColorDefaultBrush"],
        };
        _canvas.Children.Add(_image);
        frame.Children.Add(_canvas);
        AutomationProperties.SetName(frame, Words.ProfilePictureLabel());
        AutomationProperties.SetAutomationId(frame, "profile-crop-frame");
        _canvas.Clip = new RectangleGeometry { Rect = new Windows.Foundation.Rect(0, 0, Edge, Edge) };
        _canvas.ManipulationMode = ManipulationModes.TranslateX | ManipulationModes.TranslateY;
        _canvas.ManipulationStarted += (_, _) => _dragFrom = _pose;
        _canvas.ManipulationDelta += (_, e) =>
        {
            if (_dragFrom is { } from) Pose(from, e.Cumulative.Translation.X, e.Cumulative.Translation.Y, 1);
        };
        body.Children.Add(frame);
        _zoom.Header = Words.CoverZoom();
        AutomationProperties.SetName(_zoom, Words.CoverZoom());
        AutomationProperties.SetAutomationId(_zoom, "profile-crop-zoom");
        _zoom.ValueChanged += (_, e) =>
        {
            if (!_drawing && _pose is { } at) Pose(at, 0, 0, e.NewValue / at.zoom);
        };
        body.Children.Add(_zoom);
        var source = new SoftwareBitmapSource();
        await source.SetBitmapAsync(bitmap);
        _image.Source = source;
        var dialog = new ContentDialog
        {
            Title = Words.ProfilePictureLabel(),
            Content = body,
            PrimaryButtonText = Words.ProfilePictureUse(),
            CloseButtonText = Words.EntryCancel(),
            DefaultButton = ContentDialogButton.Primary,
            IsPrimaryButtonEnabled = false,
        };
        AutomationProperties.SetAutomationId(dialog, "profile-crop");
        _dialog = dialog;
        Pose(null, 0, 0, 1);
        var result = await Dialogs.Show(window, dialog);
        bitmap.Dispose();
        return result == ContentDialogResult.Primary && _pose is { } pose ? new Posed(pose) : new Cancelled();
    }

    /// <summary>Asks the core where the picture sits: from <paramref name="at"/> (or centred), panned and zoomed.</summary>
    private async void Pose(Pose? at, double panX, double panY, double factor)
    {
        var n = ++_asked;
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.CoverPose
            {
                imageWidth = _size.Width, imageHeight = _size.Height, frameWidth = Edge, frameHeight = Edge,
                at = at, crop = null, panX = panX, panY = panY, zoom = factor,
            });
            if (n != _asked || answer is not Answer.CoverPosed p) return;
            _pose = p.pose;
            _image.Width = p.width;
            _image.Height = p.height;
            Canvas.SetLeft(_image, p.pose.x);
            Canvas.SetTop(_image, p.pose.y);
            _drawing = true;
            _zoom.Value = p.pose.zoom;
            _drawing = false;
            _zoom.IsEnabled = true;
            if (_dialog is { } d) d.IsPrimaryButtonEnabled = true;
        }
        catch (Exception e)
        {
            Log.Write($"Cover.Pose failed: {e.GetType().Name}");
        }
    }
}
