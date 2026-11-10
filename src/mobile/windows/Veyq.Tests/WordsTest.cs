using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Veyq.Tests;

/// <summary>
/// Every key of words.json in English and German through the .resw catalogs the app's build compiled into its .pri and
/// MRT Core read — never the generated English fallback: German is asserted, whose words differ from it.
/// </summary>
[TestClass]
public sealed class WordsTest
{
    private static string Env(string name) =>
        Environment.GetEnvironmentVariable(name) ?? throw new InvalidOperationException($"run.py sets {name}");

    private static Dictionary<string, JsonElement> Catalog() =>
        JsonSerializer.Deserialize<Dictionary<string, JsonElement>>(File.ReadAllText(Env("VEYQ_WORDS_JSON")))!;

    /// <summary>The placeholders of <paramref name="texts"/> in the order of their number: true for <c>{n:int}</c>.</summary>
    private static List<bool> Placeholders(IEnumerable<string> texts)
    {
        var found = new SortedDictionary<int, bool>();
        foreach (var t in texts)
        {
            foreach (var part in t.Split('{').Skip(1))
            {
                var inner = part.Split('}')[0];
                if (int.TryParse(inner.Replace(":int", ""), out var n)) found[n] = inner.EndsWith(":int", StringComparison.Ordinal);
            }
        }
        return [.. found.Values];
    }

    private static string Expect(string text, IReadOnlyList<object> args)
    {
        for (var i = 0; i < args.Count; i++)
        {
            text = text.Replace($"{{{i + 1}}}", args[i].ToString()).Replace($"{{{i + 1}:int}}", args[i].ToString());
        }
        return text;
    }

    /// <summary>A text's form for <paramref name="n"/>: a plural's category (one for 1, else other), else the text.</summary>
    private static string Form(JsonElement text, long n) => text.ValueKind == JsonValueKind.Object
        ? (text.TryGetProperty(n == 1 ? "one" : "other", out var f) ? f : text.GetProperty("other")).GetString()!
        : text.GetString()!;

    [TestMethod]
    public void EveryKeySaysItsWordsInEnglishAndGerman()
    {
        var catalog = Catalog();
        CollectionAssert.AreEquivalent(catalog.Keys.ToList(), Words.Keys.ToList(), "Words.g.cs is not words.json's");
        var said = 0;
        var germanUnlikeEnglish = 0;
        foreach (var (lang, culture) in new[] { ("en", "en-US"), ("de", "de-DE") })
        {
            Words.Lookup = MrtWords.Open(Env("VEYQ_PRI"), culture);
            Assert.AreEqual(lang, Words.Lookup("_language"), $"the {culture} catalog did not answer");
            foreach (var key in Words.Keys)
            {
                var entry = catalog[key];
                var english = entry.GetProperty("en");
                var text = entry.TryGetProperty(lang, out var own) ? own : english;
                var plural = text.ValueKind == JsonValueKind.Object;
                var kinds = Placeholders(english.ValueKind == JsonValueKind.Object
                    ? english.EnumerateObject().Select(p => p.Value.GetString()!)
                    : [english.GetString()!]);
                foreach (var n in plural ? new long[] { 1, 2, 0 } : [0])
                {
                    List<object> args = [.. kinds.Select((isInt, i) => (i, isInt) switch
                    {
                        (0, true) when plural => (object)n,
                        (_, true) => (object)(40L + i),
                        _ => "‹x›",
                    })];
                    if (plural && kinds.Count == 0) args = [n];
                    var expected = Expect(Form(text, n), args);
                    Assert.AreEqual(expected, Words.Say(key, args), $"{key} in {lang}, n = {n}");
                    if (lang == "de" && expected != Expect(Form(english, n), args)) germanUnlikeEnglish++;
                    said++;
                }
            }
        }
        Assert.IsTrue(said > 2 * Words.Keys.Count, "nothing was said");
        Assert.IsTrue(germanUnlikeEnglish > Words.Keys.Count / 2, $"only {germanUnlikeEnglish} German words differ from the English fallback");
        Assert.IsNull(Words.Say("no_such_key", []));
    }

    [TestMethod]
    public void APluralTakesItsFormOnBothSidesOfOneInGerman()
    {
        Words.Lookup = MrtWords.Open(Env("VEYQ_PRI"), "de-DE");
        Assert.AreEqual("1 Objekt", Words.MemoryItems(1));
        Assert.AreEqual("2 Objekte", Words.MemoryItems(2));
        Assert.AreEqual("0 Objekte", Words.MemoryItems(0));
        Assert.AreEqual("Eine gelöschte Person.", Words.PeopleDeletedSummary(1));
        Assert.AreEqual("3 gelöschte Personen.", Words.PeopleDeletedSummary(3));
    }

    [TestMethod]
    public void APlaceholderIsFilledByItsNumberAndAnythingElseStays()
    {
        Assert.AreEqual("7 of a, {x} {3}", Words.Fill("{2:int} of {1}, {x} {3}", ["a", 7L]));
    }
}
