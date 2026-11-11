using System.Linq;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Veyq.Bridge.Wire;

namespace Veyq.Tests;

/// <summary>What the device trust's screens say (TrustWords.cs), by the core's answers: Linux's unlock_words.</summary>
[TestClass]
public sealed class TrustWordsTest
{
    [TestMethod]
    public void ARememberedUnlockSaysWhatOpensIt()
    {
        Assert.AreEqual((Words.UnlockLoginHint(), Words.UnlockRetry()), TrustWords.Unlock(Method.DeviceKey, StaySignedIn.Login));
        Assert.AreEqual((Words.UnlockRememberedHint(), Words.UnlockSubmit()), TrustWords.Unlock(Method.DeviceKey, StaySignedIn.Presence));
        Assert.AreEqual((Words.UnlockRememberedHint(), Words.UnlockSubmit()), TrustWords.Unlock(Method.DeviceKey, null));
        Assert.AreEqual((Words.UnlockPasswordHint(), Words.UnlockSubmit()), TrustWords.Unlock(Method.Device, StaySignedIn.Login));
        Assert.AreNotEqual(Words.UnlockLoginHint(), Words.UnlockRememberedHint());
    }

    [TestMethod]
    public void ManageNamesEachTrust()
    {
        Assert.AreEqual(Words.ManageTrustedHello(), TrustWords.Trusted(Method.DeviceKey, StaySignedIn.Presence));
        Assert.AreEqual(Words.ManageTrustedRemembered(), TrustWords.Trusted(Method.DeviceKey, StaySignedIn.Login));
        Assert.AreEqual(Words.ManageTrustedPassword(), TrustWords.Trusted(Method.Device, null));
        Assert.IsNull(TrustWords.Trusted(null, null));
        Assert.AreNotEqual(Words.ManageTrustedHello(), Words.ManageTrustedRemembered());
    }

    [TestMethod]
    public void TheOfferListsWhatTheCoreOffersThenNotNow()
    {
        var both = TrustWords.Offer([StaySignedIn.Presence, StaySignedIn.Login]);
        CollectionAssert.AreEqual(new StaySignedIn?[] { StaySignedIn.Presence, StaySignedIn.Login, null }, both.Select(c => c.Level).ToArray());
        Assert.AreEqual((Words.OfferHello(), Words.OfferHelloHint()), (both[0].Title, both[0].Hint));
        Assert.AreEqual((Words.SigninRemember(), Words.OfferLoginHint()), (both[1].Title, both[1].Hint));
        Assert.AreEqual((Words.OfferNotNow(), Words.OfferNotNowHint()), (both[2].Title, both[2].Hint));
        var login = TrustWords.Offer([StaySignedIn.Login]);
        CollectionAssert.AreEqual(new StaySignedIn?[] { StaySignedIn.Login, null }, login.Select(c => c.Level).ToArray());
    }

    [TestMethod]
    public void ManageListsEachOtherLevelAndTheWayBack()
    {
        StaySignedIn[] both = [StaySignedIn.Presence, StaySignedIn.Login];
        CollectionAssert.AreEqual(new StaySignedIn?[] { StaySignedIn.Login, null }, TrustWords.Changes(Method.DeviceKey, StaySignedIn.Presence, both).ToArray());
        CollectionAssert.AreEqual(new StaySignedIn?[] { StaySignedIn.Presence, null }, TrustWords.Changes(Method.DeviceKey, StaySignedIn.Login, both).ToArray());
        CollectionAssert.AreEqual(new StaySignedIn?[] { StaySignedIn.Presence, StaySignedIn.Login }, TrustWords.Changes(Method.Device, null, both).ToArray());
        Assert.AreEqual(0, TrustWords.Changes(null, null, both).Count);
        Assert.AreEqual(0, TrustWords.Changes(Method.Device, null, []).Count);
    }
}
