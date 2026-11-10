// What the core says, written the way Windows writes it (cr-the-windows-app-over-the-native-core D8): an amount of money,
// a day and a size in the person's locale, as the system holds it. Nothing here decides what is said; that is the core's
// (ADR 0127 decision 1). The same as Android's NoticeText.kt, Apple's say(notice) and Linux's said.rs.
using System;
using System.Collections.Generic;
using System.Globalization;
using System.Runtime.InteropServices;
using Windows.Globalization.NumberFormatting;

namespace Veyq;

public static partial class Said
{
    /// <summary>
    /// <paramref name="minor"/> minor units of <paramref name="currency"/> (an ISO 4217 code; null: a number alone) with
    /// <paramref name="minorUnits"/> digits after the point, as Windows writes money: any currency by its own symbol in the
    /// person's locale (<see cref="CurrencyFormatter"/>), or in <paramref name="languages"/> and <paramref name="region"/>.
    /// A code Windows does not know is written after the number.
    /// </summary>
    public static string Money(long minor, string? currency, int minorUnits, IEnumerable<string>? languages = null, string? region = null)
    {
        var value = minor / Math.Pow(10, minorUnits);
        if (currency is not null)
        {
            try
            {
                var money = languages is null ? new CurrencyFormatter(currency) : new CurrencyFormatter(currency, languages, region);
                money.FractionDigits = minorUnits;
                money.IsGrouped = true;
                return money.Format(value);
            }
            catch (ArgumentException)
            {
                // not a currency Windows knows: the number, then the code
            }
        }
        var number = languages is null ? new DecimalFormatter() : new DecimalFormatter(languages, region);
        number.FractionDigits = minorUnits;
        number.IsGrouped = true;
        var said = number.Format(value);
        return currency is null ? said : $"{said} {currency}";
    }

    /// <summary>An ISO day (<c>YYYY-MM-DD</c>) as the culture's short date (the person's: the system's regional format);
    /// the text itself where it is no day.</summary>
    public static string Day(string iso, CultureInfo? culture = null) =>
        DateOnly.TryParseExact(iso, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out var day)
            ? day.ToString("d", culture ?? CultureInfo.CurrentCulture)
            : iso;

    /// <summary>A size as Explorer writes one (<c>StrFormatByteSizeEx</c>, rounded to its shown digits), in the person's
    /// locale: Windows reads it for the process, not per thread (measured: SetThreadUILanguage and SetThreadLocale change
    /// nothing), so a size has no culture to pass.</summary>
    public static unsafe string Size(long bytes)
    {
        const int roundToNearestDisplayedDigit = 1;
        char* buffer = stackalloc char[64];
        return StrFormatByteSizeEx((ulong)Math.Max(bytes, 0), roundToNearestDisplayedDigit, buffer, 64) == 0
            ? new string(buffer)
            : bytes.ToString(CultureInfo.CurrentCulture);
    }

    [LibraryImport("shlwapi.dll")]
    private static unsafe partial int StrFormatByteSizeEx(ulong ull, int flags, char* buffer, uint size);
}
