// What the host holds of the members' pictures and what it asks for (ADR 0128 decision 1) — no control, so
// Veyq.Tests reads it; Avatars.cs draws it. Linux's avatars::Held, rule for rule: when the pictures are read is the
// core's (AvatarsModel), and the shell's state names the user ids whose picture the core holds (ShellShown.pictures).
// The host asks for each with Avatar.Picture whenever that set changes, keeps them per tenant open, and drops an answer
// that arrives for an earlier set.
using System;
using System.Collections.Generic;
using System.Linq;

namespace Veyq;

/// <summary>What a new list of ids asks of the host: the ids to ask the core for in <paramref name="Round"/>, and those
/// whose picture is gone (their people show their initials again).</summary>
internal sealed record Asked(IReadOnlyList<string> Ask, IReadOnlyList<string> Gone, ulong Round);

internal sealed class Held<T> where T : class
{
    private string? _tenant;
    private SortedSet<string>? _listed;
    private readonly Dictionary<string, T> _pictures = [];

    /// <summary>Bumped on every ask, so an answer to an earlier one is told apart.</summary>
    private ulong _round;

    /// <summary>
    /// The core says <paramref name="ids"/> have a picture in <paramref name="tenant"/>. Another tenant drops everything
    /// held; a set that differs from the last one is asked for whole (a picture may have changed with it), the same set
    /// again asks nothing.
    /// </summary>
    public Asked Listed(string tenant, IEnumerable<string> ids)
    {
        var set = new SortedSet<string>(ids, StringComparer.Ordinal);
        var gone = new List<string>();
        if (_tenant != tenant)
        {
            gone.AddRange(_pictures.Keys);
            _pictures.Clear();
            _tenant = tenant;
            _listed = null;
        }
        if (_listed is not null && _listed.SetEquals(set)) return new Asked([], gone, _round);
        var dropped = _pictures.Keys.Where(id => !set.Contains(id)).ToList();
        foreach (var id in dropped) _pictures.Remove(id);
        gone.AddRange(dropped);
        gone.Sort(StringComparer.Ordinal);
        _round++;
        _listed = set;
        return new Asked([.. set], gone, _round);
    }

    /// <summary>
    /// The core's answer for <paramref name="id"/>, asked in <paramref name="round"/>: kept unless the tenant or the set
    /// changed since, or it did not decode (null) — then what was held stays. True when it is the one shown now.
    /// </summary>
    public bool Arrived(ulong round, string id, T? picture)
    {
        var current = round == _round && _listed is not null && _listed.Contains(id);
        if (picture is null || !current) return false;
        _pictures[id] = picture;
        return true;
    }

    public T? Get(string id) => _pictures.GetValueOrDefault(id);
}
