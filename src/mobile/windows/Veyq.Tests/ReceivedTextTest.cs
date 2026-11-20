using Microsoft.VisualStudio.TestTools.UnitTesting;
using Veyq.Bridge.Wire;
using Veyq.Core;

namespace Veyq.Tests;

/// <summary>What a received link's screen offers and says without a widget (ReceivedText.cs, ADR 0150 decisions 2–8; the
/// guest-write record's notice, inbox #1018): Linux's received.rs tests, case for case.</summary>
[TestClass]
public sealed class ReceivedTextTest
{
    private static ReceivedShown Shown(ReceivedGate gate) => new()
    {
        host = "photos.example.org", gate = gate, refusal = null, askedAgain = false, busy = false, terms = "",
        operatorSection = "", record = "", note = "", rootName = "Summer", role = "READ_WRITE", page = null, atRoot = true,
        guestName = null, leaving = false, left = false, keepOffered = true, reportAsked = false, recordDays = null, privacy = null, naming = false,
    };

    [TestMethod]
    public void OnItsOwnBackAlwaysStandsInPlaceOnlyInsideTheLink()
    {
        var s = Shown(ReceivedGate.Ready);
        Assert.IsTrue(ReceivedText.Offers(s, ReceivedMode.OnItsOwn).Back);
        Assert.IsFalse(ReceivedText.Offers(s, ReceivedMode.InPlace).Back);
        Assert.IsTrue(ReceivedText.Offers(s with { atRoot = false }, ReceivedMode.InPlace).Back);
    }

    [TestMethod]
    public void KeepAndTheReportOnlyBehindTheGatesKeepOnlyOnItsOwn()
    {
        Assert.AreEqual(new ReceivedOffers(true, false, false, false), ReceivedText.Offers(Shown(ReceivedGate.Locked), ReceivedMode.OnItsOwn));
        var ready = Shown(ReceivedGate.Ready);
        Assert.AreEqual(new ReceivedOffers(true, true, true, false), ReceivedText.Offers(ready, ReceivedMode.OnItsOwn));
        Assert.IsFalse(ReceivedText.Offers(ready, ReceivedMode.InPlace).Keep);
        Assert.IsTrue(ReceivedText.Offers(ready with { guestName = "Ben" }, ReceivedMode.OnItsOwn).NotYou);
        Assert.IsFalse(ReceivedText.Offers(ready with { keepOffered = false }, ReceivedMode.OnItsOwn).Keep, "signed out: nowhere to keep it");
    }

    [TestMethod]
    public void TheRecordNoticeStandsForAWritingLinkWithItsDaysAndNeverForOneThatDoesNotWrite()
    {
        Assert.IsNull(ReceivedText.RecordNotice(null));
        Assert.IsNull(ReceivedText.RecordNotice(0));
        var notice = ReceivedText.RecordNotice(30);
        Assert.IsNotNull(notice);
        StringAssert.Contains(notice, "30");
        StringAssert.Contains(notice, Words.ShareGuestRecordObjection());
        StringAssert.Contains(ReceivedText.RecordNotice(1)!, Words.ShareGuestRecordKept(1));
    }

    [TestMethod]
    public void AProblemIsSaidInTheCatalogsWordsByItsKey()
    {
        Assert.AreEqual(Words.LinkProblemNoKey(), ReceivedText.Said(Msg.LinkProblemNoKey));
        Assert.AreEqual(Words.LinkProblemModeration(), ReceivedText.Said(Msg.LinkProblemModeration));
        Assert.AreEqual(Words.LinkRefusedOtherVersion("a.example"), ReceivedText.Refusal(ReceivedRefusal.OtherVersion, "a.example"));
        Assert.AreEqual(Words.LinkRefusedUnreachable("a.example"), ReceivedText.Refusal(null, "a.example"));
    }

    [TestMethod]
    public void TheContentIsDrawnAnewForAnotherGateOrPageNeverForABusyFlag()
    {
        var a = Shown(ReceivedGate.Locked);
        Assert.AreEqual(ReceivedText.DrawnKey(a), ReceivedText.DrawnKey(a with { busy = true }));
        Assert.AreEqual(ReceivedText.DrawnKey(a), ReceivedText.DrawnKey(a with { guestName = "Ben" }));
        Assert.AreNotEqual(ReceivedText.DrawnKey(a), ReceivedText.DrawnKey(a with { askedAgain = true }));
        Assert.AreNotEqual(ReceivedText.DrawnKey(a), ReceivedText.DrawnKey(Shown(ReceivedGate.Terms)));
        var page = new ReceivedPage { kind = ReceivedPageKind.DropBox, name = "Inbox", nodeKind = "DEPOT_DROPBOX", rows = [], file = null, gallery = null, pot = null, sent = [], writes = false };
        var ready = Shown(ReceivedGate.Ready) with { page = page };
        Assert.AreNotEqual(ReceivedText.DrawnKey(ready), ReceivedText.DrawnKey(ready with { page = page with { sent = ["a.pdf"] } }), "a file sent shows");
    }

    [TestMethod]
    public void AKeptLinksPageOffersItsActionsOnceItsMetaRead()
    {
        Assert.IsTrue(ReceivedText.BookmarkActions(BookmarkState.InPlace));
        Assert.IsTrue(ReceivedText.BookmarkActions(BookmarkState.OnItsOwn));
        Assert.IsFalse(ReceivedText.BookmarkActions(BookmarkState.Loading));
        Assert.IsFalse(ReceivedText.BookmarkActions(BookmarkState.Unreadable));
    }
}
