// The core's local day (days since 1970-01-01, the share form's count, the header CR's Phase 4) as the calendar picker's
// date and back, and as the person reads it. Which day a link ends on, today and the last day a pick may take are the
// core's (ShareSheet through Share.*); Linux's share_sheet::ymd and day_of.
using System;
using System.Globalization;

namespace Veyq;

internal static class ShareDays
{
    private static readonly int Epoch = new DateOnly(1970, 1, 1).DayNumber;

    /// <summary>The core's day as the picker's date (that calendar day, local midnight).</summary>
    public static DateTimeOffset ToDate(long day) => new(DateOnly.FromDayNumber(Epoch + (int)day).ToDateTime(TimeOnly.MinValue));

    /// <summary>The picker's date as the core's day (the calendar day it shows).</summary>
    public static long ToDay(DateTimeOffset date) => DateOnly.FromDateTime(date.DateTime).DayNumber - Epoch;

    /// <summary>A day as the person reads it: the culture's short date.</summary>
    public static string Text(long day, CultureInfo? culture = null) =>
        DateOnly.FromDayNumber(Epoch + (int)day).ToString("d", culture ?? CultureInfo.CurrentCulture);
}
