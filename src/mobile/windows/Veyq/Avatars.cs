// The members' pictures (ADR 0128 decision 1), as Linux's avatars.rs draws them: the shell's state names the
// user ids whose picture the core holds (ShellShown.pictures), each is asked for once with Avatar.Picture, its bytes
// beside the answer, and held per tenant open (Held, AvatarsHeld.cs). The Pot's update carries the same set, so
// the shell's alone feeds it. A person is drawn as Fluent draws one, a PersonPicture: their picture where it is held,
// the initials of their name otherwise. WinUI decodes the picture (BitmapImage over the bytes in memory; the web writes
// WebP, which Windows decodes through its WebP codec).
using System;
using System.Collections.Generic;
using Microsoft.UI.Xaml.Controls;
using System.Runtime.InteropServices.WindowsRuntime;
using Microsoft.UI.Xaml.Media.Imaging;
using Veyq.Bridge;
using Veyq.Bridge.Wire;
using Windows.Storage.Streams;

namespace Veyq;

internal static class Avatars
{
    private static readonly Held<BitmapImage> HeldPictures = new();

    /// <summary>Every PersonPicture in the tree, with how often it was loaded and not yet unloaded (WinUI may raise a moved
    /// element's Loaded before its Unloaded): set again when its picture arrives or goes. Held strongly, and only while
    /// loaded — never through a WeakReference: a weak reference to a XAML object resolves, once its managed wrapper was
    /// collected, through the native object's own weak reference into a second wrapper, which on an element being torn
    /// down (a closed dialog's members) left the GC's reference tracking a freed object, and the app died in coreclr
    /// (0xc0000005 in WalkExternalTrackerObjects, measured 2026-11-12 from a dump).</summary>
    private static readonly Dictionary<PersonPicture, int> Loaded = new(ReferenceEqualityComparer.Instance);

    /// <summary>Whose picture each PersonPicture shows, keyed by the managed object (no native weak reference).</summary>
    private static readonly System.Runtime.CompilerServices.ConditionalWeakTable<PersonPicture, Bound> Ids = new();

    private sealed class Bound
    {
        public string? Id;
    }

    /// <summary>The shell's list of ids with a picture in <paramref name="tenant"/> (ShellShown.pictures): asks for each
    /// when the set changed.</summary>
    public static void Listed(string tenant, IReadOnlyList<string> ids)
    {
        var asked = HeldPictures.Listed(tenant, ids);
        foreach (var id in asked.Gone) Refresh(id);
        foreach (var id in asked.Ask) Ask(asked.Round, id);
    }

    private static async void Ask(ulong round, string id)
    {
        BitmapImage? picture = null;
        try
        {
            var (answer, bytes) = await Core.CallAsync(new Call.AvatarPicture { userId = id });
            if (answer is Answer.Bytes && bytes is not null) picture = await Decode(bytes);
            else Log.Write($"Avatar.Picture answered {answer.GetType().Name}");
        }
        catch (Exception e)
        {
            // an exception out of an async void would end the app in combase, with nothing in the log
            Log.Write($"a member's picture did not decode ({e.GetType().Name}: {e.Message})");
        }
        if (HeldPictures.Arrived(round, id, picture)) Refresh(id);
    }

    /// <summary>The picture's bytes as WinUI's image: decoded from memory, never from a file.</summary>
    private static async System.Threading.Tasks.Task<BitmapImage> Decode(byte[] bytes)
    {
        using var stream = new InMemoryRandomAccessStream();
        await stream.WriteAsync(bytes.AsBuffer());
        stream.Seek(0);
        var image = new BitmapImage();
        await image.SetSourceAsync(stream);
        // the log's witness that the platform decoded it (never whose it is)
        Log.Write($"a member's picture decoded: {bytes.Length} B, {image.PixelWidth}×{image.PixelHeight}");
        return image;
    }

    /// <summary>A person as Fluent draws one: <paramref name="name"/>'s initials, or the picture of
    /// <paramref name="userId"/> once the core gave it.</summary>
    public static PersonPicture Person(string name, string? userId, double size)
    {
        var person = new PersonPicture { DisplayName = name, Width = size, Height = size };
        Show(person, userId);
        return person;
    }

    /// <summary><paramref name="person"/> shows the picture of <paramref name="userId"/> (none: its initials), now and
    /// whenever that picture arrives or goes while it is loaded.</summary>
    public static void Show(PersonPicture person, string? userId)
    {
        if (!Ids.TryGetValue(person, out var bound))
        {
            bound = new Bound();
            Ids.Add(person, bound);
            person.Loaded += (_, _) =>
            {
                Loaded[person] = Loaded.GetValueOrDefault(person) + 1;
                // a picture that arrived while it was out of the tree
                person.ProfilePicture = bound.Id is null ? null : HeldPictures.Get(bound.Id);
            };
            person.Unloaded += (_, _) =>
            {
                var count = Loaded.GetValueOrDefault(person) - 1;
                if (count > 0) Loaded[person] = count;
                else Loaded.Remove(person);
            };
        }
        bound.Id = userId;
        person.ProfilePicture = userId is null ? null : HeldPictures.Get(userId);
    }

    private static void Refresh(string id)
    {
        var picture = HeldPictures.Get(id);
        foreach (var person in new List<PersonPicture>(Loaded.Keys))
        {
            if (Ids.TryGetValue(person, out var bound) && bound.Id == id) person.ProfilePicture = picture;
        }
    }
}
