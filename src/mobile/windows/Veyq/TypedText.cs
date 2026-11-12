// A text field the person types into while the core's state for it arrives (D5 of the Expenses CR; Linux's typed.rs, rule
// for rule): the core keeps every typed text as typed, and a state for a field arrives after each edit sent — possibly one
// or two keystrokes behind what the box shows. A box set from such a state would lose what was typed since and move the
// caret. So a box is set from state only when it is not being typed into, or when the state's text is none it sent:
// another writer's version after a stale save, a rate the core filled. No control here: the rule alone, tested.
using System.Collections.Generic;

namespace Veyq;

internal sealed class TypedText
{
    /// <summary>How many sent texts a box remembers: more than any burst of keystrokes between two states.</summary>
    private const int Kept = 64;

    private readonly List<string> _sent = [];

    /// <summary><paramref name="text"/> was sent to the core as this box's edit.</summary>
    public void Sent(string text)
    {
        if (_sent.Count == Kept) _sent.RemoveAt(0);
        _sent.Add(text);
    }

    /// <summary>
    /// The text to set the box to now that the core's state says <paramref name="state"/> while it shows
    /// <paramref name="shown"/> — null keeps the box as it is. A state that echoes a text this box sent is passed over while
    /// it is <paramref name="focused"/>, and every text sent before it is forgotten (states arrive in the order the edits
    /// were applied). Any other state is set: the box is not typed into, or the core changed the text itself.
    /// </summary>
    public string? Take(bool focused, string shown, string state)
    {
        var at = _sent.IndexOf(state);
        if (at >= 0)
        {
            _sent.RemoveRange(0, at + 1);
            if (focused) return null;
        }
        else if (focused && state != shown)
        {
            _sent.Clear();
        }
        return state != shown ? state : null;
    }
}
