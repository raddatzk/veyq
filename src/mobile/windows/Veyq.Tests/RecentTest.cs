using Microsoft.VisualStudio.TestTools.UnitTesting;
using Veyq.Core;

namespace Veyq.Tests;

/// <summary>What a screen keeps of what it decoded (Recent; Linux's memory_page tiles test, case for case).</summary>
[TestClass]
public sealed class RecentTest
{
    [TestMethod]
    public void TheHeldAreBoundedTheLeastRecentlyShownFirstOut()
    {
        var held = new Recent<string>(400);
        for (var i = 0; i < 400; i++) held.Put($"b{i}", $"t{i}");
        Assert.AreEqual("t0", held.Get("b0"), "b0 shown again: the most recent now");
        held.Put("new", "t-new");
        Assert.AreEqual(400, held.Count);
        Assert.IsNotNull(held.Get("b0"));
        Assert.IsNull(held.Get("b1"), "b1 was the least recently shown");
        held.Put("b0", "again");
        Assert.AreEqual(400, held.Count, "the same key held once");
        held.Remove("b0");
        Assert.IsNull(held.Get("b0"));
    }
}
