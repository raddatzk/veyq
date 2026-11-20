// A Memory's banner on Windows (ADR 0144 decisions 4 and 5; Linux's banner in banner.rs, the web's MemoryBanner.vue):
// above the Memory's head, scrolling away with it, in the core's frame (Banner.Frame: 16:9 above a 720 column, 1:1 at or
// below, never taller than 60 % of the viewport) — the picture whole (Stretch.Uniform) over the same picture decoded down
// to the core's surround size (at most 32 px on its long edge) and stretched back by XAML's smooth scaling: a blur by
// resampling, no blur API (Win2D would be a new dependency). A video banner shows its poster with a play button; a press
// plays Banner.Play's reader in MediumPlayer with its controls, the surround staying the poster's — above and below
// only: the player letterboxes in black, so its own bars cover the surround at the sides while it plays (seen in the
// Windows pass, ADR 0144's Consequences). Nothing is drawn where the picture does not read or decode.
//
// The banner's dialog is a ContentDialog: the open picker for one image or video (Banner.Pick by path, the core opens it),
// the two previews (wide 16:9, phone 1:1) drawn the same way, Save banner with its progress and Cancel, Remove banner. A2's
// question — the banner's own picture trashed — is asked in a ContentDialog with "Remove the banner too".

namespace Veyq.Apps.Memories;

using System;
using System.Runtime.InteropServices.WindowsRuntime;
using System.Threading.Tasks;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Veyq.Bridge;
using Veyq.Bridge.Wire;
using Veyq.Core;
using Windows.Storage.Pickers;
using Windows.Storage.Streams;

/// <summary>A banner's picture decoded twice: whole (its long edge at most 2048) and down to the surround's size.</summary>
internal sealed record BannerPicture(ImageSource Whole, int Width, int Height, byte[] Bytes)
{
    /// <summary>Decodes <paramref name="bytes"/>: its oriented size and the whole picture.</summary>
    public static async Task<BannerPicture> Of(byte[] bytes)
    {
        using var stream = new InMemoryRandomAccessStream();
        await stream.WriteAsync(bytes.AsBuffer());
        stream.Seek(0);
        var decoder = await Windows.Graphics.Imaging.BitmapDecoder.CreateAsync(stream);
        var (w, h) = ((int)decoder.OrientedPixelWidth, (int)decoder.OrientedPixelHeight);
        return new BannerPicture(await MemoryPage.Decoded(bytes, 2048), w, h, bytes);
    }

    /// <summary>The picture decoded down to <paramref name="width"/> × <paramref name="height"/> (the core's surround).</summary>
    public async Task<ImageSource> Surround(int width, int height)
    {
        using var stream = new InMemoryRandomAccessStream();
        await stream.WriteAsync(Bytes.AsBuffer());
        stream.Seek(0);
        var image = new BitmapImage { DecodePixelWidth = Math.Max(1, width), DecodePixelHeight = Math.Max(1, height) };
        await image.SetSourceAsync(stream);
        return image;
    }

    /// <summary>The core's frame for a column and a viewport (Banner.Frame), and the surround's size for this picture.</summary>
    public async Task<Answer.BannerFramed?> Frame(double column, double viewport)
    {
        var (answer, _) = await Core.CallAsync(new Call.BannerFrame { columnWidth = column, viewportHeight = viewport, pictureWidth = Width, pictureHeight = Height });
        return answer as Answer.BannerFramed;
    }

    /// <summary>A frame <paramref name="width"/> × <paramref name="height"/>: the surround stretched to fill it, the picture
    /// whole over it.</summary>
    public static Grid Drawn(double width, double height, ImageSource whole, ImageSource? surround)
    {
        var frame = new Grid
        {
            Width = width,
            Height = height,
            CornerRadius = new CornerRadius(8),
            Background = (Brush)Application.Current.Resources["CardBackgroundFillColorDefaultBrush"],
        };
        if (surround is not null) frame.Children.Add(new Image { Source = surround, Stretch = Stretch.Fill });
        frame.Children.Add(new Image { Source = whole, Stretch = Stretch.Uniform });
        return frame;
    }
}

