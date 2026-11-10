// Every word the app shows (ADR 0127 decision 3): one method per key of src/mobile/words/words.json, generated into
// Words.g.cs by `generate_words.py --resw` with the .resw catalogs MRT Core reads from the app's .pri — never a word
// written here or in a screen (scripts/validate_mobile_words.py). This half is what the generated one calls: the
// catalog's lookup, a plural's form and the placeholders' filling.
using System;
using System.Globalization;
using System.Text;

namespace Veyq;

public static partial class Words
{
    /// <summary>
    /// The catalog: a resource's name to its text in the person's language, null where it has none. The app sets it
    /// once at start (<see cref="MrtWords.Open"/>); until then every word is its English, as gettext's msgid is on Linux.
    /// </summary>
    public static Func<string, string?> Lookup { get; set; } = static _ => null;

    private static string Single(string key, string english) => Lookup(key) ?? english;

    /// <summary>
    /// A plural's form for <paramref name="n"/>: the category by the rule of the language the catalog that answered names
    /// (<c>_language</c>), so a German catalog's forms are never picked by an English rule; its English where no catalog
    /// answered.
    /// </summary>
    private static string Plural(string key, string one, string other, long n)
    {
        var language = Lookup("_language");
        var category = language is null ? null : Category(language, n);
        if (category is null) return n == 1 ? one : other;
        return Lookup($"{key}#{category}") ?? Lookup($"{key}#other") ?? (n == 1 ? one : other);
    }

    /// <summary><paramref name="text"/> with each <c>{n}</c> / <c>{n:int}</c> replaced by argument n (from 1), by name, never by a
    /// format string's position; anything else stays as it is.</summary>
    internal static string Fill(string text, object[] args)
    {
        var said = new StringBuilder(text.Length);
        var at = 0;
        while (at < text.Length)
        {
            var open = text.IndexOf('{', at);
            if (open < 0)
            {
                said.Append(text, at, text.Length - at);
                break;
            }
            said.Append(text, at, open - at);
            var close = text.IndexOf('}', open + 1);
            if (close > open)
            {
                var inner = text.AsSpan(open + 1, close - open - 1);
                if (inner.EndsWith(":int", StringComparison.Ordinal)) inner = inner[..^4];
                if (int.TryParse(inner, NumberStyles.None, CultureInfo.InvariantCulture, out var n) && n >= 1 && n <= args.Length)
                {
                    said.Append(args[n - 1] is long l ? l.ToString(CultureInfo.InvariantCulture) : args[n - 1]?.ToString());
                    at = close + 1;
                    continue;
                }
            }
            said.Append('{');
            at = open + 1;
        }
        return said.ToString();
    }
}
