using System;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Windows.Storage;
using Veyq.Core;

namespace Veyq.Tests;

/// <summary>
/// The music's host side that holds without a player (ADR 0142 decisions 2, 5 and 7): a deck's volume ramp as the steps the
/// host runs, never longer than the core's 50 ms; the bar's m:ss; and a picked track's length (Media.Duration) for a WAV and
/// an Ogg file — the format Windows.Media.Editing's clip refuses, read through Media Foundation's source where Windows
/// decodes it at all (the Web Media extension), and none where it does not.
/// </summary>
[TestClass]
public sealed class MusicTest
{
    [TestMethod]
    public void ARampIsStepsOfAtMost50MsEndingAtItsVolume()
    {
        var fade = MusicRamp.Steps(0, 1, 5000);
        Assert.AreEqual(125, fade.Count, "a 5 s crossfade in 40 ms steps");
        Assert.IsTrue(MusicRamp.Interval(5000).TotalMilliseconds <= 50);
        Assert.AreEqual(1.0, fade[^1]);
        Assert.IsTrue(fade.Zip(fade.Skip(1)).All(p => p.Second > p.First), "rising all the way");
        Assert.AreEqual(0.008, fade[0], 1e-9);
        var press = MusicRamp.Steps(1, 0, 1000);
        Assert.AreEqual(25, press.Count);
        Assert.AreEqual(0.0, press[^1]);
        // a ramp of no length is set at once; an odd length still never steps longer than 50 ms
        CollectionAssert.AreEqual(new[] { 0.5 }, MusicRamp.Steps(0, 0.5, 0).ToArray());
        Assert.IsTrue(MusicRamp.Interval(1001).TotalMilliseconds <= 50);
        Assert.AreEqual(1.0, MusicRamp.Steps(0.2, 7, 100)[^1], "held to 0–1");
    }

    private static string Temp(string name) => Path.Combine(Path.GetTempPath(), $"veyq-music-{Environment.ProcessId}-{name}");

    /// <summary>2 s of 16-bit stereo PCM at 44.1 kHz, a 440 Hz tone.</summary>
    private static byte[] Wav()
    {
        const int rate = 44100, seconds = 2, channels = 2;
        var samples = rate * seconds;
        var data = samples * channels * 2;
        using var m = new MemoryStream();
        using var w = new BinaryWriter(m);
        w.Write("RIFF"u8);
        w.Write(36 + data);
        w.Write("WAVEfmt "u8);
        w.Write(16);
        w.Write((short)1);
        w.Write((short)channels);
        w.Write(rate);
        w.Write(rate * channels * 2);
        w.Write((short)(channels * 2));
        w.Write((short)16);
        w.Write("data"u8);
        w.Write(data);
        for (var i = 0; i < samples; i++)
        {
            var s = (short)(Math.Sin(2 * Math.PI * 440 * i / rate) * 8000);
            w.Write(s);
            w.Write(s);
        }
        w.Flush();
        return m.ToArray();
    }

