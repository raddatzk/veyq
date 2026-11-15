// A Memory's cover on its list row (the CR "Memories on every app" D10; Linux's home::cover_prefix, rule for rule): the
// kind's glyph until the core names a cover (Shell.RowCover), then the framed part of the stored picture, cut where the
// core says (Cover.Cut) and decoded small by WIC. A list is drawn anew on every state: each row's cover is held by node id
// with the frame it was cut for, and the core sends the picture again only once the cover or its crop changed. Decoded in
// memory only (ADR 0047).
using System;
using System.Runtime.InteropServices.WindowsRuntime;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Veyq.Bridge;
using Veyq.Bridge.Wire;
using Windows.Graphics.Imaging;
using Windows.Storage.Streams;

namespace Veyq;

internal static class RowCovers
{
    /// <summary>The longest side a row's cover is decoded at (it is drawn 64 × 36).</summary>
    private const int Edge = 128;

    private sealed record Held(string Frame, ImageSource Picture);

    private static readonly Recent<Held> Covers = new(256);

    /// <summary><paramref name="glyph"/> in a row's first column, replaced by node <paramref name="nodeId"/>'s cover once
    /// the core names one.</summary>
    public static UIElement Prefix(FontIcon glyph, string nodeId)
    {
        var image = new Image { Width = 64, Height = 36, Stretch = Stretch.UniformToFill, Visibility = Visibility.Collapsed };
        var frame = new Border { CornerRadius = new CornerRadius(4), Child = image, VerticalAlignment = VerticalAlignment.Center };
        var cell = new Grid { VerticalAlignment = VerticalAlignment.Center };
        cell.Children.Add(glyph);
        cell.Children.Add(frame);
        var held = Covers.Get(nodeId);
        if (held is not null) Show(image, glyph, held.Picture);
        Load(nodeId, held?.Frame, image, glyph);
        return cell;
    }

    private static void Show(Image image, FontIcon glyph, ImageSource? picture)
    {
        image.Source = picture;
        image.Visibility = picture is null ? Visibility.Collapsed : Visibility.Visible;
        glyph.Visibility = picture is null ? Visibility.Visible : Visibility.Collapsed;
    }

    private static async void Load(string nodeId, string? held, Image image, FontIcon glyph)
    {
        try
        {
            var (answer, bytes) = await Core.CallAsync(new Call.ShellRowCover { nodeId = nodeId, held = held });
            switch (answer)
            {
                case Answer.RowCover { sent: false }:
                    return;
                case Answer.RowCover { sent: true, frame: var f, crop: var crop } when bytes is not null:
                    var picture = await CutAsync(bytes, crop);
                    if (picture is null) return;
                    Covers.Put(nodeId, new Held(f, picture));
                    Log.Write($"a row's cover drawn: {bytes.Length} B, crop {crop.width:0.00}×{crop.height:0.00}");
                    Show(image, glyph, picture);
                    return;
                case Answer.Refused:
                    Covers.Remove(nodeId);
                    Show(image, glyph, null);
                    return;
                default:
                    Log.Write($"Shell.RowCover answered {answer.GetType().Name}");
                    return;
            }
        }
        catch (Exception e)
        {
            // an exception out of an async void would end the app in combase, with nothing in the log
            Log.Write($"a row's cover was not drawn ({e.GetType().Name})");
        }
    }

    /// <summary>The part of the stored picture <paramref name="crop"/> frames, decoded with that part's long side at
    /// <see cref="Edge"/> at most: the cut asked of the core at the picture's size, then again at the size it is decoded at.</summary>
    private static async System.Threading.Tasks.Task<ImageSource?> CutAsync(byte[] bytes, Crop crop)
    {
        using var stream = new InMemoryRandomAccessStream();
        await stream.WriteAsync(bytes.AsBuffer());
        stream.Seek(0);
        var decoder = await BitmapDecoder.CreateAsync(stream);
        var (w, h) = ((int)decoder.PixelWidth, (int)decoder.PixelHeight);
        if (await Cut(crop, w, h) is not { } whole) return null;
        var scale = Math.Min(1.0, (double)Edge / Math.Max(1, Math.Max(whole.width, whole.height)));
        var (sw, sh) = (Math.Max(1, (int)Math.Round(w * scale)), Math.Max(1, (int)Math.Round(h * scale)));
        if (await Cut(crop, sw, sh) is not { } c) return null;
        var transform = new BitmapTransform
        {
            ScaledWidth = (uint)sw, ScaledHeight = (uint)sh, InterpolationMode = BitmapInterpolationMode.Fant,
            Bounds = new BitmapBounds { X = (uint)c.x, Y = (uint)c.y, Width = (uint)c.width, Height = (uint)c.height },
        };
        var bitmap = await decoder.GetSoftwareBitmapAsync(BitmapPixelFormat.Bgra8, BitmapAlphaMode.Premultiplied, transform,
            ExifOrientationMode.IgnoreExifOrientation, ColorManagementMode.ColorManageToSRgb);
        var source = new SoftwareBitmapSource();
        await source.SetBitmapAsync(bitmap);
        return source;
    }

    private static async System.Threading.Tasks.Task<Answer.CoverCut?> Cut(Crop crop, int width, int height)
    {
        var (cut, _) = await Core.CallAsync(new Call.CoverCut { crop = crop, width = width, height = height });
        if (cut is Answer.CoverCut c && c.width > 0 && c.height > 0) return c;
        Log.Write($"a row's cover: Cover.Cut answered {cut.GetType().Name}");
        return null;
    }
}
