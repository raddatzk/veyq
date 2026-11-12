using System;
using System.Collections.Generic;
using System.IO;
using System.Text.Json;
using System.Text.Json.Serialization;
using Veyq.Bridge.Wire;

namespace Veyq.Bridge;

/// <summary>
/// The device's store (the core's <c>DeviceStore</c>: its trust marks, the trusted accounts, this device's id): names and
/// values, kept in one JSON file under the user's own <c>%LOCALAPPDATA%\veyq\</c>, written whole on each change, as Linux's
/// is. No secret is in it: the bridge carries no key (ADR 0129's Consequences).
/// </summary>
public sealed class DeviceStore
{
    private readonly string? _file;
    private readonly SortedDictionary<string, string> _values;
    private readonly object _lock = new();

    /// <summary>How many requests this store answered (the tests' witness that the core asked it).</summary>
    internal int Answered { get; private set; }

    private DeviceStore(string? file, SortedDictionary<string, string> values)
    {
        _file = file;
        _values = values;
    }

    /// <summary>The store kept in <paramref name="file"/>; a file that is not there yet is an empty store.</summary>
    public static DeviceStore Open(string file)
    {
        SortedDictionary<string, string> values;
        try
        {
            using var stream = File.OpenRead(file);
            values = JsonSerializer.Deserialize(stream, StoreContext.Default.SortedDictionaryStringString) ?? [];
        }
        catch (FileNotFoundException)
        {
            values = [];
        }
        catch (DirectoryNotFoundException)
        {
            values = [];
        }
        return new DeviceStore(file, values);
    }

    /// <summary>The store of this user on this device: <c>%LOCALAPPDATA%\veyq\device-store.json</c>.</summary>
    public static DeviceStore OpenDefault() =>
        Open(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "veyq", "device-store.json"));

    /// <summary>A store that keeps nothing past the process.</summary>
    public static DeviceStore InMemory() => new(null, []);

    /// <summary>The reply to one of the core's requests.</summary>
    public HostReply Answer(HostRequest request)
    {
        lock (_lock)
        {
            Answered++;
            bool changed;
            switch (request)
            {
                case HostRequest.StoreRead read:
                    return new HostReply.Value { value = _values.TryGetValue(read.name, out var v) ? v : null };
                case HostRequest.StoreWrite write:
                    changed = !(_values.TryGetValue(write.name, out var old) && old == write.value);
                    _values[write.name] = write.value;
                    break;
                case HostRequest.StoreRemove remove:
                    changed = _values.Remove(remove.name);
                    break;
                default:
                    return new HostReply.Failed { error = $"the host has no answer to {request.GetType().Name}" };
            }
            if (changed)
            {
                try
                {
                    Save();
                }
                catch (Exception e) when (e is IOException or UnauthorizedAccessException)
                {
                    return new HostReply.Failed { error = $"the device store did not write: {e.GetType().Name}" };
                }
            }
            return new HostReply.Done();
        }
    }

    private void Save()
    {
        if (_file == null) return;
        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(_file))!);
        var next = _file + ".next";
        using (var stream = new FileStream(next, FileMode.Create, FileAccess.Write, FileShare.None))
        {
            JsonSerializer.Serialize(stream, _values, StoreContext.Default.SortedDictionaryStringString);
            stream.Flush(flushToDisk: true);
        }
        File.Move(next, _file, overwrite: true);
    }
}

[JsonSerializable(typeof(SortedDictionary<string, string>))]
internal sealed partial class StoreContext : JsonSerializerContext;
