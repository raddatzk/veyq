// Where the node header's hero (NodeHeader.Hero, one element) was placed last: a level's column (HomeView) or a page's
// slot (HeroSlot). Kept here, never read back from the element's Parent, which stays null until that container is loaded
// — a cold start into a folder drew the level twice before a layout pass, the hero still the first column's child, and
// the second add threw 0x800F1000 out of the shell's update, ending the app in combase (inbox #1429, measured
// 2026-11-28 on the VM). No WinUI type here: Veyq.Tests holds the rule.

namespace Veyq.Core;

using System;
using System.Runtime.CompilerServices;

internal static class HeroPlaces
{
    private static readonly ConditionalWeakTable<object, Action> Last = new();

    /// <summary>
    /// <paramref name="hero"/> leaves where it was placed last, then <paramref name="leave"/> is how it leaves its new
    /// place the next time: called just before the new place adds it.
    /// </summary>
    public static void Moving(object hero, Action leave)
    {
        Leave(hero);
        Last.AddOrUpdate(hero, leave);
    }

    /// <summary><paramref name="hero"/> taken out of where it was placed last, if it was placed anywhere.</summary>
    public static void Leave(object hero)
    {
        if (!Last.TryGetValue(hero, out var leave)) return;
        Last.Remove(hero);
        leave();
    }
}
