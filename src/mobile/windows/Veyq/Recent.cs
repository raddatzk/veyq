// What a screen keeps of what it decoded, bounded (Linux's memory_page Tiles, rule for rule): at most Capacity entries by
// their key, the least recently shown first out — a gallery's tiles by their blob id, the list rows' covers by node id.
using System.Collections.Generic;

namespace Veyq;

internal sealed class Recent<T>(int capacity) where T : class
{
    private readonly Dictionary<string, LinkedListNode<(string Key, T Value)>> _held = [];
    private readonly LinkedList<(string Key, T Value)> _order = [];

    public int Count => _held.Count;

    /// <summary>The value held under <paramref name="key"/>, made the most recently shown; null where none is held.</summary>
    public T? Get(string key)
    {
        if (!_held.TryGetValue(key, out var node)) return null;
        _order.Remove(node);
        _order.AddLast(node);
        return node.Value.Value;
    }

    /// <summary><paramref name="value"/> held under <paramref name="key"/> as the most recent; the least recent goes past
    /// the capacity.</summary>
    public void Put(string key, T value)
    {
        Remove(key);
        _held[key] = _order.AddLast((key, value));
        while (_held.Count > capacity && _order.First is { } oldest)
        {
            _order.RemoveFirst();
            _held.Remove(oldest.Value.Key);
        }
    }

    public void Remove(string key)
    {
        if (!_held.Remove(key, out var node)) return;
        _order.Remove(node);
    }

    /// <summary>Everything let go at once (a map's tiles at a withdrawal).</summary>
    public void Clear()
    {
        _held.Clear();
        _order.Clear();
    }

    /// <summary>What is held, the least recently shown first, without making any of it more recent.</summary>
    public IEnumerable<(string Key, T Value)> Entries()
    {
        foreach (var e in _order) yield return e;
    }
}
