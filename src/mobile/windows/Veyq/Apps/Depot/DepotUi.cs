// Depot's UI as it registers into the core UI (ADR 0154 decision 3; the web's registerDepot, Android's DepotUi.kt): a
// file's page — its viewer (FilePage), its download under its own name and its replace through the core's header, a
// received link's file with a guest's replace — and a drop box's — its guests' folders (DropBoxPage) under the head.

namespace Veyq.Apps.Depot;

using System;
using Microsoft.UI.Xaml;
using Veyq.Bridge.Wire;
using Veyq.Core;
using ApplicationId = Veyq.Bridge.Wire.ApplicationId;

internal static class DepotUi
{
    public static void Register(Registry registry)
    {
        // a file's header learns what it may do once the file was read (FileModel.Header.facts)
        registry.Kind(new KindPage
        {
            Body = (r, host) => new FileBody(new NodeAt { scopeId = r.scopeId, nodeId = r.nodeId, kind = r.kind, name = r.name }, host),
            Guest = (p, host) => p.kind == ReceivedPageKind.File && p.file is { } file ? new GuestFileBody(host, file) : null,
            FactsWhenRead = true,
        }, "FILE");
        registry.Kind(new KindPage
        {
            Body = (r, host) => new DropBoxBody(new NodeAt { scopeId = r.scopeId, nodeId = r.nodeId, kind = r.kind, name = r.name }, host),
        }, "DEPOT_DROPBOX");
        registry.Application(new ApplicationUi { Id = ApplicationId.Depot });
    }

    /// <summary>A file's page in the shell: the header's download is its Save a copy, its replace the uploads' replace.</summary>
    private sealed class FileBody : KindBody
    {
        private readonly FilePage _page;
        private readonly PageHost _host;

        public FileBody(NodeAt node, PageHost host)
        {
            _host = host;
            // the file read: its header learns what it may do (FileModel.Header.facts)
            _page = new FilePage(node) { Read = (facts, id) => host.Facts(facts, id, null) };
        }

        public override UIElement Page => _page.Page;

        public override string NodeId => _page.Node.nodeId;

        public override bool Shows(ObjectRef r) => _page.Node == new NodeAt { scopeId = r.scopeId, nodeId = r.nodeId, kind = r.kind, name = r.name };

        public override void Download(nint window) => _page.Save(window, _host.Say, _host.Saving);

        public override void Replace()
        {
            if (_page.Id is { } id) _host.Replace(id);
        }

        public override void Dispose() => _page.Dispose();
    }

    /// <summary>A received link's file: its page over the model the core registered for the link, Replace where the
    /// core's FileRights offer it to this guest, named or not.</summary>
    private sealed class GuestFileBody : KindBody
    {
        private readonly FilePage _page;
        private bool _replaces;

        public GuestFileBody(GuestHost host, long file)
        {
            _page = new FilePage(host.Node, file)
            {
                Read = (facts, _) =>
                {
                    if (facts.canWrite == _replaces) return;
                    _replaces = facts.canWrite;
                    host.Changed();
                },
            };
        }

        public override UIElement Page => _page.Page;

        public override string NodeId => _page.Node.nodeId;

        public override bool Shows(ObjectRef r) => false;

        public override long? GuestReplaces => _replaces ? _page.Id : null;

        public override void Dispose() => _page.Dispose();
    }

    /// <summary>A drop box's page: its guests' folders under the head; its Refresh reads the shell's lists anew, so a
    /// guest's new folder shows.</summary>
    private sealed class DropBoxBody : KindBody
    {
        private readonly DropBoxPage _page;
        private readonly PageHost _host;

        public DropBoxBody(NodeAt node, PageHost host)
        {
            _host = host;
            _page = new DropBoxPage(host.Window, node, host.Said);
        }

        public override UIElement Page => _page.Page;

        public override string NodeId => _page.Node.nodeId;

        public override bool Shows(ObjectRef r) => _page.Node == new NodeAt { scopeId = r.scopeId, nodeId = r.nodeId, kind = r.kind, name = r.name };

        public override HeroSlot Hero => _page.Hero;

        public override (string AutomationId, Action Run)? Refresh => ("refresh", _host.RefreshLists);

        public override void Dispose() => _page.Dispose();
    }
}
