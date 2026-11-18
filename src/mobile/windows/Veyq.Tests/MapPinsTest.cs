using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Veyq.Tests;

/// <summary>A map pin's screen-reader name: one Memory's own, a cluster's the cluster's count (#946).</summary>
[TestClass]
public sealed class MapPinsTest
{
    [TestMethod]
    public void APinForOneMemoryIsNamedForIt() => Assert.AreEqual("Lisbon", MapPins.Name(1, "Lisbon"));

    [TestMethod]
    public void AClusterIsNamedForItsCountNotForOneOfItsMemories()
    {
        var name = MapPins.Name(3, "Lisbon");
        Assert.AreEqual(Words.MapClusterTitle(3), name);
        Assert.AreNotEqual("Lisbon", name);
        StringAssert.Contains(name, "3");
    }
}
