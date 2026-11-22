// Memories' UI as it registers into the core UI (ADR 0154 decision 3; the web's registerMemories, Android's
// MemoriesUi.kt): a Memory's and a chapter's page (MemoryPage) — its Add chapter in place of the level's New, its
// Refresh, its photos' upload and its music through the core's header, its banner behind the bar's More —, a received
// link's gallery with a guest's Add photos, New Memory, the application's places in the shell (MemoriesPlaces: the list or
// the Memories map) and its section of a person's profile (PersonMemoriesSection).

namespace Veyq.Apps.Memories;

using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Veyq.Bridge;
using Veyq.Bridge.Wire;
using Veyq.Core;
using ApplicationId = Veyq.Bridge.Wire.ApplicationId;

internal static class MemoriesUi
{
    public static void Register(Registry registry)
    {
        registry.Kind(new KindPage
        {
            Body = (r, host) => new MemoryBody(new NodeAt { scopeId = r.scopeId, nodeId = r.nodeId, kind = r.kind, name = r.name }, host),
            Guest = (p, host) => p.kind == ReceivedPageKind.Gallery && p.gallery is { } gallery ? new GuestMemoryBody(host, gallery) : null,
        }, "MEMORY", "CHAPTER");
        registry.Application(new ApplicationUi
        {
            Id = ApplicationId.Memories,
            List = host => new MemoriesPlaces(host),
            Creates = new Dictionary<Create, Action<CreateHost>> { [Create.Memory] = host => NewMemory(host) },
            PersonSection = window => new PersonMemoriesSection(window),
            RowLines = MemoryDates.Lines,
        });
    }

    /// <summary>A Memory's or a chapter's page in the shell: it creates one thing too, a chapter (its "Add chapter", where
    /// its gallery offers one) — New is not offered beside it, as on a Pot's page.</summary>
    private sealed class MemoryBody : KindBody
    {
        private readonly MemoryPage _page;

        public MemoryBody(NodeAt node, PageHost host)
        {
            long? told = null;
            _page = new MemoryPage(host.Window, node, host.Say, host.Said, host.Saving)
            {
                // each state: its "Add chapter", and once its id, the header's own upload (Header.Facts' gallery)
                Changed = (_, gallery) =>
                {
                    if (told != gallery)
                    {
                        told = gallery;
                        host.Facts(new Facts { canWrite = true, hasContent = true }, null, gallery);
                    }
                    host.Changed();
                },
            };
        }

        public override UIElement Page => _page.Page;

        public override string NodeId => _page.Node.nodeId;

        public override bool Shows(ObjectRef r) => _page.Node == new NodeAt { scopeId = r.scopeId, nodeId = r.nodeId, kind = r.kind, name = r.name };

        public override HeroSlot Hero => _page.Hero;

        // a Memory's Refresh: its gallery read anew (F5 and Ctrl+R too)
        public override (string AutomationId, Action Run)? Refresh => ("memory-refresh", _page.Reload);

        public override bool OwnsCreating => true;

        public override ICommandBarElement? Creating() =>
            _page.Shown is { } gallery && gallery.creates.Any(c => c.create == Create.Chapter)
                ? CreateAction.Button(Words.MemoryAddChapter(), "memory-add-chapter", _page.CreateChapter)
                : null;

        public override void Upload() => _page.Add();

        public override void Music() => _page.Music();

        // a Memory's banner (ADR 0144 decision 5): its header's entry, where the gallery's plan offers it
        public override void Banner() => _page.EditBanner();

        public override void Dispose() => _page.Dispose();
    }

    /// <summary>A received link's gallery: the member's page; a save of an original is followed by nothing but its own
    /// outcome here (no shell's bar); a chapter's row opens it inside the link.</summary>
    private sealed class GuestMemoryBody : KindBody
    {
        private readonly MemoryPage _page;

        public GuestMemoryBody(GuestHost host, long gallery)
        {
            _page = new MemoryPage(host.Window, host.Node, host.Say, host.Said, _ => { }, gallery)
            {
                ChapterOpens = chapter => host.OpenNode(chapter.nodeId),
                Changed = (_, _) => host.Changed(),
            };
        }

