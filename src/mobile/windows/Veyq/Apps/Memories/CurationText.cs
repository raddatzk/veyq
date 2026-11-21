// What curating a Memory says and converts on Windows without a widget (ADR 0144; Linux's curation.rs): the people line's
// slots, a participant's and a person's name, the picker's and the editors' words for what the core said, a banner's
// progress, and the capture time's local date and time as epoch ms — the platform's calendar's part (ADR 0144 decision 3:
// the core does no zone arithmetic). Pure, so Veyq.Tests reads it.

namespace Veyq.Apps.Memories;

using System;
using System.Collections.Generic;
using System.Linq;
using Veyq.Bridge.Wire;
using Veyq.Core;

internal static class CurationText
{
    /// <summary>One slot of the people line: a person's avatar (their user id) or the "+N" of the rest.</summary>
    public readonly record struct Slot(string? UserId, int More);

    /// <summary>The people line's slots as the core lists them (PeopleShown.avatars: all of up to three, else the first
    /// two and "+N" in the third).</summary>
    public static IReadOnlyList<Slot> Slots(PeopleShown p)
    {
        var slots = p.avatars.Select(id => new Slot(id, 0)).ToList();
        if (p.more > 0) slots.Add(new Slot(null, p.more));
        return slots;
    }

    /// <summary>A participant as a line names them: their name, the former-member words, or nothing while names read.</summary>
    public static string Name(string? name, bool former) => name ?? (former ? Words.MemoryParticipantsFormer() : "");

    /// <summary>"Memories with {name}", or "with this person" where the page names nobody.</summary>
    public static string MemoriesWith(PersonShown p) => p.name is { } name ? Words.MemoryPeopleWithName(name) : Words.MemoryPeopleWith();

    /// <summary>What a participants picker says after a save that did not land.</summary>
    public static string? ParticipantsSaid(CurationSaid? said) => said switch
    {
        CurationSaid.Unreadable => Words.MemoryMetaUnreadable(),
        CurationSaid.Conflict or CurationSaid.Failed => Words.MemoryParticipantsSaveFailed(),
        _ => null,
    };

    /// <summary>What a capture-time editor says after a save that did not land.</summary>
    public static string? CaptureSaid(CurationSaid? said) => said switch
    {
        CurationSaid.Conflict => Words.MediaCapturedAtConflict(),
        CurationSaid.Unreadable => Words.MediaMetaUnreadable(),
        CurationSaid.Failed => Words.MediaCapturedAtSaveFailed(),
        _ => null,
    };

    /// <summary>What the banner dialog says (core's BannerEditor.Said);
    /// <paramref name="bound"/> is the core's too-large bound as its words name it (BannerEditorShown.tooLargeBound).</summary>
    public static string? BannerSaidText(BannerSaid? said, string bound) => said switch
    {
        BannerSaid.TooLarge => Words.MemoryBannerTooLarge(bound),
        BannerSaid.Unreadable or BannerSaid.NotMedia => Words.MemoryBannerUnreadable(),
        BannerSaid.VideoUnreadable => Words.MemoryBannerVideoUnreadable(),
        BannerSaid.Stale => Words.MemoryBannerStale(),
        BannerSaid.Busy or BannerSaid.Failed => Words.MemoryBannerFailed(),
        _ => null,
    };

    /// <summary>A save's progress line: preparing until the upload moved, then its percent.</summary>
    public static string BannerProgress(double? progress) =>
        progress is { } p && p > 0 ? Words.MemoryBannerUploading((long)Math.Round(Math.Clamp(p, 0, 1) * 100)) : Words.MemoryBannerPreparing();

    /// <summary>The local date and time the pickers hold as epoch ms in <paramref name="zone"/> (the device's): a wall
    /// time skipped by a clock change is taken as the instant after the gap, an ambiguous one as its first occurrence.</summary>
    public static long EpochMs(DateTimeOffset day, TimeSpan time, TimeZoneInfo zone)
    {
        var local = new DateTime(day.Year, day.Month, day.Day, 0, 0, 0, DateTimeKind.Unspecified).Add(new TimeSpan(time.Hours, time.Minutes, 0));
        if (zone.IsInvalidTime(local))
        {
            // the hour a spring change skips: the same wall time an hour on is the instant the clock showed then
            local = local.AddHours(1);
        }
        var offset = zone.IsAmbiguousTime(local) ? zone.GetAmbiguousTimeOffsets(local).Max() : zone.GetUtcOffset(local);
        return new DateTimeOffset(local, offset).ToUnixTimeMilliseconds();
    }

    /// <summary>Epoch ms as the pickers' local day (midnight) and time of day in <paramref name="zone"/>.</summary>
    public static (DateTimeOffset Day, TimeSpan Time) Local(long epochMs, TimeZoneInfo zone)
    {
        var at = TimeZoneInfo.ConvertTime(DateTimeOffset.FromUnixTimeMilliseconds(epochMs), zone);
        return (new DateTimeOffset(at.Date, at.Offset), new TimeSpan(at.Hour, at.Minute, 0));
    }
}
