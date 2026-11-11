using System.Linq;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Veyq.Bridge.Wire;

namespace Veyq.Tests;

[TestClass]
public sealed class HeaderPlanTest
{
    [TestMethod]
    public void TheCoresActionsAreDrawnAndADownloadWhereItIsTheNodesArchive()
    {
        foreach (var id in new[] { "favorite", "share", "rename", "delete", "move", "copy", "download", "report", "cover" })
        {
            Assert.IsTrue(HeaderPlan.IsDrawn(id, "FOLDER") && HeaderPlan.IsDrawn(id, "EXPENSES_POT"), id);
        }
        Assert.IsFalse(HeaderPlan.IsDrawn("download", "FILE"), "a file's download is its own bytes");
        Assert.IsFalse(HeaderPlan.IsDrawn("an-applications-own", "FOLDER"), "an application's own action is its page's");
    }

    private static PlanAction A(string id) => new() { id = id, word = null };

    private static Plan P(string[] buttons, string[] menu, string[] last) =>
        new() { buttons = buttons.Select(A).ToList(), menu = menu.Select(A).ToList(), last = last.Select(A).ToList() };

    private static string Ids(System.Collections.Generic.IEnumerable<PlanAction?> actions) => string.Join(",", actions.Select(a => a?.id ?? "|"));

    [TestMethod]
    public void AFoldersMoreAndAPotsAreLaidOutAlikeFromThePlansGroups()
    {
        // the core's plans for two buttons (NodeHeaderTest): a folder's download in the menu, a Pot's too
        var folder = P(["favorite", "share"], ["download", "rename", "cover"], ["move", "copy", "report", "delete"]);
        var pot = P(["favorite", "share"], ["download", "rename", "cover"], ["move", "copy", "report", "delete"]);
        Assert.AreEqual("download,rename,cover,|,move,copy,report,delete", Ids(HeaderPlan.Secondary(folder, "FOLDER")));
        Assert.AreEqual(Ids(HeaderPlan.Secondary(pot, "EXPENSES_POT")), Ids(HeaderPlan.Secondary(folder, "FOLDER")));
        Assert.AreEqual("favorite,share", Ids(HeaderPlan.Primary(folder, "FOLDER")));
        // one drawn action in the menu still stands apart from the last group
        Assert.AreEqual("rename,|,delete", Ids(HeaderPlan.Secondary(P(["favorite"], ["own", "rename"], ["own2", "delete"]), "FOLDER")));
        // no separator where a group draws nothing (an application's own actions are its page's)
        Assert.AreEqual("delete", Ids(HeaderPlan.Secondary(P(["favorite"], ["own"], ["delete"]), "FOLDER")));
        Assert.AreEqual("rename", Ids(HeaderPlan.Secondary(P(["favorite"], ["rename"], ["own"]), "FOLDER")));
    }
}