/// <summary>The banner above a Memory's head on its page.</summary>
internal sealed class BannerStage : IDisposable
{
    private readonly Window _window;
    private readonly Action<bool, string> _say;
    private readonly Border _root = new() { HorizontalAlignment = HorizontalAlignment.Center, Visibility = Visibility.Collapsed };
    private long _gallery;
    private BannerShown? _banner;
    private BannerPicture? _picture;
    private ImageSource? _surround;
    private (double Column, double Viewport)? _laid;
    private Answer.BannerFramed? _frame;
    private MediumPlayer? _player;
    private Grid? _drawn;
    private long _asked;
    private bool _closed;

    public BannerStage(Window window, Action<bool, string> say)
    {
        _window = window;
        _say = say;
        AutomationProperties.SetAutomationId(_root, "memory-banner");
    }

    public UIElement View => _root;

    /// <summary>Gallery <paramref name="gallery"/>'s banner as the state shows it: its picture read anew when it changed.</summary>
    public async void Show(long gallery, BannerShown? banner)
    {
        _gallery = gallery;
        if (banner == _banner) return;
        var pictureChanged = banner?.picture != _banner?.picture;
        _banner = banner;
        if (banner is null)
        {
            Clear();
            Log.Write("banner: none");
            return;
        }
        if (!pictureChanged) return;
        var n = ++_asked;
        try
        {
            var (answer, bytes) = await Core.CallAsync(new Call.BannerPicture { gallery = gallery });
            if (n != _asked || _closed) return;
            if (answer is not Answer.Bytes || bytes is null)
            {
                Log.Write($"Banner.Picture answered {(answer is Answer.Refused r ? $"Refused {r.why}" : answer.GetType().Name)}");
                Clear();
                return;
            }
            var picture = await BannerPicture.Of(bytes);
            if (n != _asked || _closed) return;
            StopPlayer();
            // a new picture is a new frame: the last one's picture and play button go with it
            _drawn = null;
            _root.Child = null;
            _picture = picture;
            _surround = null;
            _frame = null;
            _laid = null;
            Log.Write($"banner: {banner.medium}, {picture.Width}×{picture.Height}");
            Layout(_lastColumn, _lastViewport);
        }
        catch (Exception e)
        {
            // a picture that does not decode: no frame, the head moves up (ADR 0144 decision 4, E5)
            Log.Write($"banner: the picture did not decode ({e.GetType().Name})");
            if (n == _asked) Clear();
        }
    }

    private double _lastColumn;
    private double _lastViewport;

    /// <summary>The frame laid out for a column <paramref name="column"/> wide in a viewport <paramref name="viewport"/>
    /// high (effective pixels): asked of the core when either changed.</summary>
    public async void Layout(double column, double viewport)
    {
        (_lastColumn, _lastViewport) = (column, viewport);
        if (_picture is not { } picture || column <= 0 || viewport <= 0 || _closed) return;
        if (_laid is { } l && Math.Abs(l.Column - column) < 0.5 && Math.Abs(l.Viewport - viewport) < 0.5) return;
        _laid = (column, viewport);
        try
        {
            var frame = await picture.Frame(column, viewport);
            if (frame is null || _closed || !ReferenceEquals(picture, _picture)) return;
            _surround ??= await picture.Surround(frame.surroundWidth, frame.surroundHeight);
            if (_closed || !ReferenceEquals(picture, _picture)) return;
            var changed = _frame is null || _frame.wide != frame.wide;
            _frame = frame;
            if (changed) Log.Write($"banner framed {(frame.wide ? "16:9" : "1:1")}: {frame.width:0}×{frame.height:0} in a column {column:0} wide, surround {frame.surroundWidth}×{frame.surroundHeight}");
            Draw();
        }
        catch (Exception e)
        {
            Log.Write($"Banner.Frame failed ({e.GetType().Name})");
        }
    }

