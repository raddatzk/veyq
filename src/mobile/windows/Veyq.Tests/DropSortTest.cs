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
    public void TwoFilesAndAFolderAreScreenedWithTheirPathsAndTheOrdinaryUploadIsWhatFollowsAnOffer()
    {
        var sorted = DropSort.Sort([
            new DroppedItem("a.txt", @"C:\up\a.txt", false),
            new DroppedItem("Reise ü", @"C:\up\Reise ü", true),
            new DroppedItem("b.zip", @"C:\up\b.zip", false),
        ]);
        var calls = DropSort.Calls(Trip, sorted);
        Assert.AreEqual(2, calls.Count, "the files as one batch, the folder walked by the core");
        // screened first (ADR 0152 decision 1): a veyq download among them is the core's to recognise, by its content
        var files = (Call.ImportScreen)calls[0];
        Assert.AreEqual(("s1", "f1", "Trip"), (files.scopeId, files.parentId, files.label));
        CollectionAssert.AreEqual(new[] { @"C:\up\a.txt", @"C:\up\b.zip" }, files.paths.ToArray());
        Assert.AreEqual(Words.PotDeletedPerson(), files.deletedName, "an imported Pot's erased person is named by the host's word");
        var folder = (Call.ImportScreenFolder)calls[1];
        Assert.AreEqual(("s1", "f1", "Trip", @"C:\up\Reise ü"), (folder.scopeId, folder.parentId, folder.label, folder.path));
        // one import per drop, as on the web: once one is offered, the rest goes the ordinary way
        var plainFiles = (Call.UploadFiles)DropSort.Plain(files);
        Assert.AreEqual(("s1", "f1", "Trip"), (plainFiles.scopeId, plainFiles.parentId, plainFiles.label));
        CollectionAssert.AreEqual(files.paths.ToArray(), plainFiles.paths.ToArray());
        var plainFolder = (Call.UploadFolder)DropSort.Plain(folder);
        Assert.AreEqual(("s1", "f1", @"C:\up\Reise ü"), (plainFolder.scopeId, plainFolder.parentId, plainFolder.path));
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
