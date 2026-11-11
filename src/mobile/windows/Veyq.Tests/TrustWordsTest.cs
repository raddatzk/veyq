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
        Assert.AreEqual(Words.ManageTrustedRemembered(), TrustWords.Trusted(Method.DeviceKey));
        Assert.AreEqual(Words.ManageTrustedPassword(), TrustWords.Trusted(Method.Device));
        Assert.IsNull(TrustWords.Trusted(null));
    }
}
