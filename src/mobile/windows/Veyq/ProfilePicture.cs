// One's own profile picture as this side decodes and renders it (ADR 0148 decision 2, the host's half): the cropper's
// picture decoded upright from the file the open picker gave, and the core's Picture.Square answered — the square the
// pose frames, scaled as the core's CoverFrame scales it in a frame of that edge, copied out as RGBA over white. Read in
// place from its path, nothing written; the pixels this side read are zeroed once copied out.
using System;
using System.IO;
using System.Runtime.InteropServices.WindowsRuntime;
using System.Threading.Tasks;
using Veyq.Bridge.Wire;
using Windows.Graphics.Imaging;

namespace Veyq;

/// <summary>A profile picture as this side decodes and renders it (the host's half of ADR 0148 decision 2).</summary>
internal static class ProfilePicture
{
    /// <summary>The longest side a picture is shown at in the cropper; the pose does not depend on it (only the aspect).</summary>
    private const int ShownLongSide = 1024;

    /// <summary>The core's Picture.Square, answered with <c>edge</c> × <c>edge</c> RGBA; null for a request that is no such.</summary>
    public static Task<(HostReply Reply, byte[]? Payload)>? Answer(HostRequest request) => request switch
    {
        HostRequest.PictureSquare r => Guard(r),
        _ => null,
    };

    private static async Task<(HostReply, byte[]?)> Guard(HostRequest.PictureSquare r)
    {
        try
        {
            var rgba = await Square(r.path, r.pose, r.edge);
            return (new HostReply.Pixels { width = r.edge, height = r.edge }, rgba);
        }
        catch (Exception e)
        {
            // never the path: the kind of failure alone
            Log.Write($"a profile picture was not rendered: {e.GetType().Name}");
            return (new HostReply.Failed { error = e.GetType().Name }, null);
        }
    }

    /// <summary>The picture at <paramref name="path"/> upright, its longer side at most <see cref="ShownLongSide"/>, for the cropper.</summary>
    public static async Task<SoftwareBitmap> Shown(string path)
    {
        using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read).AsRandomAccessStream();
        var decoder = await BitmapDecoder.CreateAsync(stream);
        var (ow, oh) = (decoder.OrientedPixelWidth, decoder.OrientedPixelHeight);
        if (ow == 0 || oh == 0) throw new InvalidDataException("an empty picture");
        var scale = Math.Min(1.0, (double)ShownLongSide / Math.Max(ow, oh));
        return await Decode(decoder, (uint)Math.Max(1, Math.Round(ow * scale)), (uint)Math.Max(1, Math.Round(oh * scale)));
    }

    /// <summary>The picture decoded upright at <paramref name="width"/> × <paramref name="height"/> (its oriented size scaled).</summary>
    private static Task<SoftwareBitmap> Decode(BitmapDecoder decoder, uint width, uint height)
    {
        // the transform scales the stored frame, before its orientation turns it upright
        var turned = decoder.OrientedPixelWidth != decoder.PixelWidth;
        var transform = new BitmapTransform
        {
            ScaledWidth = turned ? height : width,
            ScaledHeight = turned ? width : height,
            InterpolationMode = BitmapInterpolationMode.Fant,
        };
        return decoder.GetSoftwareBitmapAsync(BitmapPixelFormat.Bgra8, BitmapAlphaMode.Premultiplied, transform,
            ExifOrientationMode.RespectExifOrientation, ColorManagementMode.ColorManageToSRgb).AsTask();
    }

    /// <summary>The square the pose frames, <paramref name="edge"/> pixels a side, RGBA over white: the picture upright,
    /// scaled as the core's CoverFrame scales it in a frame of that edge (its base scale times the zoom), the window at
    /// the pose's offset copied out.</summary>
    public static async Task<byte[]> Square(string path, Pose pose, int edge)
    {
        using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read).AsRandomAccessStream();
        var decoder = await BitmapDecoder.CreateAsync(stream);
        var (ow, oh) = (decoder.OrientedPixelWidth, decoder.OrientedPixelHeight);
        if (ow == 0 || oh == 0) throw new InvalidDataException("an empty picture");
        var scale = Math.Max((double)edge / ow, (double)edge / oh) * pose.zoom;
        var (sw, sh) = ((uint)Math.Max(edge, Math.Round(ow * scale)), (uint)Math.Max(edge, Math.Round(oh * scale)));
        using var bitmap = await Decode(decoder, sw, sh);
        var bgra = new byte[sw * sh * 4];
        try
        {
            bitmap.CopyToBuffer(bgra.AsBuffer());
            return Window(bgra, (int)sw, (int)sh, (int)Math.Round(-pose.x), (int)Math.Round(-pose.y), edge);
        }
        finally
        {
            Array.Clear(bgra);
        }
    }

    /// <summary><paramref name="edge"/> × <paramref name="edge"/> of premultiplied BGRA rows (<paramref name="width"/> ×
    /// <paramref name="height"/>) from (<paramref name="left"/>, <paramref name="top"/>), as RGBA over white; what lies
    /// outside the picture is white.</summary>
    public static byte[] Window(byte[] bgra, int width, int height, int left, int top, int edge)
    {
        var rgba = new byte[edge * edge * 4];
        for (var y = 0; y < edge; y++)
        {
            for (var x = 0; x < edge; x++)
            {
                var to = (y * edge + x) * 4;
                int sx = left + x, sy = top + y;
                if (sx < 0 || sy < 0 || sx >= width || sy >= height)
                {
                    rgba[to] = rgba[to + 1] = rgba[to + 2] = rgba[to + 3] = 255;
                    continue;
                }
                var at = (sy * width + sx) * 4;
                var under = 255 - bgra[at + 3]; // what white shows through a transparent pixel
                rgba[to] = (byte)Math.Min(255, bgra[at + 2] + under);
                rgba[to + 1] = (byte)Math.Min(255, bgra[at + 1] + under);
                rgba[to + 2] = (byte)Math.Min(255, bgra[at] + under);
                rgba[to + 3] = 255;
            }
        }
        return rgba;
    }
}
