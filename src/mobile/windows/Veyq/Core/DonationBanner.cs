// veyq's one ask for a donation (ADR 0124 decision 7: the apps outside the stores show the web's banner), Fluent's way: an
// InfoBar above the signed-in shell (ShellShown.donationBanner) and a received link's page (ReceivedShown.donationBanner)
// with the web's text, its action *Donate* opening the donation page in the browser, its close "Close and don't show again"
// (the tooltip names it), which sends the dismissal (the core keeps it on this device, and for a member on the account).
// When it shows is the core's (Donation).

namespace Veyq.Core;

using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Veyq.Bridge.Wire;

internal static class DonationBanner
{
    /// <summary>The banner, closed until the core says it shows; its close sends <paramref name="dismiss"/>.</summary>
    public static InfoBar Make(Call dismiss)
    {
        var bar = new InfoBar
        {
            IsOpen = false, IsClosable = true, Severity = InfoBarSeverity.Informational,
            Title = Words.ProjectBannerLabel(), Message = Words.ProjectBannerText(), Margin = new Thickness(12, 0, 12, 4),
        };
        AutomationProperties.SetAutomationId(bar, "donation-banner");
        var donate = new Button { Content = Words.ProjectBannerDonate() };
        AutomationProperties.SetAutomationId(donate, "donation-banner-donate");
        donate.Click += (_, _) => ReceivedView.Launch(Words.ProjectDonateUrl());
        bar.ActionButton = donate;
        bar.CloseButtonClick += (_, _) => Act.Send(dismiss);
        ToolTipService.SetToolTip(bar, Words.ProjectBannerDismiss());
        return bar;
    }
}
