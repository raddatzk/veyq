// A view's creating action, the one place it is built (ADR 0123 decision 9; scripts/validate_mobile_platform_guidelines.py):
// "New" as the first command of the content's CommandBar, where Explorer puts it — Fluent's Add symbol with its words,
// never a button laid over a view. A level that creates one kind gets a button, one that creates several a menu. The
// drawer's New (ADR 0122 decision 2) is the pane's first control below the switcher, as Outlook's New mail heads its pane.

namespace Veyq.Core;

using System;
using System.Collections.Generic;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;

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

    /// <summary>The command that opens the kinds a level creates, each an entry of its menu (disabled where its create is
    /// null: a kind this app does not create yet).</summary>
    public static AppBarButton Menu(string label, string automationId, IEnumerable<(string Label, string AutomationId, Action? Create)> kinds)
    {
        var menu = new MenuFlyout();
        foreach (var (kindLabel, kindId, create) in kinds)
        {
            var item = new MenuFlyoutItem { Text = kindLabel, IsEnabled = create is not null };
            AutomationProperties.SetAutomationId(item, kindId);
            item.Click += (_, _) => create?.Invoke();
            menu.Items.Add(item);
        }
        var button = new AppBarButton { Icon = new SymbolIcon(Symbol.Add), Label = label, Flyout = menu };
        AutomationProperties.SetAutomationId(button, automationId);
        return button;
    }

    /// <summary>The drawer's New: an accent DropDownButton across the pane, its menu (the kinds it makes) set by the shell.</summary>
    public static DropDownButton Drawer(string label, string automationId)
    {
        var content = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        content.Children.Add(new SymbolIcon(Symbol.Add));
        content.Children.Add(new TextBlock { Text = label });
        var button = new DropDownButton
        {
            Content = content,
            HorizontalAlignment = Microsoft.UI.Xaml.HorizontalAlignment.Stretch,
            HorizontalContentAlignment = Microsoft.UI.Xaml.HorizontalAlignment.Left,
            Margin = new Microsoft.UI.Xaml.Thickness(4, 8, 4, 4),
            Style = (Microsoft.UI.Xaml.Style)Microsoft.UI.Xaml.Application.Current.Resources["AccentButtonStyle"],
        };
        AutomationProperties.SetAutomationId(button, automationId);
        AutomationProperties.SetName(button, label);
        return button;
    }
}
