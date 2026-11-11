using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Veyq.Tests;

[TestClass]
public sealed class HeaderPlanTest
{
    [TestMethod]
    public void OnlyThisPhasesActionsAreDrawnAndADownloadOnlyWhereTheAppWritesOne()
    {
        foreach (var id in new[] { "favorite", "rename", "delete" })
        {
            Assert.IsTrue(HeaderPlan.IsDrawn(id, "FOLDER") && HeaderPlan.IsDrawn(id, "EXPENSES_POT"), id);
        }
        Assert.IsTrue(HeaderPlan.IsDrawn("download", "EXPENSES_POT"));
        Assert.IsFalse(HeaderPlan.IsDrawn("download", "FOLDER"), "a folder's download is Phase 3's");
        foreach (var id in new[] { "share", "move", "copy", "report", "cover", "an-applications-own" })
        {
            Assert.IsFalse(HeaderPlan.IsDrawn(id, "FOLDER") || HeaderPlan.IsDrawn(id, "EXPENSES_POT"), $"{id} drawn before its phase");
        }
    }
}
