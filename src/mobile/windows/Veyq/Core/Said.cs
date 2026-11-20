// What the core says, written the way Windows writes it (ADR 0123's Consequences, the Windows app): an amount of money,
// a day and a size in the person's locale, as the system holds it. Nothing here decides what is said; that is the core's
// (ADR 0136 decision 1). The same as Android's NoticeText.kt, Apple's say(notice) and Linux's said.rs.

namespace Veyq.Core;

using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Runtime.InteropServices;
using Windows.Globalization;
using Windows.Globalization.NumberFormatting;

public static partial class Said
{
    /// <summary>
    /// <paramref name="minor"/> minor units of <paramref name="currency"/> (an ISO 4217 code; null: a number alone) with
    /// <paramref name="minorUnits"/> digits after the point, as Windows writes money (<see cref="CurrencyFormatter"/>) in the
    /// person's locale, or in <paramref name="languages"/> and <paramref name="region"/>: the region's own currency by its
    /// symbol; any other by the symbol ICU knows for it in that locale (CLDR's, as Android, Apple and the web write it:
    /// "€1,234.56" in en-US, "12,50 CA$" in de-DE), laid out as the culture lays out money; one ICU knows only by its code,
    /// or none, by its code (Linux's rule). Windows' own symbol for another currency is not used: CurrencyFormatter wrote
    /// Canadian dollars "12,50 $" in de-DE, the US dollar's sign. A code Windows does not know is written after the number.
    /// </summary>
    public static string Money(long minor, string? currency, int minorUnits, IEnumerable<string>? languages = null, string? region = null)
    {
        var value = minor / Math.Pow(10, minorUnits);
        if (currency is not null)
        {
            if (currency != OwnCurrency(region) && Foreign(minor, currency, minorUnits, languages, region) is { } foreign) return foreign;
            try
            {
                var money = languages is null ? new CurrencyFormatter(currency) : new CurrencyFormatter(currency, languages, region);
                if (currency != OwnCurrency(region)) money.Mode = CurrencyFormatterMode.UseCurrencyCode;
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

    /// <summary>Another currency than the region's by ICU's symbol, laid out as Windows lays out the region's own (its
    /// own written by CurrencyFormatter, its symbol swapped for the other's); null where ICU knows no symbol for either but
    /// the code, or is not there.</summary>
    private static string? Foreign(long minor, string currency, int minorUnits, IEnumerable<string>? languages, string? region)
    {
        if (OwnCurrency(region) is not { } own) return null;
        var language = languages?.FirstOrDefault() ?? Windows.System.UserProfile.GlobalizationPreferences.Languages.FirstOrDefault();
        var home = region ?? Windows.System.UserProfile.GlobalizationPreferences.HomeGeographicRegion;
        if (language is null) return null;
        var locale = $"{language.Split('-')[0]}_{home}";
        if (IcuSymbol(currency, locale) is not { } symbol || IcuSymbol(own, locale) is not { } ownSymbol) return null;
        try
        {
            var money = languages is null ? new CurrencyFormatter(own) : new CurrencyFormatter(own, languages, region);
            money.FractionDigits = minorUnits;
            money.IsGrouped = true;
            var said = money.Format(minor / Math.Pow(10, minorUnits));
            return said.Contains(ownSymbol, StringComparison.Ordinal) ? said.Replace(ownSymbol, symbol, StringComparison.Ordinal) : null;
        }
        catch (ArgumentException)
        {
            return null;
        }
    }

    /// <summary>
    /// <paramref name="code"/>'s symbol as ICU names it for <paramref name="locale"/> (<c>ucurr_getName</c>,
    /// <c>UCURR_SYMBOL_NAME</c>, through Windows' own <c>icu.dll</c>, whose C functions carry no version in their names);
    /// null where it names only the code, or ICU is not there.
    /// </summary>
    internal static unsafe string? IcuSymbol(string code, string locale)
    {
        if (code.Length != 3) return null;
        try
        {
            sbyte choice = 0;
            int length = 0, status = 0;
            fixed (char* c = code + "\0")
            {
                var name = ucurr_getName(c, locale, 0, &choice, &length, &status);
                if (status > 0 || name is null || length <= 0) return null;
                var symbol = new string(name, 0, length);
                return symbol == code ? null : symbol;
            }
        }
        catch (Exception e) when (e is DllNotFoundException or EntryPointNotFoundException)
        {
            return null;
        }
    }

    [LibraryImport("icu.dll", EntryPoint = "ucurr_getName", StringMarshalling = StringMarshalling.Utf8)]
    private static unsafe partial char* ucurr_getName(char* currency, string locale, int nameStyle, sbyte* isChoiceFormat, int* length, int* status);

    /// <summary>The currency the region uses (the person's home region where none is given); null where it names none.</summary>
    private static string? OwnCurrency(string? region)
    {
        var place = region is null ? new GeographicRegion() : new GeographicRegion(region);
        return place.CurrenciesInUse.Count > 0 ? place.CurrenciesInUse[0] : null;
    }

    /// <summary>An ISO day (<c>YYYY-MM-DD</c>) as the culture's short date (the person's: the system's regional format);
    /// the text itself where it is no day.</summary>
    public static string Day(string iso, CultureInfo? culture = null) =>
        DateOnly.TryParseExact(iso, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out var day)
            ? day.ToString("d", culture ?? CultureInfo.CurrentCulture)
            : iso;

    /// <summary>The day of a server's instant (ISO 8601, <c>2026-11-11T16:46:58.016Z</c>) in the local zone, as the
    /// culture's short date (Android's and Apple's trash rows do the same); the instant's own day where it does not read.</summary>
    public static string InstantDay(string instant, CultureInfo? culture = null) =>
        DateTimeOffset.TryParse(instant, CultureInfo.InvariantCulture, DateTimeStyles.RoundtripKind, out var at)
            ? DateOnly.FromDateTime(at.ToLocalTime().DateTime).ToString("d", culture ?? CultureInfo.CurrentCulture)
            : instant[..Math.Min(10, instant.Length)];

    /// <summary>A server's instant (ISO 8601) in the local zone as the culture's short date and time (a report's receipt);
    /// the instant itself where it does not read.</summary>
    public static string InstantMoment(string instant, CultureInfo? culture = null) =>
        DateTimeOffset.TryParse(instant, CultureInfo.InvariantCulture, DateTimeStyles.RoundtripKind, out var at)
            ? at.ToLocalTime().DateTime.ToString("g", culture ?? CultureInfo.CurrentCulture)
            : instant;

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
