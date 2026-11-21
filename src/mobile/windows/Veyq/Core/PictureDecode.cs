// A picture as Windows decodes it, and a WebP it cannot: WIC (the Windows Imaging Component) has WebP only through the
// Store's WebP Image Extension, which Windows 11 provisions and a person can remove, and a sideloaded MSIX cannot depend
// on it (ADR 0139 decision 6). WIC stays the first try; a WebP it refuses goes to the core (Picture.Decode: libwebp's
// decoder in veyq.dll), whose answer is a PNG, a codec WIC always has. So a removed extension changes nothing the person
// sees. A member's picture is always a WebP (ADR 0127).

namespace Veyq.Core;

using System;
using System.Threading.Tasks;

internal static class PictureDecode
{
    /// <summary><paramref name="bytes"/> decoded by <paramref name="platform"/> (WIC); a WebP it throws on is decoded by
    /// <paramref name="core"/> into a PNG, which <paramref name="platform"/> decodes instead. What is no WebP, and a WebP the
    /// core refuses too, throws as the platform threw.</summary>
    public static async Task<T> Decode<T>(byte[] bytes, Func<byte[], Task<T>> platform, Func<byte[], Task<byte[]?>> core)
    {
        try
        {
            return await platform(bytes);
        }
        catch (Exception e) when (IsWebp(bytes))
        {
            var png = await core(bytes);
            if (png is null) throw;
            // the log's witness of the way taken (never whose picture it is)
            Log.Write($"a WebP the platform did not decode ({e.GetType().Name}) decoded by the core: {png.Length} B");
            return await platform(png);
        }
    }

    /// <summary>A WebP's RIFF header, as the core sniffs it (blob/Sniff.kt).</summary>
    public static bool IsWebp(byte[] b) =>
        b.Length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
}
