using Microsoft.VisualStudio.TestTools.UnitTesting;
using Veyq.Bridge.Wire;

namespace Veyq.Tests;

/// <summary>What the device trust's screens say (TrustWords.cs), by the core's answers: Linux's remember_words and
/// unlock_words, with Windows Hello's own label.</summary>
[TestClass]
public sealed class TrustWordsTest
{
    [TestMethod]
    public void TheSwitchSaysWhatItUsesAndHidesWhereNothingIsKept()
    {
        Assert.IsNull(TrustWords.Remember(StaySignedIn.None));
        Assert.IsNull(TrustWords.Remember(null));
        Assert.AreEqual((Words.SigninRememberHello(), Words.SigninRememberHintHello()), TrustWords.Remember(StaySignedIn.Presence));
        Assert.AreEqual((Words.SigninRemember(), Words.SigninRememberHintLogin()), TrustWords.Remember(StaySignedIn.Login));
    }

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
        Assert.AreEqual(Words.ManageTrustedRemembered(), TrustWords.Trusted(Method.DeviceKey));
        Assert.AreEqual(Words.ManageTrustedPassword(), TrustWords.Trusted(Method.Device));
        Assert.IsNull(TrustWords.Trusted(null));
    }
}