    /// <summary>1 s of Ogg Opus, mono (ffmpeg's libopus at 6 kb/s, 2026-11-16).</summary>
    private const string Opus = "T2dnUwACAAAAAAAAAAAs3F6IAAAAAOchQWoBE09wdXNIZWFkAQE4AYC7AAAAAABPZ2dTAAAAAAAAAAAAACzcXogBAAAA1D0zRgE+T3B1c1RhZ3MNAAAATGF2ZjYyLjEyLjEwMAEAAAAdAAAAZW5jb2Rlcj1MYXZjNjIuMjguMTAwIGxpYm9wdXNPZ2dTAACAuwAAAAAAACzcXogCAAAAtqqi3DINFhAUEhIQEBMREA0SEg8REBEODg4ODAwPDA4ODw4NDgwMDA0OCw4NDw4MDg0NDw0NDgiDbYLQHP3qSf4BP8AIpxpdipguX28Fy0oa7bfzyNYFwV7ACKEvEORd+9xSahc4epgMUAihTMA963GwGbsf4uruHnFJqBPwCKE40Hcyqla+uRSVKjlbIN8gCKE40HcvR49DuM0l2MFl/nLACKE40HcyqlwzVOiT+0wNjwihLxDkXfvcBs9QhvmzHCAIoTjQdzKqVrnauMIokfHghKaACKE40Hcyqmci64tM9Bmt9IAIoUgDRtVSc9qEdz7RbVOgCKE40HcyqlZAy5RMOQihONB3MqpeTazF92SrnIxIoAihONB3MqpWudrDT94qIKnYEAihONB3MqpeUQWgNfOsgAihSANG1VJ9yZsNqn/JY/6gCKE40Hcyqla+uQJcJXJGygihLxDkXfvcBriequmQWXzQCKE40Hcyqla5v7deKWQIoTjQdzKqXlK4XKHx0QihSANG1VJ9yeIkZpzqCKE40HcyqlxPlMq4rsAIoTjQdy9Hjgew3CIIoouEseWzId2gyZoIooEu/AIzGDbFOvABjIcIoouEseWzIuJGpMAIoouEsbsCkIazBO8LWwiii4Sx5bMg7XUhNDT1CKKLhLHlsyDtbmsDUw8gCKKBLvwCMxg2xTnzScAIOpRFnjEwvwtYj+jICDquAmrKiBj6MGbwacAIO2S1njE4n76pxjgIO2S1njE4oLH9T4AIO2S1ni8wWlpu3eAIO2S1ni76iEC5Cv8eCDthdqKeZ1bwrog+boAIO2S1njE4oTiUhwg6lEWeMT21xHRK0CdcCDqURZ4vMF8QZPA1OAg6lEWeLx0fLQQDOHj7MAg6kQainmgV0iekKSagCDqURZ4xOJ+0PhD4CDqURZ4xMKsM7hBPp4AIOpRFni8wVkSETBJ4CDqURZ4vG8QPFzcIIAg6kQainktenTL42GxzgAg6lEWeMTihTwduKzgIOpRFnjEwhSOvqzCWCDqURZ4vMUgXN5B5mRBPZ2dTAAS4vAAAAAAAACzcXogDAAAAL3qrZQEOCAY6fkI3rwSqvFqw/zo=";

    [TestMethod]
    public async Task APickedTracksLengthIsReadForAWavAndForAnOggWhereWindowsDecodesIt()
    {
        var wav = Temp("tone.wav");
        var ogg = Temp("tone.ogg");
        await File.WriteAllBytesAsync(wav, Wav());
        await File.WriteAllBytesAsync(ogg, Convert.FromBase64String(Opus));
        try
        {
            var wavMs = await MediaTiers.DurationMsAsync(wav);
            Assert.IsTrue(wavMs is >= 1950 and <= 2050, $"the WAV: {wavMs} ms");
            // what Media Foundation itself says of the Ogg: read where the Web Media extension is there, refused where not
            long? decoded;
            try
            {
                using var source = Windows.Media.Core.MediaSource.CreateFromStorageFile(await StorageFile.GetFileFromPathAsync(ogg));
                await source.OpenAsync();
                decoded = (long?)source.Duration?.TotalMilliseconds;
            }
            catch (Exception)
            {
                decoded = null;
            }
            var oggMs = await MediaTiers.DurationMsAsync(ogg);
            if (decoded is null) Assert.IsNull(oggMs, "no length where Windows decodes no Ogg");
            else Assert.IsTrue(oggMs is >= 950 and <= 1050, $"the Ogg, which the clip refuses: {oggMs} ms (the source says {decoded})");
            Console.WriteLine($"Ogg Opus decoded here: {decoded is not null}");
        }
        finally
        {
            File.Delete(wav);
            File.Delete(ogg);
        }
    }
}