    private void Draw()
    {
        if (_picture is not { } picture || _frame is not { } frame) return;
        if (_drawn is { } d && _player is null)
        {
            d.Width = frame.width;
            d.Height = frame.height;
            _root.Visibility = Visibility.Visible;
            return;
        }
        if (_drawn is { } playing)
        {
            playing.Width = frame.width;
            playing.Height = frame.height;
            return;
        }
        var drawn = BannerPicture.Drawn(frame.width, frame.height, picture.Whole, _surround);
        if (_banner?.medium == BannerMedium.Video)
        {
            AutomationProperties.SetName(drawn, Words.MemoryBannerVideo());
            var play = new Button
            {
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
                Width = 56,
                Height = 56,
                CornerRadius = new CornerRadius(28),
                Content = new FontIcon { Glyph = "", FontSize = 22 },
                Style = (Style)Application.Current.Resources["AccentButtonStyle"],
            };
            AutomationProperties.SetAutomationId(play, "banner-play");
            AutomationProperties.SetName(play, Words.MemoryBannerPlay());
            ToolTipService.SetToolTip(play, Words.MemoryBannerPlay());
            play.Click += (_, _) => Play();
            drawn.Children.Add(play);
        }
        _drawn = drawn;
        _root.Child = drawn;
        _root.Visibility = Visibility.Visible;
    }

    /// <summary>The video banner played on a press (never on its own): Banner.Play's reader in the system's player over the
    /// poster's surround.</summary>
    private async void Play()
    {
        if (_player is not null || _drawn is not { } frame) return;
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.BannerPlay { gallery = _gallery });
            if (answer is not Answer.Reader { reader: var reader, size: var size, mediaType: var type })
            {
                Log.Write($"Banner.Play answered {(answer is Answer.Refused r ? $"Refused {r.why}" : answer.GetType().Name)}");
                _say(true, Words.MemoryBannerPlayFailed());
                return;
            }
            if (_closed || !ReferenceEquals(frame, _drawn))
            {
                Act.Send(new Call.ReaderClose { reader = reader });
                return;
            }
            Log.Write($"banner video: reader {reader}, {size} B, {type ?? _banner?.mediaType ?? "no type"}");
            var player = new MediumPlayer(reader, size, type ?? _banner?.mediaType, "banner-video", Words.MemoryBannerVideo(), true, _root.DispatcherQueue, () =>
            {
                if (_closed) return;
                _say(true, Words.MemoryBannerPlayFailed());
                StopPlayer();
            });
            _player = player;
            // the poster's surround stays behind the playing video (ADR 0144 decision 4's deviation)
            while (frame.Children.Count > 1) frame.Children.RemoveAt(frame.Children.Count - 1);
            frame.Children.Add(player.Element);
        }
        catch (Exception e)
        {
            Log.Write($"the banner's video did not open ({e.GetType().Name}: {e.Message})");
            _say(true, Words.MemoryBannerPlayFailed());
        }
    }

    private void StopPlayer()
    {
        if (_player is not { } p) return;
        _player = null;
        p.Dispose();
        // drawn anew: the poster and its play button
        _drawn = null;
        _root.Child = null;
        Draw();
    }

    private void Clear()
    {
        StopPlayer();
        _picture = null;
        _surround = null;
        _frame = null;
        _laid = null;
        _drawn = null;
        _root.Child = null;
        _root.Visibility = Visibility.Collapsed;
    }

    public void Dispose()
    {
        if (_closed) return;
        _closed = true;
        _player?.Dispose();
        _player = null;
        _root.Child = null;
    }

    /// <summary>A2: the banner's own picture asked to be trashed — the banner stays unless "Remove the banner too" is
    /// ticked; Cancel deletes nothing (Gallery.TrashBannerPicture, Gallery.CancelAsk).</summary>
    public static async void Ask(Window window, long gallery, Action<bool, string> say)
    {
        try
        {
            var too = new CheckBox { Content = Words.MemoryBannerPictureRemove() };
            AutomationProperties.SetAutomationId(too, "banner-picture-remove");
            var content = new StackPanel { Spacing = 12 };
            content.Children.Add(new TextBlock { Text = Words.MemoryBannerPictureNotice(), TextWrapping = TextWrapping.Wrap });
            content.Children.Add(too);
            var dialog = new ContentDialog
            {
                Title = Words.MemoryBannerPictureTitle(),
                Content = content,
                PrimaryButtonText = Words.MemoryTrash(),
                CloseButtonText = Words.EntryCancel(),
                DefaultButton = ContentDialogButton.Close,
            };
            AutomationProperties.SetAutomationId(dialog, "banner-picture-dialog");
            if (await Dialogs.Show(window, dialog) == ContentDialogResult.Primary)
            {
                var remove = too.IsChecked == true;
                var (answer, _) = await Core.CallAsync(new Call.GalleryTrashBannerPicture { gallery = gallery, removeBanner = remove });
                Log.Write($"Gallery.TrashBannerPicture (remove the banner {remove}): {(answer is Answer.Refused r ? $"Refused {r.why}" : answer.GetType().Name)}");
                if (answer is Answer.Said { notice: { } n }) say(false, Said.Notice(n));
            }
            else
            {
                Log.Write("banner picture: the trash declined");
                Act.Send(new Call.GalleryCancelAsk { gallery = gallery });
            }
        }
        catch (Exception e)
        {
            Log.Write($"the banner's question failed ({e.GetType().Name})");
        }
    }
}

