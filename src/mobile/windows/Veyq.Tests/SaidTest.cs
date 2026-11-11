using System.Globalization;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Veyq.Tests;

/// <summary>Money, days and sizes as Windows writes them: money and days in two cultures; a size in the person's
/// (Explorer's formatter reads the user's locale for the process, never a thread's).</summary>
[TestClass]
public sealed class SaidTest
{
    private const char Nbsp = ' ';

    [TestMethod]
    public void MoneyIsWrittenInTheCulturesOwnWayWithEachCurrencysSymbol()
    {
        Assert.AreEqual($"1.234,56{Nbsp}€", Said.Money(123456, "EUR", 2, ["de-DE"], "DE"));
        Assert.AreEqual("€1,234.56", Said.Money(123456, "EUR", 2, ["en-US"], "US"));
        Assert.AreEqual($"-67,34{Nbsp}€", Said.Money(-6734, "EUR", 2, ["de-DE"], "DE"));
        Assert.AreEqual($"1.234{Nbsp}¥", Said.Money(1234, "JPY", 0, ["de-DE"], "DE"));
        Assert.AreEqual("$1,234.56", Said.Money(123456, "USD", 2, ["en-US"], "US"));
        Assert.AreEqual("1.234,56", Said.Money(123456, null, 2, ["de-DE"], "DE"));
    }

    [TestMethod]
    public void ADayIsTheCulturesShortDate()
    {
        Assert.AreEqual("10.11.2026", Said.Day("2026-11-10", CultureInfo.GetCultureInfo("de-DE")));
        Assert.AreEqual("11/10/2026", Said.Day("2026-11-10", CultureInfo.GetCultureInfo("en-US")));
        Assert.AreEqual("no day", Said.Day("no day", CultureInfo.GetCultureInfo("de-DE")));
    }

    [TestMethod]
    public void AServersInstantIsItsLocalDayAndOneThatDoesNotReadItsFirstTenCharacters()
    {
        // noon UTC is the same day in every zone a test host runs in (W. Europe, the Mac's)
        Assert.AreEqual("10.11.2026", Said.InstantDay("2026-11-10T12:00:00.016Z", CultureInfo.GetCultureInfo("de-DE")));
        Assert.AreEqual("11/10/2026", Said.InstantDay("2026-11-10T12:00:00Z", CultureInfo.GetCultureInfo("en-US")));
        Assert.AreEqual("2026-11-10", Said.InstantDay("2026-11-10 or so", CultureInfo.GetCultureInfo("de-DE")));
        // a report's receipt: the instant's local date and time (the host's zone is W. Europe Standard Time, UTC+1 in November)
        Assert.AreEqual("10.11.2026 13:00", Said.InstantMoment("2026-11-10T12:00:00.5Z", CultureInfo.GetCultureInfo("de-DE")));
        Assert.AreEqual("received", Said.InstantMoment("received", CultureInfo.GetCultureInfo("de-DE")));
    }

    [TestMethod]
    public void ASizeIsExplorersInThePersonsLocale()
    {
        var point = CultureInfo.CurrentCulture.NumberFormat.NumberDecimalSeparator;
        Assert.AreEqual($"1{point}50 KB", Said.Size(1536));
        Assert.AreEqual($"1{point}18 MB", Said.Size(1234567));
        StringAssert.StartsWith(Said.Size(12), "12 ");
    }
}
