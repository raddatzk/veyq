// The composition root (ADR 0154 decision 3; the web's src/web/src/applications.ts, Android's Applications.kt): the one
// file of the app that names the applications' UIs. Each application registers into the core UI's Registry here, once,
// before anything is drawn; scripts/validate_mobile_ui_direction.py holds every other file of the core UI (Core/) from
// naming an application (Apps/<Application>/), and an application from naming another.
using Veyq.Apps.Depot;
using Veyq.Apps.Expenses;
using Veyq.Apps.Memories;
using Veyq.Core;

namespace Veyq;

internal static class Applications
{
    public static void Register()
    {
        var registry = Registry.Shared;
        if (registry.IsSealed) return;
        MemoriesUi.Register(registry);
        DepotUi.Register(registry);
        ExpensesUi.Register(registry);
        registry.Seal();
    }
}
