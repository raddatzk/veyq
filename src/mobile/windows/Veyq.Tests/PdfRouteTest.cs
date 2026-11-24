using Microsoft.VisualStudio.TestTools.UnitTesting;
using Veyq.Core;

namespace Veyq.Tests;

/// <summary>Where a PDF is shown (PdfWeb.Route, inbox #964): in WebView2's viewer wherever the device has it — a Depot
/// file and an Expenses receipt alike, a Depot file's protected PDF too, its password asked by the viewer alone —, the
/// pages drawn by Windows.Data.Pdf only without it; a receipt never asks a password, as the web's ReceiptView.</summary>
[TestClass]
public sealed class PdfRouteTest
{
    [TestMethod]
    public void EveryPdfThatOpensGoesToTheViewerWhereThereIsOne()
    {
        Assert.AreEqual(PdfWeb.Shown.Viewer, PdfWeb.Route(PdfWeb.Opening.Opened, viewer: true, asksPassword: true));
        Assert.AreEqual(PdfWeb.Shown.Viewer, PdfWeb.Route(PdfWeb.Opening.Opened, viewer: true, asksPassword: false));
        Assert.AreEqual(PdfWeb.Shown.Pages, PdfWeb.Route(PdfWeb.Opening.Opened, viewer: false, asksPassword: true));
        Assert.AreEqual(PdfWeb.Shown.Pages, PdfWeb.Route(PdfWeb.Opening.Opened, viewer: false, asksPassword: false));
    }

    [TestMethod]
    public void AProtectedFileGoesToTheViewerUnaskedAndIsAskedHereOnlyWithoutIt()
    {
        // asked here first, the viewer would ask again: it reads the bytes as they are
        Assert.AreEqual(PdfWeb.Shown.Viewer, PdfWeb.Route(PdfWeb.Opening.Locked, viewer: true, asksPassword: true));
        Assert.AreEqual(PdfWeb.Shown.AskHere, PdfWeb.Route(PdfWeb.Opening.Locked, viewer: false, asksPassword: true));
    }

    [TestMethod]
    public void AProtectedReceiptIsOfferedForSavingNeverAsked()
    {
        Assert.AreEqual(PdfWeb.Shown.OnlySaved, PdfWeb.Route(PdfWeb.Opening.Locked, viewer: true, asksPassword: false));
        Assert.AreEqual(PdfWeb.Shown.OnlySaved, PdfWeb.Route(PdfWeb.Opening.Locked, viewer: false, asksPassword: false));
    }

    [TestMethod]
    public void BytesNoReaderOpensAreNotShownOrOnlySaved()
    {
        Assert.AreEqual(PdfWeb.Shown.NotShown, PdfWeb.Route(PdfWeb.Opening.Failed, viewer: true, asksPassword: true));
        Assert.AreEqual(PdfWeb.Shown.OnlySaved, PdfWeb.Route(PdfWeb.Opening.Failed, viewer: true, asksPassword: false));
    }
}
