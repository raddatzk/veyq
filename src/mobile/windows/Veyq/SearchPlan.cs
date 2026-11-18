// What the search's suggestion list holds and what each of its entries does (ADR 0146 decisions 7 and 12; Linux's
// search.rs draws the same order): the field's status lines, a *Filter by* heading with the chips offered, a separator and
// a *Results* heading with the rows, then *Show more*. Pure — no control — so its rules are tested beside the app
// (SearchPlanTest); SearchBox draws it.
using System.Collections.Generic;
using System.Linq;
using Veyq.Bridge.Wire;

namespace Veyq;

internal static class SearchPlan
{
    /// <summary>One entry of the suggestion list.</summary>
    public abstract record Entry
    {
        /// <summary>A line the field says (a space loading, spaces not searched, offline's photos, the empty state).</summary>
        public sealed record Status(Notice Said) : Entry;

        /// <summary>A heading over a part of the list (<c>search_filter_by</c>, <c>search_results</c>).</summary>
        public sealed record Heading(string Key) : Entry;

        /// <summary>The line between the chips offered and the results.</summary>
        public sealed record Separator : Entry;

        /// <summary>Suggestion [Index] of <c>SearchShown.suggestions</c>: chosen, its chip is set.</summary>
        public sealed record Choose(int Index) : Entry;

        /// <summary>Result [Index] of <c>SearchShown.results</c>: opened where it lives.</summary>
        public sealed record Open(int Index) : Entry;

        /// <summary><i>Show {count} more</i>.</summary>
        public sealed record More(int Count) : Entry;

        /// <summary>Whether the entry does anything when chosen (a heading, a line or the separator does not).</summary>
        public bool Acts => this is Choose or Open or More;
    }

    /// <summary>The list for <paramref name="s"/>: empty while nothing is asked (a calm focus shows no list).</summary>
    public static IReadOnlyList<Entry> Entries(SearchShown s)
    {
        var entries = new List<Entry>();
        if (!s.asked) return entries;
        entries.AddRange(s.status.Select(n => new Entry.Status(n)));
        if (s.suggestions.Count > 0)
        {
            entries.Add(new Entry.Heading("search_filter_by"));
            entries.AddRange(Enumerable.Range(0, s.suggestions.Count).Select(i => new Entry.Choose(i)));
        }
        if (s.results.Count > 0)
        {
            if (s.suggestions.Count > 0) entries.Add(new Entry.Separator());
            entries.Add(new Entry.Heading("search_results"));
            entries.AddRange(Enumerable.Range(0, s.results.Count).Select(i => new Entry.Open(i)));
        }
        if (s.more > 0) entries.Add(new Entry.More(s.more));
        return entries;
    }

    /// <summary>What Enter takes when no entry is highlighted: the first chip offered, else the first result — never
    /// <i>Show more</i>.</summary>
    public static Entry? First(IEnumerable<Entry> entries) => entries.FirstOrDefault(e => e is Entry.Choose or Entry.Open);

    /// <summary>A result's or a chip's icon kind (the core's, the web's icon names) as a Segoe Fluent Icons glyph, as the
    /// shell draws the same kinds (HomeView.KindGlyph).</summary>
    public static string Glyph(string kind) => kind switch
    {
        "folder" => "",
        "folder-open" => "",
        "bookmark" => "",
        "inbox" => "",
        "wallet" => "",
        "images" => "",
        "image" => "",
        "play" => "",
        "user" => "",
        "users" => "",
        "settings" => "",
        "calendar" => "",
        "map-marker" => "",
        "tag" => "",
        _ => "",
    };

    /// <summary>What a screen reader hears for a result's row: its name, its second line (<paramref name="line"/>, said)
    /// and its reason, the empty ones left out.</summary>
    public static string Spoken(ResultShown r, string line) =>
        string.Join(", ", new[] { string.Concat(r.name.Select(p => p.text)), line, string.Concat((r.reason ?? []).Select(p => p.text)) }.Where(t => t.Length > 0));
}
