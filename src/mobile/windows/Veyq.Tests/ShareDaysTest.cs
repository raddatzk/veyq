using System;
using System.Globalization;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Veyq.Tests;

/// <summary>The share form's days: the core's count to the picker's date and back, and as a culture writes it.</summary>
[TestClass]
public sealed class ShareDaysTest
{
    [TestMethod]
    public void ADayGoesToThePickerAndBack()
    {
        Assert.AreEqual(new DateTime(1970, 1, 1), ShareDays.ToDate(0).DateTime);
        Assert.AreEqual(new DateTime(2026, 11, 11), ShareDays.ToDate(20_768).DateTime);
        Assert.AreEqual(20_768L, ShareDays.ToDay(ShareDays.ToDate(20_768)));
        Assert.AreEqual(11_016L, ShareDays.ToDay(new DateTimeOffset(new DateTime(2000, 2, 29, 23, 30, 0))));
        Assert.AreEqual("24.12.2026", ShareDays.Text(20_811, CultureInfo.GetCultureInfo("de-DE")));
        Assert.AreEqual("12/24/2026", ShareDays.Text(20_811, CultureInfo.GetCultureInfo("en-US")));
    }
}
