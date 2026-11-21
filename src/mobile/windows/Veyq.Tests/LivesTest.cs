using System;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Veyq.Bridge.Wire;
using Veyq.Core;

namespace Veyq.Tests;

/// <summary>How a notice lives on Windows (ADR 0154 decision 4): every lifetime, every word's kind and the separator are
/// the core's answer (Notice.Lifetimes), none the app's own.</summary>
[TestClass]
public sealed class LivesTest
{
    [TestMethod]
    public void AWordOfTheAppsOwnLivesAsTheCoresTableSaysAndALineJoinsWithItsSeparator()
    {
        // values unlike the core's own, so a lifetime or a separator of the app's would show
        var core = new Answer.Lifetimes
        {
            saidMs = 4100, undoMs = 6100, copiedMs = 2100, nothingPlaysMs = 5100,
            saidWords = ["link_kept", "receipts_saved"], undoWords = ["trash_moved"], separator = " | ",
        };
        Lives.Take(core);
        Assert.AreEqual(NoticeKind.Said, Spoken.Own("link_kept", "kept").Kind);
        Assert.AreEqual(NoticeKind.Held, Spoken.Own("share_failed", "failed").Kind, "a word not in the table is held");
        Assert.AreEqual(NoticeKind.Undo, Lives.KindOf("trash_moved"));
        Assert.AreEqual(TimeSpan.FromMilliseconds(core.saidMs), Lives.Of(NoticeKind.Said));
        Assert.IsNull(Lives.Of(NoticeKind.Held), "a held notice stays until closed");
        Assert.IsNull(Lives.Of(NoticeKind.Undo), "an undo stays while the core offers it");
        Assert.AreEqual(TimeSpan.FromMilliseconds(core.copiedMs), Lives.Copied);
        Assert.AreEqual(TimeSpan.FromMilliseconds(core.nothingPlaysMs), Lives.NothingPlays);
        Assert.AreEqual("a | c", Lives.Joined(["a", null, "", "c"]));
    }
}
