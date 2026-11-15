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

    /// <summary>A drop onto a gallery (Linux's memory_page test, rule for rule): its files in one Memory.Upload, a folder
    /// said and not taken (owner question 3), what is not on this computer said as Depot's drop says it.</summary>
    [TestMethod]
    public void ADropOntoAGallerySendsItsFilesAndSaysEachFolderNotTaken()
    {
        var sorted = DropSort.Sort([
            new DroppedItem("a.jpg", @"C:\Bilder\a.jpg", false),
            new DroppedItem("Reise ü", @"C:\Bilder\Reise ü", true),
            new DroppedItem("clip.webm", @"C:\Bilder\clip.webm", false),
            new DroppedItem("away.jpg", "", false),
        ]);
        var call = (Call.MemoryUpload)DropSort.GalleryCall(7, sorted)!;
        Assert.AreEqual(7, call.gallery);
        CollectionAssert.AreEqual(new[] { @"C:\Bilder\a.jpg", @"C:\Bilder\clip.webm" }, call.paths.ToArray(), "the files, one batch, no folder");
        CollectionAssert.AreEqual(new[] { Words.MemoryDropFolder("Reise ü"), Words.DepotDropNotLocal("away.jpg") }, DropSort.GallerySaid(sorted).ToArray());
        var onlyFolder = DropSort.Sort([new DroppedItem("y", @"C:\x\y\", true)]);
        Assert.IsNull(DropSort.GalleryCall(7, onlyFolder), "a folder alone sends nothing");
        CollectionAssert.AreEqual(new[] { Words.MemoryDropFolder("y") }, DropSort.GallerySaid(onlyFolder).ToArray());
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
