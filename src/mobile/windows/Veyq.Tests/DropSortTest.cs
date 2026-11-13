using System.Linq;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Veyq.Bridge.Wire;

namespace Veyq.Tests;

/// <summary>What a drop is sorted into and the calls it makes (DropSort; Linux's drop tests, rule for rule).</summary>
[TestClass]
public sealed class DropSortTest
{
    private static readonly UploadLevel Trip = new("s1", "f1", "Trip");

    [TestMethod]
    public void TwoFilesAndAFolderReachUploadFilesAndUploadFolderWithTheirPaths()
    {
        var sorted = DropSort.Sort([
            new DroppedItem("a.txt", @"C:\up\a.txt", false),
            new DroppedItem("Reise ü", @"C:\up\Reise ü", true),
            new DroppedItem("b.jpg", @"C:\up\b.jpg", false),
        ]);
        var calls = DropSort.Calls(Trip, sorted);
        Assert.AreEqual(2, calls.Count, "the files as one batch, the folder walked by the core");
        var files = (Call.UploadFiles)calls[0];
        Assert.AreEqual("s1", files.scopeId);
        Assert.AreEqual("f1", files.parentId);
        Assert.AreEqual("Trip", files.label);
        CollectionAssert.AreEqual(new[] { @"C:\up\a.txt", @"C:\up\b.jpg" }, files.paths.ToArray());
        var folder = (Call.UploadFolder)calls[1];
        Assert.AreEqual(("s1", "f1", @"C:\up\Reise ü"), (folder.scopeId, folder.parentId, folder.path));
    }

    [TestMethod]
    public void AnItemWithoutALocalPathIsRefusedAndNothingIsSentForIt()
    {
        var sorted = DropSort.Sort([new DroppedItem("away.pdf", "", false), new DroppedItem("phone", null, true)]);
        CollectionAssert.AreEqual(new[] { "away.pdf", "phone" }, sorted.Refused.ToArray());
        Assert.AreEqual(0, DropSort.Calls(Trip, sorted).Count, "a drop of nothing local sends nothing");
    }

    [TestMethod]
    public void ADroppedPathsFactsNameItsRootAndWhatTheFileSystemSaysNeverItsName()
    {
        var gone = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "Windows drop gone ü.bin");
        var facts = DropSort.Facts(gone);
        StringAssert.EndsWith(facts, "FileNotFoundException", facts);
        Assert.IsFalse(facts.Contains("Windows drop"), $"the log names the file: {facts}");
        var here = System.IO.Path.GetTempFileName();
        try
        {
            StringAssert.StartsWith(DropSort.Facts(here), "Fixed ");
        }
        finally
        {
            System.IO.File.Delete(here);
        }
    }
}
