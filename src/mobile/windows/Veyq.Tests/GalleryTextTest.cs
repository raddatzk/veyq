using Microsoft.VisualStudio.TestTools.UnitTesting;
using Veyq.Bridge.Wire;

namespace Veyq.Tests;

/// <summary>What a Memory's page says of its filter and its marks without a widget (GalleryText.cs, ADR 0149 decisions 4
/// and 5): whether the filter narrows, what shows where it leaves nothing, and a tile's badge's words.</summary>
[TestClass]
public sealed class GalleryTextTest
{
    private static FilterShown Filter(MarkMode mode = MarkMode.All, string text = "", params string[] chips) => new()
    {
        offered = true,
        mode = mode,
        modeOffered = true,
        chips = [.. System.Linq.Enumerable.Select(chips, c => new ChipShown { chip = $"place:{c}", label = c, rest = null })],
        text = text,
        suggestions = [],
        shown = 0,
        total = 4,
    };

    private static GalleryRow Row(int? marks, bool mine) => new()
    {
        mediaId = "m", name = "a.jpg", mediaType = "image/jpeg", video = false, blocked = false, tile = null, picture = null,
        capturedAt = null, width = null, height = null, said = null, canDelete = false, marks = marks, mine = mine,
    };

    [TestMethod]
    public void TheFilterNarrowsByAChipATextOrTheMarksModeNotByBlanks()
    {
        Assert.IsFalse(GalleryText.Narrows(Filter()));
        Assert.IsFalse(GalleryText.Narrows(Filter(text: "   ")), "a blank text narrows nothing");
        Assert.IsTrue(GalleryText.Narrows(Filter(text: "sea")));
        Assert.IsTrue(GalleryText.Narrows(Filter(chips: "Rome")));
        Assert.IsTrue(GalleryText.Narrows(Filter(MarkMode.Mine)));
    }

    [TestMethod]
    public void TheMarksModeAloneSaysItsOwnEmptyWordsElseTheFilterIsNamed()
    {
        Assert.AreEqual(Words.MediaMarksEmptyMine(), GalleryText.Empty(Filter(MarkMode.Mine)));
        Assert.AreEqual(Words.MediaMarksEmptyEveryone(), GalleryText.Empty(Filter(MarkMode.Everyone)));
        Assert.AreEqual(
            Words.MediaFilterEmpty($"Rome, 2024, {Words.MediaFilterText("sea")}, {Words.MediaFilterMarksMine()}"),
            GalleryText.Empty(Filter(MarkMode.Mine, " sea ", "Rome", "2024")));
        Assert.AreEqual(Words.MediaFilterEmpty("Rome"), GalleryText.Empty(Filter(chips: "Rome")));
    }

    [TestMethod]
    public void ADayChipIsSaidAsTheCulturesDateAnyOtherByItsLabel()
    {
        Assert.AreEqual(Said.Day("2024-05-03"), GalleryText.ChipLabel(new ChipShown { chip = "day:2024-05-03", label = "2024-05-03", rest = null }));
        Assert.AreNotEqual("2024-05-03", Said.Day("2024-05-03", System.Globalization.CultureInfo.GetCultureInfo("de-DE")), "a day said its culture's way");
        Assert.AreEqual("Rome", GalleryText.ChipLabel(new ChipShown { chip = "place:rome", label = "Rome", rest = null }));
        Assert.AreEqual(Words.MediaFilterEmpty(Said.Day("2024-05-03")),
            GalleryText.Empty(Filter() with { chips = [new ChipShown { chip = "day:2024-05-03", label = "2024-05-03", rest = null }] }));
    }

    [TestMethod]
    public void ABadgeSaysWhoMarkedItAndHowOften()
    {
        Assert.IsNull(GalleryText.MarkWords(Row(0, false)), "nobody marked it: no badge");
        Assert.IsNull(GalleryText.MarkWords(Row(null, false)), "a personal scope unmarked: no badge");
        Assert.AreEqual(Words.MediaMarksYours(), GalleryText.MarkWords(Row(1, true)), "the caller's own mark alone is not counted twice");
        Assert.AreEqual(Words.MediaMarksYours(), GalleryText.MarkWords(Row(null, true)), "a personal scope counts nothing");
        Assert.AreEqual($"{Words.MediaMarksYours()}, {Words.MediaMarksCount(3)}", GalleryText.MarkWords(Row(3, true)));
        Assert.AreEqual(Words.MediaMarksCount(2), GalleryText.MarkWords(Row(2, false)));
    }
}
