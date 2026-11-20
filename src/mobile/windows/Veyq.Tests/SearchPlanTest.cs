using System.Linq;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Veyq.Bridge.Wire;
using Veyq.Core;
using E = Veyq.Core.SearchPlan.Entry;

namespace Veyq.Tests;

[TestClass]
public sealed class SearchPlanTest
{
    private static Notice N(string msg) => new() { msg = msg, args = [] };

    private static Piece P(string text) => new Piece.Plain { text = text };

    private static SuggestionShown Offered(string id) => new() { id = id, word = "search_chip_year", label = P(id), icon = "calendar", where = [] };

    private static ResultShown Found(string name) =>
        new() { id = name, icon = "folder", name = [new PartShown { text = name[..1], strong = true }, new PartShown { text = name[1..], strong = false }], line = [P("Home")], reason = null };

    private static SearchShown Shown(bool asked, int offered, int found, int more = 0, params string[] status) => new()
    {
        text = "k", chips = [], asked = asked,
        suggestions = Enumerable.Range(0, offered).Select(i => Offered($"20{i:00}")).ToList(),
        results = Enumerable.Range(0, found).Select(i => Found($"K{i}")).ToList(),
        more = more, total = found + more, status = status.Select(N).ToList(), announcement = [], openSettings = null, openSettingsCount = 0,
    };

    [TestMethod]
    public void TheListHoldsTheStatusTheChipsOfferedASeparatorTheResultsAndShowMoreInThatOrder()
    {
        var entries = SearchPlan.Entries(Shown(true, 2, 2, more: 5, "search_loading"));
        CollectionAssert.AreEqual(new E[]
        {
            new E.Status(N("search_loading")) , new E.Heading("search_filter_by"), new E.Choose(0), new E.Choose(1), new E.Separator(),
            new E.Heading("search_results"), new E.Open(0), new E.Open(1), new E.More(5),
        }.Select(e => e.ToString()).ToList(), entries.Select(e => e.ToString()).ToList());
    }

    [TestMethod]
    public void NoSeparatorWithoutChipsOfferedAndNoHeadingWithoutItsPart()
    {
        var only = SearchPlan.Entries(Shown(true, 0, 1));
        CollectionAssert.AreEqual(new[] { "search_results" }, only.OfType<E.Heading>().Select(h => h.Key).ToList());
        Assert.IsFalse(only.OfType<E.Separator>().Any(), "a separator sets apart two parts, never one");
        Assert.IsFalse(SearchPlan.Entries(Shown(true, 1, 0)).OfType<E.Separator>().Any());
        Assert.IsFalse(SearchPlan.Entries(Shown(true, 0, 0)).OfType<E.More>().Any(), "no Show more where the core counts none");
    }

    [TestMethod]
    public void ACalmFocusShowsNoList()
    {
        Assert.AreEqual(0, SearchPlan.Entries(Shown(false, 2, 3, more: 1, "search_loading")).Count, "nothing asked: no list, whatever the state holds");
    }

    [TestMethod]
    public void EnterTakesTheFirstChipOfferedElseTheFirstResultNeverShowMore()
    {
        Assert.AreEqual(new E.Choose(0), SearchPlan.First(SearchPlan.Entries(Shown(true, 1, 2))));
        Assert.AreEqual(new E.Open(0), SearchPlan.First(SearchPlan.Entries(Shown(true, 0, 2, more: 3, "search_loading"))));
        Assert.IsNull(SearchPlan.First([new E.Status(N("search_empty")), new E.More(3)]), "Show more and a line are no first entry");
        Assert.IsFalse(new E.Heading("search_results").Acts);
        Assert.IsTrue(new E.More(1).Acts);
    }

    [TestMethod]
    public void EveryIconKindTheCoreSendsHasItsOwnGlyph()
    {
        string[] kinds = ["folder", "folder-open", "bookmark", "inbox", "wallet", "images", "image", "play", "user", "users", "settings", "calendar", "map-marker", "tag"];
        foreach (var kind in kinds) Assert.AreNotEqual(SearchPlan.Glyph("file"), SearchPlan.Glyph(kind), kind);
        Assert.AreEqual(kinds.Length, kinds.Select(SearchPlan.Glyph).Distinct().Count(), "two kinds drawn alike");
    }

    [TestMethod]
    public void AResultIsSpokenAsItsNameItsLineAndItsReasonLeavingOutTheEmpty()
    {
        var r = Found("Kiel");
        Assert.AreEqual("Kiel, Home", SearchPlan.Spoken(r, "Home"));
        Assert.AreEqual("Kiel", SearchPlan.Spoken(r, ""));
        Assert.AreEqual("Storage, Settings, cache", SearchPlan.Spoken(r with { name = [new PartShown { text = "Storage", strong = false }], reason = [new PartShown { text = "cache", strong = true }] }, "Settings"));
    }
}
