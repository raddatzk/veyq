using Microsoft.VisualStudio.TestTools.UnitTesting;
using ApplicationId = Veyq.Bridge.Wire.ApplicationId;

namespace Veyq.Tests;

/// <summary>Where a list is put back (ListScrolls; Linux's shell tests, rule for rule).</summary>
[TestClass]
public sealed class ListScrollsTest
{
    [TestMethod]
    public void AListsPlaceNamesItsApplicationOrItsSpaceFolderAndFilter()
    {
        var expenses = ListScrolls.Place(ApplicationId.Expenses, "s1", null, null);
        Assert.AreEqual(expenses, ListScrolls.Place(ApplicationId.Expenses, "s1", "f", null), "an application's list is one place in its space");
        Assert.AreNotEqual(expenses, ListScrolls.Place(ApplicationId.Depot, "s1", null, null));
        Assert.AreNotEqual(expenses, ListScrolls.Place(ApplicationId.Expenses, "s2", null, null));
        var root = ListScrolls.Place(null, "s1", null, null);
        Assert.AreNotEqual(root, ListScrolls.Place(null, "s1", "f1", null), "each folder is a place of its own");
        Assert.AreNotEqual(root, ListScrolls.Place(null, "s1", null, ApplicationId.Depot), "each filter is a place of its own");
    }

    [TestMethod]
    public void AListIsPutBackOnceItIsTallEnoughElseAtItsEndOnceLaidOut()
    {
        Assert.IsNull(ListScrolls.PutBack(1200, 800, 600, false), "not laid out tall enough yet");
        Assert.AreEqual(1200.0, ListScrolls.PutBack(1200, 2000, 600, false));
        Assert.AreEqual(400.0, ListScrolls.PutBack(1200, 1000, 600, true), "grown shorter: its end");
        Assert.IsNull(ListScrolls.PutBack(1200, 500, 600, true), "no scroll at all: left at its top");
        var held = new ListScrolls();
        Assert.IsNull(held.At("home:s1::all"));
        held.Moved("home:s1::all", 900);
        Assert.AreEqual(900.0, held.At("home:s1::all"));
        held.Moved("home:s1::all", 0);
        Assert.IsNull(held.At("home:s1::all"), "a list at its top is not put back");
    }
}
