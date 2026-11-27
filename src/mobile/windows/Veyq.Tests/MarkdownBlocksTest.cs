using Microsoft.VisualStudio.TestTools.UnitTesting;
using Veyq.Core;

namespace Veyq.Tests;

/// <summary>The terms' and the operator section's Markdown as the app draws it (MarkdownBlocks.cs; Linux's terms.rs, case for case).</summary>
[TestClass]
public sealed class MarkdownBlocksTest
{
    [TestMethod]
    public void AHeadingIsItsLineAListIsBulletedAndAnEmphasisIsItsWords()
    {
        var b = MarkdownBlocks.Of("## Rules\n\nBe kind.\n- one\n* **two**\nUse *Report* on what you see.\n2 * 3 * 4 stays\n\n\n# Contact\nWrite to us.");
        Assert.AreEqual(4, b.Count);
        Assert.AreEqual(("Rules", true), b[0]);
        Assert.AreEqual(("Be kind.\n• one\n• two\nUse Report on what you see.\n2 * 3 * 4 stays", false), b[1]);
        Assert.AreEqual(("Contact", true), b[2]);
        Assert.AreEqual(("Write to us.", false), b[3]);
    }
}
