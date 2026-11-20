// The core UI's one registry (ADR 0154 decision 3; the web's core/applications/registry.ts, Android's core/Registry.kt):
// each application registers into it from the composition root (Applications.cs, the web's applications.ts) — per kind its
// page's body and the page a received link's guest is shown of it, per application its own list, its creates and its
// section of a person's profile —, and the shell, a received link's screen, New and a person's profile ask it, so the
// core UI names no application. A kind or an application registered twice is refused, and a lookup before the
// composition root sealed it fails rather than draw nothing. What the header offers stays the Kotlin core's (AppKinds);
// the frame — the header with its hero, the notices, the tray, the CommandBar — is the core UI's around every body.
// scripts/validate_mobile_ui_direction.py holds the direction.

namespace Veyq.Core;

using System;
using System.Collections.Generic;
using System.Linq;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Veyq.Bridge.Wire;
using ApplicationId = Veyq.Bridge.Wire.ApplicationId;

/// <summary>What the frame hands a kind's page in the shell: the window, how an action went (under the bar), the bar a
/// notice with an action stands in (a trash's Undo), a Save a copy's progress, the header's facts once the page read
/// them, the CommandBar drawn anew, a replace of a file's content queued, the shell's lists read anew.</summary>
internal sealed class PageHost
{
    public required Window Window { get; init; }
    public required Action<bool, string> Say { get; init; }
    public required InfoBar Said { get; init; }
    public required Action<long> Saving { get; init; }
    public required Action<Facts?, long?, long?> Facts { get; init; }
    public required Action Changed { get; init; }
    public required Action<long> Replace { get; init; }
    public required Action RefreshLists { get; init; }
}

/// <summary>What a received link's screen hands a kind's guest page: the window, how an action went, its own bar, the
/// node as the link names it, a node of the link opened (a chapter's row), and its CommandBar drawn anew.</summary>
internal sealed class GuestHost
{
    public required Window Window { get; init; }
    public required Action<bool, string> Say { get; init; }
    public required InfoBar Said { get; init; }
    public required NodeAt Node { get; init; }
    public required Action<string> OpenNode { get; init; }
    public required Action Changed { get; init; }
}

/// <summary>Where the frame places the header's hero in a page (NodeHeader.Hero): the page reserves the slot in its own
/// scroll, the frame puts the hero of the node on screen into it — another header's replaces it.</summary>
internal sealed class HeroSlot
{
    public HeroSlot(Thickness padding = default) => View = new Border { Padding = padding };

    /// <summary>The slot as the page lays it out.</summary>
    public Border View { get; }

    /// <summary>The hero placed, null while none is.</summary>
    public UIElement? Hero { get; private set; }

    /// <summary>Told after a hero was placed (a page that lays the slot out only while it holds one draws anew).</summary>
    public Action? Placed { get; set; }

    public void Place(UIElement? hero)
    {
        if (ReferenceEquals(hero, Hero)) return;
        View.Child = null;
        Hero = hero;
        if (hero is FrameworkElement { Parent: Border other }) other.Child = null;
        if (hero is FrameworkElement { Parent: Panel panel }) panel.Children.Remove(hero);
        View.Child = hero;
        Placed?.Invoke();
    }
}

/// <summary>
/// A kind's page body as an application registers it, held by the frame while it is on screen: what the frame asks of
/// it — its hero slot, the places the shell offers it, its Refresh, the content's creating command (in place of the
/// level's New), the header's download, replace, upload and music, its secondary commands — and, on a received link's
/// screen, its guest write and replace. Each answers nothing by default.
/// </summary>
internal abstract class KindBody : IDisposable
{
    public abstract UIElement Page { get; }

    /// <summary>The node the page shows.</summary>
    public abstract string NodeId { get; }

    /// <summary>Whether the page shows <paramref name="r"/> (else the frame opens the kind's page anew).</summary>
    public abstract bool Shows(ObjectRef r);

    public virtual HeroSlot? Hero => null;

    /// <summary>The places the shell offers for the page and the one selected (Shell.barTabs, selectedTab).</summary>
    public virtual void Tabs(IReadOnlyList<ShownTab> tabs, BarTab? selected) { }

    /// <summary>A held write made offline opened in its conflict's editor (the tray's Open).</summary>
    public virtual void OpenHeld(string write) { }

    /// <summary>The page's Refresh (its automation id; F5 and Ctrl+R run it too); null: F5 reads the shell's lists anew.</summary>
    public virtual (string AutomationId, Action Run)? Refresh => null;

    /// <summary>The page creates its one thing itself: the level's New is not offered beside it.</summary>
    public virtual bool OwnsCreating => false;

    /// <summary>The content's creating command, first in the bar; null while it offers none.</summary>
    public virtual ICommandBarElement? Creating() => null;

    /// <summary>Whether what the page said under the bar goes with it (it closes when the page leaves or opens).</summary>
    public virtual bool SaysUnderBar => false;

    public virtual void Download(nint window) { }

    public virtual void Replace() { }

    public virtual void Upload() { }

