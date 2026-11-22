// A row's second line an application says beside the core's (ADR 0154 decision 3, inbox #1066; Linux's home rows, Apple's
// `Holding.rowLines`): each registered application's lines (`ApplicationUi.RowLines`, Memories' dates) held by node id
// and asked again when the shell's objects change or a page closes back onto a list (an edited date shows then); the list
// is drawn anew once lines arrive that differ.

namespace Veyq.Core;

using System;
using System.Collections.Generic;
using System.Linq;

internal static class RowLines
{
    private static Dictionary<string, string> _lines = [];
    private static string _asked = "";
    private static bool _asking;

    /// <summary>Node <paramref name="nodeId"/>'s second line; null where it has none (or none is held yet).</summary>
    public static string? Line(string nodeId) => _lines.TryGetValue(nodeId, out var l) ? l : null;

    /// <summary>The lines asked of every application for the objects named by <paramref name="key"/> unless already asked
    /// (or <paramref name="again"/>); <paramref name="changed"/> runs once they arrived and differ from those held.</summary>
    public static async void Ask(string key, bool again, Action changed)
    {
        if (_asking || (key == _asked && !again)) return;
        _asking = true;
        _asked = key;
        try
        {
            var next = new Dictionary<string, string>();
            foreach (var app in Registry.Shared.Applications)
            {
                if (app.RowLines is not { } ask) continue;
                if (await ask() is not { } lines) return; // an application's ask failed: the lines held stay
                foreach (var (node, line) in lines) next.TryAdd(node, line);
            }
            var same = next.Count == _lines.Count && next.All(p => _lines.TryGetValue(p.Key, out var l) && l == p.Value);
            _lines = next;
            if (same) return;
            Log.Write($"row lines: {next.Count}");
            changed();
        }
        catch (Exception e)
        {
            Log.Write($"row lines failed ({e.GetType().Name})");
        }
        finally
        {
            _asking = false;
        }
    }
}
