// The terms' and the operator section's Markdown as the app draws it (Linux's terms.rs `blocks`, case for case): a
// heading is its own line (the lines after it in the same block are the paragraph under it), a list item bulleted,
// **strong** and *emphasis* shown as their words (the operator section's "*Report*": the stars were drawn, inbox #1418's
// German pass). No control here: the rule alone, tested.

namespace Veyq.Core;

using System.Collections.Generic;
using System.Linq;
using System.Text.RegularExpressions;

internal static partial class MarkdownBlocks
{
    /// <summary>A `*` before a non-space, the next `*` on its line after one.</summary>
    [GeneratedRegex(@"\*([^\s*](?:[^*\n]*[^\s*])?)\*")]
    private static partial Regex Emphasis();

    /// <summary><paramref name="text"/>'s paragraphs, each with whether it is a heading.</summary>
    public static List<(string Text, bool Heading)> Of(string text) =>
        text.Trim().Replace("\r\n", "\n").Split("\n\n").Select(b => b.Trim()).Where(b => b.Length > 0)
            .SelectMany(b => b.StartsWith('#') && b.IndexOf('\n') is var nl and > 0 && b[(nl + 1)..].Trim().Length > 0
                ? new[] { b[..nl].Trim(), b[(nl + 1)..].Trim() } : new[] { b })
            .Select(b =>
            b.StartsWith('#')
                ? (b.TrimStart('#').Trim(), true)
                : (Emphasis().Replace(string.Join("\n", b.Split('\n').Select(l => l.Trim()).Select(l => l.StartsWith("- ") || l.StartsWith("* ") ? "• " + l[2..] : l)).Replace("**", ""), "$1"), false)
        ).ToList();
}