/// <summary>The banner's dialog (core's BannerEditor, the web's BannerDialog.vue).</summary>
internal sealed class BannerDialog
{
    private const double WideWidth = 320, WideHeight = 180, PhoneEdge = 180;

    /// <summary>The file types the picker offers: images and videos, as "Add photos" offers them.</summary>
    private static readonly string[] Types =
        [".jpg", ".jpeg", ".png", ".gif", ".webp", ".heic", ".heif", ".avif", ".tif", ".tiff", ".bmp", ".mp4", ".m4v", ".mov", ".webm", ".mkv", ".avi", ".3gp", ".wmv"];

    private readonly Window _window;
    private readonly long _gallery;
    private readonly long _id;
    private readonly Grid _wide = new() { Width = WideWidth, Height = WideHeight };
    private readonly Grid _phone = new() { Width = PhoneEdge, Height = PhoneEdge };
    private readonly Button _choose = new() { Content = Words.MemoryBannerChoose() };
    private readonly ProgressRing _ring = new() { IsActive = true, Width = 24, Height = 24, Visibility = Visibility.Collapsed };
    private readonly ProgressBar _progress = new() { Minimum = 0, Maximum = 1, Visibility = Visibility.Collapsed };
    private readonly TextBlock _progressText = new() { Visibility = Visibility.Collapsed };
    private readonly Button _cancel = new() { Content = Words.UploadCancel(), Visibility = Visibility.Collapsed };
    private readonly TextBlock _problem = new() { TextWrapping = TextWrapping.Wrap, Visibility = Visibility.Collapsed };
    private readonly ContentDialog _dialog;
    private long? _previewed;
    private bool _saving;
    private Watch? _watch;