    public virtual void Music() { }

    /// <summary>The page's own commands behind the bar's More, after the header's.</summary>
    public virtual IEnumerable<ICommandBarElement> Secondary() => [];

    /// <summary>A received link's guest write, first in its bar (<paramref name="write"/> asks a guest's name first
    /// where the write needs one); null while the page offers none.</summary>
    public virtual ICommandBarElement? GuestCreate(Action<Action> write) => null;

    /// <summary>The file a received link's guest may replace (the core's FileRights), else null.</summary>
    public virtual long? GuestReplaces => null;

    public abstract void Dispose();
}

/// <summary>A kind's page as an application registers it (the web's KindDescriptor): its body in the shell
/// (<see cref="Body"/>), the page a received link's guest is shown of it (<see cref="Guest"/>: null where the link's
/// page holds no model of it yet), and whether the header waits for the page to read the node's facts.</summary>
internal sealed class KindPage
{
    public Func<ObjectRef, PageHost, KindBody>? Body { get; init; }
    public Func<ReceivedPage, GuestHost, KindBody?>? Guest { get; init; }
    public bool FactsWhenRead { get; init; }
}

/// <summary>What the shell hands an application's own list: the window, the list's scroll followed and put back by its
/// place (ListScrolls), what the shell shows now, and whether an element is on screen.</summary>
internal sealed class ListHost
{
    public required Window Window { get; init; }
    public required Action<ScrollViewer, string> Keep { get; init; }
    public required Func<ShellShown?> Shown { get; init; }
    public required Func<UIElement, bool> Showing { get; init; }
}

/// <summary>An application's own list in the shell, in place of core's arranged list where it draws one.</summary>
internal abstract class ApplicationList
{
    /// <summary>The list for <paramref name="state"/>; null: core's arranged list.</summary>
    public abstract UIElement? Draw(ShellModelState state, ShellShown shown);

    /// <summary>The list left the screen (what it holds open closes).</summary>
    public abstract void Close();

    /// <summary>Whether a level's View (List or Tiles) is offered beside what it shows.</summary>
    public virtual bool OffersView(ShellShown shown) => true;
}

/// <summary>What New hands an application's create: the window, how it went, the shell's state as it is when asked.</summary>
internal sealed class CreateHost
{
    public required Window Window { get; init; }
    public required Action<bool, string> Say { get; init; }
    public required Func<ShellModelState?> State { get; init; }
}

/// <summary>An application's section of a person's profile, under core's frame (ADR 0144 decision 11).</summary>
internal abstract class PersonSection : IDisposable
{
    public abstract UIElement View { get; }

    /// <summary>The person's page as the core shows it, of person page <paramref name="person"/>.</summary>
    public abstract void Shown(long person, PersonShown shown);

    public abstract void Dispose();
}

/// <summary>What an application registers for itself beside its kinds: its own list (null: core's), the dialogs of what
/// it creates (the web's createDialog, opened by core's New) and its section of a person's profile.</summary>
internal sealed class ApplicationUi
{
    public required ApplicationId Id { get; init; }
    public Func<ListHost, ApplicationList>? List { get; init; }
    public IReadOnlyDictionary<Create, Action<CreateHost>> Creates { get; init; } = new Dictionary<Create, Action<CreateHost>>();
    public Func<Window, PersonSection>? PersonSection { get; init; }
}

internal sealed class Registry
{
    /// <summary>The app's one registry, filled by the composition root before anything is drawn.</summary>
    public static Registry Shared { get; } = new();

    private readonly Dictionary<string, KindPage> _kinds = new();
    private readonly List<ApplicationUi> _applications = new();
    private bool _sealed;

    public void Kind(KindPage page, params string[] kinds)
    {
        if (_sealed) throw new InvalidOperationException("the registry is sealed");
        foreach (var k in kinds)
        {
            if (!_kinds.TryAdd(k, page)) throw new InvalidOperationException($"the kind {k} is registered twice");
        }
    }

    public void Application(ApplicationUi ui)
    {
        if (_sealed) throw new InvalidOperationException("the registry is sealed");
        if (_applications.Any(a => a.Id == ui.Id)) throw new InvalidOperationException($"the application {ui.Id} is registered twice");
        _applications.Add(ui);
    }

    /// <summary>Called once by the composition root after every registration.</summary>
    public void Seal() => _sealed = true;

    public bool IsSealed => _sealed;

    public KindPage? Page(string kind) => Ready()._kinds.GetValueOrDefault(kind);

    public ApplicationUi? Application(ApplicationId id) => Ready()._applications.FirstOrDefault(a => a.Id == id);

    /// <summary>Every application, in the order the composition root registered them.</summary>
    public IReadOnlyList<ApplicationUi> Applications => Ready()._applications;

    public Action<CreateHost>? Create(Create create) =>
        Ready()._applications.Select(a => a.Creates.GetValueOrDefault(create)).FirstOrDefault(c => c is not null);

    private Registry Ready() =>
        _sealed ? this : throw new InvalidOperationException("the registry is read before the composition root registered the applications");
}
