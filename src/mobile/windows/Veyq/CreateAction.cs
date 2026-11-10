// A view's creating action, the one place it is built (ADR 0123 decision 9; scripts/validate_mobile_platform_guidelines.py):
// "New" as the first command of the content's CommandBar, where Explorer puts it — Fluent's Add symbol with its words,
// never a button laid over a view. A level that creates one kind gets a button, one that creates several a menu.
using System;
using System.Collections.Generic;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;

namespace Veyq;

internal static class CreateAction
{
    /// <summary>The command that creates the one kind a level creates.</summary>
    public static AppBarButton Button(string label, string automationId, Action create)
    {
        var button = new AppBarButton { Icon = new SymbolIcon(Symbol.Add), Label = label };
        AutomationProperties.SetAutomationId(button, automationId);
        button.Click += (_, _) => create();
        return button;
    }

    /// <summary>The command that opens the kinds a level creates, each an entry of its menu.</summary>
    public static AppBarButton Menu(string label, string automationId, IEnumerable<(string Label, string AutomationId, Action Create)> kinds)
    {
        var menu = new MenuFlyout();
        foreach (var (kindLabel, kindId, create) in kinds)
        {
            var item = new MenuFlyoutItem { Text = kindLabel };
            AutomationProperties.SetAutomationId(item, kindId);
            item.Click += (_, _) => create();
            menu.Items.Add(item);
        }
        var button = new AppBarButton { Icon = new SymbolIcon(Symbol.Add), Label = label, Flyout = menu };
        AutomationProperties.SetAutomationId(button, automationId);
        return button;
    }
}
