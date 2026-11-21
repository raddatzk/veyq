// Expenses' UI as it registers into the core UI (ADR 0154 decision 3; the web's registerExpenses, Android's
// ExpensesUi.kt): a Pot's page (PotPage) — its places, its Add entry in place of the level's New, its Refresh, its
// download as the web's ZIP through the core's header, a held entry's conflict opened from the tray —, a received link's
// Pot with a guest's Add entry, and New Pot (PotCreateDialog).

namespace Veyq.Apps.Expenses;

using System;
using System.Collections.Generic;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Veyq.Bridge.Wire;
using Veyq.Core;
using ApplicationId = Veyq.Bridge.Wire.ApplicationId;

internal static class ExpensesUi
{
    public static void Register(Registry registry)
    {
        // a Pot's header learns whether it takes writes once the Pot was read (AppKinds.Facts)
        registry.Kind(new KindPage
        {
            Body = (r, host) => new PotBody(r, host),
            Guest = (p, host) => p.kind == ReceivedPageKind.Pot && p.pot is { } pot ? new GuestPotBody(p, host, pot) : null,
            FactsWhenRead = true,
        }, "EXPENSES_POT");
        registry.Application(new ApplicationUi
        {
            Id = ApplicationId.Expenses,
            Creates = new Dictionary<Create, Action<CreateHost>> { [Create.Pot] = host => PotCreateDialog.Open(host.Window, host.Say) },
        });
    }

    /// <summary>A Pot's page in the shell: it creates one thing, an entry (as Android's and Apple's Pot screens) — the
    /// level's New is not offered beside it, two Add commands side by side would ask which one adds what.</summary>
    private sealed class PotBody : KindBody
    {
        private readonly PotPage _page;
        private readonly PageHost _host;

        public PotBody(ObjectRef r, PageHost host)
        {
            _host = host;
            _page = new PotPage(r, host.Window, host.Say);
            // the Pot read: the header learns whether it takes writes (AppKinds.Facts)
            _page.Read = canWrite => host.Facts(new Facts { canWrite = canWrite, hasContent = true }, null, null);
            _page.Changed = host.Changed;
        }

        public override UIElement Page => _page.Page;

        public override string NodeId => _page.Ref.nodeId;

        public override bool Shows(ObjectRef r) => _page.Ref == r;

        public override HeroSlot Hero => _page.Hero;

        public override void Tabs(IReadOnlyList<ShownTab> tabs, BarTab? selected) => _page.Places(tabs, selected);

        public override void OpenHeld(string write) => _page.OpenHeld(write);

        // an open Pot's Refresh: desktops have no pull gesture (F5 and Ctrl+R, as Explorer and Edge refresh)
        public override (string AutomationId, Action Run)? Refresh => ("pot-refresh", _page.Refresh);

        public override bool OwnsCreating => true;

        // its "Add entry" is the content's creating action, first, where its places show and it takes writes
        public override ICommandBarElement? Creating() =>
            _page.OffersAdd ? CreateAction.Button(Words.PotAdd(), "pot-add", _page.AddEntry) : null;

        public override bool SaysUnderBar => true;

        public override void Download(nint window) => _page.Download(window, _host.Say);

        public override void Dispose() => _page.Dispose();
    }

    /// <summary>A received link's Pot: the member's page over the model the core registered for the link.</summary>
    private sealed class GuestPotBody : KindBody
    {
        private readonly PotPage _page;

        public GuestPotBody(ReceivedPage p, GuestHost host, long pot)
        {
            var r = new ObjectRef { scopeId = "", nodeId = "", kind = p.nodeKind, application = ApplicationId.Expenses, name = p.name };
            _page = new PotPage(r, host.Window, host.Say, pot);
            _page.Changed = host.Changed;
        }

        public override UIElement Page => _page.Page;

        public override string NodeId => _page.Ref.nodeId;

        public override bool Shows(ObjectRef r) => false;

        // Add entry where the core offers it (PotView.offersEntries: a READ_WRITE link, named or not); an unnamed guest's
        // save asks its name through the core's naming (AskNaming), as the web's requestGuest at the write
        public override ICommandBarElement? GuestCreate(Action<Action> write) =>
            _page.OffersAdd ? CreateAction.Button(Words.PotAdd(), "received-add", _page.AddEntry) : null;

        public override void Dispose() => _page.Dispose();
    }
}
