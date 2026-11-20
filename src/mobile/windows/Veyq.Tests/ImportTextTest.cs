using System.Collections.Generic;
using System.Linq;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Veyq.Bridge.Wire;
using Veyq.Core;

namespace Veyq.Tests;

/// <summary>What an import's dialog shows at each step (ImportText; the web's ImportSubtreeDialog.vue): the buttons the web
/// offers, where each of the core's lines lands.</summary>
[TestClass]
public sealed class ImportTextTest
{
    private static Notice N(string msg, params NoticeArg[] args) => new() { msg = msg, args = args };

    private static readonly Notice Summary = N("import_summary", new NoticeArg.Int { value = 7 }, new NoticeArg.Bytes { count = 2048 }, new NoticeArg.Text { text = "Trip" });

    private static ImportShown Shown(ImportStep step, Notice? summary = null, bool canImport = false, bool canAsFile = true,
        IReadOnlyList<Notice>? said = null, IReadOnlyList<Notice>? leftOut = null, int done = 0, int total = 0, string importWord = "import_submit") => new()
    {
        step = step,
        root = "Reise",
        summary = summary,
        leftOut = leftOut ?? [],
        fits = null,
        done = done,
        total = total,
        progress = step == ImportStep.Running ? N("import_importing", new NoticeArg.Int { value = done }, new NoticeArg.Int { value = total }) : null,
        said = said ?? [],
        canImport = canImport,
        importWord = importWord,
        canAsFile = canAsFile,
        closed = false,
    };

    [TestMethod]
    public void WhileItReadsImportIsShownShutAndNothingOfThePlanShows()
    {
        var face = ImportText.Of(Shown(ImportStep.Reading));
        Assert.IsTrue(face.Reading);
        Assert.IsNull(face.Root);
        Assert.AreEqual(Words.ImportSubmit(), face.Primary);
        Assert.IsFalse(face.PrimaryEnabled);
        Assert.IsTrue(face.AsFile && face.AsFileEnabled, "a ZIP from an upload may go as a file while it reads");
    }

    [TestMethod]
    public void ReadyShowsTheSummaryTheNoticeAndWhatIsLeftOutUnderImportTheRest()
    {
        var left = N("import_skip_unknown_application", new NoticeArg.Int { value = 2 }, new NoticeArg.Text { text = "chess" });
        var face = ImportText.Of(Shown(ImportStep.Ready, Summary, canImport: true, leftOut: [left], importWord: "import_rest"));
        Assert.AreEqual("Reise", face.Root);
        Assert.AreEqual(Said.Notice(Summary), face.Summary);
        CollectionAssert.AreEqual(new[] { Said.Notice(left) }, face.LeftOut.ToArray());
        Assert.IsTrue(face.Notice, "the ownership notice wherever something is importable");
        Assert.AreEqual(Words.ImportRest(), face.Primary);
        Assert.IsTrue(face.PrimaryEnabled);
        Assert.IsNull(face.Problem);
    }

    [TestMethod]
    public void NoRoomIsAWarningAndKeepsImportShut()
    {
        var noRoom = N("import_no_room", new NoticeArg.Bytes { count = 2048 });
        var face = ImportText.Of(Shown(ImportStep.Ready, Summary, canImport: false, said: [noRoom]));
        Assert.AreEqual(Said.Notice(noRoom), face.NoRoom);
        Assert.IsNull(face.Problem, "no room is the web's warning, not its error");
        Assert.IsNotNull(face.Primary);
        Assert.IsFalse(face.PrimaryEnabled);
    }

    [TestMethod]
    public void NothingImportableOffersNoImportAndSaysSoInTheSummarysPlace()
    {
        var face = ImportText.Of(Shown(ImportStep.Ready, summary: null, said: [N("import_nothing")]));
        Assert.IsNull(face.Primary, "an archive with nothing importable offers no Import");
        Assert.AreEqual(Words.ImportNothing(), face.Nothing);
        Assert.IsNull(face.Problem);
        Assert.IsFalse(face.Notice);
    }

    [TestMethod]
    public void ARefusedDownloadSaysItsReasonWithItsDetailAndOffersNoImport()
    {
        var face = ImportText.Of(Shown(ImportStep.Invalid, said: [N("import_refused_unsafe_path"), N("import_detail", new NoticeArg.Text { text = "../x" })]));
        Assert.IsNull(face.Primary);
        Assert.IsNull(face.Root);
        Assert.AreEqual($"{Words.ImportRefusedUnsafePath()} {Said.Notice(N("import_detail", new NoticeArg.Text { text = "../x" }))}", face.Problem);
        Assert.IsTrue(face.AsFile && face.AsFileEnabled, "a refused ZIP may still go as a file");
    }

    [TestMethod]
    public void RunningShowsItsProgressAndShutsBothActionsButCancel()
    {
        var face = ImportText.Of(Shown(ImportStep.Running, Summary, done: 3, total: 12));
        Assert.AreEqual(Said.Notice(N("import_importing", new NoticeArg.Int { value = 3 }, new NoticeArg.Int { value = 12 })), face.Progress);
        Assert.AreEqual(0.25, face.Fraction, 1e-9);
        Assert.IsFalse(face.PrimaryEnabled);
        Assert.IsFalse(face.AsFileEnabled, "Upload as a file is shut while it runs");
    }

    [TestMethod]
    public void AFailedRunSaysWhyAndHowFarInOneMessage()
    {
        var face = ImportText.Of(Shown(ImportStep.Ready, Summary, canImport: true,
            said: [N("import_quota"), N("import_failed", new NoticeArg.Int { value = 2 }, new NoticeArg.Int { value = 7 })]));
        Assert.AreEqual($"{Words.ImportQuota()} {Words.ImportFailed(2, 7)}", face.Problem);
    }

    [TestMethod]
    public void ADoneImportLeavesTheCoresImportedLine()
    {
        var done = Shown(ImportStep.Done, Summary, said: [N("import_imported", new NoticeArg.Text { text = "Reise" })]);
        Assert.AreEqual(Words.ImportImported("Reise"), ImportText.Done(done));
        Assert.IsNull(ImportText.Of(done).Problem);
    }
}
