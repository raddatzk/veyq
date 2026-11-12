using System;
using System.Collections.Generic;
using System.Text;
using System.Threading.Tasks;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Windows.Graphics.Imaging;

namespace Veyq.Tests;

/// <summary>A receipt's PDF through Windows.Data.Pdf in an unpackaged process (D4 of the Expenses CR, its Phase 5 spike):
/// opened from memory, a page rendered to a PNG at the width asked, its drawing where the PDF put it.</summary>
[TestClass]
public sealed class PdfPagesTest
{
    /// <summary>A PDF of <paramref name="pages"/> pages, 200 × 100 pt each, the left half of each filled black.</summary>
    internal static byte[] Pdf(int pages)
    {
        var objects = new List<string> { "<< /Type /Catalog /Pages 2 0 R >>" };
        var kids = new StringBuilder();
        for (var i = 0; i < pages; i++) kids.Append($"{3 + 2 * i} 0 R ");
        objects.Add($"<< /Type /Pages /Kids [{kids}] /Count {pages} >>");
        const string draw = "0 0 0 rg 0 0 100 100 re f";
        for (var i = 0; i < pages; i++)
        {
            objects.Add($"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 100] /Contents {4 + 2 * i} 0 R >>");
            objects.Add($"<< /Length {draw.Length} >>\nstream\n{draw}\nendstream");
        }
        var pdf = new StringBuilder("%PDF-1.4\n");
        var offsets = new List<int>();
        for (var i = 0; i < objects.Count; i++)
        {
            offsets.Add(pdf.Length);
            pdf.Append($"{i + 1} 0 obj\n{objects[i]}\nendobj\n");
        }
        var xref = pdf.Length;
        pdf.Append($"xref\n0 {objects.Count + 1}\n0000000000 65535 f \n");
        foreach (var o in offsets) pdf.Append($"{o:D10} 00000 n \n");
        pdf.Append($"trailer\n<< /Size {objects.Count + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n");
        return Encoding.ASCII.GetBytes(pdf.ToString());
    }

    [TestMethod]
    public async Task AOnePagePdfRendersFromMemoryAtTheWidthAsked()
    {
        var pdf = await PdfPages.OpenAsync(Pdf(1));
        Assert.AreEqual(1u, pdf.PageCount);
        using var png = await PdfPages.RenderAsync(pdf, 0, 400);
        var decoder = await BitmapDecoder.CreateAsync(png);
        Assert.AreEqual(400u, decoder.PixelWidth);
        Assert.AreEqual(200u, decoder.PixelHeight);
        var pixels = (await decoder.GetPixelDataAsync(BitmapPixelFormat.Bgra8, BitmapAlphaMode.Premultiplied,
            new BitmapTransform(), ExifOrientationMode.IgnoreExifOrientation, ColorManagementMode.DoNotColorManage)).DetachPixelData();
        byte At(int x, int y) => pixels[(y * 400 + x) * 4];
        Assert.IsTrue(At(50, 100) < 32, "the left half is not drawn black");
        Assert.IsTrue(At(350, 100) > 224, "the right half is not white");
    }

    [TestMethod]
    public async Task APdfOfManyPagesCountsThemAll()
    {
        var pdf = await PdfPages.OpenAsync(Pdf(25));
        Assert.AreEqual(25u, pdf.PageCount);
    }

    [TestMethod]
    public async Task BytesThatAreNoPdfThrow()
    {
        var opened = true;
        try
        {
            await PdfPages.OpenAsync(Encoding.ASCII.GetBytes("no pdf"));
        }
        catch (Exception)
        {
            opened = false;
        }
        Assert.IsFalse(opened, "bytes that are no PDF opened");
    }
}
