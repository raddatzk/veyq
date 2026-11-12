using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Veyq.Tests;

/// <summary>A text box typed into while the core's states arrive (TypedText.cs; Linux's typed.rs, case for case).</summary>
[TestClass]
public sealed class TypedTextTest
{
    [TestMethod]
    public void AFocusedBoxKeepsWhatWasTypedWhileItsEchoesArrive()
    {
        var t = new TypedText();
        foreach (var s in new[] { "1", "12", "123" }) t.Sent(s);
        Assert.IsNull(t.Take(true, "123", "1"));
        Assert.IsNull(t.Take(true, "123", "12"));
        Assert.IsNull(t.Take(true, "123", "123"));
    }

    [TestMethod]
    public void ATextTheCoreChangedIsSetEvenIntoAFocusedBox()
    {
        var t = new TypedText();
        t.Sent("Museum");
        Assert.AreEqual("Museum and lunch", t.Take(true, "Museum", "Museum and lunch"));
    }

    [TestMethod]
    public void AnEarlierTextAfterALaterEchoIsTheCores()
    {
        var t = new TypedText();
        t.Sent("1");
        t.Sent("12");
        Assert.IsNull(t.Take(true, "12", "12"));
        Assert.AreEqual("1", t.Take(true, "12", "1"));
    }

    [TestMethod]
    public void ABoxNotTypedIntoFollowsTheState()
    {
        var t = new TypedText();
        Assert.AreEqual("1,082954", t.Take(false, "", "1,082954"));
        Assert.IsNull(t.Take(false, "1,082954", "1,082954"));
        t.Sent("2");
        Assert.AreEqual("1", t.Take(false, "2", "1"));
    }

    [TestMethod]
    public void ALaggingStateNeverRewritesAFocusedBox()
    {
        var t = new TypedText();
        foreach (var s in new[] { "a", "ab", "abc", "abcd" }) t.Sent(s);
        foreach (var lag in new[] { "a", "ab", "abc" }) Assert.IsNull(t.Take(true, "abcd", lag), lag);
    }
}