        public override UIElement Page => _page.Page;

        public override string NodeId => _page.Node.nodeId;

        public override bool Shows(ObjectRef r) => false;

        // Add photos where the gallery's rights offer it, named or not (#1044): the core asks an unnamed guest's name at the
        // upload (GalleryModel.uploadAsking, AskNaming)
        public override ICommandBarElement? GuestCreate() =>
            _page.Shown is { canUpload: true } ? CreateAction.Button(Words.MemoryAdd(), "received-add", _page.Add) : null;

        public override void Dispose() => _page.Dispose();
    }

    /// <summary>New Memory (Shell.CreateMemory) in the level shown, named in a dialog that asks again with the core's
    /// problem; the Memory made opens (the web's MemoryNodeDialog).</summary>
    private static async void NewMemory(CreateHost host) => await NewMemory(host, "", null, null, null);

    private static async Task NewMemory(CreateHost host, string name, string? problem, bool? wasThereBefore, bool? placeBefore)
    {
        if (host.State() is not { } state || state.shell.space is not { } space) return;
        var parent = state.shell.path.Count > 0 ? state.shell.path[^1].nodeId : null;
        // "I was there", ticked, where the core offers it (a tenant or group scope: ADR 0144 decision 2); "Determine location
        // automatically", unticked, where the map services are shown (uc.memory.place A1); neither remembered
        var offered = false;
        var offersPlace = false;
        try
        {
            if ((await Core.CallAsync(new Call.ShellOffersWasThere { scopeId = space })).Item1 is Answer.WasThere w)
            {
                offered = w.offered;
                offersPlace = w.autoPlace;
            }
        }
        catch (Exception e)
        {
            Log.Write($"Shell.OffersWasThere failed ({e.GetType().Name})");
        }
        CheckBox? Check(bool shown, string id, string label, string hint, bool ticked)
        {
            if (!shown) return null;
            var box = new CheckBox { Content = label, IsChecked = ticked };
            AutomationProperties.SetAutomationId(box, id);
            ToolTipService.SetToolTip(box, hint);
            AutomationProperties.SetHelpText(box, hint);
            return box;
        }
        var place = Check(offersPlace, "new-memory-auto-place", Words.MemoryPlaceAuto(), Words.MemoryPlaceAutoHint(), placeBefore ?? false);
        var wasThere = Check(offered, "new-memory-was-there", Words.MemoryParticipantsWasThere(), Words.MemoryParticipantsWasThereHint(), wasThereBefore ?? true);
        StackPanel? extra = null;
        if (place is not null || wasThere is not null)
        {
            extra = new StackPanel();
            if (place is not null) extra.Children.Add(place);
            if (wasThere is not null) extra.Children.Add(wasThere);
        }
        var typed = await Dialogs.AskName(host.Window, "new-memory", Words.MemoryCreateMemoryTitle(), Words.NodeName(), name, problem, Words.FolderCreate(), extra);
        if (typed is null) return;
        var there = wasThere?.IsChecked == true;
        var armed = place?.IsChecked == true;
        Log.Write($"new memory: I was there offered {offered}, ticked {there}; the place offered {offersPlace}, ticked {armed}");
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.ShellCreateMemory { scopeId = space, parentId = parent, name = typed, wasThere = there, autoPlace = armed });
            switch (answer)
            {
                case Answer.Created { queued: true } created:
                    // made offline: waiting to be sent, said; nothing opens yet (it shows where it was made)
                    Log.Write("new memory: queued");
                    if (created.notice is { } waiting) host.Say(Spoken.Of(waiting));
                    break;
                case Answer.Created { node: { } made }:
                    Log.Write("new memory: made");
                    MemoryPage.OpenNode(made);
                    break;
                case Answer.Created { notice: { } n }:
                    Log.Write($"new memory: {n.msg}");
                    await NewMemory(host, typed, Said.Notice(n), there, armed);
                    break;
                default:
                    Log.Write($"Shell.CreateMemory answered {answer.GetType().Name}");
                    break;
            }
        }
        catch (Exception e)
        {
            Log.Write($"Shell.CreateMemory failed ({e.GetType().Name})");
        }
    }
}