    /// <summary>Gallery <paramref name="gallery"/>'s banner dialog: opened in the core (Banner.Open) and shown.</summary>
    public static async void Open(Window window, long gallery, bool hasBanner)
    {
        if (Dialogs.Open) return;
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.BannerOpen { gallery = gallery });
            if (answer is not Answer.BannerOpened { banner: var id })
            {
                Log.Write($"Banner.Open answered {(answer is Answer.Refused r ? $"Refused {r.why}" : answer.GetType().Name)}");
                return;
            }
            Log.Write($"banner dialog {id} open");
            await new BannerDialog(window, gallery, id, hasBanner).Show();
        }
        catch (Exception e)
        {
            Log.Write($"the banner dialog failed ({e.GetType().Name})");
        }
    }

    private BannerDialog(Window window, long gallery, long id, bool hasBanner)
    {
        _window = window;
        _gallery = gallery;
        _id = id;
        var content = new StackPanel { Spacing = 12, MinWidth = 520 };
        content.Children.Add(new TextBlock { Text = Words.MemoryBannerHint(), TextWrapping = TextWrapping.Wrap, Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"] });
        var previews = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 16 };
        previews.Children.Add(Preview(_wide, Words.MemoryBannerPreviewWide(), "banner-preview-wide"));
        previews.Children.Add(Preview(_phone, Words.MemoryBannerPreviewPhone(), "banner-preview-phone"));
        content.Children.Add(previews);
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        AutomationProperties.SetAutomationId(_choose, "banner-choose");
        _choose.Click += (_, _) => Choose();
        buttons.Children.Add(_choose);
        buttons.Children.Add(_ring);
        content.Children.Add(buttons);
        AutomationProperties.SetAutomationId(_progress, "banner-progress");
        content.Children.Add(_progress);
        AutomationProperties.SetAutomationId(_progressText, "banner-progress-text");
        content.Children.Add(_progressText);
        AutomationProperties.SetAutomationId(_cancel, "banner-cancel");
        _cancel.Click += (_, _) =>
        {
            Log.Write("banner dialog: cancel pressed");
            Act.Send(new Call.BannerCancel { banner = _id });
        };
        content.Children.Add(_cancel);
        _problem.Foreground = (Brush)Application.Current.Resources["SystemFillColorCriticalBrush"];
        AutomationProperties.SetAutomationId(_problem, "banner-problem");
        content.Children.Add(_problem);
        _dialog = new ContentDialog
        {
            Title = hasBanner ? Words.MemoryBannerChange() : Words.MemoryBannerAdd(),
            Content = new ScrollViewer { Content = content, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Padding = new Thickness(0, 0, 16, 0) },
            PrimaryButtonText = Words.MemoryBannerSave(),
            CloseButtonText = Words.EntryCancel(),
            DefaultButton = ContentDialogButton.Primary,
            IsPrimaryButtonEnabled = false,
        };
        MemoryFacts.Widen(_dialog, 640);
        AutomationProperties.SetAutomationId(_dialog, "banner-dialog");
        _dialog.PrimaryButtonClick += (_, args) =>
        {
            args.Cancel = true;
            Run(new Call.BannerSave { banner = _id }, "Banner.Save");
        };
        _dialog.SecondaryButtonClick += (_, args) =>
        {
            args.Cancel = true;
            Run(new Call.BannerRemove { banner = _id }, "Banner.Remove");
        };
        // a save under way is stopped by its own Cancel, never by closing the dialog under it
        _dialog.Closing += (_, args) =>
        {
            if (_saving) args.Cancel = true;
        };
        if (hasBanner) Current();
        _watch = Core.Watch(new Watched.Banner { banner = id }, Updated, e => Log.Write($"a banner dialog update: {e.Message}"));
    }

    private static StackPanel Preview(Grid frame, string caption, string id)
    {
        frame.CornerRadius = new CornerRadius(8);
        frame.Background = (Brush)Application.Current.Resources["CardBackgroundFillColorDefaultBrush"];
        frame.BorderBrush = (Brush)Application.Current.Resources["CardStrokeColorDefaultBrush"];
        frame.BorderThickness = new Thickness(1);
        AutomationProperties.SetAutomationId(frame, id);
        AutomationProperties.SetName(frame, caption);
        var column = new StackPanel { Spacing = 4 };
        column.Children.Add(frame);
        column.Children.Add(new TextBlock { Text = caption, Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"] });
        return column;
    }

    private async Task Show()
    {
        await Dialogs.Show(_window, _dialog);
        _watch?.Dispose();
        Act.Send(new Call.BannerClose { banner = _id });
        Log.Write($"banner dialog {_id} closed");
    }

    /// <summary>The banner as it is, in both previews, until a file is picked.</summary>
    private async void Current()
    {
        try
        {
            var (answer, bytes) = await Core.CallAsync(new Call.BannerPicture { gallery = _gallery });
            if (answer is Answer.Bytes && bytes is not null && _previewed is null) await Fill(bytes);
        }
        catch (Exception e)
        {
            Log.Write($"banner dialog: the banner's picture was not drawn ({e.GetType().Name})");
        }
    }

    /// <summary>Both previews drawn from <paramref name="bytes"/> as the page draws the banner.</summary>
    private async Task Fill(byte[] bytes)
    {
        var picture = await BannerPicture.Of(bytes);
        var frame = await picture.Frame(WideWidth, 10000);
        var surround = frame is null ? null : await picture.Surround(frame.surroundWidth, frame.surroundHeight);
        _wide.Children.Clear();
        _wide.Children.Add(BannerPicture.Drawn(WideWidth, WideHeight, picture.Whole, surround));
        _phone.Children.Clear();
        _phone.Children.Add(BannerPicture.Drawn(PhoneEdge, PhoneEdge, picture.Whole, surround));
    }

    /// <summary>"Choose an image or a video": the open picker for one file, its path to the core (Banner.Pick).</summary>
    private async void Choose()
    {
        try
        {
            var picker = new FileOpenPicker { SuggestedStartLocation = PickerLocationId.PicturesLibrary, ViewMode = PickerViewMode.Thumbnail };
            foreach (var t in Types) picker.FileTypeFilter.Add(t);
            WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(_window));
            var file = await picker.PickSingleFileAsync();
            if (file is null) return;
            var (answer, _) = await Core.CallAsync(new Call.BannerPick { banner = _id, path = file.Path });
            Log.Write($"Banner.Pick: {(answer is Answer.Refused r ? $"Refused {r.why}" : answer.GetType().Name)}");
        }
        catch (Exception e)
        {
            Log.Write($"banner dialog: the pick failed ({e.GetType().Name}: {e.Message})");
        }
    }

    /// <summary>A save or a removal, answered when it ended: done closes the dialog, anything else is said in it.</summary>
    private async void Run(Call call, string what)
    {
        _saving = true;
        try
        {
            var (answer, _) = await Core.CallAsync(call);
            Log.Write($"{what}: {(answer is Answer.BannerSaved s ? s.outcome.ToString() : answer is Answer.Refused r ? $"Refused {r.why}" : answer.GetType().Name)}");
            _saving = false;
            if (answer is Answer.BannerSaved { outcome: BannerSave.Done }) _dialog.Hide();
        }
        catch (Exception e)
        {
            Log.Write($"{what} failed ({e.GetType().Name})");
        }
        finally
        {
            _saving = false;
        }
    }

    private async void Updated(Update update)
    {
        if (update is not Update.Banner { shown: var s }) return;
        if (s is null)
        {
            _saving = false;
            _dialog.Hide();
            return;
        }
        Log.Write($"banner dialog shown: has {s.hasBanner}, picking {s.picking}, picked {s.picked?.medium.ToString() ?? "-"} #{s.picked?.pick}, saving {s.saving} {s.progress?.ToString("0.00") ?? "-"}, said {s.said?.ToString() ?? "-"}, done {s.done}, save {s.canSave}, remove {s.canRemove}");
        _ring.Visibility = s.picking ? Visibility.Visible : Visibility.Collapsed;
        _choose.IsEnabled = !s.saving;
        _progress.Visibility = _progressText.Visibility = _cancel.Visibility = s.saving ? Visibility.Visible : Visibility.Collapsed;
        _progress.IsIndeterminate = s.progress is null or <= 0;
        _progress.Value = s.progress ?? 0;
        _progressText.Text = CurationText.BannerProgress(s.progress);
        var said = CurationText.BannerSaidText(s.said);
        _problem.Text = said ?? "";
        _problem.Visibility = said is null ? Visibility.Collapsed : Visibility.Visible;
        _dialog.IsPrimaryButtonEnabled = s.canSave && !s.saving;
        _dialog.SecondaryButtonText = s.canRemove ? Words.MemoryBannerRemove() : "";
        _dialog.IsSecondaryButtonEnabled = s.canRemove && !s.saving;
        if (s.done)
        {
            _saving = false;
            _dialog.Hide();
            return;
        }
        if (s.picked is { } picked && picked.pick != _previewed)
        {
            _previewed = picked.pick;
            try
            {
                var (answer, bytes) = await Core.CallAsync(new Call.BannerPreview { banner = _id });
                if (answer is Answer.Bytes && bytes is not null && _previewed == picked.pick) await Fill(bytes);
                else Log.Write($"Banner.Preview answered {answer.GetType().Name}");
            }
            catch (Exception e)
            {
                Log.Write($"banner dialog: the preview was not drawn ({e.GetType().Name})");
            }
        }
    }
}
