using System;
using System.Linq;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Veyq.Bridge.Wire;
using Veyq.Core;

namespace Veyq.Tests;

[TestClass]
public sealed class HeaderPlanTest
{
    [TestMethod]
    public void EveryEntrysWireIdIsItsSerialName()
    {
        foreach (var a in Enum.GetValues<HeaderAction>())
        {
            Assert.AreEqual(System.Text.Json.JsonSerializer.Serialize(a).Trim('"'), HeaderPlan.Wire(a), a.ToString());
        }
    }

    [TestMethod]
    public void ADownloadIsThePagesWhereItsKindDownloadsThroughIt()
    {
        Assert.IsTrue(HeaderPlan.DownloadsByPage("FILE"), "a file's download is its page's Save a copy");
        Assert.IsTrue(HeaderPlan.DownloadsByPage("EXPENSES_POT"));
        Assert.IsFalse(HeaderPlan.DownloadsByPage("FOLDER"), "a folder's download is the header's ZIP");
        Assert.IsFalse(HeaderPlan.DownloadsByPage("MEMORY"));
    }

    private static PlanAction A(string id) => new() { id = System.Text.Json.JsonSerializer.Deserialize<HeaderAction>($"\"{id}\""), word = null };

    private static Plan P(string[] buttons, string[] menu, string[] last) =>
        new() { buttons = buttons.Select(A).ToList(), menu = menu.Select(A).ToList(), last = last.Select(A).ToList() };

    private static string Ids(System.Collections.Generic.IEnumerable<PlanAction?> actions) => string.Join(",", actions.Select(a => a is null ? "|" : HeaderPlan.Wire(a.id)));

    [TestMethod]
    public void EveryEntryIsLaidOutInThePlansOrderDeleteLast()
    {
        // the core's plans for two buttons (NodeHeaderTest): a folder's download in the menu, a Pot's too
        var folder = P(["favorite", "share"], ["download", "rename", "cover"], ["move", "copy", "report", "delete"]);
        Assert.AreEqual("download,rename,cover,|,move,copy,report,delete", Ids(HeaderPlan.Secondary(folder)));
        Assert.AreEqual("favorite,share", Ids(HeaderPlan.Primary(folder)));
        // every entry the core plans is laid out, its own ones (a banner, a kept link's) among them — none left out
        Assert.AreEqual("banner,rename,|,report-shared,delete", Ids(HeaderPlan.Secondary(P(["favorite"], ["banner", "rename"], ["report-shared", "delete"]))));
        Assert.AreEqual("open-own-page,upload,music", Ids(HeaderPlan.Primary(P(["open-own-page", "upload", "music"], [], ["delete"]))));
        // no separator where a group holds nothing
        Assert.AreEqual("delete", Ids(HeaderPlan.Secondary(P(["favorite"], [], ["delete"]))));
        Assert.AreEqual("rename", Ids(HeaderPlan.Secondary(P(["favorite"], ["rename"], []))));
    }
}
