using System.IO;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Veyq.Core;

namespace Veyq.Tests;

/// <summary>What this install keeps of its appearance and language (ADR 0162; Linux's appearance tests, case for case).</summary>
[TestClass]
public sealed class AppearanceKeptTest
{
    [TestMethod]
    public void AKeptChoiceReadsBackAndNothingKeptIsTheSystems()
    {
        var folder = Path.Combine(Path.GetTempPath(), $"veyq-appearance-{System.Guid.NewGuid():N}");
        Assert.AreEqual(AppearanceKept.Systems, AppearanceKept.Read(folder), "no file: the system's");
        var kept = new AppearanceKept("dark", "de");
        kept.Write(folder);
        Assert.AreEqual(kept, AppearanceKept.Read(folder));
        File.WriteAllText(Path.Combine(folder, AppearanceKept.File), "not json {");
        Assert.AreEqual(AppearanceKept.Systems, AppearanceKept.Read(folder), "an unreadable file: the system's");
        Directory.Delete(folder, true);
    }

    [TestMethod]
    public void AKeptLanguageIsTheCatalogsCulture()
    {
        Assert.AreEqual("de-DE", AppearanceKept.CultureOf("de"));
        Assert.AreEqual("en-US", AppearanceKept.CultureOf("en"));
        Assert.IsNull(AppearanceKept.CultureOf("system"));
        Assert.IsNull(AppearanceKept.CultureOf("fr"));
    }

    [TestMethod]
    public void TheGermanCatalogSpeaksWhereGermanIsKept()
    {
        // the words' language a kept "de" opens the catalog in: German words, not the English fallback
        var lookup = MrtWords.Open(System.Environment.GetEnvironmentVariable("VEYQ_PRI") ?? throw new System.InvalidOperationException("run.py sets VEYQ_PRI"), AppearanceKept.CultureOf("de"));
        Assert.AreEqual("Darstellung", lookup("appearance_theme"));
        Assert.AreEqual("de", lookup("_language"));
    }
}
