using System;
using System.Linq;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Veyq.Bridge.Wire;
using Veyq.Apps.Memories;
using Veyq.Core;

namespace Veyq.Tests;

/// <summary>What curating a Memory converts and says without a widget (CurationText.cs): the people line's slots, the
/// capture time's local date and time as epoch ms in the device's zone, and the editors' words.</summary>
[TestClass]
public sealed class CurationTextTest
{
    private static PeopleShown People(int count, string[] avatars, int more) => new()
    {
        people = Enumerable.Range(0, count).Select(i => new ParticipantShown { userId = $"u{i}", name = $"N{i}", former = false, opensProfile = true }).ToList(),
        avatars = avatars,
        more = more,
        named = true,
        edits = true,
        add = false,
    };

    [TestMethod]
    public void UpToThreeFacesAreDrawnElseTwoAndThePlusInTheThirdSlot()
    {
        var three = CurationText.Slots(People(3, ["u0", "u1", "u2"], 0));
        CollectionAssert.AreEqual(new[] { "u0", "u1", "u2" }, three.Select(s => s.UserId).ToArray());
        var five = CurationText.Slots(People(5, ["u0", "u1"], 3));
        Assert.AreEqual(3, five.Count);
        Assert.AreEqual("u1", five[1].UserId);
        Assert.IsNull(five[2].UserId, "the third slot is the +N");
        Assert.AreEqual(3, five[2].More);
    }

    [TestMethod]
    public void AFormerMemberIsNamedByTheFormerWordsAnUnnamedPersonByTheUnknownOnes()
    {
        Assert.AreEqual("Ada", CurationText.Name("Ada", false));
        Assert.AreEqual(Words.MemoryParticipantsFormer(), CurationText.Name(null, true));
        var unknown = new PersonShown { userId = "x", name = null, former = false, picture = false, username = null, sections = [], memories = null };
        Assert.AreEqual(Words.PeopleUnknownMember(), PersonText.Title(unknown));
        Assert.AreEqual(Words.MemoryParticipantsFormer(), PersonText.Title(unknown with { former = true }));
        Assert.AreEqual(Words.MemoryPeopleWithName("Ada"), CurationText.MemoriesWith(unknown with { name = "Ada" }));
    }

    [TestMethod]
    public void ThePickedLocalDateAndTimeAreEpochMillisecondsInTheDevicesZone()
    {
        var berlin = TimeZoneInfo.FindSystemTimeZoneById("W. Europe Standard Time");
        // summer: UTC+2
        Assert.AreEqual(DateTimeOffset.Parse("2026-07-14T08:30:00Z").ToUnixTimeMilliseconds(),
            CurationText.EpochMs(new DateTimeOffset(2026, 7, 14, 0, 0, 0, TimeSpan.FromHours(2)), new TimeSpan(10, 30, 45), berlin), "the seconds dropped, the summer offset");
        // winter: UTC+1, and the day as the picker hands it (any offset: only its date counts)
        Assert.AreEqual(DateTimeOffset.Parse("2026-01-02T22:05:00Z").ToUnixTimeMilliseconds(),
            CurationText.EpochMs(new DateTimeOffset(2026, 1, 2, 0, 0, 0, TimeSpan.Zero), new TimeSpan(23, 5, 0), berlin));
        // the spring change's skipped hour lands after the gap
        Assert.AreEqual(DateTimeOffset.Parse("2026-03-29T01:30:00Z").ToUnixTimeMilliseconds(),
            CurationText.EpochMs(new DateTimeOffset(2026, 3, 29, 0, 0, 0, TimeSpan.Zero), new TimeSpan(2, 30, 0), berlin));
        // the autumn change's doubled hour: its first occurrence (still summer time)
        Assert.AreEqual(DateTimeOffset.Parse("2026-10-25T00:30:00Z").ToUnixTimeMilliseconds(),
            CurationText.EpochMs(new DateTimeOffset(2026, 10, 25, 0, 0, 0, TimeSpan.Zero), new TimeSpan(2, 30, 0), berlin));
        // and back: the pickers show the local day and the minute
        var (day, time) = CurationText.Local(DateTimeOffset.Parse("2026-07-14T08:30:59Z").ToUnixTimeMilliseconds(), berlin);
        Assert.AreEqual((2026, 7, 14), (day.Year, day.Month, day.Day));
        Assert.AreEqual(new TimeSpan(10, 30, 0), time);
    }

    [TestMethod]
    public void TheEditorsSayTheWebsWordsForWhatTheCoreSaid()
    {
        Assert.AreEqual(Words.MediaCapturedAtConflict(), CurationText.CaptureSaid(CurationSaid.Conflict));
        Assert.AreEqual(Words.MediaCapturedAtSaveFailed(), CurationText.CaptureSaid(CurationSaid.Failed));
        Assert.AreEqual(Words.MediaMetaUnreadable(), CurationText.CaptureSaid(CurationSaid.Unreadable));
        Assert.IsNull(CurationText.CaptureSaid(null));
        Assert.AreEqual(Words.MemoryParticipantsSaveFailed(), CurationText.ParticipantsSaid(CurationSaid.Failed));
        Assert.AreEqual(Words.MemoryBannerStale(), CurationText.BannerSaidText(BannerSaid.Stale, "40 MB"));
        // the too-large words name the core's bound as it wrote it (BannerEditorShown.tooLargeBound)
        Assert.AreEqual(Words.MemoryBannerTooLarge("40 MB"), CurationText.BannerSaidText(BannerSaid.TooLarge, "40 MB"));
        Assert.AreEqual(Words.MemoryBannerPreparing(), CurationText.BannerProgress(null));
        Assert.AreEqual(Words.MemoryBannerUploading(42), CurationText.BannerProgress(0.42));
    }
}
